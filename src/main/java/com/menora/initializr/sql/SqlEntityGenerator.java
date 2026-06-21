package com.menora.initializr.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

        List<TableModel> tables = parseTables(sql, dialect);
        if (tables.isEmpty()) {
            log.warn("SQL parse produced no CREATE TABLE statements for dialect {}", dialect);
            return List.of();
        }

        Set<String> knownTableNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, TableModel> tablesByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (TableModel t : tables) {
            knownTableNames.add(t.name());
            tablesByName.put(t.name(), t);
        }

        List<GeneratedJavaFile> files = new ArrayList<>();
        for (TableModel table : tables) {
            files.add(renderEntity(table, dialect, packageName, opts, knownTableNames));
            if (table.hasCompositePk()) {
                files.add(renderIdClass(table, dialect, packageName, opts));
            }
            // A generated REST stack needs a repository even if the per-table
            // toggle is off — and only makes sense for an addressable (PK) table.
            boolean apiOn = opts.apiMode().generatesApi() && !table.pkColumns().isEmpty();
            if (opts.generateRepositoryFor(table.name()) || apiOn) {
                files.add(renderRepository(table, dialect, packageName, opts));
            }
            if (opts.apiMode().generatesApi() && table.pkColumns().isEmpty()) {
                log.debug("Skipping REST stack for table {} — no primary key to address rows by", table.name());
            }
            if (apiOn) {
                files.add(renderService(table, dialect, packageName, opts, tablesByName, knownTableNames));
                if (opts.apiMode().generatesDto()) {
                    files.add(renderDto(table, dialect, packageName, opts, tablesByName, knownTableNames));
                }
                if (opts.apiMode() == SqlApiMode.MAPSTRUCT_DTO) {
                    files.add(renderMapper(table, dialect, packageName, opts, tablesByName, knownTableNames));
                }
                files.add(renderController(table, dialect, packageName, opts, tablesByName, knownTableNames));
            }
        }
        return files;
    }

    /** Exposed for lightweight preview and up-front validation — just returns
     *  detected table names (also throws {@link SqlParseException} if the DDL
     *  cannot be parsed, so controllers can surface the error as HTTP 400). */
    public List<String> detectTableNames(String sql, SqlDialect dialect) {
        return parseTables(sql, dialect).stream().map(TableModel::name).toList();
    }

    /** Exposed for callers that need the full parsed schema (e.g. the fullstack
     *  wizard's "Import from DDL"). Throws {@link SqlParseException} on parse
     *  failure so the controller can surface HTTP 400. */
    public List<TableModel> parseTablesForImport(String sql, SqlDialect dialect) {
        return parseTables(sql, dialect);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /** Oracle's {@code NUMBER(*, N)} / {@code NUMBER(*)} syntax means "max
     *  precision (38), scale N". JSqlParser's grammar rejects {@code *} in the
     *  argument position, so normalize to {@code NUMBER(38, N)} before parsing. */
    private static final Pattern ORACLE_NUMBER_WILDCARD =
            Pattern.compile("\\bNUMBER\\s*\\(\\s*\\*\\s*(,\\s*\\d+\\s*)?\\)",
                    Pattern.CASE_INSENSITIVE);

    /** DB2 lets you write {@code DEFAULT CURRENT TIMESTAMP} (two words), but
     *  JSqlParser only recognizes the underscored form. Rewrite to the
     *  underscored variant so the column default parses cleanly. */
    private static final Pattern DB2_CURRENT_TS_DATE_TIME =
            Pattern.compile("\\bCURRENT\\s+(TIMESTAMP|DATE|TIME)\\b",
                    Pattern.CASE_INSENSITIVE);

    /** DB2-for-i (iSeries) lets a column carry a short system name ahead of its
     *  type: {@code STATUS_CODE FOR COLUMN STATU00001 NUMERIC(3,0)}. JSqlParser
     *  hits {@code FOR} where it expects a data type, so strip the clause — the
     *  real (long) column name is the one we keep. */
    private static final Pattern DB2_FOR_COLUMN =
            Pattern.compile("\\bFOR\\s+COLUMN\\s+\\w+", Pattern.CASE_INSENSITIVE);

    /** DB2 column character-set clause ({@code CHAR(24) CCSID 424}); JSqlParser
     *  does not model it. */
    private static final Pattern DB2_CCSID =
            Pattern.compile("\\bCCSID\\s+(?:\\d+|UNICODE)", Pattern.CASE_INSENSITIVE);

    /** DB2 column-data clauses ({@code FOR BIT DATA} etc.) JSqlParser rejects. */
    private static final Pattern DB2_FOR_DATA =
            Pattern.compile("\\bFOR\\s+(?:BIT|SBCS|MIXED)\\s+DATA", Pattern.CASE_INSENSITIVE);

    /** Schema-qualified constraint name ({@code CONSTRAINT ENTV.Q_… PRIMARY KEY})
     *  — JSqlParser's grammar takes a single identifier, so drop the schema. */
    private static final Pattern DB2_QUALIFIED_CONSTRAINT =
            Pattern.compile("(\\bCONSTRAINT\\s+)\\w+\\.(\\w+)", Pattern.CASE_INSENSITIVE);

    /** Statements we safely skip — valid DDL the wizard does not act on.
     *  Matches the leading keyword(s) of the trimmed statement. */
    private static final Pattern IGNORABLE_STATEMENT = Pattern.compile(
            "^\\s*(?:COMMENT\\s+ON|CREATE\\s+(?:UNIQUE\\s+)?INDEX|CREATE\\s+SEQUENCE|"
                    + "GRANT\\b|REVOKE\\b|ALTER\\b|SET\\b|USE\\b|DROP\\b|ANALYZE\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_TABLE_LEAD = Pattern.compile(
            "^\\s*CREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+|TEMPORARY\\s+|TEMP\\s+)?TABLE\\b",
            Pattern.CASE_INSENSITIVE);

    private List<TableModel> parseTables(String sql, SqlDialect dialect) {
        String prepared = sql;
        if (dialect == SqlDialect.ORACLE) prepared = normalizeOracle(prepared);
        if (dialect == SqlDialect.DB2)    prepared = normalizeDb2(prepared);

        List<TableModel> result = new ArrayList<>();
        List<String> statements = splitStatements(prepared);
        int idx = 0;
        for (String raw : statements) {
            idx++;
            String stmt = stripComments(raw).trim();
            if (stmt.isEmpty()) continue;

            if (CREATE_TABLE_LEAD.matcher(stmt).find()) {
                CreateTable ct;
                try {
                    Statement parsed = CCJSqlParserUtil.parse(stmt);
                    if (!(parsed instanceof CreateTable)) {
                        // Leading keywords matched but parser saw something else — skip.
                        continue;
                    }
                    ct = (CreateTable) parsed;
                } catch (JSQLParserException e) {
                    throw new SqlParseException(null, idx, snippet(stmt),
                            "Could not parse CREATE TABLE statement #" + idx + ": " + e.getMessage(), e);
                }
                result.add(toTableModel(ct));
                continue;
            }

            if (IGNORABLE_STATEMENT.matcher(stmt).find()) {
                log.debug("Skipping non-table statement #{}: {}", idx, snippet(stmt));
                continue;
            }

            // Unknown leading keyword — try parsing; if it parses to anything
            // other than CreateTable, ignore. If it fails outright, treat as
            // a hard error so the user is not silently confused.
            try {
                Statement parsed = CCJSqlParserUtil.parse(stmt);
                if (parsed instanceof CreateTable ct) {
                    result.add(toTableModel(ct));
                }
            } catch (JSQLParserException e) {
                throw new SqlParseException(null, idx, snippet(stmt),
                        "Could not parse statement #" + idx + ": " + e.getMessage(), e);
            }
        }
        return result;
    }

    private static String normalizeOracle(String sql) {
        return ORACLE_NUMBER_WILDCARD.matcher(sql).replaceAll(m ->
                m.group(1) == null ? "NUMBER(38)" : "NUMBER(38" + m.group(1) + ")");
    }

    private static String normalizeDb2(String sql) {
        String out = DB2_CURRENT_TS_DATE_TIME.matcher(sql).replaceAll(m ->
                "CURRENT_" + m.group(1).toUpperCase(Locale.ROOT));
        // Strip DB2-for-i column short-names before the broader FOR…DATA rule so
        // the two patterns do not overlap.
        out = DB2_FOR_COLUMN.matcher(out).replaceAll("");
        out = DB2_CCSID.matcher(out).replaceAll("");
        out = DB2_FOR_DATA.matcher(out).replaceAll("");
        out = DB2_QUALIFIED_CONSTRAINT.matcher(out).replaceAll("$1$2");
        return out;
    }

    /** Split on {@code ;} while respecting single-quoted string literals (with
     *  doubled-quote escape) so that semicolons inside a default value or a
     *  COMMENT ON … IS '…;…' do not break the statement. */
    static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inSingle) {
                cur.append(c);
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        cur.append('\''); i++;
                    } else {
                        inSingle = false;
                    }
                }
            } else if (c == '\'') {
                cur.append(c);
                inSingle = true;
            } else if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Strip {@code --} line comments and {@code /* … *\/} block comments so
     *  the leading-keyword classifier sees actual SQL. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        boolean inSingle = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (inSingle) {
                out.append(c);
                if (c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        out.append('\''); i += 2; continue;
                    }
                    inSingle = false;
                }
                i++;
                continue;
            }
            if (c == '\'') { inSingle = true; out.append(c); i++; continue; }
            if (c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
                int eol = s.indexOf('\n', i);
                if (eol < 0) break;
                i = eol + 1;
                out.append('\n');
                continue;
            }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) break;
                i = end + 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String snippet(String stmt) {
        String oneLine = stmt.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    /** Thrown when the submitted DDL cannot be parsed. Callers in a request
     *  context should surface this as HTTP 400. {@code depId} is populated by
     *  the controller that knows which dep-keyed script failed; the generator
     *  itself leaves it null. {@code statementIndex} is 1-based. */
    public static class SqlParseException extends RuntimeException {
        private final String depId;
        private final Integer statementIndex;
        private final String statementSnippet;
        public SqlParseException(String message, Throwable cause) {
            this(null, null, null, message, cause);
        }
        public SqlParseException(String depId, String message, Throwable cause) {
            this(depId, null, null, message, cause);
        }
        public SqlParseException(String depId, Integer statementIndex,
                                 String statementSnippet, String message, Throwable cause) {
            super(message, cause);
            this.depId = depId;
            this.statementIndex = statementIndex;
            this.statementSnippet = statementSnippet;
        }
        public String depId() { return depId; }
        public Integer statementIndex() { return statementIndex; }
        public String statementSnippet() { return statementSnippet; }
    }

    private TableModel toTableModel(CreateTable ct) {
        String tableName = unquote(ct.getTable().getName());
        String schema = unquote(ct.getTable().getSchemaName());
        // Be robust to JSqlParser variants that leave the schema fused onto the
        // name (e.g. "ENTV.TD_APP_STP"): split on the last dot ourselves.
        if (tableName != null && tableName.contains(".")) {
            int dot = tableName.lastIndexOf('.');
            if (schema == null || schema.isBlank()) schema = tableName.substring(0, dot);
            tableName = tableName.substring(dot + 1);
        }
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
                        c.nullable(), isPk, c.isAutoIncrement(), c.isForeignKey(), c.isUnique()));
            }
            columns = fixed;
        }

        return new TableModel(tableName, schema, columns, pkColumns, fks);
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
        // Inline column-level UNIQUE (table-level UNIQUE(...) constraints are not tracked here).
        boolean isUnique = specStr.contains("UNIQUE");
        return new ColumnModel(name, rawType, precision, scale, !notNull, isPk, isAutoInc, isFk, isUnique);
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
        sb.append("@Table(name = \"").append(table.name()).append('"');
        if (table.schema() != null && !table.schema().isBlank()) {
            sb.append(", schema = \"").append(table.schema()).append('"');
        }
        sb.append(")\n");
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

    private GeneratedJavaFile renderIdClass(TableModel table, SqlDialect dialect,
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
        StringBuilder fields = new StringBuilder();
        for (String pkCol : table.pkColumns()) {
            ColumnModel col = table.columns().stream()
                    .filter(c -> c.name().equalsIgnoreCase(pkCol))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "PK column '" + pkCol + "' not found in table " + table.name()));
            JavaType jt = TypeMappers.map(dialect, col.rawType(), col.precision(), col.scale());
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

        IdType id = resolveIdType(table, dialect, entityPkg, entityClass);
        String idType = id.simpleName();
        Set<String> imports = new LinkedHashSet<>();
        imports.add("org.springframework.data.jpa.repository.JpaRepository");
        imports.add("org.springframework.stereotype.Repository");
        imports.add(entityPkg + "." + entityClass);
        imports.addAll(id.imports());

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

    // ── REST stack rendering (Service / DTO / Mapper / Controller) ─────────────

    /**
     * A view-model member of an entity, derived by replaying the exact same
     * column→field decisions {@link #processColumn} makes for the entity body
     * (using the same naming helpers), so association field names line up.
     * A {@code SCALAR} member is a plain column; an {@code assoc} member is a
     * JPA association, with {@code flatten} set when it can be reduced to a
     * single foreign-key id field on the DTO.
     */
    private record Member(boolean assoc, String fieldName, String pascalName,
                          String javaType, Set<String> imports, boolean isPk, FlattenInfo flatten) {}

    private record FlattenInfo(String dtoIdField, String idType, Set<String> idImports,
                               String targetEntity, String targetPkPascal) {}

    private record EntityView(List<Member> members, boolean hasSkippedColumns) {}

    private record IdType(String simpleName, Set<String> imports) {}

    private record PkPart(String name, String javaType, Set<String> imports) {}

    /** Mirror of {@link #processColumn}'s branching, producing view-model members
     *  instead of entity source. Field names match because both paths share the
     *  same {@link #pickAssociationFieldName}/{@link #toCamelCase} helpers and
     *  iterate columns in the same order against a shared used-name set. */
    private EntityView analyze(TableModel table, SqlDialect dialect,
                               Map<String, TableModel> tablesByName, Set<String> knownTableNames) {
        List<Member> members = new ArrayList<>();
        Set<ForeignKey> emittedFks = new HashSet<>();
        Set<String> used = new HashSet<>();
        boolean skipped = false;
        for (ColumnModel c : table.columns()) {
            JavaType jt = TypeMappers.map(dialect, c.rawType(), c.precision(), c.scale());

            ForeignKey compositeFk = findCompositeForeignKey(c.name(), table.foreignKeys());
            if (compositeFk != null) {
                boolean refKnown = knownTableNames.contains(compositeFk.referencedTable());
                if (refKnown && !emittedFks.contains(compositeFk)) {
                    String fieldName = pickAssociationFieldName(null, compositeFk, used, true);
                    used.add(fieldName);
                    emittedFks.add(compositeFk);
                    members.add(new Member(true, fieldName, capitalize(fieldName),
                            toPascalCase(compositeFk.referencedTable()), Set.of(), false, null));
                    skipped = true; // composite FK can't flatten to a single id
                }
                if (c.isPk()) {
                    members.add(scalarMember(c, jt, used));
                } else if (!refKnown) {
                    members.add(scalarMember(c, jt, used));
                } else {
                    skipped = true; // non-PK column absorbed by the association
                }
                continue;
            }

            ForeignKey singleFk = findSingleForeignKey(c.name(), table.foreignKeys());
            if (singleFk != null && knownTableNames.contains(singleFk.referencedTable())) {
                String fieldName = pickAssociationFieldName(c, singleFk, used, false);
                used.add(fieldName);
                emittedFks.add(singleFk);
                FlattenInfo flatten = buildFlatten(c, singleFk, dialect, tablesByName);
                if (flatten == null) skipped = true;
                members.add(new Member(true, fieldName, capitalize(fieldName),
                        toPascalCase(singleFk.referencedTable()), Set.of(), false, flatten));
                continue;
            }

            members.add(scalarMember(c, jt, used));
        }
        return new EntityView(members, skipped);
    }

    private Member scalarMember(ColumnModel c, JavaType jt, Set<String> used) {
        String fieldName = toCamelCase(c.name());
        if (used.contains(fieldName)) {
            int n = 2;
            while (used.contains(fieldName + n)) n++;
            fieldName = fieldName + n;
        }
        used.add(fieldName);
        return new Member(false, fieldName, capitalize(fieldName),
                jt.simpleName(), jt.imports(), c.isPk(), null);
    }

    /** Resolve the flattened DTO id field for a single-column FK, or null when
     *  the target can't be addressed by one id (composite/unknown PK). */
    private FlattenInfo buildFlatten(ColumnModel fkCol, ForeignKey fk, SqlDialect dialect,
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

    private IdType resolveIdType(TableModel table, SqlDialect dialect, String entityPkg, String entityClass) {
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

    private List<PkPart> pkParts(TableModel table, SqlDialect dialect) {
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
    private String singleGeneratedPkSetter(TableModel table) {
        if (table.pkColumns().size() != 1) return null;
        ColumnModel pk = table.columns().stream().filter(ColumnModel::isPk).findFirst().orElse(null);
        if (pk == null || !pk.isAutoIncrement()) return null;
        return capitalize(toCamelCase(pk.name()));
    }

    private GeneratedJavaFile renderService(TableModel table, SqlDialect dialect, String packageName,
                                            SqlDepOptions opts, Map<String, TableModel> tablesByName,
                                            Set<String> knownTableNames) {
        String entityClass = toPascalCase(table.name());
        String entityPkg = packageName + "." + opts.subPackage();
        IdType id = resolveIdType(table, dialect, entityPkg, entityClass);
        EntityView view = analyze(table, dialect, tablesByName, knownTableNames);

        Set<String> imports = new TreeSet<>();
        imports.add(entityPkg + "." + entityClass);
        imports.addAll(id.imports());
        imports.add(packageName + ".repository." + entityClass + "Repository");
        imports.add("org.springframework.data.domain.Page");
        imports.add("org.springframework.data.domain.Pageable");
        imports.add("org.springframework.stereotype.Service");
        imports.add("org.springframework.transaction.annotation.Transactional");

        String genPkSetter = singleGeneratedPkSetter(table);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(".service;\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        sb.append('\n');
        sb.append("@Service\n@Transactional\n");
        sb.append("public class ").append(entityClass).append("Service {\n\n");
        sb.append("    private final ").append(entityClass).append("Repository repository;\n\n");
        sb.append("    public ").append(entityClass).append("Service(")
                .append(entityClass).append("Repository repository) {\n");
        sb.append("        this.repository = repository;\n    }\n\n");
        sb.append("    @Transactional(readOnly = true)\n");
        sb.append("    public Page<").append(entityClass).append("> findAll(Pageable pageable) {\n");
        sb.append("        return repository.findAll(pageable);\n    }\n\n");
        sb.append("    @Transactional(readOnly = true)\n");
        sb.append("    public ").append(entityClass).append(" findById(").append(id.simpleName()).append(" id) {\n");
        sb.append("        return repository.findById(id).orElseThrow(() ->\n");
        sb.append("                new java.util.NoSuchElementException(\"")
                .append(entityClass).append(" \" + id + \" not found\"));\n    }\n\n");
        sb.append("    public ").append(entityClass).append(" create(").append(entityClass).append(" entity) {\n");
        if (genPkSetter != null) sb.append("        entity.set").append(genPkSetter).append("(null);\n");
        sb.append("        return repository.save(entity);\n    }\n\n");
        sb.append("    public ").append(entityClass).append(" update(")
                .append(id.simpleName()).append(" id, ").append(entityClass).append(" updated) {\n");
        sb.append("        ").append(entityClass).append(" existing = findById(id);\n");
        for (Member m : view.members()) {
            if (m.isPk()) continue; // never overwrite the key
            sb.append("        existing.set").append(m.pascalName())
                    .append("(updated.get").append(m.pascalName()).append("());\n");
        }
        sb.append("        return repository.save(existing);\n    }\n\n");
        sb.append("    public void delete(").append(id.simpleName()).append(" id) {\n");
        sb.append("        repository.deleteById(id);\n    }\n}\n");

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/service/" + entityClass + "Service.java", sb.toString());
    }

    private GeneratedJavaFile renderDto(TableModel table, SqlDialect dialect, String packageName,
                                        SqlDepOptions opts, Map<String, TableModel> tablesByName,
                                        Set<String> knownTableNames) {
        String entityClass = toPascalCase(table.name());
        String entityPkg = packageName + "." + opts.subPackage();
        EntityView view = analyze(table, dialect, tablesByName, knownTableNames);
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

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(".dto;\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        if (!imports.isEmpty()) sb.append('\n');
        if (view.hasSkippedColumns()) {
            sb.append("// TODO: some columns (composite or unresolved foreign keys) are omitted from this DTO\n");
        }

        List<String> comps = new ArrayList<>();
        for (Member m : scalars) comps.add("        " + m.javaType() + " " + m.fieldName());
        for (Member m : flat) comps.add("        " + m.flatten().idType() + " " + m.flatten().dtoIdField());
        sb.append("public record ").append(entityClass).append("Dto(\n");
        sb.append(String.join(",\n", comps)).append("\n)");

        if (!inline) {
            sb.append(" {\n}\n");
            return new GeneratedJavaFile(
                    "src/main/java/{{packagePath}}/dto/" + entityClass + "Dto.java", sb.toString());
        }

        sb.append(" {\n\n");
        sb.append("    public static ").append(entityClass).append("Dto from(")
                .append(entityClass).append(" entity) {\n");
        sb.append("        return new ").append(entityClass).append("Dto(\n");
        List<String> args = new ArrayList<>();
        for (Member m : scalars) args.add("                entity.get" + m.pascalName() + "()");
        for (Member m : flat) {
            args.add("                entity.get" + m.pascalName() + "() == null ? null : entity.get"
                    + m.pascalName() + "().get" + m.flatten().targetPkPascal() + "()");
        }
        sb.append(String.join(",\n", args)).append("\n        );\n    }\n\n");
        sb.append("    public ").append(entityClass).append(" toEntity() {\n");
        sb.append("        ").append(entityClass).append(" entity = new ").append(entityClass).append("();\n");
        for (Member m : scalars) {
            sb.append("        entity.set").append(m.pascalName()).append("(this.").append(m.fieldName()).append(");\n");
        }
        for (Member m : flat) {
            FlattenInfo fi = m.flatten();
            sb.append("        if (this.").append(fi.dtoIdField()).append(" != null) {\n");
            sb.append("            ").append(fi.targetEntity()).append(' ').append(m.fieldName())
                    .append(" = new ").append(fi.targetEntity()).append("();\n");
            sb.append("            ").append(m.fieldName()).append(".set").append(fi.targetPkPascal())
                    .append("(this.").append(fi.dtoIdField()).append(");\n");
            sb.append("            entity.set").append(m.pascalName()).append('(').append(m.fieldName()).append(");\n");
            sb.append("        }\n");
        }
        sb.append("        return entity;\n    }\n}\n");

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/dto/" + entityClass + "Dto.java", sb.toString());
    }

    private GeneratedJavaFile renderMapper(TableModel table, SqlDialect dialect, String packageName,
                                           SqlDepOptions opts, Map<String, TableModel> tablesByName,
                                           Set<String> knownTableNames) {
        String entityClass = toPascalCase(table.name());
        String entityPkg = packageName + "." + opts.subPackage();
        EntityView view = analyze(table, dialect, tablesByName, knownTableNames);
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

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(".mapper;\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        sb.append('\n');
        sb.append("@Mapper(config = MapstructConfig.class)\n");
        sb.append("public interface ").append(entityClass).append("Mapper {\n\n");
        for (Member m : flat) {
            sb.append("    @Mapping(target = \"").append(m.flatten().dtoIdField())
                    .append("\", source = \"").append(m.fieldName()).append('.')
                    .append(decapitalize(m.flatten().targetPkPascal())).append("\")\n");
        }
        sb.append("    ").append(entityClass).append("Dto toDto(").append(entityClass).append(" entity);\n\n");
        for (Member m : flat) {
            sb.append("    @Mapping(target = \"").append(m.fieldName())
                    .append("\", source = \"").append(m.flatten().dtoIdField())
                    .append("\", qualifiedByName = \"").append(m.fieldName()).append("FromId\")\n");
        }
        sb.append("    ").append(entityClass).append(" toEntity(").append(entityClass).append("Dto dto);\n\n");
        sb.append("    List<").append(entityClass).append("Dto> toDtoList(List<")
                .append(entityClass).append("> entities);\n");
        for (Member m : flat) {
            FlattenInfo fi = m.flatten();
            sb.append('\n');
            sb.append("    @Named(\"").append(m.fieldName()).append("FromId\")\n");
            sb.append("    default ").append(fi.targetEntity()).append(' ').append(m.fieldName())
                    .append("FromId(").append(fi.idType()).append(" id) {\n");
            sb.append("        if (id == null) return null;\n");
            sb.append("        ").append(fi.targetEntity()).append(" e = new ").append(fi.targetEntity()).append("();\n");
            sb.append("        e.set").append(fi.targetPkPascal()).append("(id);\n");
            sb.append("        return e;\n    }\n");
        }
        sb.append("}\n");

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/mapper/" + entityClass + "Mapper.java", sb.toString());
    }

    private GeneratedJavaFile renderController(TableModel table, SqlDialect dialect, String packageName,
                                               SqlDepOptions opts, Map<String, TableModel> tablesByName,
                                               Set<String> knownTableNames) {
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

        String pathVars = parts.stream()
                .map(p -> "@PathVariable " + p.javaType() + ' ' + p.name())
                .collect(Collectors.joining(", "));
        String pkPath = parts.stream().map(p -> "{" + p.name() + "}")
                .collect(Collectors.joining("/", "/", ""));
        String newKey = "new " + entityClass + "Id("
                + parts.stream().map(PkPart::name).collect(Collectors.joining(", ")) + ")";

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(".controller;\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        sb.append('\n');
        sb.append("@RestController\n");
        sb.append("@RequestMapping(\"/api/").append(kebab).append("\")\n");
        sb.append("public class ").append(entityClass).append("Controller {\n\n");
        sb.append("    private final ").append(entityClass).append("Service service;\n");
        if (mapstruct) sb.append("    private final ").append(entityClass).append("Mapper mapper;\n");
        sb.append('\n');
        sb.append("    public ").append(entityClass).append("Controller(")
                .append(entityClass).append("Service service");
        if (mapstruct) sb.append(", ").append(entityClass).append("Mapper mapper");
        sb.append(") {\n");
        sb.append("        this.service = service;\n");
        if (mapstruct) sb.append("        this.mapper = mapper;\n");
        sb.append("    }\n\n");

        // list
        sb.append("    @GetMapping\n");
        sb.append("    public Page<").append(retType).append("> list(Pageable pageable) {\n");
        sb.append("        return service.findAll(pageable)").append(listMap).append(";\n    }\n\n");

        // getOne
        if (composite) {
            sb.append("    @GetMapping(\"").append(pkPath).append("\")\n");
            sb.append("    public ").append(retType).append(" getOne(").append(pathVars).append(") {\n");
            sb.append("        return ").append(convOpen).append("service.findById(").append(newKey).append(")")
                    .append(convClose).append(";\n    }\n\n");
        } else {
            sb.append("    @GetMapping(\"/{id}\")\n");
            sb.append("    public ").append(retType).append(" getOne(@PathVariable ")
                    .append(id.simpleName()).append(" id) {\n");
            sb.append("        return ").append(convOpen).append("service.findById(id)")
                    .append(convClose).append(";\n    }\n\n");
        }

        // create
        sb.append("    @PostMapping\n");
        sb.append("    public ").append(retType).append(" create(@RequestBody ").append(retType).append(" body) {\n");
        sb.append("        return ").append(convOpen).append("service.create(").append(bodyToEntity).append(")")
                .append(convClose).append(";\n    }\n\n");

        // update
        if (composite) {
            sb.append("    @PutMapping(\"").append(pkPath).append("\")\n");
            sb.append("    public ").append(retType).append(" update(").append(pathVars)
                    .append(", @RequestBody ").append(retType).append(" body) {\n");
            sb.append("        return ").append(convOpen).append("service.update(").append(newKey).append(", ")
                    .append(bodyToEntity).append(")").append(convClose).append(";\n    }\n\n");
        } else {
            sb.append("    @PutMapping(\"/{id}\")\n");
            sb.append("    public ").append(retType).append(" update(@PathVariable ")
                    .append(id.simpleName()).append(" id, @RequestBody ").append(retType).append(" body) {\n");
            sb.append("        return ").append(convOpen).append("service.update(id, ")
                    .append(bodyToEntity).append(")").append(convClose).append(";\n    }\n\n");
        }

        // delete
        if (composite) {
            sb.append("    @DeleteMapping(\"").append(pkPath).append("\")\n");
            sb.append("    public ResponseEntity<Void> delete(").append(pathVars).append(") {\n");
            sb.append("        service.delete(").append(newKey).append(");\n");
        } else {
            sb.append("    @DeleteMapping(\"/{id}\")\n");
            sb.append("    public ResponseEntity<Void> delete(@PathVariable ")
                    .append(id.simpleName()).append(" id) {\n");
            sb.append("        service.delete(id);\n");
        }
        sb.append("        return ResponseEntity.noContent().build();\n    }\n\n");

        sb.append("    @ExceptionHandler(java.util.NoSuchElementException.class)\n");
        sb.append("    public ResponseEntity<Void> handleNotFound(java.util.NoSuchElementException ex) {\n");
        sb.append("        return ResponseEntity.notFound().build();\n    }\n}\n");

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/controller/" + entityClass + "Controller.java", sb.toString());
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

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Lower-kebab a raw table name for use as a REST collection path segment
     *  ({@code TD_APP_STP} → {@code td-app-stp}, {@code line_items} → {@code line-items}). */
    private static String toKebab(String raw) {
        String[] parts = raw.split("[_\\-\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('-');
            sb.append(p.toLowerCase(Locale.ROOT));
        }
        return sb.length() == 0 ? "items" : sb.toString();
    }

    private static boolean isStringType(JavaType jt) {
        return "String".equals(jt.simpleName());
    }

    private static boolean isDecimalType(JavaType jt) {
        return "BigDecimal".equals(jt.simpleName());
    }
}
