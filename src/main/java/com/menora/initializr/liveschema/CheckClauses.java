package com.menora.initializr.liveschema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a CHECK constraint the way a database gives it back and recognises one that limits a
 * column to a list of strings — the shape the SQL wizard turns into an enum. Each database
 * rewrites {@code status IN ('OPEN','CLOSED')} in its own way:
 * <ul>
 *   <li>PostgreSQL: {@code ((status)::text = ANY ((ARRAY['OPEN'::character varying, …])::text[]))}</li>
 *   <li>SQL Server: {@code ([status]='CLOSED' OR [status]='OPEN')}</li>
 *   <li>Oracle and DB2 keep the text as written; H2: {@code "STATUS" IN('OPEN', 'CLOSED')}</li>
 * </ul>
 * Anything else — a range, a compound condition, a NOT NULL check — is not an enum and gives null.
 */
public final class CheckClauses {

    private CheckClauses() {}

    private static final String IDENT = "[\"\\[`]?([A-Za-z_][A-Za-z0-9_$#@]*)[\"\\]`]?";
    private static final Pattern CAST = Pattern.compile(
            "::\\s*[A-Za-z_][A-Za-z0-9_]*(\\s+(varying|precision|without time zone|with time zone))?(\\(\\d+\\))?(\\[\\])?");
    private static final Pattern PAREN_IDENT = Pattern.compile("\\(\\s*(" + "[\"\\[`]?[A-Za-z_][A-Za-z0-9_$#@]*[\"\\]`]?" + ")\\s*\\)");
    private static final Pattern IN = Pattern.compile("(?is)^" + IDENT + "\\s+IN\\s*\\((.*)\\)$");
    private static final Pattern ANY = Pattern.compile("(?is)^" + IDENT + "\\s*=\\s*ANY\\s*\\(+\\s*ARRAY\\s*\\[(.*)\\]\\s*\\)+$");
    private static final Pattern EQ = Pattern.compile("(?is)^" + IDENT + "\\s*=\\s*N?('(?:[^']|'')*')$");
    private static final Pattern LITERAL = Pattern.compile("^N?'((?:[^']|'')*)'$");

    /**
     * The column and values of {@code clause}, or null when it is not a list of strings.
     * {@code columnOf} turns the name in the clause into the table's own spelling, or null
     * when the table has no such column.
     */
    public static LiveSchemaModel.CheckIn inList(String clause, Function<String, String> columnOf) {
        if (clause == null || clause.isBlank()) return null;
        String s = clause.trim();
        if (s.regionMatches(true, 0, "CHECK", 0, 5)) s = s.substring(5).trim();
        s = CAST.matcher(s).replaceAll("");
        // (status) → status, repeatedly: PostgreSQL wraps a column in parentheses before its cast.
        String before;
        do {
            before = s;
            s = PAREN_IDENT.matcher(s).replaceAll("$1");
        } while (!s.equals(before));
        s = stripOuterParens(s);

        LiveSchemaModel.CheckIn found = matchList(IN.matcher(s), columnOf);
        if (found == null) found = matchList(ANY.matcher(s), columnOf);
        if (found == null) found = orChain(s, columnOf);
        return found;
    }

    private static LiveSchemaModel.CheckIn matchList(Matcher m, Function<String, String> columnOf) {
        if (!m.matches()) return null;
        String column = columnOf.apply(m.group(1));
        if (column == null) return null;
        List<String> values = new ArrayList<>();
        for (String part : splitTopLevel(m.group(2), ",")) {
            String v = literal(stripOuterParens(part.trim()));
            if (v == null) return null;
            values.add(v);
        }
        return values.isEmpty() ? null : new LiveSchemaModel.CheckIn(column, values);
    }

    /** {@code col = 'A' OR col = 'B'}, every part naming the same column. */
    private static LiveSchemaModel.CheckIn orChain(String s, Function<String, String> columnOf) {
        List<String> parts = splitTopLevel(s, " OR ");
        if (parts.size() < 2) return null;
        String column = null;
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            Matcher m = EQ.matcher(stripOuterParens(part.trim()));
            if (!m.matches()) return null;
            String c = columnOf.apply(m.group(1));
            if (c == null || (column != null && !column.equals(c))) return null;
            column = c;
            String v = literal(m.group(2).startsWith("N") ? m.group(2).substring(1) : m.group(2));
            if (v == null) return null;
            values.add(v);
        }
        // SQL Server sorts the alternatives; keep them, the order is not the database's to know.
        return new LiveSchemaModel.CheckIn(column, values);
    }

    private static String literal(String s) {
        Matcher m = LITERAL.matcher(s);
        return m.matches() ? m.group(1).replace("''", "'") : null;
    }

    /** Removes parentheses that wrap the whole text, as many pairs as there are. */
    static String stripOuterParens(String s) {
        String out = s.trim();
        while (out.startsWith("(") && out.endsWith(")") && closingOf(out, 0) == out.length() - 1) {
            out = out.substring(1, out.length() - 1).trim();
        }
        return out;
    }

    private static int closingOf(String s, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') quoted = !quoted;
            if (quoted) continue;
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** Splits on {@code sep} (case-insensitive) outside quotes, parentheses and brackets. */
    static List<String> splitTopLevel(String s, String sep) {
        List<String> out = new ArrayList<>();
        String upperSep = sep.toUpperCase(Locale.ROOT);
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') quoted = !quoted;
            if (quoted) continue;
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (depth == 0 && s.regionMatches(true, i, upperSep, 0, upperSep.length())) {
                out.add(s.substring(start, i));
                i += upperSep.length() - 1;
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }
}
