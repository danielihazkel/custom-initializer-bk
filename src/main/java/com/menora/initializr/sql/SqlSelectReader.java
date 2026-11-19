package com.menora.initializr.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.menora.initializr.sql.SqlEntityGenerator.*;
import static com.menora.initializr.sql.SqlScriptParser.*;
import static com.menora.initializr.sql.SqlEntityRenderer.*;
import static com.menora.initializr.sql.SqlRestRenderer.*;
import static com.menora.initializr.sql.SqlJavaNames.*;

/** Reads the projection of a SELECT: its columns by alias, through JSqlParser or, when it cannot parse the query, by scanning the text. Part of {@link SqlEntityGenerator}. */
final class SqlSelectReader {

    private SqlSelectReader() {}

    /** What {@link SqlEntityGenerator#parseSelectForImport} answers. */
    static SelectProjection parseSelect(String sql, SqlDialect dialect) {
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

    static final Pattern SELECT_LEAD =
            Pattern.compile("^\\s*(?:WITH\\b|SELECT\\b|\\()", Pattern.CASE_INSENSITIVE);

    /** First top-level {@code FROM <ident>} after the outer projection — best-effort
     *  entity-name hint for the heuristic path. */
    private static final Pattern FROM_TABLE =
            Pattern.compile("\\bFROM\\s+([\\w$.\"`\\[\\]]+)", Pattern.CASE_INSENSITIVE);

    /** Best-effort projection for a SELECT JSqlParser rejected: extract whatever column
     *  labels we can from the text. May return zero columns (then the user adds fields
     *  manually); always preserves the raw query for {@code @Subselect}. */
    static SelectProjection heuristicProjection(String trimmed) {
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
        List<String> out = new ArrayList<>();
        for (String item : projectionItems(sql)) {
            String name = projectedItemName(item);
            if (name != null && isPlainIdentifier(name)) out.add(name);
        }
        return out;
    }

    /** The items of the outer {@code SELECT … FROM} projection, as text. */
    private static List<String> projectionItems(String sql) {
        String s = stripComments(sql);
        int selectStart = lastTopLevelKeyword(s, "SELECT");
        if (selectStart < 0) return List.of();
        int projStart = selectStart + "SELECT".length();
        // Drop a leading DISTINCT / DISTINCT ON (...) / ALL quantifier.
        String afterSelect = s.substring(projStart);
        int fromRel = topLevelKeyword(afterSelect, "FROM");
        String projection = fromRel < 0 ? afterSelect : afterSelect.substring(0, fromRel);
        return splitTopLevelCommas(stripLeadingQuantifier(projection));
    }

    // ── What a view's columns are typed as ────────────────────────────────────

    /** A SQL type as a script writes it: {@code NUMERIC}, 10, 2. */
    record SqlType(String rawType, Integer precision, Integer scale) {}

    /**
     * One column of a query's result: its label ({@code null}: an expression the query gave no
     * name), whether the query quoted the label, and the type the script gives it ({@code null}:
     * it does not say). {@code star} marks a {@code *} over a table the script does not have.
     */
    record Projected(String name, boolean quoted, SqlType type, boolean star) {}

    /** A table a query reads, by the name the query uses for it. */
    private record Source(String alias, String name, TableModel table) {

        boolean is(String qualifier) {
            return qualifier.equalsIgnoreCase(alias) || (alias == null && qualifier.equalsIgnoreCase(name));
        }
    }

    /**
     * The columns of {@code select}'s result with the types the script gives them: a column takes
     * the type of the table column it reads (a table or an earlier view of the same script, by
     * bare lower-case name in {@code tables}), a {@code CAST(x AS type)} the type it names,
     * {@code count(…)} a {@code BIGINT}, and {@code min}/{@code max} the type of what they are
     * taken over. {@code *} and {@code t.*} become the columns of the tables they stand for.
     * Null when JSqlParser cannot read the query (vendor SQL): the caller falls back to
     * {@link #heuristicProjection} and {@link #castTypes}.
     */
    static List<Projected> project(String select, Map<String, TableModel> tables) {
        Statement parsed;
        try {
            parsed = CCJSqlParserUtil.parse(select.trim());
        } catch (JSQLParserException e) {
            return null;
        }
        if (!(parsed instanceof Select query)) return null;
        PlainSelect ps;
        try {
            ps = asPlainSelect(query, select);
        } catch (SqlParseException e) {
            return null;
        }
        List<Source> sources = new ArrayList<>();
        addSource(sources, ps.getFromItem(), tables);
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) addSource(sources, join.getFromItem(), tables);
        }
        List<Projected> out = new ArrayList<>();
        for (SelectItem<?> item : ps.getSelectItems()) {
            Expression expr = item.getExpression();
            if (expr instanceof AllTableColumns all) {
                String qualifier = stripQuotes(all.getTable().getName());
                Source source = sources.stream().filter(s -> s.is(qualifier)).findFirst().orElse(null);
                if (source == null || source.table() == null) out.add(new Projected(null, false, null, true));
                else addColumns(out, source.table());
            } else if (expr instanceof AllColumns) {
                if (sources.isEmpty() || sources.stream().anyMatch(s -> s.table() == null)) {
                    out.add(new Projected(null, false, null, true));
                } else {
                    for (Source source : sources) addColumns(out, source.table());
                }
            } else {
                Alias alias = item.getAlias();
                String raw = alias != null && alias.getName() != null && !alias.getName().isBlank() ? alias.getName()
                        : expr instanceof Column col ? col.getColumnName() : null;
                String name = stripQuotes(raw);
                out.add(new Projected(name, raw != null && !raw.equals(name), typeOf(expr, sources), false));
            }
        }
        return out;
    }

    private static void addSource(List<Source> sources, FromItem from, Map<String, TableModel> tables) {
        if (from == null) return;
        String alias = from.getAlias() == null ? null : stripQuotes(from.getAlias().getName());
        if (from instanceof Table t) {
            String name = stripQuotes(t.getName());
            sources.add(new Source(alias, name, tables.get(name.toLowerCase(Locale.ROOT))));
        } else {
            // A sub-query in FROM: its columns are not followed.
            sources.add(new Source(alias, null, null));
        }
    }

    private static void addColumns(List<Projected> out, TableModel table) {
        for (ColumnModel c : table.columns()) {
            out.add(new Projected(c.name(), table.quotedNames().contains(c.name()),
                    c.rawType() == null ? null : new SqlType(c.rawType(), c.precision(), c.scale()), false));
        }
    }

    private static SqlType typeOf(Expression expr, List<Source> sources) {
        if (expr instanceof Parenthesis p) return typeOf(p.getExpression(), sources);
        if (expr instanceof ParenthesedExpressionList<?> list && list.size() == 1) return typeOf(list.get(0), sources);
        if (expr instanceof CastExpression cast && cast.getColDataType() != null) {
            ColDataType type = cast.getColDataType();
            List<String> args = type.getArgumentsStringList();
            return new SqlType(type.getDataType(), intOrNull(args, 0), intOrNull(args, 1));
        }
        if (expr instanceof Column col) {
            String qualifier = col.getTable() == null || col.getTable().getName() == null ? null
                    : stripQuotes(col.getTable().getName());
            String name = stripQuotes(col.getColumnName());
            ColumnModel found = null;
            int hits = 0;
            for (Source source : sources) {
                if (source.table() == null || (qualifier != null && !source.is(qualifier))) continue;
                for (ColumnModel c : source.table().columns()) {
                    if (c.name().equalsIgnoreCase(name)) {
                        found = c;
                        hits++;
                    }
                }
            }
            return hits == 1 && found.rawType() != null
                    ? new SqlType(found.rawType(), found.precision(), found.scale()) : null;
        }
        if (expr instanceof Function f && f.getName() != null) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.equals("count")) return new SqlType("BIGINT", null, null);
            if ((name.equals("min") || name.equals("max")) && f.getParameters() != null && f.getParameters().size() == 1) {
                return typeOf(f.getParameters().get(0), sources);
            }
        }
        return null;
    }

    private static Integer intOrNull(List<String> args, int i) {
        if (args == null || args.size() <= i) return null;
        try {
            return Integer.parseInt(args.get(i).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code CAST(… AS type[(p[,s])]) [AS] label}, one item of a projection. */
    private static final Pattern CAST_ITEM = Pattern.compile(
            "^\\s*CAST\\s*\\(.*\\bAS\\s+([A-Za-z][A-Za-z0-9_ ]*?)\\s*(?:\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?\\s*\\)"
                    + "\\s*(?:AS\\s+)?([\\w$\"`\\[\\]]+)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * For a query JSqlParser cannot read: the types its {@code CAST(x AS type) AS label} items
     * name, by lower-case label — read from the text, like {@link #extractProjectedColumns}.
     */
    static Map<String, SqlType> castTypes(String select) {
        Map<String, SqlType> out = new LinkedHashMap<>();
        for (String item : projectionItems(select)) {
            Matcher m = CAST_ITEM.matcher(item);
            if (!m.matches()) continue;
            out.put(stripQuotes(m.group(4)).toLowerCase(Locale.ROOT), new SqlType(m.group(1).trim(),
                    m.group(2) == null ? null : Integer.valueOf(m.group(2)),
                    m.group(3) == null ? null : Integer.valueOf(m.group(3))));
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
    static PlainSelect asPlainSelect(Select select, String full) {
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
    static String columnNameOf(SelectItem<?> item, String full) {
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
    static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        char a = s.charAt(0), b = s.charAt(s.length() - 1);
        if ((a == '"' && b == '"') || (a == '`' && b == '`') || (a == '[' && b == ']')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
