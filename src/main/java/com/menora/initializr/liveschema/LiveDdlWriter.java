package com.menora.initializr.liveschema;

import com.menora.initializr.liveschema.LiveSchemaModel.CheckIn;
import com.menora.initializr.liveschema.LiveSchemaModel.Column;
import com.menora.initializr.liveschema.LiveSchemaModel.ForeignKey;
import com.menora.initializr.liveschema.LiveSchemaModel.Kind;
import com.menora.initializr.liveschema.LiveSchemaModel.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Writes what was read as the SQL a person would have pasted into the SQL wizard, in the
 * database's own dialect: a {@code CREATE TABLE} per table (columns, primary key, unique and
 * {@code CHECK … IN} constraints), the foreign keys between the tables read as
 * {@code ALTER TABLE … ADD FOREIGN KEY} after all of them (so the script also runs as a Flyway
 * baseline, whatever the order), and a {@code SELECT} of its columns per view, which the wizard
 * turns into a read-only view entity.
 *
 * <p>Going through text keeps one path: what is generated from a database is exactly what the
 * same script pasted would generate, and the person sees and can edit it first.
 */
public final class LiveDdlWriter {

    private LiveDdlWriter() {}

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Words that cannot be a bare name in any of the dialects — quoted when a table or column has one. */
    private static final Set<String> RESERVED = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    static {
        RESERVED.addAll(List.of("ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BETWEEN", "BY", "CASE", "CHECK",
                "COLUMN", "CONSTRAINT", "CREATE", "CROSS", "CURRENT", "CURRENT_DATE", "CURRENT_TIME",
                "CURRENT_TIMESTAMP", "CURRENT_USER", "DEFAULT", "DELETE", "DESC", "DISTINCT", "DROP", "ELSE",
                "END", "EXCEPT", "EXISTS", "FALSE", "FETCH", "FOR", "FOREIGN", "FROM", "FULL", "GRANT", "GROUP",
                "HAVING", "IN", "INDEX", "INNER", "INSERT", "INTERSECT", "INTO", "IS", "JOIN", "KEY", "LEFT",
                "LIKE", "LIMIT", "LEVEL", "MINUS", "NATURAL", "NOT", "NULL", "OFFSET", "ON", "OR", "ORDER",
                "OUTER", "PRIMARY", "REFERENCES", "RIGHT", "ROW", "ROWNUM", "ROWS", "SELECT", "SESSION", "SET",
                "SIZE", "TABLE", "THEN", "TO", "TOP", "TRUE", "UNION", "UNIQUE", "UPDATE", "USER", "USING",
                "VALUE", "VALUES", "VIEW", "WHEN", "WHERE", "WITH", "DATE", "TIME", "TIMESTAMP", "COMMENT",
                "NUMBER", "UID", "FILE", "MODE", "RESOURCE", "START", "ACCESS", "AUDIT", "OPTION"));
    }

    /**
     * @param source  how the database is named in the script's opening comment (no password)
     * @param qualify write each name with its schema ({@code hr.employees}) — the generated
     *                entities then carry {@code @Table(schema = …)}
     */
    public static String write(DialectSupport support, LiveSchemaModel.Schema read, String source, boolean qualify) {
        List<Table> tables = read.tables().stream().filter(t -> t.kind() == Kind.TABLE).toList();
        List<Table> views = read.tables().stream().filter(t -> t.kind() == Kind.VIEW).toList();
        Set<String> readNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Table t : tables) readNames.add(t.name());

        StringBuilder out = new StringBuilder();
        out.append("-- Read from ").append(source).append(", schema ").append(read.schema()).append(": ")
                .append(count(tables.size(), "table")).append(", ").append(count(views.size(), "view")).append(".\n");
        for (String w : read.warnings()) out.append("-- Note: ").append(oneLine(w)).append('\n');

