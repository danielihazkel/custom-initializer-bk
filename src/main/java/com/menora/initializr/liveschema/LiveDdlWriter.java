package com.menora.initializr.liveschema;

import com.menora.initializr.liveschema.LiveSchemaModel.CheckIn;
import com.menora.initializr.liveschema.LiveSchemaModel.Column;
import com.menora.initializr.liveschema.LiveSchemaModel.ForeignKey;
import com.menora.initializr.liveschema.LiveSchemaModel.Index;
import com.menora.initializr.liveschema.LiveSchemaModel.Kind;
import com.menora.initializr.liveschema.LiveSchemaModel.Table;
import com.menora.initializr.sql.JavaType;
import com.menora.initializr.sql.TypeMappers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes what was read as the SQL a person would have pasted into the SQL wizard, in the
 * database's own dialect: a {@code CREATE TABLE} per table (columns with their defaults, primary
 * key, unique and {@code CHECK … IN} constraints), the foreign keys between the tables read as
 * {@code ALTER TABLE … ADD FOREIGN KEY} after all of them (so the script also runs as a Flyway
 * baseline, whatever the order), the indexes as {@code CREATE INDEX}, the comments as
 * {@code COMMENT ON}, and a {@code SELECT} of its columns per view — each cast to its type —
 * which the wizard turns into a read-only view entity with typed fields.
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
        List<String> indexes = new ArrayList<>();
        for (Table t : tables) {
            for (Index index : t.indexes()) indexes.add(indexLine(support, t, index, qualify));
        }
        if (!indexes.isEmpty()) {
            out.append('\n');
            for (String i : indexes) out.append(i).append('\n');
        }
        List<String> comments = new ArrayList<>();
        for (Table t : read.tables()) comments.addAll(commentLines(support, t, qualify));
        if (!comments.isEmpty()) {
            out.append('\n');
            for (String c : comments) out.append(c).append('\n');
        }
        for (Table v : views) {
            out.append("\n-- View ").append(v.schema()).append('.').append(v.name())
                    .append(", read-only. Each cast names the type of its column: the SQL wizard types the field by it.\n");
            out.append(typedSelectOf(support, v, qualify)).append(";\n");
        }
        return out.toString();
    }

    static String createTable(DialectSupport support, Table t, boolean qualify) {
        // Each line with what goes after its comma: a default the script cannot carry, as a comment.
        List<String[]> lines = new ArrayList<>();
        for (Column c : t.columns()) lines.add(columnLine(support, c));
        if (!t.primaryKey().isEmpty()) lines.add(line("PRIMARY KEY (" + columnList(support, t.primaryKey()) + ")"));
        for (List<String> u : t.uniqueKeys()) lines.add(line("UNIQUE (" + columnList(support, u) + ")"));
        for (CheckIn check : t.checks()) {
            List<String> quoted = check.values().stream().map(v -> "'" + v.replace("'", "''") + "'").toList();
            lines.add(line("CHECK (" + ident(support, check.column()) + " IN (" + String.join(", ", quoted) + "))"));
        }
        StringBuilder out = new StringBuilder("CREATE TABLE ").append(name(support, t, qualify)).append(" (\n");
        for (int i = 0; i < lines.size(); i++) {
            out.append("    ").append(lines.get(i)[0]).append(i < lines.size() - 1 ? "," : "");
            if (lines.get(i)[1] != null) out.append(" -- ").append(lines.get(i)[1]);
            out.append('\n');
        }
        return out.append(");\n").toString();
    }

    private static String[] line(String sql) {
        return new String[]{sql, null};
    }

    private static String[] columnLine(DialectSupport support, Column c) {
        StringBuilder line = new StringBuilder(ident(support, c.name())).append(' ').append(support.typeOf(c));
        String note = null;
        if (c.autoIncrement()) {
            line.append(' ').append(support.identityClause());
        } else if (c.defaultValue() != null) {
            String literal = defaultLiteral(c.defaultValue());
            if (literal != null) line.append(" DEFAULT ").append(literal);
            else if (!c.defaultValue().trim().equalsIgnoreCase("NULL")) note = "default in the database: " + brief(c.defaultValue());
        }
        if (!c.nullable()) line.append(" NOT NULL");
        return new String[]{line.toString(), note};
    }

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern STRING = Pattern.compile("'(?:[^']|'')*'");
    /** PostgreSQL writes a literal's type after it: {@code 'open'::character varying}, {@code 0::numeric(10,2)}. */
    private static final Pattern PG_CAST = Pattern.compile("::\\s*\"?[A-Za-z_][\\w ]*\"?(?:\\(\\d+(?:\\s*,\\s*\\d+)?\\))?(?:\\[\\])?\\s*$");
    private static final Set<String> NOW = Set.of("NOW()", "GETDATE()", "CURRENT_TIMESTAMP()", "CURRENT TIMESTAMP");
    private static final Set<String> KEYWORDS = Set.of("CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME", "LOCALTIMESTAMP",
            "SYSDATE", "SYSTIMESTAMP", "TRUE", "FALSE");

    /**
     * A column default as the script can carry it — a number, a text, the current date or time,
     * true or false — out of the form the catalog keeps it in ({@code ((0))} on SQL Server,
     * {@code 'open'::character varying} on PostgreSQL), or null for anything else: a sequence, a
     * function call, an expression. Those stay where they are; the script says so in a comment.
     */
    static String defaultLiteral(String raw) {
        String v = raw.trim();
        // SQL Server wraps a default in parentheses, a number in two pairs.
        while (v.length() >= 2 && v.startsWith("(") && closes(v)) v = v.substring(1, v.length() - 1).trim();
        Matcher cast = PG_CAST.matcher(v);
        if (cast.find()) {
            String head = v.substring(0, cast.start()).trim();
            if (NUMBER.matcher(head).matches() || STRING.matcher(head).matches()) v = head;
        }
        // SQL Server's national-text prefix.
        if (v.length() > 2 && (v.charAt(0) == 'N' || v.charAt(0) == 'n') && STRING.matcher(v.substring(1)).matches()) {
            v = v.substring(1);
        }
        if (NUMBER.matcher(v).matches() || STRING.matcher(v).matches()) return v;
        String upper = v.toUpperCase(Locale.ROOT);
        if (NOW.contains(upper)) return "CURRENT_TIMESTAMP";
        if (KEYWORDS.contains(upper)) return upper;
        return null;
    }

    /** True when the parenthesis that opens {@code v} is closed by its last character. */
    private static boolean closes(String v) {
        int depth = 0;
        boolean inText = false;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\'') inText = !inText;
            else if (!inText && ch == '(') depth++;
            else if (!inText && ch == ')' && --depth == 0) return i == v.length() - 1;
        }
        return false;
    }

    private static String brief(String s) {
        String one = oneLine(s);
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }

    /** {@code CREATE INDEX name ON table (a, b DESC);}, or a comment for an index the statement cannot describe. */
    private static String indexLine(DialectSupport support, Table t, Index index, boolean qualify) {
        if (index.skipped() != null) {
            return "-- " + (index.unique() ? "Unique index " : "Index ") + index.name() + " on " + t.name()
                    + " is not in this script: " + index.skipped() + ".";
        }
        List<String> keys = index.columns().stream()
                .map(k -> ident(support, k.name()) + (k.descending() ? " DESC" : "")).toList();
        return "CREATE INDEX " + ident(support, index.name()) + " ON " + name(support, t, qualify)
                + " (" + String.join(", ", keys) + ");";
    }

    /**
     * A table's comment and its columns', as {@code COMMENT ON} statements — or, where the
     * database has no such statement (SQL Server), as comments of the script.
     */
    private static List<String> commentLines(DialectSupport support, Table t, boolean qualify) {
        List<String> out = new ArrayList<>();
        String table = name(support, t, qualify);
        boolean statements = support.commentOnStatements();
        // A view is not created by this script, so a statement about it would fail on an empty database.
        boolean asText = !statements || t.kind() == Kind.VIEW;
        if (t.comment() != null) {
            out.add(asText ? "-- " + table + ": " + oneLine(t.comment())
                    : "COMMENT ON TABLE " + table + " IS " + text(t.comment()) + ";");
        }
        for (Column c : t.columns()) {
            if (c.comment() == null) continue;
            String column = table + "." + ident(support, c.name());
            out.add(asText ? "-- " + column + ": " + oneLine(c.comment())
                    : "COMMENT ON COLUMN " + column + " IS " + text(c.comment()) + ";");
        }
        return out;
    }

    private static String text(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** {@code SELECT a, b FROM schema.view}: the wizard names the view's entity after the FROM. */
    public static String selectOf(DialectSupport support, Table view, boolean qualify) {
        List<String> cols = view.columns().stream().map(c -> ident(support, c.name())).toList();
        return "SELECT " + String.join(", ", cols) + " FROM " + name(support, view, qualify);
    }

    /** A DDL type as {@link DialectSupport#typeOf} writes it: its name, then a size or a precision and scale. */
    public static final Pattern TYPE = Pattern.compile("([A-Z][A-Z0-9_ ]*?)\\s*(?:\\((\\d+|MAX)(?:,(\\d+))?\\))?(?:\\s.*)?");

    /**
     * {@link #selectOf} with each column cast to its own type:
     * {@code SELECT CAST(id AS BIGINT) AS id, CAST(name AS VARCHAR(80)) AS name FROM schema.view}.
     * A cast to the type a column already has changes nothing in the database, and it is how a
     * query says its types to the SQL wizard, which otherwise writes a view's fields as
     * {@code String} with a note to set the real type. Left as they are: binary columns and large
     * objects (not every database casts to one), and a column whose type the reader did not know
     * and wrote as text — a cast would then be a conversion, which the database may refuse.
     */
    public static String typedSelectOf(DialectSupport support, Table view, boolean qualify) {
        List<String> cols = new ArrayList<>();
        for (Column c : view.columns()) {
            String id = ident(support, c.name());
            String type = support.typeOf(c);
            Matcher m = TYPE.matcher(type);
            String raw = m.matches() ? m.group(1).trim() : type;
            Integer precision = m.matches() && m.group(2) != null && !m.group(2).equals("MAX") ? Integer.valueOf(m.group(2)) : null;
            Integer scale = m.matches() && m.group(3) != null ? Integer.valueOf(m.group(3)) : null;
            JavaType java = TypeMappers.map(support.dialect(), raw, precision, scale);
            boolean cast = java.simpleName().equals("String") ? castableText(support, c, raw, type)
                    : !java.simpleName().equals("byte[]");
            cols.add(cast ? "CAST(" + id + " AS " + type + ") AS " + id : id);
        }
        return "SELECT " + String.join(", ", cols) + " FROM " + name(support, view, qualify);
    }

    private static final Set<String> CHARACTER_TYPES = Set.of("CHAR", "VARCHAR", "NCHAR", "NVARCHAR", "VARCHAR2",
            "NVARCHAR2", "CHARACTER", "GRAPHIC", "VARGRAPHIC");
    private static final Set<String> POSTGRES_TEXT = Set.of("text", "citext", "name");

    /**
     * Whether a text column can be cast to the type written for it: only when the driver itself
     * calls it a character type. {@link DialectSupport#typeOf} writes a type it does not know (a
     * geometry, an array, a DATALINK) as text too, and that one must stay bare. Oracle takes no
     * {@code VARCHAR2} without a length.
     */
    private static boolean castableText(DialectSupport support, Column c, String raw, String type) {
        String driver = c.typeName() == null ? "" : c.typeName().toLowerCase(Locale.ROOT);
        if (driver.contains("bit data")) return false;
        if (CHARACTER_TYPES.contains(raw)) {
            return (driver.contains("char") || driver.contains("graphic"))
                    && (type.contains("(") || !(support instanceof DialectSupport.Oracle));
        }
        return raw.equals("TEXT") && support instanceof DialectSupport.Postgres && POSTGRES_TEXT.contains(driver);
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
                && !com.menora.initializr.sql.SqlKeywords.reservedAnywhere(name)
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
