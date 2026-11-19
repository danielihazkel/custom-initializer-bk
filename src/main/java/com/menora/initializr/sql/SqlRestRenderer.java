package com.menora.initializr.sql;

import com.menora.initializr.gen.Naming;
import com.menora.initializr.config.WizardTemplates;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.menora.initializr.sql.SqlEntityGenerator.*;
import static com.menora.initializr.sql.SqlSelectReader.*;
import static com.menora.initializr.sql.SqlScriptParser.*;
import static com.menora.initializr.sql.SqlEntityRenderer.*;
import static com.menora.initializr.sql.SqlJavaNames.*;

/** Writes the REST stack over an entity: service, DTO, mapper, controller and its test. Part of {@link SqlEntityGenerator}. */
final class SqlRestRenderer {

    private SqlRestRenderer() {}

    // ── REST stack rendering (Service / DTO / Mapper / Controller) ─────────────

    /**
     * A view-model member of an entity, derived by replaying the exact same
     * column→field decisions {@link SqlEntityRenderer#processColumn} makes for the entity body
     * (using the same naming helpers), so association field names line up.
     * A {@code SCALAR} member is a plain column; an {@code assoc} member is a
     * JPA association, with {@code flatten} set when it can be reduced to a
     * single foreign-key id field on the DTO.
     */
    private record Member(boolean assoc, String fieldName, String pascalName,
                          String javaType, Set<String> imports, boolean isPk, FlattenInfo flatten,
                          List<String> constraints) {}

    private record FlattenInfo(String dtoIdField, String idType, Set<String> idImports,
                               String targetEntity, String targetPkPascal) {}

    private record EntityView(List<Member> members, boolean hasSkippedColumns) {}

    record IdType(String simpleName, Set<String> imports) {}

    private record PkPart(String name, String javaType, Set<String> imports) {}

    /** Mirror of {@link SqlEntityRenderer#processColumn}'s branching, producing view-model members
     *  instead of entity source. Field names match because both paths share the
     *  same {@link SqlEntityRenderer#pickAssociationFieldName}/{@link SqlJavaNames#toCamelCase} helpers and
     *  iterate columns in the same order against a shared used-name set. */
    private static EntityView analyze(TableModel table, SqlDialect dialect,
                                      Map<String, TableModel> tablesByName, Set<String> knownTableNames, String entityPkg) {
        List<Member> members = new ArrayList<>();
        Set<ForeignKey> emittedFks = new HashSet<>();
        Set<String> used = new HashSet<>();
        boolean skipped = false;
        for (ColumnModel c : table.columns()) {
            // The field's own type: an enum for a column a CHECK limits (a DTO carries the same).
            JavaType jt = table.isView() ? TypeMappers.map(dialect, c.rawType(), c.precision(), c.scale())
                    : fieldType(table, c, dialect, entityPkg, knownTableNames);

            ForeignKey compositeFk = findCompositeForeignKey(c.name(), table.foreignKeys());
            if (compositeFk != null) {
                boolean refKnown = knownTableNames.contains(compositeFk.referencedTable());
                if (refKnown && !emittedFks.contains(compositeFk)) {
                    String fieldName = pickAssociationFieldName(null, compositeFk, used, true);
                    used.add(fieldName);
                    emittedFks.add(compositeFk);
                    members.add(new Member(true, fieldName, capitalize(fieldName),
                            toPascalCase(compositeFk.referencedTable()), Set.of(), false, null, List.of()));
                    skipped = true; // composite FK can't flatten to a single id
                }
                if (c.isPk()) {
                    members.add(scalarMember(c, jt, used, dialect));
                } else if (!refKnown) {
                    members.add(scalarMember(c, jt, used, dialect));
                } else {
                    skipped = true; // non-PK column absorbed by the association
                }
                continue;
            }

            ForeignKey singleFk = findSingleForeignKey(c.name(), table.foreignKeys());
            if (singleFk != null && knownTableNames.contains(singleFk.referencedTable()) && isKeyColumn(c)) {
                // The key part is the member; the association that reads through it is derived.
                members.add(scalarMember(c, jt, used, dialect));
                used.add(pickAssociationFieldName(c, singleFk, used, false));
                emittedFks.add(singleFk);
                continue;
            }
            if (singleFk != null && knownTableNames.contains(singleFk.referencedTable())) {
                String fieldName = pickAssociationFieldName(c, singleFk, used, false);
                used.add(fieldName);
                emittedFks.add(singleFk);
                FlattenInfo flatten = buildFlatten(c, singleFk, dialect, tablesByName);
                if (flatten == null) skipped = true;
                // The id a DTO carries for the link is required when the foreign key is.
                members.add(new Member(true, fieldName, capitalize(fieldName),
                        toPascalCase(singleFk.referencedTable()), Set.of(), false, flatten,
                        c.nullable() ? List.of() : List.of("@NotNull")));
                continue;
            }

            members.add(scalarMember(c, jt, used, dialect));
        }
        return new EntityView(members, skipped);
    }

