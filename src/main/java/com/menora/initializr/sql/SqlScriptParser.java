package com.menora.initializr.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.menora.initializr.sql.SqlEntityGenerator.*;
import static com.menora.initializr.sql.SqlSelectReader.*;
import static com.menora.initializr.sql.SqlEntityRenderer.*;
import static com.menora.initializr.sql.SqlRestRenderer.*;
import static com.menora.initializr.sql.SqlJavaNames.*;

/** Turns a pasted script into {@link TableModel}s: the dialect clean-up, the statement splitter and the model of each table and view. Part of {@link SqlEntityGenerator}. */
final class SqlScriptParser {

    private SqlScriptParser() {}

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
     *  real (long) column name is the one we keep. IBM i system names may
     *  contain {@code @}, {@code #} and {@code $} ({@code @SOXEN#}, {@code @#PLS_SXNH})
     *  or be double-quoted, so the name class is wider than {@code \w}. */
    private static final Pattern DB2_FOR_COLUMN =
            Pattern.compile("\\bFOR\\s+COLUMN\\s+(?:\"[^\"]+\"|[\\w@#$]+)", Pattern.CASE_INSENSITIVE);

    /** Db2-for-i markers ({@code FOR COLUMN}, {@code CCSID n}) that appear in no
     *  other dialect's DDL — used to auto-route Db2 normalization when the caller
     *  left the dialect on the H2 default. */
    private static final Pattern DB2I_MARKER =
            Pattern.compile("\\bFOR\\s+COLUMN\\b|\\bCCSID\\s+(?:\\d+|UNICODE)\\b", Pattern.CASE_INSENSITIVE);

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

    /** SQL Server index / storage option clause ({@code WITH (PAD_INDEX = OFF, …)})
     *  hung off a PRIMARY KEY / index; JSqlParser has no grammar for it. T-SQL
     *  index options do not nest parentheses, so a flat {@code (…)} match is safe. */
    private static final Pattern MSSQL_WITH_OPTIONS =
            Pattern.compile("\\bWITH\\s*\\([^)]*\\)", Pattern.CASE_INSENSITIVE);

    /** SQL Server filegroup placement ({@code ON [PRIMARY]}, {@code TEXTIMAGE_ON [..]},
     *  {@code FILESTREAM_ON [..]}). Bracket-anchored so it never swallows an FK's
     *  {@code ON DELETE}/{@code ON UPDATE} action (those are not bracketed). */
    private static final Pattern MSSQL_FILEGROUP =
            Pattern.compile("\\b(?:TEXTIMAGE_ON|FILESTREAM_ON|ON)\\s+\\[[^\\]]+\\]",
                    Pattern.CASE_INSENSITIVE);

    /** SQL Server index clustering keyword on a PRIMARY KEY / UNIQUE / index clause. */
    private static final Pattern MSSQL_CLUSTERED =
            Pattern.compile("\\b(?:NON)?CLUSTERED\\b", Pattern.CASE_INSENSITIVE);

    /** A bracket-quoted identifier ({@code [dbo]}, {@code [id]}, {@code [nvarchar]}).
     *  Requires a real identifier inside, so a Postgres array type {@code int[]}
     *  (empty {@code []}) is not mistaken for T-SQL. */
    private static final Pattern MSSQL_BRACKET_IDENT =
            Pattern.compile("\\[([A-Za-z_]\\w*)\\]");

    /** Statements we safely skip — valid DDL the wizard does not act on.
     *  Matches the leading keyword(s) of the trimmed statement. */
    private static final Pattern IGNORABLE_STATEMENT = Pattern.compile(
            "^\\s*(?:COMMENT\\s+ON|CREATE\\s+(?:UNIQUE\\s+)?INDEX|CREATE\\s+SEQUENCE|"
                    + "GRANT\\b|REVOKE\\b|ALTER\\b|SET\\b|USE\\b|DROP\\b|ANALYZE\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_TABLE_LEAD = Pattern.compile(
            "^\\s*CREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+|TEMPORARY\\s+|TEMP\\s+)?TABLE\\b",
            Pattern.CASE_INSENSITIVE);

    static List<TableModel> parseTables(String sql, SqlDialect dialect) {
        String prepared = sql;
        if (dialect == SqlDialect.ORACLE)   prepared = normalizeOracle(prepared);
        // DB2 when explicitly selected, or auto-detected by Db2-for-i clauses
        // (FOR COLUMN, CCSID) under any other dialect (incl. the H2 default) so a
        // pasted iSeries export parses even if the user didn't pick "DB2".
        else if (dialect == SqlDialect.DB2 || looksLikeDb2i(prepared)) prepared = normalizeDb2(prepared);
        // MSSQL when explicitly selected, or auto-detected by its bracket-quoting
        // ([dbo].[t], [col]) under any other dialect (incl. the H2 default) so a
        // pasted T-SQL script parses even if the user didn't pick "SQL Server".
        else if (dialect == SqlDialect.MSSQL || looksLikeTSql(prepared)) prepared = normalizeMssql(prepared);

        List<TableModel> result = new ArrayList<>();
        // PascalCase class names already used (lower-cased), so a SELECT-derived view name
        // can be deduped against table entities and other views.
        Set<String> usedClassNames = new HashSet<>();
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
                TableModel tm = toTableModel(ct, stmt);
                usedClassNames.add(toPascalCase(tm.name()).toLowerCase(Locale.ROOT));
                result.add(tm);
                continue;
            }

            // ALTER TABLE … ADD PRIMARY KEY / FOREIGN KEY / UNIQUE: how pg_dump, SSMS and most
            // schema exports declare a table's constraints. Anything else an ALTER does is skipped.
            if (SqlConstraintReader.isAlterTable(stmt)) {
                SqlConstraintReader.applyAlterTable(result, stmt);
                continue;
            }

            // A unique index over plain columns is a unique constraint to the application.
            if (SqlConstraintReader.isCreateUniqueIndex(stmt)) {
                SqlConstraintReader.applyUniqueIndex(result, stmt);
                continue;
            }

            if (IGNORABLE_STATEMENT.matcher(stmt).find()) {
                log.debug("Skipping non-table statement #{}: {}", idx, snippet(stmt));
                continue;
            }

            // A SELECT becomes a read-only @Immutable/@Subselect view entity.
            if (SELECT_LEAD.matcher(stmt).find()) {
                result.add(toViewTableModel(stmt, dialect, usedClassNames));
                continue;
            }

            // Unknown leading keyword — try parsing; if it parses to anything
            // other than CreateTable, ignore. If it fails outright, treat as
            // a hard error so the user is not silently confused.
            try {
                Statement parsed = CCJSqlParserUtil.parse(stmt);
                if (parsed instanceof CreateTable ct) {
                    result.add(toTableModel(ct, stmt));
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

    /** True when the script carries Db2-for-i column clauses ({@code FOR COLUMN},
     *  {@code CCSID n}) — used to auto-route normalization when the caller didn't
     *  explicitly pick the DB2 dialect. */
    private static boolean looksLikeDb2i(String sql) {
        return DB2I_MARKER.matcher(sql).find();
    }

    /** True when the script uses SQL Server bracket-quoting ({@code [ident]}) — a
     *  reliable T-SQL signal, used to auto-route normalization when the caller
     *  didn't explicitly pick the MSSQL dialect. */
    private static boolean looksLikeTSql(String sql) {
        return MSSQL_BRACKET_IDENT.matcher(sql).find();
    }

    /**
     * Rewrites SQL Server T-SQL into the JSqlParser-digestible subset. JSqlParser 4.9
     * can't parse bracket-quoted identifiers/type names ({@code [dbo].[t]},
     * {@code [nvarchar](36)}), {@code CLUSTERED} key clauses, {@code WITH (…)} index
     * options, or {@code ON [PRIMARY]} filegroups, so strip them. Order matters: the
     * bracket-identifier strip runs last because the filegroup rule keys off brackets.
     */
    private static String normalizeMssql(String sql) {
        String out = MSSQL_WITH_OPTIONS.matcher(sql).replaceAll("");
        out = MSSQL_FILEGROUP.matcher(out).replaceAll("");
        out = MSSQL_CLUSTERED.matcher(out).replaceAll("");
        out = MSSQL_BRACKET_IDENT.matcher(out).replaceAll("$1");
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
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                // A line comment is carried whole: a ';' or an apostrophe in it is prose, not SQL
                // ("-- an enum; the client's own" used to cut the statement in two).
                int end = sql.indexOf('\n', i);
                if (end < 0) end = sql.length();
                cur.append(sql, i, end);
                i = end - 1;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? sql.length() : end + 2;
                cur.append(sql, i, end);
                i = end - 1;
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
                appendNewline(out);
                continue;
            }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) break;
                i = end + 2;
                continue;
            }
            if (c == '\n') appendNewline(out); else out.append(c);
            i++;
        }
        return out.toString();
    }

    /** JSqlParser 4.9 reads three consecutive bare LFs as a statement terminator
     *  ({@code ST_SEMICOLON}), so a run of blank lines inside a CREATE TABLE splits
     *  the statement and fails the parse. Two adjacent {@code --} comment lines
     *  (an iSeries export is full of them) would produce exactly that once blanked
     *  out above, as would two real blank lines in a hand-written script. Cap runs
     *  at two LFs (one blank line) outside string literals. */
    private static void appendNewline(StringBuilder out) {
        int n = out.length();
        if (n >= 2 && out.charAt(n - 1) == '\n' && out.charAt(n - 2) == '\n') return;
        out.append('\n');
    }

    static String snippet(String stmt) {
        String oneLine = stmt.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    private static TableModel toTableModel(CreateTable ct, String sourceSql) {
        // Names the DDL quoted keep their exact spelling in the generated code (SqlNames).
        Set<String> quoted = new HashSet<>();
        String rawName = ct.getTable().getName();
        String rawSchema = ct.getTable().getSchemaName();
        // Be robust to JSqlParser variants that leave the schema fused onto the
        // name (e.g. "ENTV.TD_APP_STP"): split on the last dot ourselves, before
        // unquoting, so "VNRF"."Orders" splits into two quoted parts.
        if (rawName != null && rawName.contains(".")) {
            int dot = rawName.lastIndexOf('.');
            if (rawSchema == null || rawSchema.isBlank()) rawSchema = rawName.substring(0, dot);
            rawName = rawName.substring(dot + 1);
        }
        String tableName = unquote(rawName, quoted);
        String schema = unquote(rawSchema, quoted);
        List<String> pkColumns = new ArrayList<>();
        List<ForeignKey> fks = new ArrayList<>();
        List<List<String>> uniques = new ArrayList<>();
        Set<String> fkColumnNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        if (ct.getIndexes() != null) {
            for (Index ix : ct.getIndexes()) {
                String type = ix.getType() == null ? "" : ix.getType().toUpperCase(Locale.ROOT);
                if (type.contains("PRIMARY KEY")) {
                    for (String col : ix.getColumnsNames()) {
                        pkColumns.add(unquote(col, quoted));
                    }
                }
                // A table-level UNIQUE (…): one column is that column's own; several stay together.
                if (type.contains("UNIQUE") && !type.contains("PRIMARY KEY") && !(ix instanceof ForeignKeyIndex)
                        && ix.getColumnsNames() != null) {
                    uniques.add(ix.getColumnsNames().stream().map(n -> unquote(n, quoted)).toList());
                }
                if (ix instanceof ForeignKeyIndex fk) {
                    List<String> cols = fk.getColumnsNames().stream().map(n -> unquote(n, quoted)).toList();
                    String refTable = fk.getTable() != null ? unquote(fk.getTable().getName(), quoted) : "";
                    List<String> refCols = fk.getReferencedColumnNames() == null ? List.of()
                            : fk.getReferencedColumnNames().stream().map(n -> unquote(n, quoted)).toList();
                    fks.add(new ForeignKey(cols, refTable, refCols));
                    fkColumnNames.addAll(cols);
                }
            }
        }

        List<ColumnModel> columns = new ArrayList<>();
        List<ColumnDefinition> defs = ct.getColumnDefinitions() == null
                ? Collections.emptyList() : ct.getColumnDefinitions();
        // A foreign key written on the column itself (team_id BIGINT REFERENCES teams(id)) — the
        // usual way to write one in PostgreSQL and MySQL scripts. Only the table-level form was read.
        for (ColumnDefinition cd : defs) {
            if (cd.getColumnSpecs() == null) continue;
            Matcher ref = SqlConstraintReader.INLINE_REFERENCES.matcher(String.join(" ", cd.getColumnSpecs()));
            if (!ref.find()) continue;
            String column = unquote(cd.getColumnName(), quoted);
            if (fkColumnNames.contains(column)) continue;
            List<String> refCols = ref.group(2) == null || ref.group(2).isBlank()
                    ? List.of() : List.of(unquote(ref.group(2).trim(), quoted));
            fks.add(new ForeignKey(List.of(column), SqlConstraintReader.bareName(ref.group(1).replaceAll("\\s+", "")), refCols));
            fkColumnNames.add(column);
        }
        for (ColumnDefinition cd : defs) {
            columns.add(toColumnModel(cd, pkColumns, fkColumnNames, quoted));
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

        TableModel table = new TableModel(tableName, schema, columns, pkColumns, fks, null, sourceSql, quoted);
        for (List<String> unique : uniques) {
            List<String> canonical = new ArrayList<>();
            for (String name : unique) {
                table.columns().stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst()
                        .ifPresent(c -> canonical.add(c.name()));
            }
            if (canonical.size() == unique.size()) table = table.withUnique(canonical);
        }
        // CHECK (col IN ('A', 'B')) limits a column to a fixed set of values. Read from the
        // statement's text: a column-level check only survives JSqlParser as loose tokens.
        table = SqlConstraintReader.withChecks(table, sourceSql);
        return table;
    }

    /**
     * The enum a column's field is typed with, or null when it stays its mapped type. A column a
     * CHECK limits to a set of values becomes an enum only when that is safe on an existing
     * database: the values go to the column as the constants are spelt
     * ({@code @Enumerated(EnumType.STRING)}), so every value must already be a Java identifier —
     * {@code 'open'} is the constant {@code open}, and {@code 'in progress'} leaves the column a
     * String. Never a key or a foreign key, and never under a name another class already has.
     */
    static String enumClassOf(TableModel table, ColumnModel c, JavaType mapped, Set<String> knownTableNames) {
        List<String> values = table.enumValuesOf(c.name());
        if (values == null || c.isPk() || c.isForeignKey() || !isStringType(mapped)) return null;
        if (SqlConstraintReader.enumConstants(values) == null) return null;
        String name = toPascalCase(table.name()) + toPascalCase(c.name());
        for (String other : knownTableNames) {
            if (toPascalCase(other).equals(name)) return null;
        }
        return name;
    }

    /** A column's field type: its mapped Java type, or the enum of {@link #enumClassOf}. */
    static JavaType fieldType(TableModel table, ColumnModel c, SqlDialect dialect, String entityPkg,
                              Set<String> knownTableNames) {
        JavaType mapped = TypeMappers.map(dialect, c.rawType(), c.precision(), c.scale());
        String enumClass = enumClassOf(table, c, mapped, knownTableNames);
        return enumClass == null ? mapped : JavaType.of(enumClass, entityPkg + "." + enumClass);
    }

    /**
     * Builds a read-only view {@link TableModel} from a single SELECT statement: reuses
     * {@link SqlEntityGenerator#parseSelectForImport} for the projected column labels (incl. the native-SQL
     * heuristic fallback), defaults every field to {@code String} (a SELECT has no column
     * types, and the wizard has no editing step), and makes the first column the PK.
     *
     * <p>Unlike the fullstack import flow, the wizard has no editor to add fields manually,
     * so a query whose columns can't be auto-named is a hard error rather than an empty view.
     */
    private static TableModel toViewTableModel(String stmt, SqlDialect dialect, Set<String> usedClassNames) {
        SelectProjection p = parseSelect(stmt, dialect);
        if (p.columns().isEmpty()) {
            throw new SqlParseException(null, null, snippet(stmt),
                    "Couldn't detect columns for this SELECT — alias each projected column with "
                            + "AS <name> so it maps to a field", null);
        }
        String base = (p.fromTable() != null && !p.fromTable().isBlank())
                ? toPascalCase(p.fromTable()) : "View";
        String className = base;
        if (usedClassNames.contains(className.toLowerCase(Locale.ROOT))) {
            int n = 2;
            while (usedClassNames.contains((base + n).toLowerCase(Locale.ROOT))) n++;
            className = base + n;
        }
        usedClassNames.add(className.toLowerCase(Locale.ROOT));

        List<ColumnModel> columns = new ArrayList<>();
        List<String> pkColumns = new ArrayList<>();
        List<String> cols = p.columns();
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            boolean isPk = i == 0;
            // rawType "VARCHAR" → String everywhere via TypeMappers, so the repository/service/
            // controller id-type derivation needs no view special-casing.
            columns.add(new ColumnModel(col, "VARCHAR", null, null, true, isPk, false, false, false));
            if (isPk) pkColumns.add(col);
        }
        return new TableModel(className, null, columns, pkColumns, List.of(), stmt);
    }

    private static ColumnModel toColumnModel(ColumnDefinition cd, List<String> tablePkCols, Set<String> fkColumnNames,
                                             Set<String> quoted) {
        String name = unquote(cd.getColumnName(), quoted);
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

    /** {@link #unquote(String)}, recording the bare name in {@code quoted} when it was quoted. */
    private static String unquote(String s, Set<String> quoted) {
        String bare = unquote(s);
        if (bare != null && !bare.equals(s)) quoted.add(bare);
        return bare;
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
}
