package com.menora.initializr.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parses CREATE TABLE DDL via JSqlParser and emits JPA entity (and optionally
 * Spring Data repository) source files. One {@code SqlEntityGenerator} call
 * handles one dialect's worth of DDL for a single project.
 */
@Service
public class SqlEntityGenerator {

    private static final Logger log = LoggerFactory.getLogger(SqlEntityGenerator.class);

    /** Parses {@code sql} for the declared {@code dialect} and returns all
     *  generated files keyed under {@code src/main/java/{{packagePath}}/...}. */
    public List<GeneratedJavaFile> generate(String sql, SqlDialect dialect,
                                            String packageName, SqlDepOptions options) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        SqlDepOptions opts = options != null ? options : new SqlDepOptions("entity", List.of());

        List<TableModel> tables = parseTables(sql);
        if (tables.isEmpty()) {
            log.warn("SQL parse produced no CREATE TABLE statements for dialect {}", dialect);
            return List.of();
        }

        Set<String> knownTableNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (TableModel t : tables) knownTableNames.add(t.name());

        List<GeneratedJavaFile> files = new ArrayList<>();
        for (TableModel table : tables) {
            files.add(renderEntity(table, dialect, packageName, opts, knownTableNames));
            if (table.hasCompositePk()) {
                files.add(renderIdClass(table, packageName, opts));
            }
            if (opts.generateRepositoryFor(table.name())) {
                files.add(renderRepository(table, dialect, packageName, opts));
            }
        }
        return files;
    }

    /** Exposed for lightweight preview — just returns detected table names. */
    public List<String> detectTableNames(String sql) {
        return parseTables(sql).stream().map(TableModel::name).toList();
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private List<TableModel> parseTables(String sql) {
        Statements parsed;
        try {
            parsed = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("Could not parse SQL: " + e.getMessage(), e);
        }
        List<TableModel> result = new ArrayList<>();
        for (Statement s : parsed) {
            if (s instanceof CreateTable ct) {
                result.add(toTableModel(ct));
            }
        }
        return result;
    }

    private TableModel toTableModel(CreateTable ct) {
        String tableName = unquote(ct.getTable().getName());
        List<String> pkColumns = new ArrayList<>();
        List<ForeignKey> fks = new ArrayList<>();
        Set<String> fkColumnNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        if (ct.getIndexes() != null) {
            for (Index ix : ct.getIndexes()) {
                String type = ix.getType() == null ? "" : ix.getType().toUpperCase(Locale.ROOT);
                if (type.contains("PRIMARY KEY")) {
                    for (String col : ix.getColumnsNames()) {
                        pkColumns.add(unquote(col));
                    }
                }
                if (ix instanceof ForeignKeyIndex fk) {
                    List<String> cols = fk.getColumnsNames().stream().map(SqlEntityGenerator::unquote).toList();
                    String refTable = fk.getTable() != null ? unquote(fk.getTable().getName()) : "";
                    List<String> refCols = fk.getReferencedColumnNames() == null ? List.of()
                            : fk.getReferencedColumnNames().stream().map(SqlEntityGenerator::unquote).toList();
                    fks.add(new ForeignKey(cols, refTable, refCols));
                    fkColumnNames.addAll(cols);
                }
            }
        }

        List<ColumnModel> columns = new ArrayList<>();
        List<ColumnDefinition> defs = ct.getColumnDefinitions() == null
                ? Collections.emptyList() : ct.getColumnDefinitions();
        for (ColumnDefinition cd : defs) {
            columns.add(toColumnModel(cd, pkColumns, fkColumnNames));
        }

        // PKs can also be declared inline on columns. Collect them if missing.
        if (pkColumns.isEmpty()) {
            for (ColumnModel c : columns) {
                if (c.isPk()) pkColumns.add(c.name());
            }
        } else {
            // Mark columns flagged by table-level PK
            Set<String> pkSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            pkSet.addAll(pkColumns);
            List<ColumnModel> fixed = new ArrayList<>();
            for (ColumnModel c : columns) {
                boolean isPk = c.isPk() || pkSet.contains(c.name());
                fixed.add(new ColumnModel(c.name(), c.rawType(), c.precision(), c.scale(),
                        c.nullable(), isPk, c.isAutoIncrement(), c.isForeignKey()));
            }
            columns = fixed;
        }

        return new TableModel(tableName, columns, pkColumns, fks);
    }

    private ColumnModel toColumnModel(ColumnDefinition cd, List<String> tablePkCols, Set<String> fkColumnNames) {
        String name = unquote(cd.getColumnName());
        String rawType = cd.getColDataType().getDataType();
        Integer precision = null, scale = null;
        List<String> args = cd.getColDataType().getArgumentsStringList();
        if (args != null && !args.isEmpty()) {
            precision = parseIntOrNull(args.get(0));
            if (args.size() > 1) scale = parseIntOrNull(args.get(1));
        }

        List<String> specs = cd.getColumnSpecs() == null ? List.of()
                : cd.getColumnSpecs().stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();
        String specStr = String.join(" ", specs);

        boolean isPk = tablePkCols.stream().anyMatch(n -> n.equalsIgnoreCase(name))
                || specStr.contains("PRIMARY KEY");
        boolean notNull = specStr.contains("NOT NULL") || isPk;

        String rawTypeUpper = rawType == null ? "" : rawType.toUpperCase(Locale.ROOT);
        boolean isAutoInc = specStr.contains("AUTO_INCREMENT")
                || specStr.contains("IDENTITY")
                || specStr.contains("GENERATED ALWAYS AS IDENTITY")
                || specStr.contains("GENERATED BY DEFAULT AS IDENTITY")
                || rawTypeUpper.endsWith("SERIAL");

        boolean isFk = fkColumnNames.contains(name);
        return new ColumnModel(name, rawType, precision, scale, !notNull, isPk, isAutoInc, isFk);
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2) {
            char first = s.charAt(0), last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private GeneratedJavaFile renderEntity(TableModel table, SqlDialect dialect,
                                           String packageName, SqlDepOptions opts,
                                           Set<String> knownTableNames) {
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
        StringBuilder body = new StringBuilder();
        for (ColumnModel c : table.columns()) {
            JavaType jt = TypeMappers.map(dialect, c.rawType(), c.precision(), c.scale());
            imports.addAll(jt.imports());
            processColumn(body, c, jt, table, knownTableNames, emittedFks, usedFieldNames, imports);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(fullPkg).append(";\n\n");
        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append('\n');
        sb.append("@Entity\n");
        sb.append("@Table(name = \"").append(table.name()).append("\")\n");
        sb.append("@Data\n");
        sb.append("@NoArgsConstructor\n");
        sb.append("@AllArgsConstructor\n");
        if (table.hasCompositePk()) {
            sb.append("@IdClass(").append(className).append("Id.class)\n");
        }
        sb.append("public class ").append(className).append(" {\n\n");
        sb.append(body);
        sb.append("}\n");

        String path = "src/main/java/{{packagePath}}/" + subPkg + "/" + className + ".java";
        return new GeneratedJavaFile(path, sb.toString());
    }

    /**
     * Decide how to render a single column: as a JPA association (@ManyToOne /
     * shared-PK @MapsId), as part of a composite-FK association, or as a plain
     * scalar field. Falls back to scalar+TODO when the FK target table is not
     * in the same generation batch.
     */
    private void processColumn(StringBuilder body, ColumnModel c, JavaType jt, TableModel table,
                               Set<String> knownTableNames, Set<ForeignKey> emittedFks,
                               Set<String> usedFieldNames, Set<String> imports) {
        ForeignKey compositeFk = findCompositeForeignKey(c.name(), table.foreignKeys());
        if (compositeFk != null) {
            boolean refKnown = knownTableNames.contains(compositeFk.referencedTable());
            if (refKnown && !emittedFks.contains(compositeFk)) {
                appendCompositeAssociation(body, compositeFk, usedFieldNames, imports);
                emittedFks.add(compositeFk);
            }
            // PK columns must still emit their scalar @Id field for @IdClass to bind.
            // Non-PK composite-FK columns are absorbed by the association unless the
            // ref table is missing (then fall back to scalar+TODO).
            if (c.isPk()) {
                appendScalarField(body, c, jt, usedFieldNames);
            } else if (!refKnown) {
                body.append("    // TODO: map as @ManyToOne (composite FK to unknown table)\n");
                appendScalarField(body, c, jt, usedFieldNames);
            }
            return;
        }

        ForeignKey singleFk = findSingleForeignKey(c.name(), table.foreignKeys());
        if (singleFk != null && knownTableNames.contains(singleFk.referencedTable())) {
            appendSingleAssociation(body, c, singleFk, table, usedFieldNames, imports);
            emittedFks.add(singleFk);
            return;
        }

        if (c.isForeignKey()) {
            body.append("    // TODO: map as @ManyToOne\n");
        }
        appendScalarField(body, c, jt, usedFieldNames);
    }

    private void appendSingleAssociation(StringBuilder body, ColumnModel c, ForeignKey fk,
                                         TableModel table, Set<String> usedFieldNames,
                                         Set<String> imports) {
        String refEntity = toPascalCase(fk.referencedTable());
        String fieldName = pickAssociationFieldName(c, fk, usedFieldNames, false);
        usedFieldNames.add(fieldName);
        imports.add("jakarta.persistence.ManyToOne");
        imports.add("jakarta.persistence.JoinColumn");
        imports.add("jakarta.persistence.FetchType");

        // Shared-PK one-to-one: single PK column that is also a single-column FK.
        if (c.isPk() && table.pkColumns().size() == 1) {
            imports.add("jakarta.persistence.OneToOne");
            imports.add("jakarta.persistence.MapsId");
            body.append("    @Id\n");
            body.append("    @OneToOne(fetch = FetchType.LAZY)\n");
            body.append("    @MapsId\n");
            body.append("    @JoinColumn(name = \"").append(c.name()).append("\")\n");
            body.append("    private ").append(refEntity).append(' ').append(fieldName).append(";\n\n");
            return;
        }

        body.append("    @ManyToOne(fetch = FetchType.LAZY)\n");
        body.append("    @JoinColumn(name = \"").append(c.name()).append('"');
        if (!c.nullable()) body.append(", nullable = false");
        body.append(")\n");
        body.append("    private ").append(refEntity).append(' ').append(fieldName).append(";\n\n");
    }

    private void appendCompositeAssociation(StringBuilder body, ForeignKey fk,
                                            Set<String> usedFieldNames, Set<String> imports) {
        String refEntity = toPascalCase(fk.referencedTable());
        String fieldName = pickAssociationFieldName(null, fk, usedFieldNames, true);
        usedFieldNames.add(fieldName);
        imports.add("jakarta.persistence.ManyToOne");
        imports.add("jakarta.persistence.JoinColumn");
        imports.add("jakarta.persistence.JoinColumns");
        imports.add("jakarta.persistence.FetchType");

        body.append("    @ManyToOne(fetch = FetchType.LAZY)\n");
        body.append("    @JoinColumns({\n");
        for (int i = 0; i < fk.columns().size(); i++) {
            String col = fk.columns().get(i);
            String refCol = i < fk.referencedColumns().size()
                    ? fk.referencedColumns().get(i) : col;
            body.append("        @JoinColumn(name = \"").append(col)
                    .append("\", referencedColumnName = \"").append(refCol).append("\")");
            if (i < fk.columns().size() - 1) body.append(',');
            body.append('\n');
        }
        body.append("    })\n");
        body.append("    private ").append(refEntity).append(' ').append(fieldName).append(";\n\n");
    }

    private void appendScalarField(StringBuilder body, ColumnModel c, JavaType jt,
                                   Set<String> usedFieldNames) {
        String fieldName = toCamelCase(c.name());
        if (usedFieldNames.contains(fieldName)) {
            int n = 2;
            while (usedFieldNames.contains(fieldName + n)) n++;
            fieldName = fieldName + n;
        }
        usedFieldNames.add(fieldName);
        boolean renameViaColumn = !fieldName.equals(c.name());

        if (c.isPk()) {
            body.append("    @Id\n");
            if (c.isAutoIncrement()) {
                body.append("    @GeneratedValue(strategy = GenerationType.IDENTITY)\n");
            }
        }

        StringBuilder col = new StringBuilder("    @Column(");
        List<String> parts = new ArrayList<>();
        if (renameViaColumn) parts.add("name = \"" + c.name() + "\"");
        if (!c.nullable()) parts.add("nullable = false");
        if (c.precision() != null && isStringType(jt)) {
            parts.add("length = " + c.precision());
        } else if (c.precision() != null && isDecimalType(jt)) {
            parts.add("precision = " + c.precision());
            if (c.scale() != null) parts.add("scale = " + c.scale());
        }
        if (parts.isEmpty()) {
            col.setLength(0);
        } else {
            col.append(String.join(", ", parts)).append(")\n");
        }
        if (col.length() > 0) body.append(col);

        body.append("    private ").append(jt.simpleName()).append(' ')
                .append(fieldName).append(";\n\n");
    }

    private static ForeignKey findSingleForeignKey(String columnName, List<ForeignKey> fks) {
        for (ForeignKey fk : fks) {
            if (fk.columns().size() == 1 && fk.columns().get(0).equalsIgnoreCase(columnName)) {
                return fk;
            }
        }
        return null;
    }

    private static ForeignKey findCompositeForeignKey(String columnName, List<ForeignKey> fks) {
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
    private String pickAssociationFieldName(ColumnModel c, ForeignKey fk,
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

    private GeneratedJavaFile renderIdClass(TableModel table, String packageName, SqlDepOptions opts) {
        String className = toPascalCase(table.name()) + "Id";
        String fullPkg = packageName + "." + opts.subPackage();

        Set<String> imports = new TreeSet<>();
        imports.add("java.io.Serializable");
        imports.add("java.util.Objects");
        imports.add("lombok.AllArgsConstructor");
        imports.add("lombok.Data");
        imports.add("lombok.NoArgsConstructor");

        // Resolve PK column Java types — we need their imports too.
        StringBuilder fields = new StringBuilder();
        for (String pkCol : table.pkColumns()) {
            ColumnModel col = table.columns().stream()
                    .filter(c -> c.name().equalsIgnoreCase(pkCol))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "PK column '" + pkCol + "' not found in table " + table.name()));
            JavaType jt = TypeMappers.map(SqlDialect.POSTGRESQL /*irrelevant for PK id class*/,
                    col.rawType(), col.precision(), col.scale());
            imports.addAll(jt.imports());
            fields.append("    private ").append(jt.simpleName()).append(' ')
                    .append(toCamelCase(col.name())).append(";\n");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(fullPkg).append(";\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        sb.append('\n');
        sb.append("@Data\n");
        sb.append("@NoArgsConstructor\n");
        sb.append("@AllArgsConstructor\n");
        sb.append("public class ").append(className).append(" implements Serializable {\n\n");
        sb.append(fields);
        sb.append("}\n");

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/" + opts.subPackage() + "/" + className + ".java",
                sb.toString());
    }

    private GeneratedJavaFile renderRepository(TableModel table, SqlDialect dialect,
                                               String packageName, SqlDepOptions opts) {
        String entityClass = toPascalCase(table.name());
        String entityPkg = packageName + "." + opts.subPackage();
        String repoPkg = packageName + ".repository";

        // Resolve the id type
        String idType;
        Set<String> imports = new LinkedHashSet<>();
        imports.add("org.springframework.data.jpa.repository.JpaRepository");
        imports.add("org.springframework.stereotype.Repository");
        imports.add(entityPkg + "." + entityClass);

        if (table.hasCompositePk()) {
            idType = entityClass + "Id";
            imports.add(entityPkg + "." + entityClass + "Id");
        } else if (table.pkColumns().isEmpty()) {
            idType = "Long";
        } else {
            ColumnModel pk = table.columns().stream()
                    .filter(ColumnModel::isPk).findFirst().orElseThrow();
            JavaType jt = TypeMappers.map(dialect, pk.rawType(), pk.precision(), pk.scale());
            idType = jt.simpleName();
            imports.addAll(jt.imports());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(repoPkg).append(";\n\n");
        for (String imp : new TreeSet<>(imports)) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append('\n');
        sb.append("@Repository\n");
        sb.append("public interface ").append(entityClass).append("Repository extends JpaRepository<")
                .append(entityClass).append(", ").append(idType).append("> {\n");
        sb.append("}\n");

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/repository/" + entityClass + "Repository.java",
                sb.toString());
    }

    // ── Name helpers ──────────────────────────────────────────────────────────

    private static String toPascalCase(String snake) {
        String[] parts = snake.split("[_\\-\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.length() == 0 ? "Entity" : sb.toString();
    }

    private static String toCamelCase(String snake) {
        String pascal = toPascalCase(snake);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    private static boolean isStringType(JavaType jt) {
        return "String".equals(jt.simpleName());
    }

    private static boolean isDecimalType(JavaType jt) {
        return "BigDecimal".equals(jt.simpleName());
    }
}