    private static Member scalarMember(ColumnModel c, JavaType jt, Set<String> used, SqlDialect dialect) {
        String fieldName = toCamelCase(c.name());
        if (used.contains(fieldName)) {
            int n = 2;
            while (used.contains(fieldName + n)) n++;
            fieldName = fieldName + n;
        }
        used.add(fieldName);
        JavaType mapped = TypeMappers.map(dialect, c.rawType(), c.precision(), c.scale());
        return new Member(false, fieldName, capitalize(fieldName),
                jt.simpleName(), jt.imports(), c.isPk(), null,
                constraintsOf(c, mapped, !mapped.simpleName().equals(jt.simpleName())));
    }

    /** Resolve the flattened DTO id field for a single-column FK, or null when
     *  the target can't be addressed by one id (composite/unknown PK). */
    private static FlattenInfo buildFlatten(ColumnModel fkCol, ForeignKey fk, SqlDialect dialect,
                                            Map<String, TableModel> tablesByName) {
        TableModel target = tablesByName.get(fk.referencedTable());
        if (target == null || target.hasCompositePk() || target.pkColumns().isEmpty()) {
            return null;
        }
        String pkColName = target.pkColumns().get(0);
        ColumnModel pkCol = target.columns().stream()
                .filter(c -> c.name().equalsIgnoreCase(pkColName)).findFirst().orElse(null);
        if (pkCol == null) return null;
        JavaType pkType = TypeMappers.map(dialect, pkCol.rawType(), pkCol.precision(), pkCol.scale());
        String targetPkProperty = toCamelCase(pkCol.name());
        return new FlattenInfo(toCamelCase(fkCol.name()), pkType.simpleName(), pkType.imports(),
                toPascalCase(fk.referencedTable()), capitalize(targetPkProperty));
    }

    static IdType resolveIdType(TableModel table, SqlDialect dialect, String entityPkg, String entityClass) {
        if (table.hasCompositePk()) {
            return new IdType(entityClass + "Id", Set.of(entityPkg + "." + entityClass + "Id"));
        }
        if (table.pkColumns().isEmpty()) {
            return new IdType("Long", Set.of());
        }
        ColumnModel pk = table.columns().stream().filter(ColumnModel::isPk).findFirst().orElseThrow();
        JavaType jt = TypeMappers.map(dialect, pk.rawType(), pk.precision(), pk.scale());
        return new IdType(jt.simpleName(), jt.imports());
    }

    private static List<PkPart> pkParts(TableModel table, SqlDialect dialect) {
        List<PkPart> parts = new ArrayList<>();
        for (String pkColName : table.pkColumns()) {
            ColumnModel col = table.columns().stream()
                    .filter(c -> c.name().equalsIgnoreCase(pkColName)).findFirst().orElse(null);
            JavaType jt = col == null ? JavaType.langType("Long")
                    : TypeMappers.map(dialect, col.rawType(), col.precision(), col.scale());
            parts.add(new PkPart(toCamelCase(pkColName), jt.simpleName(), jt.imports()));
        }
        return parts;
    }

    /** Setter suffix of a lone auto-increment PK (reset to null before insert),
     *  or null when the entity has no single generated PK. */
    private static String singleGeneratedPkSetter(TableModel table) {
        if (table.pkColumns().size() != 1) return null;
        ColumnModel pk = table.columns().stream().filter(ColumnModel::isPk).findFirst().orElse(null);
        if (pk == null || !pk.isAutoIncrement()) return null;
        return capitalize(toCamelCase(pk.name()));
    }