        List<String> alters = new ArrayList<>();
        List<String> outside = new ArrayList<>();
        for (Table t : tables) {
            out.append('\n').append(createTable(support, t, qualify));
            for (ForeignKey fk : t.foreignKeys()) {
                if (readNames.contains(fk.referencedTable()) && sameSchema(fk.referencedSchema(), t.schema())) {
                    alters.add("ALTER TABLE " + name(support, t, qualify) + " ADD FOREIGN KEY ("
                            + columnList(support, fk.columns()) + ") REFERENCES "
                            + qualified(support, t.schema(), fk.referencedTable(), qualify)
                            + " (" + columnList(support, fk.referencedColumns()) + ");");
                } else {
                    outside.add("-- " + t.name() + " (" + String.join(", ", fk.columns()) + ") references "
                            + (fk.referencedSchema() == null ? "" : fk.referencedSchema() + ".") + fk.referencedTable()
                            + ", which was not read: the column stays a plain field.");
                }
            }
        }
        if (!alters.isEmpty() || !outside.isEmpty()) {
            out.append('\n');
            for (String a : alters) out.append(a).append('\n');
            for (String o : outside) out.append(o).append('\n');
        }
        for (Table v : views) {
            out.append("\n-- View ").append(v.schema()).append('.').append(v.name())
                    .append(", as a read-only entity (the SQL wizard writes its fields as String).\n");
            out.append(selectOf(support, v, qualify)).append(";\n");
        }
        return out.toString();
    }

    static String createTable(DialectSupport support, Table t, boolean qualify) {
        List<String> lines = new ArrayList<>();
        for (Column c : t.columns()) lines.add(columnLine(support, c));
        if (!t.primaryKey().isEmpty()) lines.add("PRIMARY KEY (" + columnList(support, t.primaryKey()) + ")");
        for (List<String> u : t.uniqueKeys()) lines.add("UNIQUE (" + columnList(support, u) + ")");
        for (CheckIn check : t.checks()) {
            List<String> quoted = check.values().stream().map(v -> "'" + v.replace("'", "''") + "'").toList();
            lines.add("CHECK (" + ident(support, check.column()) + " IN (" + String.join(", ", quoted) + "))");
        }
        return "CREATE TABLE " + name(support, t, qualify) + " (\n    " + String.join(",\n    ", lines) + "\n);\n";
    }

    private static String columnLine(DialectSupport support, Column c) {
        StringBuilder line = new StringBuilder(ident(support, c.name())).append(' ').append(support.typeOf(c));
        if (c.autoIncrement()) line.append(' ').append(support.identityClause());
        if (!c.nullable()) line.append(" NOT NULL");
        return line.toString();
    }

    /** {@code SELECT a, b FROM schema.view}: the wizard names the view's entity after the FROM. */
    public static String selectOf(DialectSupport support, Table view, boolean qualify) {
        List<String> cols = view.columns().stream().map(c -> ident(support, c.name())).toList();
        return "SELECT " + String.join(", ", cols) + " FROM " + name(support, view, qualify);
    }

    private static String name(DialectSupport support, Table t, boolean qualify) {
        return qualified(support, t.schema(), t.name(), qualify);
    }

    private static String qualified(DialectSupport support, String schema, String name, boolean qualify) {
        return qualify && schema != null && !schema.isBlank()
                ? ident(support, schema) + "." + ident(support, name)
                : ident(support, name);
    }

    private static String columnList(DialectSupport support, List<String> names) {
        return String.join(", ", names.stream().map(n -> ident(support, n)).toList());
    }

    /**
     * A name as the database stores it, written so the database reads it back the same: bare when
     * it is a plain word in the dialect's fold case and not a keyword, quoted otherwise
     * ({@code "OrderDate"} on PostgreSQL, {@code [Order Date]} on SQL Server).
     */
    static String ident(DialectSupport support, String name) {
        boolean plain = PLAIN.matcher(name).matches() && !RESERVED.contains(name)
                && name.equals(support.fold(name));
        if (plain) return name;
        if (support instanceof DialectSupport.SqlServer) {
            // Brackets only around a word: the parser reads [x] and drops them; anything else is quoted.
            return PLAIN.matcher(name).matches() ? "[" + name + "]" : "\"" + name.replace("\"", "\"\"") + "\"";
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static boolean sameSchema(String a, String b) {
        return a == null || b == null || a.equalsIgnoreCase(b);
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
