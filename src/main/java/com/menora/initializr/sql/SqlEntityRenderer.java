package com.menora.initializr.sql;

import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import com.menora.initializr.config.WizardTemplates;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.menora.initializr.sql.SqlEntityGenerator.*;
import static com.menora.initializr.sql.SqlSelectReader.*;
import static com.menora.initializr.sql.SqlScriptParser.*;
import static com.menora.initializr.sql.SqlRestRenderer.*;
import static com.menora.initializr.sql.SqlJavaNames.*;

/** Writes a table's entity, enums, id class and repository. Part of {@link SqlEntityGenerator}. */
final class SqlEntityRenderer {

    private SqlEntityRenderer() {}

    // ── Rendering ─────────────────────────────────────────────────────────────

    /** True when one of the table's rendered names comes out quoted, so the project needs
     *  {@link SqlNames#namingStrategySource} for it to reach the database as written. */
    static boolean rendersQuotedName(TableModel table, SqlDialect dialect) {
        List<String> names = new ArrayList<>();
        names.add(table.name());
        if (table.schema() != null) names.add(table.schema());
        table.columns().forEach(c -> names.add(c.name()));
        for (ForeignKey fk : table.foreignKeys()) names.addAll(fk.referencedColumns());
        return names.stream().anyMatch(n -> SqlNames.isQuoted(table.physical(n, dialect)));
    }

    /** A table/schema/column name as the body of a Java string literal in the entity — the
     *  name Spring Boot's naming strategy will hand to the database unchanged (see {@link SqlNames}). */
    private static String sqlName(TableModel table, String name, SqlDialect dialect) {
        return SqlNames.javaLiteral(table.physical(name, dialect));
    }

    static GeneratedJavaFile renderEntity(TableModel table, SqlDialect dialect,
                                          String packageName, SqlDepOptions opts,
                                          Set<String> knownTableNames, SqlProjectFacts facts) {
        if (table.isView()) {
            return renderViewEntity(table, dialect, packageName, opts);
        }
        String className = toPascalCase(table.name());
        String subPkg = opts.subPackage();
        String fullPkg = packageName + "." + subPkg;

        Set<String> imports = new TreeSet<>();
        imports.add("jakarta.persistence.Column");
        imports.add("jakarta.persistence.Entity");
        imports.add("jakarta.persistence.Id");
        imports.add("jakarta.persistence.Table");
        imports.add("lombok.AllArgsConstructor");
        imports.add("lombok.Data");
        imports.add("lombok.NoArgsConstructor");

        boolean hasAutoIncPk = table.columns().stream().anyMatch(c -> c.isPk() && c.isAutoIncrement());
        if (hasAutoIncPk) {
            imports.add("jakarta.persistence.GeneratedValue");
            imports.add("jakarta.persistence.GenerationType");
        }

        if (table.hasCompositePk()) {
            imports.add("jakarta.persistence.IdClass");
        }

        Set<ForeignKey> emittedFks = new HashSet<>();
        Set<String> usedFieldNames = new HashSet<>();
        // Exposed directly (no DTO), the entity is the request body: it carries the validation.
        Members body = new Members(facts.validation() && opts.apiMode() == SqlApiMode.ENTITY_DIRECT);
        for (ColumnModel c : table.columns()) {
            JavaType jt = TypeMappers.map(dialect, c.rawType(), c.precision(), c.scale());
            imports.addAll(jt.imports());
            processColumn(body, c, jt, table, dialect, knownTableNames, emittedFks, usedFieldNames, imports);
        }
        addConstraintImports(imports, body.list.stream()
                .flatMap(m -> ((List<String>) m.get("lines")).stream()).toList());
        // UNIQUE over several columns is the table's; over one column it is on the column.
        String uniqueConstraints = table.uniqueConstraints().stream()
                .map(cols -> "@UniqueConstraint(columnNames = {" + cols.stream()
                        .map(col -> "\"" + sqlName(table, col, dialect) + "\"").collect(Collectors.joining(", ")) + "})")
                .collect(Collectors.joining(", "));
        if (!uniqueConstraints.isEmpty()) imports.add("jakarta.persistence.UniqueConstraint");
        // A plain CREATE INDEX: what Hibernate creates with the table, and what a reader of the entity sees.
        String indexes = table.indexes().stream()
                .map(ix -> "@Index(" + (ix.name() == null ? "" : "name = \"" + SqlNames.javaLiteral(ix.name()) + "\", ")
                        + "columnList = \"" + ix.columns().stream()
                        .map(col -> sqlName(table, col.name(), dialect) + (col.descending() ? " DESC" : ""))
                        .collect(Collectors.joining(", ")) + "\")")
                .collect(Collectors.joining(", "));
        if (!indexes.isEmpty()) imports.add("jakarta.persistence.Index");

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("imports", imports);
        view.put("tableName", sqlName(table, table.name(), dialect));
        boolean hasSchema = table.schema() != null && !table.schema().isBlank();
        view.put("hasSchema", hasSchema);
        view.put("schema", hasSchema ? sqlName(table, table.schema(), dialect) : null);
        // No key: not an entity (Hibernate refuses one without an identifier, and the app with it).
        view.put("keyless", table.pkColumns().isEmpty());
        view.put("tableComment", table.name().replace("*/", "* /"));
        view.put("hasUniqueConstraints", !uniqueConstraints.isEmpty());
        view.put("uniqueConstraints", "{" + uniqueConstraints + "}");
        view.put("hasIndexes", !indexes.isEmpty());
        view.put("indexes", "{" + indexes + "}");
        // What the script says of the table that the entity does not carry (a partial index…).
        view.put("notes", table.notes().stream().map(n -> n.replaceAll("\\s+", " ")).toList());
        view.put("hasCompositePk", table.hasCompositePk());
        view.put("className", className);
        view.put("members", body.list);

        String path = "src/main/java/{{packagePath}}/" + subPkg + "/" + className + ".java";
        return new GeneratedJavaFile(path, WizardTemplates.render("sql/Entity.java", view));
    }

