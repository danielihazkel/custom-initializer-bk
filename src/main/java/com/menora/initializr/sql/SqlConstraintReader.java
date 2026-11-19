package com.menora.initializr.sql;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The constraints a DDL script declares beyond what JSqlParser models — read from the
 * statements' text, for the SQL wizard and the fullstack DDL import alike (both read the
 * {@link TableModel}s {@link SqlEntityGenerator} parses; the import used to keep its own reading
 * of CHECK, with its own rule for which values make an enum).
 *
 * <ul>
 *   <li>{@code ALTER TABLE … ADD [CONSTRAINT x] PRIMARY KEY | FOREIGN KEY | UNIQUE (…)} and
 *       {@code … ADD [CONSTRAINT x] CHECK (col IN (…))} — how pg_dump, SSMS and most schema exports
 *       declare a table's constraints;</li>
 *   <li>{@code CREATE [UNIQUE] INDEX … ON t (cols)} — a unique index is a unique constraint to an
 *       application and any other a plain index, unless it is partial ({@code WHERE …}), over an
 *       expression or of a kind JPA has no word for: those are a note on the table;</li>
 *   <li>a foreign key written on its column ({@code team_id BIGINT REFERENCES teams(id)});</li>
 *   <li>{@code CHECK (col IN ('A', 'B'))}, column- or table-level: only survives JSqlParser as
 *       loose tokens.</li>
 * </ul>
 */
public final class SqlConstraintReader {

    private SqlConstraintReader() {}

    static final Pattern ALTER_TABLE_LEAD = Pattern.compile(
            "^\\s*ALTER\\s+TABLE\\s+(?:ONLY\\s+)?(?:IF\\s+EXISTS\\s+)?(\\S+)", Pattern.CASE_INSENSITIVE);

    /** A column's own {@code REFERENCES [schema.]table [(column)]}. */
    static final Pattern INLINE_REFERENCES = Pattern.compile(
            "(?i)\\bREFERENCES\\s+((?:[^\\s(.]+\\s*\\.\\s*)*[^\\s(.]+)\\s*(?:\\(\\s*([^),]+?)\\s*\\))?");