    static GeneratedJavaFile renderService(TableModel table, SqlDialect dialect, String packageName,
                                           SqlDepOptions opts, Map<String, TableModel> tablesByName,
                                           Set<String> knownTableNames) {
        String entityClass = toPascalCase(table.name());
        String entityPkg = packageName + "." + opts.subPackage();
        IdType id = resolveIdType(table, dialect, entityPkg, entityClass);
        EntityView view = analyze(table, dialect, tablesByName, knownTableNames, entityPkg);

        Set<String> imports = new TreeSet<>();
        imports.add(entityPkg + "." + entityClass);
        imports.addAll(id.imports());
        imports.add(packageName + ".repository." + entityClass + "Repository");
        imports.add("org.springframework.data.domain.Page");
        imports.add("org.springframework.data.domain.Pageable");
        imports.add("org.springframework.stereotype.Service");
        imports.add("org.springframework.transaction.annotation.Transactional");

        String genPkSetter = singleGeneratedPkSetter(table);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("package", packageName + ".service");
        model.put("imports", imports);
        model.put("entityClass", entityClass);
        model.put("idType", id.simpleName());
        // A read-only @Subselect view exposes GET-only scaffolding — no create/update/delete.
        model.put("mutable", !table.isView());
        model.put("hasGeneratedKey", genPkSetter != null);
        model.put("generatedKeySetter", genPkSetter);
        // What an update copies over: every member but the key (never overwrite the key).
        model.put("copied", view.members().stream().filter(m -> !m.isPk()).map(Member::pascalName).toList());

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/service/" + entityClass + "Service.java",
                WizardTemplates.render("sql/Service.java", model));
    }

    static GeneratedJavaFile renderDto(TableModel table, SqlDialect dialect, String packageName,
                                       SqlDepOptions opts, Map<String, TableModel> tablesByName,
                                       Set<String> knownTableNames, SqlProjectFacts facts) {
        String entityClass = toPascalCase(table.name());
        String entityPkg = packageName + "." + opts.subPackage();
        EntityView view = analyze(table, dialect, tablesByName, knownTableNames, entityPkg);
        boolean inline = opts.apiMode() == SqlApiMode.INLINE_DTO;

        List<Member> scalars = view.members().stream().filter(m -> !m.assoc()).toList();
        List<Member> flat = view.members().stream()
                .filter(m -> m.assoc() && m.flatten() != null).toList();

        Set<String> imports = new TreeSet<>();
        for (Member m : scalars) imports.addAll(m.imports());
        for (Member m : flat) imports.addAll(m.flatten().idImports());
        if (inline) {
            imports.add(entityPkg + "." + entityClass);
            for (Member m : flat) imports.add(entityPkg + "." + m.flatten().targetEntity());
        }

        List<Map<String, Object>> components = new ArrayList<>();
        List<Map<String, Object>> fromArgs = new ArrayList<>();
        List<Map<String, Object>> scalarViews = new ArrayList<>();
        List<Map<String, Object>> flatViews = new ArrayList<>();
        List<String> constraintLines = new ArrayList<>();
        for (Member m : scalars) {
            components.add(component(m.javaType(), m.fieldName(), facts.validation() ? m.constraints() : List.of()));
            if (facts.validation()) constraintLines.addAll(m.constraints());
            fromArgs.add(Map.of("expr", "entity.get" + m.pascalName() + "()"));
            scalarViews.add(Map.of("pascal", m.pascalName(), "name", m.fieldName()));
        }
        for (Member m : flat) {
            FlattenInfo fi = m.flatten();
            components.add(component(fi.idType(), fi.dtoIdField(), facts.validation() ? m.constraints() : List.of()));
            if (facts.validation()) constraintLines.addAll(m.constraints());
            fromArgs.add(Map.of("expr", "entity.get" + m.pascalName() + "() == null ? null : entity.get"
                    + m.pascalName() + "().get" + fi.targetPkPascal() + "()"));
            flatViews.add(flatView(m));
        }

        addConstraintImports(imports, constraintLines);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("package", packageName + ".dto");
        model.put("imports", imports);
        model.put("hasImports", !imports.isEmpty());
        model.put("hasSkippedColumns", view.hasSkippedColumns());
        model.put("entityClass", entityClass);
        model.put("components", markLast(components));
        model.put("hasComponents", !components.isEmpty());
        model.put("inline", inline);
        model.put("fromArgs", markLast(fromArgs));
        model.put("scalars", scalarViews);
        model.put("flat", flatViews);

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/dto/" + entityClass + "Dto.java",
                WizardTemplates.render("sql/Dto.java", model));
    }

    private static Map<String, Object> component(String type, String name, List<String> constraints) {
        Map<String, Object> c = new LinkedHashMap<>();
        // Each with a trailing space: the template writes them straight before the type.
        c.put("constraints", constraints.stream().map(a -> a + " ").collect(Collectors.joining()));
        c.put("type", type);
        c.put("name", name);
        return c;
    }

    /** A foreign key a DTO carries as the target's id, as the DTO and mapper templates read it. */
    private static Map<String, Object> flatView(Member m) {
        FlattenInfo fi = m.flatten();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", m.fieldName());
        f.put("pascal", m.pascalName());
        f.put("dtoIdField", fi.dtoIdField());
        f.put("idType", fi.idType());
        f.put("targetEntity", fi.targetEntity());
        f.put("targetPkPascal", fi.targetPkPascal());
        f.put("targetPkProperty", decapitalize(fi.targetPkPascal()));
        return f;
    }

    /** The same views, each saying whether it is the last (a template writes no comma after it). */
    private static List<Map<String, Object>> markLast(List<Map<String, Object>> views) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < views.size(); i++) {
            Map<String, Object> v = new LinkedHashMap<>(views.get(i));
            v.put("last", i == views.size() - 1);
            out.add(v);
        }
        return out;
    }

    static GeneratedJavaFile renderMapper(TableModel table, SqlDialect dialect, String packageName,
                                          SqlDepOptions opts, Map<String, TableModel> tablesByName,
                                          Set<String> knownTableNames) {
        String entityClass = toPascalCase(table.name());
        String entityPkg = packageName + "." + opts.subPackage();
        EntityView view = analyze(table, dialect, tablesByName, knownTableNames, entityPkg);
        List<Member> flat = view.members().stream()
                .filter(m -> m.assoc() && m.flatten() != null).toList();

        Set<String> imports = new TreeSet<>();
        imports.add("org.mapstruct.Mapper");
        if (!flat.isEmpty()) {
            imports.add("org.mapstruct.Mapping");
            imports.add("org.mapstruct.Named");
        }
        imports.add(packageName + ".config.MapstructConfig");
        imports.add(entityPkg + "." + entityClass);
        for (Member m : flat) {
            imports.add(entityPkg + "." + m.flatten().targetEntity());
            imports.addAll(m.flatten().idImports());
        }
        imports.add(packageName + ".dto." + entityClass + "Dto");
        imports.add("java.util.List");

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("package", packageName + ".mapper");
        model.put("imports", imports);
        model.put("entityClass", entityClass);
        model.put("flat", flat.stream().map(SqlRestRenderer::flatView).toList());

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/mapper/" + entityClass + "Mapper.java",
                WizardTemplates.render("sql/Mapper.java", model));
    }

    static GeneratedJavaFile renderController(TableModel table, SqlDialect dialect, String packageName,
                                              SqlDepOptions opts, Map<String, TableModel> tablesByName,
                                              Set<String> knownTableNames, SqlProjectFacts facts) {
        String entityClass = toPascalCase(table.name());
        String entityPkg = packageName + "." + opts.subPackage();
        SqlApiMode mode = opts.apiMode();
        boolean dto = mode.generatesDto();
        boolean mapstruct = mode == SqlApiMode.MAPSTRUCT_DTO;
        boolean composite = table.hasCompositePk();
        IdType id = resolveIdType(table, dialect, entityPkg, entityClass);
        List<PkPart> parts = composite ? pkParts(table, dialect) : List.of();
        String kebab = toKebab(table.name());
        String retType = dto ? entityClass + "Dto" : entityClass;

        // Conversion fragments parameterized by mode.
        String convOpen = dto ? (mapstruct ? "mapper.toDto(" : entityClass + "Dto.from(") : "";
        String convClose = dto ? ")" : "";
        String listMap = dto ? (mapstruct ? ".map(mapper::toDto)" : ".map(" + entityClass + "Dto::from)") : "";
        String bodyToEntity = dto ? (mapstruct ? "mapper.toEntity(body)" : "body.toEntity()") : "body";

        Set<String> imports = new TreeSet<>();
        imports.add(packageName + ".service." + entityClass + "Service");
        if (dto) imports.add(packageName + ".dto." + entityClass + "Dto");
        else imports.add(entityPkg + "." + entityClass);
        if (mapstruct) imports.add(packageName + ".mapper." + entityClass + "Mapper");
        if (composite) {
            imports.add(entityPkg + "." + entityClass + "Id");
            for (PkPart p : parts) imports.addAll(p.imports());
        } else {
            imports.addAll(id.imports());
        }
        imports.add("org.springframework.data.domain.Page");
        imports.add("org.springframework.data.domain.Pageable");
        imports.add("org.springframework.http.ResponseEntity");
        imports.add("org.springframework.web.bind.annotation.*");
        if (facts.validation() && !table.isView()) imports.add("jakarta.validation.Valid");

        String pathVars = parts.stream()
                .map(p -> "@PathVariable " + p.javaType() + ' ' + p.name())
                .collect(Collectors.joining(", "));
        String pkPath = parts.stream().map(p -> "{" + p.name() + "}")
                .collect(Collectors.joining("/", "/", ""));
        String newKey = "new " + entityClass + "Id("
                + parts.stream().map(PkPart::name).collect(Collectors.joining(", ")) + ")";

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("package", packageName + ".controller");
        model.put("imports", imports);
        model.put("kebab", kebab);
        model.put("entityClass", entityClass);
        model.put("mapstruct", mapstruct);
        model.put("retType", retType);
        model.put("listMap", listMap);
        model.put("convOpen", convOpen);
        model.put("convClose", convClose);
        model.put("bodyToEntity", bodyToEntity);
        // The body is validated where the validation starter is on the build.
        model.put("bodyAnnotation", facts.validation() ? "@Valid @RequestBody" : "@RequestBody");
        // How a row is addressed: one {id}, or the key's parts as ordered path segments.
        model.put("keyPath", composite ? pkPath : "/{id}");
        model.put("keyParams", composite ? pathVars : "@PathVariable " + id.simpleName() + " id");
        model.put("keyArg", composite ? newKey : "id");
        // create / update / delete — omitted for read-only @Subselect views (GET-only).
        model.put("mutable", !table.isView());

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/controller/" + entityClass + "Controller.java",
                WizardTemplates.render("sql/Controller.java", model));
    }

    /**
     * A slice test of the generated controller ({@code @WebMvcTest}, the service mocked): the list
     * endpoint answers. It is a place to start from — a project's own tests grow out of it.
     */
    static GeneratedJavaFile renderControllerTest(TableModel table, String packageName, SqlDepOptions opts,
                                                  SqlProjectFacts facts) {
        String entityClass = toPascalCase(table.name());
        boolean mapstruct = opts.apiMode() == SqlApiMode.MAPSTRUCT_DTO;
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("package", packageName + ".controller");
        model.put("servicePackage", packageName + ".service");
        model.put("entityPackage", packageName + "." + opts.subPackage());
        model.put("mapperPackage", packageName + ".mapper");
        model.put("entityClass", entityClass);
        model.put("kebab", toKebab(table.name()));
        model.put("mapstruct", mapstruct);
        model.put("mockAnnotation", facts.mockitoBean() ? "MockitoBean" : "MockBean");
        model.put("mockImport", facts.mockitoBean()
                ? "org.springframework.test.context.bean.override.mockito.MockitoBean"
                : "org.springframework.boot.test.mock.mockito.MockBean");
        return new GeneratedJavaFile(
                "src/test/java/{{packagePath}}/controller/" + entityClass + "ControllerTest.java",
                WizardTemplates.render("sql/ControllerTest.java", model));
    }

    // ── Name helpers ──────────────────────────────────────────────────────────

    // Case conversion is shared with the fullstack scaffolder via Naming; these thin
    // wrappers add the wizard's defensive fallbacks for empty/degenerate identifiers.
}
