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

        List<GeneratedJavaFile> files = new ArrayList<>();
        for (TableModel table : tables) {
            files.add(renderEntity(table, dialect, packageName, opts));
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
                                           String packageName, SqlDepOptions opts) {
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

        StringBuilder body = new StringBuilder();
        for (ColumnModel c : table.columns()) {
            JavaType jt = TypeMappers.map(dialect, c.rawType(), c.precision(), c.scale());
            imports.addAll(jt.imports());
            appendField(body, c, jt, table);
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

    private void appendField(StringBuilder body, ColumnModel c, JavaType jt, TableModel table) {
        String fieldName = toCamelCase(c.name());
        boolean renameViaColumn = !fieldName.equals(c.name());
        boolean isPk = c.isPk();

        if (c.isForeignKey()) {
            body.append("    // TODO: map as @ManyToOne\n");
        }
        if (isPk) {
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
            col.setLength(0); // skip empty @Column
        } else {
            col.append(String.join(", ", parts)).append(")\n");
        }
        if (col.length() > 0) body.append(col);

        body.append("    private ").append(jt.simpleName()).append(' ')
                .append(fieldName).append(";\n\n");
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