    /**
     * The members of an entity in declaration order, as the entity template reads them: each its
     * annotation lines (and a TODO comment that goes with it), its type and its name. A comment
     * is held until the member it precedes is added.
     */
    private static final class Members {
        final List<Map<String, Object>> list = new ArrayList<>();
        private final List<String> pending = new ArrayList<>();
        /** Whether the members carry Bean Validation annotations. */
        final boolean validated;

        Members(boolean validated) {
            this.validated = validated;
        }

        /** A comment line for the next member. */
        void comment(String line) {
            pending.add(line);
        }

        void add(List<String> annotations, String type, String name) {
            List<String> lines = new ArrayList<>(pending);
            pending.clear();
            lines.addAll(annotations);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lines", lines);
            m.put("type", type);
            m.put("name", name);
            list.add(m);
        }
    }

    /**
     * Renders a read-only {@code @Immutable}/{@code @Subselect} view entity from a SELECT or a
     * {@code CREATE VIEW}. A field takes the type the script gives its column
     * ({@link SqlScriptParser}: the table column it selects, a {@code CAST}); one the script
     * does not type is a {@code String} with a TODO for the user to fix in source. The
     * {@code @Column(name=…)} uses the projected label <em>verbatim</em> so it matches the
     * column the query actually returns. The first projected column is the {@code @Id}. The
     * query goes into a Java text block.
     */
    private static GeneratedJavaFile renderViewEntity(TableModel table, SqlDialect dialect, String packageName,
                                                      SqlDepOptions opts) {
        String className = toPascalCase(table.name());
        String subPkg = opts.subPackage();
        String fullPkg = packageName + "." + subPkg;

        List<Map<String, Object>> columns = new ArrayList<>();
        Set<String> usedFieldNames = new HashSet<>();
        Set<String> typeImports = new TreeSet<>();
        for (ColumnModel c : table.columns()) {
            JavaType jt = TypeMappers.map(dialect, c.rawType(), c.precision(), c.scale());
            typeImports.addAll(jt.imports());
            String fieldName = toCamelCase(c.name());
            if (usedFieldNames.contains(fieldName)) {
                int n = 2;
                while (usedFieldNames.contains(fieldName + n)) n++;
                fieldName = fieldName + n;
            }
            usedFieldNames.add(fieldName);
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("isPk", c.isPk());
            // Match the SELECT's projected column label — that's the name Hibernate reads back
            // from the @Subselect result set. A camelCase alias goes through SqlNames, or the
            // naming strategy would look for full_name instead of fullName.
            cv.put("column", sqlName(table, c.name(), dialect));
            cv.put("name", fieldName);
            cv.put("type", jt.simpleName());
            cv.put("untyped", c.rawType() == null);
            columns.add(cv);
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("viewQuery", table.viewQuery());
        view.put("className", className);
        view.put("typeImports", typeImports);
        view.put("columns", columns);

        String path = "src/main/java/{{packagePath}}/" + subPkg + "/" + className + ".java";
        return new GeneratedJavaFile(path, WizardTemplates.render("sql/ViewEntity.java", view));
    }

    /**
     * Decide how to render a single column: as a JPA association (@ManyToOne), as a key
     * column with a read-only association beside it (a key that is also a foreign key),
     * as part of a composite-FK association, or as a plain
     * scalar field. Falls back to scalar+TODO when the FK target table is not
     * in the same generation batch.
     */
    static void processColumn(Members body, ColumnModel c, JavaType jt, TableModel table, SqlDialect dialect,
                              Set<String> knownTableNames, Set<ForeignKey> emittedFks,
                              Set<String> usedFieldNames, Set<String> imports) {
        ForeignKey compositeFk = findCompositeForeignKey(c.name(), table.foreignKeys());
        if (compositeFk != null) {
            boolean refKnown = knownTableNames.contains(compositeFk.referencedTable());
            if (refKnown && !emittedFks.contains(compositeFk)) {
                appendCompositeAssociation(body, compositeFk, table, dialect, usedFieldNames, imports);
                emittedFks.add(compositeFk);
            }
            // PK columns must still emit their scalar @Id field for @IdClass to bind.
            // Non-PK composite-FK columns are absorbed by the association unless the
            // ref table is missing (then fall back to scalar+TODO).
            if (c.isPk()) {
                appendScalarField(body, c, jt, table, dialect, usedFieldNames, null);
            } else if (!refKnown) {
                body.comment("// TODO: map as @ManyToOne (composite FK to unknown table)");
                appendScalarField(body, c, jt, table, dialect, usedFieldNames, null);
            }
            return;
        }

        ForeignKey singleFk = findSingleForeignKey(c.name(), table.foreignKeys());
        if (singleFk != null && knownTableNames.contains(singleFk.referencedTable())) {
            if (isKeyColumn(c)) {
                // A key column that is also a foreign key — one part of a composite key
                // (order_items.order_id) or the whole key (author_profiles.author_id, a shared-key
                // one-to-one): the key stays a scalar @Id, which the @IdClass and Spring Data's
                // repository bind to, and the association reads through the same column. With the
                // association in the key's place the @IdClass had no property to match, and a
                // repository over "@Id @OneToOne" could not tell its id type: neither app started.
                appendScalarField(body, c, jt, table, dialect, usedFieldNames, null);
                appendReadOnlyAssociation(body, c, singleFk, table, dialect, usedFieldNames, imports);
            } else {
                appendSingleAssociation(body, c, singleFk, table, dialect, usedFieldNames, imports);
            }
            emittedFks.add(singleFk);
            return;
        }

        if (c.isForeignKey()) {
            body.comment("// TODO: map as @ManyToOne");
        }
        String enumClass = enumClassOf(table, c, jt, knownTableNames);
        if (enumClass != null) {
            imports.add("jakarta.persistence.EnumType");
            imports.add("jakarta.persistence.Enumerated");
        }
        appendScalarField(body, c, jt, table, dialect, usedFieldNames, enumClass);
    }

    private static void appendSingleAssociation(Members body, ColumnModel c, ForeignKey fk,
                                                TableModel table, SqlDialect dialect, Set<String> usedFieldNames,
                                                Set<String> imports) {
        String refEntity = toPascalCase(fk.referencedTable());
        String fieldName = pickAssociationFieldName(c, fk, usedFieldNames, false);
        usedFieldNames.add(fieldName);
        imports.add("jakarta.persistence.ManyToOne");
        imports.add("jakarta.persistence.JoinColumn");
        imports.add("jakarta.persistence.FetchType");

        List<String> lines = new ArrayList<>(lombokExcluded(imports));
        if (body.validated && !c.nullable()) lines.add("@NotNull");
        lines.add("@ManyToOne(fetch = FetchType.LAZY)");
        lines.add("@JoinColumn(name = \"" + sqlName(table, c.name(), dialect) + '"'
                + (c.nullable() ? "" : ", nullable = false") + ")");
        body.add(lines, refEntity, fieldName);
    }

    /**
     * The entity is Lombok {@code @Data}, whose equals, hashCode and toString read every field: an
     * association there loads the lazy row it points at (a query each, or a
     * {@code LazyInitializationException} outside a transaction), and two entities that point at
     * each other recurse. An association is therefore left out of all three — as the fullstack
     * Lombok set does.
     */
    private static List<String> lombokExcluded(Set<String> imports) {
        imports.add("lombok.EqualsAndHashCode");
        imports.add("lombok.ToString");
        return List.of("@EqualsAndHashCode.Exclude", "@ToString.Exclude");
    }

    /**
     * What Bean Validation can say of a column: a value is required unless the column is nullable
     * or the database generates it, and text is no longer than the column.
     */
    static List<String> constraintsOf(ColumnModel c, JavaType mapped, boolean isEnum) {
        List<String> out = new ArrayList<>();
        if (!c.nullable() && !(c.isPk() && c.isAutoIncrement())) out.add("@NotNull");
        if (!isEnum && c.precision() != null && isStringType(mapped)) out.add("@Size(max = " + c.precision() + ")");
        return out;
    }

    /** The imports of the constraint annotations among {@code lines}. */
    static void addConstraintImports(Set<String> imports, List<String> lines) {
        if (lines.stream().anyMatch(l -> l.startsWith("@NotNull"))) imports.add("jakarta.validation.constraints.NotNull");
        if (lines.stream().anyMatch(l -> l.startsWith("@Size("))) imports.add("jakarta.validation.constraints.Size");
    }

    /** Whether {@code c} is the key or one column of it. */
    static boolean isKeyColumn(ColumnModel c) {
        return c.isPk();
    }

    /** The association of a key column that stays a scalar: read through the column, never written. */
    private static void appendReadOnlyAssociation(Members body, ColumnModel c, ForeignKey fk, TableModel table,
                                                  SqlDialect dialect, Set<String> usedFieldNames, Set<String> imports) {
        String refEntity = toPascalCase(fk.referencedTable());
        String fieldName = pickAssociationFieldName(c, fk, usedFieldNames, false);
        usedFieldNames.add(fieldName);
        // The whole key: at most one row per parent.
        String kind = table.pkColumns().size() == 1 ? "OneToOne" : "ManyToOne";
        imports.add("jakarta.persistence." + kind);
        imports.add("jakarta.persistence.JoinColumn");
        imports.add("jakarta.persistence.FetchType");
        List<String> lines = new ArrayList<>(lombokExcluded(imports));
        lines.add("@" + kind + "(fetch = FetchType.LAZY)");
        lines.add("@JoinColumn(name = \"" + sqlName(table, c.name(), dialect) + "\", insertable = false, updatable = false)");
        body.add(lines, refEntity, fieldName);
    }

    private static void appendCompositeAssociation(Members body, ForeignKey fk, TableModel table, SqlDialect dialect,
                                                   Set<String> usedFieldNames, Set<String> imports) {
        String refEntity = toPascalCase(fk.referencedTable());
        String fieldName = pickAssociationFieldName(null, fk, usedFieldNames, true);
        usedFieldNames.add(fieldName);
        imports.add("jakarta.persistence.ManyToOne");
        imports.add("jakarta.persistence.JoinColumn");
        imports.add("jakarta.persistence.JoinColumns");
        imports.add("jakarta.persistence.FetchType");

        List<String> lines = new ArrayList<>(lombokExcluded(imports));
        lines.add("@ManyToOne(fetch = FetchType.LAZY)");
        lines.add("@JoinColumns({");
        for (int i = 0; i < fk.columns().size(); i++) {
            String col = fk.columns().get(i);
            String refCol = i < fk.referencedColumns().size()
                    ? fk.referencedColumns().get(i) : col;
            lines.add("    @JoinColumn(name = \"" + sqlName(table, col, dialect)
                    + "\", referencedColumnName = \"" + sqlName(table, refCol, dialect) + "\")"
                    + (i < fk.columns().size() - 1 ? "," : ""));
        }
        lines.add("})");
        body.add(lines, refEntity, fieldName);
    }

    private static void appendScalarField(Members body, ColumnModel c, JavaType jt, TableModel table,
                                          SqlDialect dialect, Set<String> usedFieldNames, String enumClass) {
        String fieldName = toCamelCase(c.name());
        if (usedFieldNames.contains(fieldName)) {
            int n = 2;
            while (usedFieldNames.contains(fieldName + n)) n++;
            fieldName = fieldName + n;
        }
        usedFieldNames.add(fieldName);
        // The column the naming strategy must receive; named whenever the field alone would not
        // produce it (a different name, or a mixed-case/quoted one SqlNames had to rewrite).
        String column = sqlName(table, c.name(), dialect);
        boolean renameViaColumn = !fieldName.equals(column);

        List<String> lines = new ArrayList<>();
        if (c.isPk()) {
            lines.add("@Id");
            if (c.isAutoIncrement()) {
                lines.add("@GeneratedValue(strategy = GenerationType.IDENTITY)");
            }
        }

        if (body.validated) lines.addAll(constraintsOf(c, jt, enumClass != null));
        if (enumClass != null) {
            lines.add("@Enumerated(EnumType.STRING)");
        }

        List<String> parts = new ArrayList<>();
        if (renameViaColumn) parts.add("name = \"" + column + "\"");
        if (!c.nullable()) parts.add("nullable = false");
        if (c.isUnique() && !c.isPk()) parts.add("unique = true");
        if (c.precision() != null && isStringType(jt)) {
            parts.add("length = " + c.precision());
        } else if (c.precision() != null && isDecimalType(jt)) {
            parts.add("precision = " + c.precision());
            if (c.scale() != null) parts.add("scale = " + c.scale());
        }
        if (!parts.isEmpty()) {
            lines.add("@Column(" + String.join(", ", parts) + ")");
        }
        body.add(lines, enumClass != null ? enumClass : jt.simpleName(), fieldName);
    }

    /** The enum behind each column a CHECK limits to a set of values (see {@link SqlScriptParser#enumClassOf}). */
    static List<GeneratedJavaFile> renderEnums(TableModel table, SqlDialect dialect, String packageName,
                                               SqlDepOptions opts, Set<String> knownTableNames) {
        List<GeneratedJavaFile> files = new ArrayList<>();
        if (table.isView()) return files;
        for (ColumnModel c : table.columns()) {
            JavaType mapped = TypeMappers.map(dialect, c.rawType(), c.precision(), c.scale());
            String enumClass = enumClassOf(table, c, mapped, knownTableNames);
            if (enumClass == null) continue;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("package", packageName + "." + opts.subPackage());
            view.put("className", enumClass);
            view.put("table", table.name());
            view.put("column", c.name());
            List<String> values = table.enumValuesOf(c.name());
            List<Map<String, Object>> constants = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                Map<String, Object> cv = new LinkedHashMap<>();
                cv.put("name", values.get(i));
                cv.put("last", i == values.size() - 1);
                constants.add(cv);
            }
            view.put("constants", constants);
            files.add(new GeneratedJavaFile(
                    "src/main/java/{{packagePath}}/" + opts.subPackage() + "/" + enumClass + ".java",
                    WizardTemplates.render("sql/Enum.java", view)));
        }
        return files;
    }

