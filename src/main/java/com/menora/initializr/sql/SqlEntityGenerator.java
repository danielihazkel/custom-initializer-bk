package com.menora.initializr.sql;

import com.menora.initializr.gen.Naming;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import com.menora.initializr.config.WizardTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
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
        if (tables.stream().anyMatch(t -> rendersQuotedName(t, dialect))) {
            files.add(new GeneratedJavaFile(SqlNames.NAMING_STRATEGY_PATH, SqlNames.namingStrategySource(packageName)));
        }
        for (TableModel table : tables) {
            files.add(renderEntity(table, dialect, packageName, opts, knownTableNames));
            files.addAll(renderEnums(table, dialect, packageName, opts, knownTableNames));
            if (table.hasCompositePk()) {
                files.add(renderIdClass(table, dialect, packageName, opts));
            }
            // A generated REST stack needs a repository even if the per-table
            // toggle is off — and only makes sense for an addressable (PK) table.
            boolean apiOn = opts.apiMode().generatesApi() && !table.pkColumns().isEmpty();
            // …and a repository only for an entity: a table without a key is not one (see the entity template).
            if ((opts.generateRepositoryFor(table.name()) || apiOn) && !table.pkColumns().isEmpty()) {
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

    /** A SELECT's projected column names plus the raw query text, for the
     *  fullstack "Import from SELECT" → read-only {@code @Subselect} view flow.
     *  {@code heuristic} is true when the columns came from the regex fallback
     *  (JSqlParser couldn't parse the query — native/vendor SQL), so callers can
     *  warn the user to double-check the detected fields. */
    public record SelectProjection(String fromTable, List<String> columns, String rawSql,
                                   boolean heuristic) {}

    private static final Pattern SELECT_LEAD =
            Pattern.compile("^\\s*(?:WITH\\b|SELECT\\b|\\()", Pattern.CASE_INSENSITIVE);

    /** First top-level {@code FROM <ident>} after the outer projection — best-effort
     *  entity-name hint for the heuristic path. */
    private static final Pattern FROM_TABLE =
            Pattern.compile("\\bFROM\\s+([\\w$.\"`\\[\\]]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Parses a single SELECT and returns its projected column names (using each
     * item's alias, else the bare column name). Unlike CREATE TABLE, a SELECT
     * carries no column types — callers default every field to STRING and let the
     * user pick types.
     *
     * <p>When JSqlParser can't parse the query (valid native/vendor SQL it doesn't
     * model), this falls back to a best-effort regex extraction of the projected
     * column labels rather than failing — {@code @Subselect} runs the raw SQL
     * regardless. A deliberate {@code SELECT *} or unaliased expression still throws
     * {@link SqlParseException} (those parse fine; they just can't be auto-named).
     */
    public SelectProjection parseSelectForImport(String sql, SqlDialect dialect) {
        if (sql == null || sql.isBlank()) {
            throw new SqlParseException(null, null, null, "No SELECT query provided", null);
        }
        String trimmed = sql.trim();
        Statement parsed;
        try {
            parsed = CCJSqlParserUtil.parse(trimmed);
        } catch (JSQLParserException e) {
            // Native/vendor SQL JSqlParser can't model — fall back to regex column
            // extraction so the user can still import (the @Subselect runs it as-is).
            if (SELECT_LEAD.matcher(trimmed).find()) {
                return heuristicProjection(trimmed);
            }
            throw new SqlParseException(null, null, snippet(trimmed),
                    "Could not parse SELECT query: " + e.getMessage(), e);
        }
        if (!(parsed instanceof Select select)) {
            throw new SqlParseException(null, null, snippet(trimmed),
                    "Expected a SELECT query but got: " + snippet(trimmed), null);
        }
        PlainSelect ps = asPlainSelect(select, trimmed);

        List<String> columns = new ArrayList<>();
        for (SelectItem<?> item : ps.getSelectItems()) {
            columns.add(columnNameOf(item, trimmed));
        }
        if (columns.isEmpty()) {
            throw new SqlParseException(null, null, snippet(trimmed),
                    "SELECT projects no columns", null);
        }

        String fromTable = null;
        FromItem from = ps.getFromItem();
        if (from instanceof Table t) {
            fromTable = stripQuotes(t.getName());
        }
        return new SelectProjection(fromTable, columns, trimmed, false);
    }

    /** Best-effort projection for a SELECT JSqlParser rejected: extract whatever column
     *  labels we can from the text. May return zero columns (then the user adds fields
     *  manually); always preserves the raw query for {@code @Subselect}. */
    private static SelectProjection heuristicProjection(String trimmed) {
        List<String> columns = extractProjectedColumns(trimmed);
        Matcher m = FROM_TABLE.matcher(stripComments(trimmed));
        String fromTable = m.find() ? unqualify(stripQuotes(m.group(1))) : null;
        return new SelectProjection(fromTable, columns, trimmed, true);
    }

    /**
     * Heuristic projected-column extraction for native SQL JSqlParser can't parse.
     * Finds the outer {@code SELECT … FROM} projection (the last top-level SELECT, so
     * CTEs resolve to the outer query), splits it on top-level commas, and derives a
     * field name per item from its {@code AS} alias or trailing identifier. Items that
     * can't be named ({@code *}, {@code t.*}, unaliased {@code count(*)}, literals) are
     * skipped. Package-private for direct unit testing.
     */
    static List<String> extractProjectedColumns(String sql) {
        String s = stripComments(sql);
        int selectStart = lastTopLevelKeyword(s, "SELECT");
        if (selectStart < 0) return List.of();
        int projStart = selectStart + "SELECT".length();
        // Drop a leading DISTINCT / DISTINCT ON (...) / ALL quantifier.
        String afterSelect = s.substring(projStart);
        int fromRel = topLevelKeyword(afterSelect, "FROM");
        String projection = fromRel < 0 ? afterSelect : afterSelect.substring(0, fromRel);
        projection = stripLeadingQuantifier(projection);

        List<String> out = new ArrayList<>();
        for (String item : splitTopLevelCommas(projection)) {
            String name = projectedItemName(item);
            if (name != null && isPlainIdentifier(name)) out.add(name);
        }
        return out;
    }

    /** Name for one projection item: the {@code AS} alias, else a trailing bare/dotted
     *  identifier; null when it can't be named (expression with no alias, {@code *}). */
    private static String projectedItemName(String rawItem) {
        String item = rawItem.trim();
        if (item.isEmpty() || item.equals("*") || item.endsWith(".*")) return null;
        // Explicit alias: "... AS name" (case-insensitive, name is the last token).
        Matcher as = Pattern.compile("\\bAS\\s+([\\w$.\"`\\[\\]]+)\\s*$", Pattern.CASE_INSENSITIVE).matcher(item);
        if (as.find()) return unqualify(stripQuotes(as.group(1)));
        // Implicit alias / bare column: only trust a single trailing token (no spaces),
        // so "col" and "t.col" name a field but "lower(x)" (no alias) does not.
        String[] tokens = item.split("\\s+");
        String last = tokens[tokens.length - 1];
        if (tokens.length == 1 || isPlainIdentifier(unqualify(stripQuotes(last)))) {
            String candidate = unqualify(stripQuotes(last));
            // A bare function call like count(*) has no usable name.
            if (candidate.indexOf('(') >= 0 || candidate.indexOf(')') >= 0) return null;
            return candidate;
        }
        return null;
    }

    /** Strips a leading {@code DISTINCT [ON (...)]} / {@code ALL} from a projection. */
    private static String stripLeadingQuantifier(String projection) {
        String p = projection.strip();
        Matcher distinctOn = Pattern.compile("^DISTINCT\\s+ON\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(p);
        if (distinctOn.find()) {
            int close = matchingParen(p, distinctOn.end() - 1);
            if (close > 0) return p.substring(close + 1);
        }
        Matcher lead = Pattern.compile("^(?:DISTINCT|ALL)\\b", Pattern.CASE_INSENSITIVE).matcher(p);
        if (lead.find()) return p.substring(lead.end());
        return p;
    }

    /** {@code schema.table} / {@code a.b.c} → last segment. */
    private static String unqualify(String s) {
        if (s == null) return null;
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static boolean isPlainIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    /** Index of the last top-level (paren depth 0, outside quotes) occurrence of a
     *  whole-word keyword, or -1. Used to resolve a CTE's outer SELECT. */
    private static int lastTopLevelKeyword(String s, String keyword) {
        int found = -1, from = 0;
        while (true) {
            int rel = topLevelKeyword(s.substring(from), keyword);
            if (rel < 0) return found;
            found = from + rel;
            from = found + keyword.length();
        }
    }

    /** Index of the first top-level whole-word keyword in {@code s}, or -1. */
    private static int topLevelKeyword(String s, String keyword) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') { quote = c; continue; }
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (depth == 0 && matchesWordAt(s, i, keyword)) return i;
        }
        return -1;
    }

    private static boolean matchesWordAt(String s, int i, String word) {
        if (i + word.length() > s.length()) return false;
        if (!s.regionMatches(true, i, word, 0, word.length())) return false;
        if (i > 0 && Character.isJavaIdentifierPart(s.charAt(i - 1))) return false;
        int after = i + word.length();
        return after >= s.length() || !Character.isJavaIdentifierPart(s.charAt(after));
    }

    /** Splits a projection list on commas at paren depth 0, outside quotes. */
    private static List<String> splitTopLevelCommas(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') { quote = c; }
            else if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    /** Index of the {@code )} matching the {@code (} at {@code openIdx}, or -1. */
    private static int matchingParen(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** Unwraps parenthesized / set-operation (UNION etc.) selects down to the first
     *  {@link PlainSelect} so we can read its projected columns. */
    private static PlainSelect asPlainSelect(Select select, String full) {
        if (select instanceof PlainSelect ps) {
            return ps;
        }
        if (select instanceof ParenthesedSelect paren) {
            return asPlainSelect(paren.getSelect(), full);
        }
        if (select instanceof SetOperationList set && !set.getSelects().isEmpty()) {
            return asPlainSelect(set.getSelects().get(0), full);
        }
        throw new SqlParseException(null, null, snippet(full),
                "Unsupported SELECT shape — use a plain SELECT listing its columns", null);
    }

    /** Field name for one projected item: the alias if present, else the bare
     *  column name. Rejects {@code *} and unaliased non-column expressions. */
    private static String columnNameOf(SelectItem<?> item, String full) {
        Alias alias = item.getAlias();
        if (alias != null && alias.getName() != null && !alias.getName().isBlank()) {
            return stripQuotes(alias.getName());
        }
        Expression expr = item.getExpression();
        if (expr instanceof Column col) {
            return stripQuotes(col.getColumnName());
        }
        if (expr instanceof AllColumns || expr instanceof AllTableColumns) {
            throw new SqlParseException(null, null, snippet(full),
                    "SELECT * is not supported — list the columns explicitly so each maps to a field", null);
        }
        throw new SqlParseException(null, null, snippet(full),
                "Expression '" + expr + "' needs an AS alias to become a field name", null);
    }

    /** Strips surrounding SQL identifier quotes ({@code "x"}, {@code `x`}, {@code [x]}). */
    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        char a = s.charAt(0), b = s.charAt(s.length() - 1);
        if ((a == '"' && b == '"') || (a == '`' && b == '`') || (a == '[' && b == ']')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
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

    private static final Pattern ALTER_TABLE_LEAD = Pattern.compile(
            "^\\s*ALTER\\s+TABLE\\s+(?:ONLY\\s+)?(?:IF\\s+EXISTS\\s+)?(\\S+)", Pattern.CASE_INSENSITIVE);

    /** One {@code ADD [CONSTRAINT name] PRIMARY KEY|FOREIGN KEY|UNIQUE (cols) [REFERENCES t (cols)]} clause. */
    private static final Pattern ALTER_ADD_CONSTRAINT = Pattern.compile(
            "\\bADD\\s+(?:CONSTRAINT\\s+\\S+\\s+)?(PRIMARY\\s+KEY|FOREIGN\\s+KEY|UNIQUE)(?:\\s+(?:NON)?CLUSTERED)?\\s*\\(([^)]*)\\)"
                    + "(?:\\s*REFERENCES\\s+([^\\s(]+)\\s*\\(([^)]*)\\))?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** {@code CHECK ( col IN ( 'A', 'B' ) )}, the column name optionally quoted. */
    private static final Pattern CHECK_IN = Pattern.compile(
            "(?is)CHECK\\s*\\(\\s*[\"`\\[]?(\\w+)[\"`\\]]?\\s+IN\\s*\\(([^)]*)\\)\\s*\\)");

    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Applies the constraints an {@code ALTER TABLE … ADD …} declares to the table it names, when
     * that table is in the script (a table created elsewhere is none of the wizard's business).
     */
    private static void applyAlterTable(List<TableModel> tables, String stmt) {
        Matcher lead = ALTER_TABLE_LEAD.matcher(stmt);
        if (!lead.find()) return;
        String tableName = bareName(lead.group(1));
        int index = -1;
        for (int i = 0; i < tables.size(); i++) {
            if (!tables.get(i).isView() && tables.get(i).name().equalsIgnoreCase(tableName)) index = i;
        }
        if (index < 0) return;
        TableModel table = tables.get(index);
        Matcher add = ALTER_ADD_CONSTRAINT.matcher(stmt);
        while (add.find()) {
            String kind = add.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            List<String> columns = canonicalColumns(table, add.group(2));
            if (columns.isEmpty()) continue;
            switch (kind) {
                case "PRIMARY KEY" -> table = table.withPrimaryKey(columns);
                case "UNIQUE" -> table = table.withUnique(columns);
                case "FOREIGN KEY" -> {
                    if (add.group(3) == null) continue;
                    List<String> referenced = new ArrayList<>();
                    for (String c : add.group(4).split(",")) referenced.add(bareName(c));
                    table = table.withForeignKey(new ForeignKey(columns, bareName(add.group(3)), referenced));
                }
                default -> { }
            }
        }
        tables.set(index, table);
    }

    /** The columns a constraint lists, as the table spells them; empty when one is not the table's. */
    private static List<String> canonicalColumns(TableModel table, String list) {
        List<String> out = new ArrayList<>();
        for (String raw : list.split(",")) {
            String name = bareName(raw.trim().replaceAll("(?i)\\s+(ASC|DESC)$", ""));
            ColumnModel column = table.columns().stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (column == null) return List.of();
            out.add(column.name());
        }
        return out;
    }

    /** {@code "inv"."Stock Levels"} / {@code [dbo].[Orders]} / {@code public.orders} → the bare, unquoted last part. */
    private static String bareName(String raw) {
        String s = raw.trim();
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        return unquote(s.trim());
    }

    private List<TableModel> parseTables(String sql, SqlDialect dialect) {
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
            if (ALTER_TABLE_LEAD.matcher(stmt).find()) {
                applyAlterTable(result, stmt);
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

    private TableModel toTableModel(CreateTable ct, String sourceSql) {
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
        Matcher check = CHECK_IN.matcher(sourceSql == null ? "" : sourceSql);
        while (check.find()) {
            String column = check.group(1);
            List<String> values = new ArrayList<>();
            for (String raw : check.group(2).split(",")) {
                String v = raw.trim();
                if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) values.add(v.substring(1, v.length() - 1));
            }
            boolean known = table.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(column));
            if (known && !values.isEmpty()) table = table.withEnum(column, values);
        }
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
    private static String enumClassOf(TableModel table, ColumnModel c, JavaType mapped, Set<String> knownTableNames) {
        List<String> values = table.enumValuesOf(c.name());
        if (values == null || c.isPk() || c.isForeignKey() || !isStringType(mapped)) return null;
        Set<String> distinct = new HashSet<>();
        for (String v : values) {
            if (!JAVA_IDENTIFIER.matcher(v).matches() || javax.lang.model.SourceVersion.isKeyword(v) || !distinct.add(v)) {
                return null;
            }
        }
        String name = toPascalCase(table.name()) + toPascalCase(c.name());
        for (String other : knownTableNames) {
            if (toPascalCase(other).equals(name)) return null;
        }
        return name;
    }

    /** A column's field type: its mapped Java type, or the enum of {@link #enumClassOf}. */
    private static JavaType fieldType(TableModel table, ColumnModel c, SqlDialect dialect, String entityPkg,
                                      Set<String> knownTableNames) {
        JavaType mapped = TypeMappers.map(dialect, c.rawType(), c.precision(), c.scale());
        String enumClass = enumClassOf(table, c, mapped, knownTableNames);
        return enumClass == null ? mapped : JavaType.of(enumClass, entityPkg + "." + enumClass);
    }

    /**
     * Builds a read-only view {@link TableModel} from a single SELECT statement: reuses
     * {@link #parseSelectForImport} for the projected column labels (incl. the native-SQL
     * heuristic fallback), defaults every field to {@code String} (a SELECT has no column
     * types, and the wizard has no editing step), and makes the first column the PK.
     *
     * <p>Unlike the fullstack import flow, the wizard has no editor to add fields manually,
     * so a query whose columns can't be auto-named is a hard error rather than an empty view.
     */
    private TableModel toViewTableModel(String stmt, SqlDialect dialect, Set<String> usedClassNames) {
        SelectProjection p = parseSelectForImport(stmt, dialect);
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

    private ColumnModel toColumnModel(ColumnDefinition cd, List<String> tablePkCols, Set<String> fkColumnNames,
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

    // ── Rendering ─────────────────────────────────────────────────────────────

    /** True when one of the table's rendered names comes out quoted, so the project needs
     *  {@link SqlNames#namingStrategySource} for it to reach the database as written. */
    private static boolean rendersQuotedName(TableModel table, SqlDialect dialect) {
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

    private GeneratedJavaFile renderEntity(TableModel table, SqlDialect dialect,
                                           String packageName, SqlDepOptions opts,
                                           Set<String> knownTableNames) {
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
        Members body = new Members();
        for (ColumnModel c : table.columns()) {
            JavaType jt = TypeMappers.map(dialect, c.rawType(), c.precision(), c.scale());
            imports.addAll(jt.imports());
            processColumn(body, c, jt, table, dialect, knownTableNames, emittedFks, usedFieldNames, imports);
        }
        // UNIQUE over several columns is the table's; over one column it is on the column.
        String uniqueConstraints = table.uniqueConstraints().stream()
                .map(cols -> "@UniqueConstraint(columnNames = {" + cols.stream()
                        .map(col -> "\"" + sqlName(table, col, dialect) + "\"").collect(Collectors.joining(", ")) + "})")
                .collect(Collectors.joining(", "));
        if (!uniqueConstraints.isEmpty()) imports.add("jakarta.persistence.UniqueConstraint");

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
     * Renders a read-only {@code @Immutable}/{@code @Subselect} view entity from a SELECT.
     * A SELECT carries no column types, so every field is a {@code String} with a TODO for
     * the user to fix in source; the {@code @Column(name=…)} uses the projected label
     * <em>verbatim</em> so it matches the column the query actually returns. The first
     * projected column is the {@code @Id}. The raw query goes into a Java text block.
     */
    private GeneratedJavaFile renderViewEntity(TableModel table, SqlDialect dialect, String packageName,
                                               SqlDepOptions opts) {
        String className = toPascalCase(table.name());
        String subPkg = opts.subPackage();
        String fullPkg = packageName + "." + subPkg;

        List<Map<String, Object>> columns = new ArrayList<>();
        Set<String> usedFieldNames = new HashSet<>();
        for (ColumnModel c : table.columns()) {
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
            columns.add(cv);
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPkg);
        view.put("viewQuery", table.viewQuery());
        view.put("className", className);
        view.put("columns", columns);

        String path = "src/main/java/{{packagePath}}/" + subPkg + "/" + className + ".java";
        return new GeneratedJavaFile(path, WizardTemplates.render("sql/ViewEntity.java", view));
    }

    /**
     * Decide how to render a single column: as a JPA association (@ManyToOne /
     * shared-PK @MapsId), as part of a composite-FK association, or as a plain
     * scalar field. Falls back to scalar+TODO when the FK target table is not
     * in the same generation batch.
     */
    private void processColumn(Members body, ColumnModel c, JavaType jt, TableModel table, SqlDialect dialect,
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
            if (isCompositeKeyPart(table, c)) {
                // One part of a composite key that is also a foreign key (order_items.order_id):
                // the key part stays a scalar @Id — the @IdClass binds to it by name — and the
                // association reads through the same column. Replacing the scalar with the
                // association left the @IdClass without its property, and the app did not start.
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

    private void appendSingleAssociation(Members body, ColumnModel c, ForeignKey fk,
                                         TableModel table, SqlDialect dialect, Set<String> usedFieldNames,
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
            body.add(List.of(
                    "@Id",
                    "@OneToOne(fetch = FetchType.LAZY)",
                    "@MapsId",
                    "@JoinColumn(name = \"" + sqlName(table, c.name(), dialect) + "\")"), refEntity, fieldName);
            return;
        }

        body.add(List.of(
                "@ManyToOne(fetch = FetchType.LAZY)",
                "@JoinColumn(name = \"" + sqlName(table, c.name(), dialect) + '"'
                        + (c.nullable() ? "" : ", nullable = false") + ")"), refEntity, fieldName);
    }

    /** Whether {@code c} is one column of a key of several. */
    private static boolean isCompositeKeyPart(TableModel table, ColumnModel c) {
        return c.isPk() && table.pkColumns().size() > 1;
    }

    /** The association of a key column that stays a scalar: read through the column, never written. */
    private void appendReadOnlyAssociation(Members body, ColumnModel c, ForeignKey fk, TableModel table,
                                           SqlDialect dialect, Set<String> usedFieldNames, Set<String> imports) {
        String refEntity = toPascalCase(fk.referencedTable());
        String fieldName = pickAssociationFieldName(c, fk, usedFieldNames, false);
        usedFieldNames.add(fieldName);
        imports.add("jakarta.persistence.ManyToOne");
        imports.add("jakarta.persistence.JoinColumn");
        imports.add("jakarta.persistence.FetchType");
        body.add(List.of(
                "@ManyToOne(fetch = FetchType.LAZY)",
                "@JoinColumn(name = \"" + sqlName(table, c.name(), dialect) + "\", insertable = false, updatable = false)"),
                refEntity, fieldName);
    }

    private void appendCompositeAssociation(Members body, ForeignKey fk, TableModel table, SqlDialect dialect,
                                            Set<String> usedFieldNames, Set<String> imports) {
        String refEntity = toPascalCase(fk.referencedTable());
        String fieldName = pickAssociationFieldName(null, fk, usedFieldNames, true);
        usedFieldNames.add(fieldName);
        imports.add("jakarta.persistence.ManyToOne");
        imports.add("jakarta.persistence.JoinColumn");
        imports.add("jakarta.persistence.JoinColumns");
        imports.add("jakarta.persistence.FetchType");

        List<String> lines = new ArrayList<>();
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

    private void appendScalarField(Members body, ColumnModel c, JavaType jt, TableModel table,
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

    /** The enum behind each column a CHECK limits to a set of values (see {@link #enumClassOf}). */
    private List<GeneratedJavaFile> renderEnums(TableModel table, SqlDialect dialect, String packageName,
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

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", repoPkg);
        view.put("imports", new TreeSet<>(imports));
        view.put("entityClass", entityClass);
        view.put("idType", idType);

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/repository/" + entityClass + "Repository.java",
                WizardTemplates.render("sql/Repository.java", view));
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
            if (singleFk != null && knownTableNames.contains(singleFk.referencedTable()) && isCompositeKeyPart(table, c)) {
                // The key part is the member; the association that reads through it is derived.
                members.add(scalarMember(c, jt, used));
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

    private GeneratedJavaFile renderDto(TableModel table, SqlDialect dialect, String packageName,
                                        SqlDepOptions opts, Map<String, TableModel> tablesByName,
                                        Set<String> knownTableNames) {
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
        for (Member m : scalars) {
            components.add(component(m.javaType(), m.fieldName()));
            fromArgs.add(Map.of("expr", "entity.get" + m.pascalName() + "()"));
            scalarViews.add(Map.of("pascal", m.pascalName(), "name", m.fieldName()));
        }
        for (Member m : flat) {
            FlattenInfo fi = m.flatten();
            components.add(component(fi.idType(), fi.dtoIdField()));
            fromArgs.add(Map.of("expr", "entity.get" + m.pascalName() + "() == null ? null : entity.get"
                    + m.pascalName() + "().get" + fi.targetPkPascal() + "()"));
            flatViews.add(flatView(m));
        }

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

    private static Map<String, Object> component(String type, String name) {
        Map<String, Object> c = new LinkedHashMap<>();
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

    private GeneratedJavaFile renderMapper(TableModel table, SqlDialect dialect, String packageName,
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
        model.put("flat", flat.stream().map(SqlEntityGenerator::flatView).toList());

        return new GeneratedJavaFile(
                "src/main/java/{{packagePath}}/mapper/" + entityClass + "Mapper.java",
                WizardTemplates.render("sql/Mapper.java", model));
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

    // ── Name helpers ──────────────────────────────────────────────────────────

    // Case conversion is shared with the fullstack scaffolder via Naming; these thin
    // wrappers add the wizard's defensive fallbacks for empty/degenerate identifiers.

    private static String toPascalCase(String snake) {
        String pascal = Naming.toPascalCase(snake);
        return pascal.isEmpty() ? "Entity" : pascal;
    }

    private static String toCamelCase(String snake) {
        return Naming.decapitalize(toPascalCase(snake));
    }

    private static String capitalize(String s) {
        return Naming.capitalize(s);
    }

    private static String decapitalize(String s) {
        return Naming.decapitalize(s);
    }

    /** Lower-kebab a raw table name for use as a REST collection path segment
     *  ({@code TD_APP_STP} → {@code td-app-stp}, {@code line_items} → {@code line-items}). */
    private static String toKebab(String raw) {
        String kebab = Naming.toKebabCase(raw);
        return kebab.isEmpty() ? "items" : kebab;
    }

    private static boolean isStringType(JavaType jt) {
        return "String".equals(jt.simpleName());
    }

    private static boolean isDecimalType(JavaType jt) {
        return "BigDecimal".equals(jt.simpleName());
    }
}