    /** One {@code ADD [CONSTRAINT name] PRIMARY KEY|FOREIGN KEY|UNIQUE (cols) [REFERENCES t (cols)]} clause. */
    private static final Pattern ALTER_ADD_CONSTRAINT = Pattern.compile(
            "\\bADD\\s+(?:CONSTRAINT\\s+\\S+\\s+)?(PRIMARY\\s+KEY|FOREIGN\\s+KEY|UNIQUE)(?:\\s+(?:NON)?CLUSTERED)?\\s*\\(([^)]*)\\)"
                    + "(?:\\s*REFERENCES\\s+([^\\s(]+)\\s*\\(([^)]*)\\))?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** {@code CHECK ( col IN ( 'A', 'B' ) )}, the column name optionally quoted. */
    private static final Pattern CHECK_IN = Pattern.compile(
            "(?is)CHECK\\s*\\(\\s*[\"`\\[]?(\\w+)[\"`\\]]?\\s+IN\\s*\\(([^)]*)\\)\\s*\\)");

    /**
     * {@code CREATE [UNIQUE] [[NON]CLUSTERED] [BITMAP] INDEX [CONCURRENTLY] [IF NOT EXISTS] [name] ON [ONLY] table [USING m] (},
     * up to the parenthesis that opens the key list (which may nest: {@code (lower(email))}).
     */
    static final Pattern CREATE_INDEX = Pattern.compile(
            "^\\s*CREATE\\s+(UNIQUE\\s+)?(?:(?:NON)?CLUSTERED\\s+)?(BITMAP\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:(\\S+)\\s+)?ON\\s+(?:ONLY\\s+)?([^\\s(]+)\\s*(?:USING\\s+(\\w+)\\s*)?\\(",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** One key of an index when it is a plain column: {@code name [COLLATE c] [ASC|DESC] [NULLS FIRST|LAST]}. */
    private static final Pattern INDEX_COLUMN = Pattern.compile(
            "^\\s*(\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|[A-Za-z_][\\w$#@]*)(?:\\s+COLLATE\\s+\\S+)?(?:\\s+(ASC|DESC))?"
                    + "(?:\\s+NULLS\\s+(?:FIRST|LAST))?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WHERE = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    // ── After the table ───────────────────────────────────────────────────────

    static boolean isAlterTable(String stmt) {
        return ALTER_TABLE_LEAD.matcher(stmt).find();
    }

    static boolean isCreateIndex(String stmt) {
        return CREATE_INDEX.matcher(stmt).find();
    }

    /**
     * Applies the constraints an {@code ALTER TABLE … ADD …} declares to the table it names, when
     * that table is in the script (a table created elsewhere is none of the reader's business).
     */
    static void applyAlterTable(List<TableModel> tables, String stmt) {
        Matcher lead = ALTER_TABLE_LEAD.matcher(stmt);
        if (!lead.find()) return;
        int index = tableIndex(tables, bareName(lead.group(1)));
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
        // ADD [CONSTRAINT x] CHECK (col IN (…)): the same reading as a check in the CREATE.
        tables.set(index, withChecks(table, stmt));
    }

    /**
     * A {@code CREATE INDEX} on a table of the script. Over plain columns, a unique one is that
     * table's UNIQUE and any other one of its {@link TableModel#indexes()}. A partial index
     * ({@code WHERE …}) allows duplicates outside its condition and covers only some rows, an index
     * over an expression ({@code lower(email)}) names no column, and a hash, GIN or bitmap index is
     * not what JPA would create: each of those is a note on the table instead, so the generated
     * entity says the database has an index it does not carry.
     */
    static void applyIndex(List<TableModel> tables, String stmt) {
        Matcher m = CREATE_INDEX.matcher(stmt);
        if (!m.find()) return;
        int index = tableIndex(tables, bareName(m.group(4)));
        if (index < 0) return;
        TableModel table = tables.get(index);
        boolean unique = m.group(1) != null;
        String name = m.group(3) == null ? null : bareName(m.group(3));
        int open = m.end() - 1;
        int close = closingParen(stmt, open);
        if (close < 0) return;
        String keys = stmt.substring(open + 1, close);
        String rest = stmt.substring(close + 1);

        List<TableIndex.Column> columns = new ArrayList<>();
        for (String raw : splitTopLevel(keys)) {
            Matcher key = INDEX_COLUMN.matcher(raw);
            ColumnModel column = !key.matches() ? null : table.columns().stream()
                    .filter(c -> c.name().equalsIgnoreCase(unquote(key.group(1)))).findFirst().orElse(null);
            if (column == null) {
                columns = null;
                break;
            }
            columns.add(new TableIndex.Column(column.name(), "DESC".equalsIgnoreCase(key.group(2))));
        }

        String method = m.group(2) != null ? "bitmap" : m.group(5) == null ? null : m.group(5).toLowerCase(Locale.ROOT);
        String why = null;
        if (columns == null || columns.isEmpty()) why = "it is over an expression, not plain columns";
        else if (WHERE.matcher(rest).find()) why = "it is partial (it covers only the rows its WHERE picks)";
        else if (method != null && !method.equals("btree")) why = "it is a " + method + " index";
        if (why != null) {
            String what = (unique ? "Unique index" : "Index") + (name == null ? "" : " " + name)
                    + " (" + keys.replaceAll("\\s+", " ").trim() + ")";
            tables.set(index, table.withNote(what + " is not mapped: " + why + "."));
            return;
        }
        if (unique) {
            tables.set(index, table.withUnique(columns.stream().map(TableIndex.Column::name).toList()));
        } else {
            tables.set(index, table.withIndex(new TableIndex(name, columns)));
        }
    }

    /** The index of the {@code )} that closes the {@code (} at {@code open}, quotes skipped; -1 when there is none. */
    static int closingParen(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** {@code s} cut at the commas outside parentheses and quotes. */
    static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    private static int tableIndex(List<TableModel> tables, String name) {
        int index = -1;
        for (int i = 0; i < tables.size(); i++) {
            if (!tables.get(i).isView() && tables.get(i).name().equalsIgnoreCase(name)) index = i;
        }
        return index;
    }

    // ── In the table ──────────────────────────────────────────────────────────

    /**
     * {@code table} with every {@code CHECK (col IN (…))} in {@code text} on one of its columns: the
     * single-quoted values, as the database holds them. Whether they make an enum is
     * {@link #enumConstants}'s call.
     */
    static TableModel withChecks(TableModel table, String text) {
        Matcher check = CHECK_IN.matcher(text == null ? "" : text);
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
     * The values a CHECK lists as enum constants, or null when they cannot be: the values go to
     * the column as the constants are spelt ({@code @Enumerated(EnumType.STRING)}), so on an
     * existing database each must already be a Java identifier — {@code 'open'} is the constant
     * {@code open}, and {@code 'in progress'} leaves the column a String. Not a keyword, no repeats.
     */
    public static List<String> enumConstants(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        Set<String> distinct = new HashSet<>();
        for (String v : values) {
            if (!JAVA_IDENTIFIER.matcher(v).matches() || javax.lang.model.SourceVersion.isKeyword(v) || !distinct.add(v)) {
                return null;
            }
        }
        return List.copyOf(values);
    }

    // ── Names ─────────────────────────────────────────────────────────────────

    /** The columns a constraint lists, as the table spells them; empty when one is not the table's. */
    static List<String> canonicalColumns(TableModel table, String list) {
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
    static String bareName(String raw) {
        String s = raw.trim();
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        return unquote(s.trim());
    }

    static String unquote(String s) {
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