    static ForeignKey findSingleForeignKey(String columnName, List<ForeignKey> fks) {
        for (ForeignKey fk : fks) {
            if (fk.columns().size() == 1 && fk.columns().get(0).equalsIgnoreCase(columnName)) {
                return fk;
            }
        }
        return null;
    }

    static ForeignKey findCompositeForeignKey(String columnName, List<ForeignKey> fks) {
        for (ForeignKey fk : fks) {
            if (fk.columns().size() > 1
                    && fk.columns().stream().anyMatch(n -> n.equalsIgnoreCase(columnName))) {
                return fk;
            }
        }
        return null;
    }

    /**
     * Pick a field name for a JPA association without colliding with previously
     * emitted fields. Single-column FKs prefer the column name with a trailing
     * {@code _id}/{@code _fk}/{@code _uuid} stripped (e.g. {@code buyer_id} →
     * {@code buyer}), so two FKs from one table to the same target get distinct
     * names. Composite FKs default to the referenced table name.
     */
    static String pickAssociationFieldName(ColumnModel c, ForeignKey fk,
                                           Set<String> usedFieldNames, boolean composite) {
        String base;
        if (composite) {
            base = toCamelCase(fk.referencedTable());
        } else {
            String stripped = stripFkSuffix(c.name());
            base = stripped.isEmpty()
                    ? toCamelCase(fk.referencedTable())
                    : toCamelCase(stripped);
        }
        if (!usedFieldNames.contains(base)) return base;
        String fallback = composite ? base : toCamelCase(c.name());
        if (!usedFieldNames.contains(fallback)) return fallback;
        int n = 2;
        while (usedFieldNames.contains(fallback + n)) n++;
        return fallback + n;
    }

