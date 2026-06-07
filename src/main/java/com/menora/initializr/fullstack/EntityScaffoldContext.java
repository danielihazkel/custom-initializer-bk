package com.menora.initializr.fullstack;

import com.menora.initializr.db.entity.ColorPaletteEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the Mustache view-models exposed to fullstack templates.
 *
 * <p>Two map flavors:
 * <ul>
 *   <li><b>Project-wide</b> ({@link #buildProjectContext}): used for non-perEntity files
 *       and as the base for per-entity contexts. Includes a top-level {@code entities}
 *       list so a non-perEntity template can iterate (e.g. {@code AppRoutes.tsx}).</li>
 *   <li><b>Per-entity</b> ({@link #buildEntityContext}): project-wide + entity naming
 *       variants + {@code fields} iterable with per-field flags ({@code isString},
 *       {@code isPrimaryKey}, …).</li>
 * </ul>
 */
public final class EntityScaffoldContext {

    private EntityScaffoldContext() {}

    public static Map<String, Object> buildProjectContext(
            String artifactId,
            String groupId,
            String version,
            String packageName,
            String domainPackage,
            String javaVersion,
            String packaging,
            List<EntityDefinition> entities) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("artifactId", artifactId);
        ctx.put("groupId", groupId);
        ctx.put("version", version);
        ctx.put("packageName", packageName);
        ctx.put("packagePath", packageName == null ? "" : packageName.replace('.', '/'));
        ctx.put("javaVersion", javaVersion);
        ctx.put("packaging", packaging);

        // The base package the generated CRUD classes live under (defaults to packageName),
        // split into one conventional sub-package per layer. Constrained at the controller to be
        // at or below packageName so default component/entity scanning still finds the beans.
        String domain = (domainPackage == null || domainPackage.isBlank()) ? packageName : domainPackage;
        ctx.put("domainPackage", domain);
        putPackage(ctx, "entityPackage", domain, "entity");
        putPackage(ctx, "repositoryPackage", domain, "repository");
        putPackage(ctx, "dtoPackage", domain, "dto");
        putPackage(ctx, "servicePackage", domain, "service");
        putPackage(ctx, "controllerPackage", domain, "controller");

        // Relations reference other entities, so resolve a per-entity summary (PK type/name +
        // naming variants) up front and stash it so the per-entity context (built later by
        // buildEntityContext) can resolve relation targets too.
        Map<String, Map<String, Object>> summaries = buildSummaries(entities);
        ctx.put(ENTITY_SUMMARIES_KEY, summaries);

        List<Map<String, Object>> entityViews = new ArrayList<>(entities.size());
        for (int i = 0; i < entities.size(); i++) {
            Map<String, Object> view = entityViewModel(entities.get(i), summaries);
            view.put("first", i == 0);
            view.put("last", i == entities.size() - 1);
            entityViews.add(view);
        }
        ctx.put("entities", entityViews);
        return ctx;
    }

    /** Internal key under which the entity-summary lookup rides in the project context.
     *  Not referenced by any template. */
    private static final String ENTITY_SUMMARIES_KEY = "__entitySummaries";

    /** Builds a {@code lower(name) → summary} lookup with each entity's PK type/name and
     *  naming variants, so a relation can resolve its target's FK id type and class name. */
    private static Map<String, Map<String, Object>> buildSummaries(List<EntityDefinition> entities) {
        Map<String, Map<String, Object>> summaries = new LinkedHashMap<>();
        for (EntityDefinition e : entities) {
            FieldDefinition pk = e.fields().stream()
                    .filter(FieldDefinition::primaryKey).findFirst().orElse(null);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("pascal", Naming.toPascalCase(e.name()));
            s.put("camel", Naming.toCamelCase(e.name()));
            s.put("kebab", Naming.toKebabCase(e.name()));
            String pkName = pk == null ? "id" : pk.name();
            s.put("pkName", pkName);
            s.put("PkName", Naming.toPascalCase(pkName));
            s.put("pkJavaType", pk == null ? "Long" : pk.type().javaType());
            s.put("pkTsType", pk == null ? "number" : pk.type().tsType());
            summaries.put(e.name().toLowerCase(Locale.ROOT), s);
        }
        return summaries;
    }

    /**
     * Adds color-palette variables to a project context so frontend theme templates
     * (e.g. {@code index.css.mustache}) can resolve brand colors. Exposes a {@code palette}
     * map ({@code primary}/{@code secondary}/{@code accent}/{@code error}, blanks for nulls)
     * plus {@code hasPaletteAccent}/{@code hasPaletteError} section flags. Because per-entity
     * contexts are copied from the project context, per-entity templates inherit these too.
     */
    public static void putPaletteVars(Map<String, Object> ctx, ColorPaletteEntity palette) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", palette.getPaletteId());
        p.put("name", palette.getName());
        p.put("primary", palette.getPrimary());
        p.put("secondary", palette.getSecondary());
        p.put("accent", palette.getAccent() == null ? "" : palette.getAccent());
        p.put("error", palette.getError() == null ? "" : palette.getError());
        ctx.put("palette", p);
        ctx.put("hasPaletteAccent", palette.getAccent() != null && !palette.getAccent().isBlank());
        ctx.put("hasPaletteError", palette.getError() != null && !palette.getError().isBlank());
    }

    /** Escapes a string for embedding in a Java or JS double-quoted string literal
     *  (backslash and double-quote only — both languages share C-style escaping). */
    private static String escapeStringLiteral(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Puts {@code <name>} = {@code base.layer} and {@code <name>Path} = the slash form. */
    private static void putPackage(Map<String, Object> ctx, String name, String base, String layer) {
        String pkg = (base == null || base.isBlank()) ? layer : base + "." + layer;
        ctx.put(name, pkg);
        ctx.put(name + "Path", pkg.replace('.', '/'));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildEntityContext(
            Map<String, Object> projectContext,
            EntityDefinition entity) {
        Map<String, Object> ctx = new LinkedHashMap<>(projectContext);
        Map<String, Map<String, Object>> summaries =
                (Map<String, Map<String, Object>>) projectContext.get(ENTITY_SUMMARIES_KEY);
        ctx.putAll(entityViewModel(entity, summaries == null ? Map.of() : summaries));
        return ctx;
    }

    private static Map<String, Object> entityViewModel(
            EntityDefinition entity, Map<String, Map<String, Object>> summaries) {
        Map<String, Object> view = new LinkedHashMap<>();

        String pascal = Naming.toPascalCase(entity.name());
        String camel = Naming.toCamelCase(entity.name());
        String snake = Naming.toSnakeCase(entity.name());
        String kebab = Naming.toKebabCase(entity.name());
        String pascalPlural = Naming.pluralize(pascal);
        String camelPlural = Naming.pluralize(camel);
        String kebabPlural = Naming.pluralize(kebab);
        String snakePlural = Naming.pluralize(snake);

        view.put("EntityName", pascal);
        view.put("entityName", camel);
        view.put("entity_name", snake);
        view.put("entityNameKebab", kebab);
        view.put("EntityNamePlural", pascalPlural);
        view.put("entityNamePlural", camelPlural);
        view.put("entityNamePluralKebab", kebabPlural);
        view.put("entity_name_plural", snakePlural);
        view.put("tableName", entity.tableName() != null ? entity.tableName() : snakePlural);

        List<Map<String, Object>> fieldViews = new ArrayList<>(entity.fields().size());
        Map<String, Object> pkView = null;
        List<Map<String, Object>> nonPkViews = new ArrayList<>();
        Set<String> imports = new TreeSet<>();

        for (int i = 0; i < entity.fields().size(); i++) {
            FieldDefinition f = entity.fields().get(i);
            Map<String, Object> fv = fieldViewModel(pascal, f);
            fv.put("first", i == 0);
            fv.put("last", i == entity.fields().size() - 1);
            fieldViews.add(fv);
            if (f.primaryKey()) pkView = fv;
            else nonPkViews.add(fv);
            if (f.type() != FieldType.ENUM && f.type().javaImport() != null
                    && !f.type().javaImport().startsWith("java.lang.")) {
                imports.add(f.type().javaImport());
            }
        }
        // Re-tag last on nonPkViews
        for (int i = 0; i < nonPkViews.size(); i++) {
            nonPkViews.get(i).put("lastNonPk", i == nonPkViews.size() - 1);
        }

        view.put("fields", fieldViews);
        view.put("nonPkFields", nonPkViews);
        view.put("pkField", pkView);
        view.put("hasEnumFields", fieldViews.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("isEnum"))));

        List<Map<String, Object>> stringFieldViews = new ArrayList<>();
        for (Map<String, Object> fv : fieldViews) {
            if (Boolean.TRUE.equals(fv.get("isString"))) {
                stringFieldViews.add(fv);
            }
        }
        for (int i = 0; i < stringFieldViews.size(); i++) {
            stringFieldViews.get(i).put("lastString", i == stringFieldViews.size() - 1);
        }
        view.put("stringFields", stringFieldViews);
        view.put("hasStringFields", !stringFieldViews.isEmpty());

        // Relations (MANY_TO_ONE foreign keys). Each resolves its target's PK type/name from the
        // summary lookup so the entity gets a typed @ManyToOne, the DTO exposes the key as
        // <field>Id, and the service can stub the reference on create/update.
        List<Map<String, Object>> relationViews = new ArrayList<>(entity.relations().size());
        for (int i = 0; i < entity.relations().size(); i++) {
            RelationDefinition rel = entity.relations().get(i);
            Map<String, Object> target = summaries == null ? null
                    : summaries.get(rel.targetEntity().toLowerCase(Locale.ROOT));
            String relField = Naming.toCamelCase(rel.fieldName());
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("fieldName", relField);
            rv.put("FieldName", Naming.toPascalCase(rel.fieldName()));
            rv.put("fkFieldName", relField + "Id");
            rv.put("joinColumn", Naming.toSnakeCase(rel.fieldName()) + "_id");
            rv.put("targetEntity", target != null ? target.get("pascal") : Naming.toPascalCase(rel.targetEntity()));
            rv.put("targetEntityCamel", target != null ? target.get("camel") : Naming.toCamelCase(rel.targetEntity()));
            rv.put("targetEntityKebab", target != null ? target.get("kebab") : Naming.toKebabCase(rel.targetEntity()));
            rv.put("targetPkName", target != null ? target.get("pkName") : "id");
            rv.put("TargetPkName", target != null ? target.get("PkName") : "Id");
            rv.put("targetPkJavaType", target != null ? target.get("pkJavaType") : "Long");
            rv.put("targetPkTsType", target != null ? target.get("pkTsType") : "number");
            rv.put("required", rel.required());
            rv.put("isManyToOne", rel.type() == RelationType.MANY_TO_ONE);
            rv.put("last", i == entity.relations().size() - 1);
            relationViews.add(rv);
        }
        boolean hasRequiredRelations = relationViews.stream()
                .anyMatch(m -> Boolean.TRUE.equals(m.get("required")));
        view.put("relations", relationViews);
        view.put("hasRelations", !relationViews.isEmpty());

        // Aggregate flags so the DTO template only imports a Bean Validation constraint it
        // actually uses. @NotNull is skipped on a generated PK (it is null until persisted);
        // a required relation's FK id, however, does carry @NotNull.
        view.put("hasNotNullFields", hasRequiredRelations || fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("isRequired")) && !Boolean.TRUE.equals(m.get("isGenerated"))));
        view.put("hasSizeFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasLength"))));
        // @Min/@Max apply to integral fields; @DecimalMin/@DecimalMax to BigDecimal; @Pattern/@Email
        // to strings. Each gates its own DTO import so we never import an unused constraint.
        view.put("hasMinFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMin")) && Boolean.TRUE.equals(m.get("isIntegral"))));
        view.put("hasMaxFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMax")) && Boolean.TRUE.equals(m.get("isIntegral"))));
        view.put("hasDecimalMinFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMin")) && Boolean.TRUE.equals(m.get("isBigDecimal"))));
        view.put("hasDecimalMaxFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMax")) && Boolean.TRUE.equals(m.get("isBigDecimal"))));
        view.put("hasPatternFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasPattern"))));
        view.put("hasEmailFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("isEmail"))));

        List<Map<String, Object>> importViews = new ArrayList<>(imports.size());
        for (String imp : imports) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", imp);
            importViews.add(m);
        }
        view.put("imports", importViews);
        return view;
    }

    private static Map<String, Object> fieldViewModel(String entityPascal, FieldDefinition f) {
        Map<String, Object> fv = new LinkedHashMap<>();
        fv.put("name", f.name());
        fv.put("Name", Naming.toPascalCase(f.name()));
        fv.put("column", Naming.toSnakeCase(f.name()));

        String javaType;
        String enumTypeName = null;
        if (f.type() == FieldType.ENUM) {
            enumTypeName = entityPascal + Naming.toPascalCase(f.name()) + "Type";
            javaType = enumTypeName;
        } else {
            javaType = f.type().javaType();
        }
        fv.put("javaType", javaType);
        fv.put("tsType", f.type().tsType());
        fv.put("enumTypeName", enumTypeName);

        boolean isIntegral = f.type() == FieldType.LONG || f.type() == FieldType.INTEGER;
        boolean isBigDecimal = f.type() == FieldType.BIG_DECIMAL;
        fv.put("isPrimaryKey", f.primaryKey());
        fv.put("isGenerated", f.generated());
        fv.put("isRequired", f.required());
        fv.put("isUnique", f.unique());
        fv.put("isString", f.type().isString());
        fv.put("isNumeric", f.type().isNumeric());
        fv.put("isIntegral", isIntegral);
        fv.put("isBigDecimal", isBigDecimal);
        fv.put("isBoolean", f.type().isBoolean());
        fv.put("isTemporal", f.type().isTemporal());
        fv.put("isDate", f.type() == FieldType.LOCAL_DATE);
        fv.put("isDateTime", f.type() == FieldType.LOCAL_DATE_TIME);
        fv.put("isEnum", f.type().isEnum());
        fv.put("hasLength", f.length() != null);
        fv.put("length", f.length());
        // Numeric bounds (rendered as @Min/@Max on integral types, @DecimalMin/@DecimalMax on BigDecimal).
        fv.put("hasMin", f.min() != null);
        fv.put("min", f.min());
        fv.put("hasMax", f.max() != null);
        fv.put("max", f.max());
        // String constraints. The pattern is injected into Java (@Pattern(regexp="..")) and JS
        // ("..") string literals, so backslashes and double-quotes are escaped once here — the
        // C-style escaping is valid in both languages.
        boolean hasPattern = f.pattern() != null && !f.pattern().isBlank();
        fv.put("hasPattern", hasPattern);
        fv.put("pattern", f.pattern());
        fv.put("patternEscaped", hasPattern ? escapeStringLiteral(f.pattern()) : null);
        fv.put("isEmail", f.email());

        if (f.type() == FieldType.ENUM) {
            List<Map<String, Object>> values = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String v : f.enumValues()) {
                String constant = v.toUpperCase(Locale.ROOT);
                if (!seen.add(constant)) continue;
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("value", constant);
                values.add(ev);
            }
            for (int i = 0; i < values.size(); i++) {
                values.get(i).put("last", i == values.size() - 1);
            }
            fv.put("enumValues", values);
        } else {
            fv.put("enumValues", List.of());
        }
        return fv;
    }
}