    private static String stripFkSuffix(String column) {
        String upper = column.toUpperCase(Locale.ROOT);
        for (String suffix : new String[]{"_ID", "_FK", "_UUID"}) {
            if (upper.endsWith(suffix)) {
                return column.substring(0, column.length() - suffix.length());
            }
        }
        return column;
    }

    static GeneratedJavaFile renderIdClass(TableModel table, SqlDialect dialect,
                                           String packageName, SqlDepOptions opts) {
        String className = toPascalCase(table.name()) + "Id";
        String fullPkg = packageName + "." + opts.subPackage();

        Set<String> imports = new TreeSet<>();
        imports.add("java.io.Serializable");
        imports.add("java.util.Objects");
        imports.add("lombok.AllArgsConstructor");
        imports.add("lombok.Data");
        imports.add("lombok.NoArgsConstructor");

        // Resolve PK column Java types — we need their imports too.
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String pkCol : table.pkColumns()) {
            ColumnModel col = table.columns().stream()
                    .filter(c -> c.name().equalsIgnoreCase(pkCol))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "PK column '" + pkCol + "' not found in table " + table.name()));
            JavaType jt = TypeMappers.map(dialect, col.rawType(), col.precision(), col.scale());
            imports.addAll(jt.imports());
            Map<String, Object> fv = new LinkedHashMap<>();
            fv.put("type", jt.simpleName());
            fv.put("name", toCamelCase(col.name()));
            fields.add(fv);
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("imports", imports);
        view.put("className", className);
        view.put("fields", fields);

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/" + opts.subPackage() + "/" + className + ".java",
                WizardTemplates.render("sql/IdClass.java", view));
    }

    static GeneratedJavaFile renderRepository(TableModel table, SqlDialect dialect,
                                              String packageName, SqlDepOptions opts) {
        String entityClass = toPascalCase(table.name());
        String entityPkg = packageName + "." + opts.subPackage();
        String repoPkg = packageName + ".repository";

        IdType id = resolveIdType(table, dialect, entityPkg, entityClass);
        String idType = id.simpleName();
        Set<String> imports = new LinkedHashSet<>();
        imports.add("org.springframework.data.jpa.repository.JpaRepository");
        imports.add("org.springframework.stereotype.Repository");
        imports.add(entityPkg + "." + entityClass);
        imports.addAll(id.imports());

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", repoPkg);
        view.put("imports", new TreeSet<>(imports));
        view.put("entityClass", entityClass);
        view.put("idType", idType);

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/repository/" + entityClass + "Repository.java",
                WizardTemplates.render("sql/Repository.java", view));
    }
}
