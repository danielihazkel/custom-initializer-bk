package com.menora.initializr.liveschema;

import com.menora.initializr.liveschema.LiveSchemaModel.CheckIn;
import com.menora.initializr.liveschema.LiveSchemaModel.Column;
import com.menora.initializr.liveschema.LiveSchemaModel.ForeignKey;
import com.menora.initializr.liveschema.LiveSchemaModel.Kind;
import com.menora.initializr.liveschema.LiveSchemaModel.Table;
import com.menora.initializr.liveschema.LiveSchemaModel.TableRef;
import com.menora.initializr.sql.SqlDialect;
import org.springframework.http.HttpStatus;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads a schema through JDBC: the driver's {@link DatabaseMetaData} for tables, columns, keys,
 * foreign keys and unique indexes, and one catalog query per dialect for CHECK constraints (and
 * Oracle's identity columns, PostgreSQL's enum types). Every connection is read-only, opened for
 * one call and closed at its end; every statement has a timeout.
 *
 * <p>Only {@code java.sql} is used, so a driver missing from the classpath (one a build left out)
 * is a 400 naming it, not a startup failure.
 */
public class LiveSchemaReader {

    /** At most this many names in a schema's table list. */
    public static final int MAX_LISTED = 5000;

    private final DialectSupport support;

    public LiveSchemaReader(DialectSupport support) {
        this.support = support;
    }

    public DialectSupport support() { return support; }

    /** Opens a read-only connection; a failure is a 400 in the driver's words, without the password. */
    public Connection connect(ConnectionSpec spec) {
        if (!support.driverPresent()) {
            throw new LiveSchemaException(HttpStatus.BAD_REQUEST, "No driver",
                    "The " + label(support.dialect()) + " driver is not installed on this server.");
        }
        String url = support.url(spec);
        try {
            Driver driver = DriverManager.getDriver(url);
            Connection c = driver.connect(url, support.properties(spec));
            if (c == null) throw new SQLException("The driver did not accept the connection");
            try {
                c.setReadOnly(true);
            } catch (SQLException ignored) {
                // Some drivers refuse once connected; the connection still only runs queries.
            }
            return c;
        } catch (SQLException e) {
            throw new LiveSchemaException(HttpStatus.BAD_REQUEST, "Could not connect",
                    "Could not connect to " + spec.label() + ": " + describe(e, spec));
        }
    }

    /** The schemas a user may pick, system schemas left out; and the one the connection opened in. */
    public record Schemas(List<String> schemas, String current) {}

    public Schemas schemas(Connection c) throws SQLException {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        String query = support.schemasQuery();
        boolean listed = false;
        if (query != null) {
            try (PreparedStatement ps = prepare(c, query); ResultSet rs = ps.executeQuery()) {
                while (rs.next() && names.size() < MAX_LISTED) names.add(rs.getString(1));
                listed = true;
            } catch (SQLException e) {
                names.clear();
            }
        }
        if (!listed) {
            try (ResultSet rs = c.getMetaData().getSchemas()) {
                while (rs.next() && names.size() < MAX_LISTED) names.add(rs.getString("TABLE_SCHEM"));
            }
        }
        names.removeIf(n -> n == null || support.systemSchema(n));
        String current = currentSchema(c);
        return new Schemas(new ArrayList<>(names), current);
    }

    private String currentSchema(Connection c) {
        try {
            String s = c.getSchema();
            if (s != null && !s.isBlank()) return s;
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException ignored) {
            // Older drivers: fall back to the user name below.
        }
        try {
            String user = c.getMetaData().getUserName();
            return user == null ? null : support.fold(user);
        } catch (SQLException e) {
            return null;
        }
    }

    public record Tables(List<TableRef> tables, boolean truncated) {}

    public Tables tables(Connection c, String schema) throws SQLException {
        List<TableRef> out = new ArrayList<>();
        boolean truncated = false;
        try (ResultSet rs = c.getMetaData().getTables(null, schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                if (out.size() >= MAX_LISTED) {
                    truncated = true;
                    break;
                }
                String type = rs.getString("TABLE_TYPE");
                out.add(new TableRef(rs.getString("TABLE_NAME"),
                        type != null && type.toUpperCase(Locale.ROOT).contains("VIEW") ? Kind.VIEW : Kind.TABLE));
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new Tables(out, truncated);
    }

    /** Reads the named tables and views of {@code schema}; a name the schema does not have is a 400. */
    public LiveSchemaModel.Schema read(Connection c, String schema, Collection<String> names) throws SQLException {
        Map<String, TableRef> available = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (TableRef t : tables(c, schema).tables()) available.put(t.name(), t);
        List<TableRef> wanted = new ArrayList<>();
        for (String name : names) {
            TableRef ref = available.get(name);
            if (ref == null) throw LiveSchemaException.badRequest("Schema " + schema + " has no table or view " + name);
            if (wanted.stream().noneMatch(w -> w.name().equals(ref.name()))) wanted.add(ref);
        }

        List<String> warnings = new ArrayList<>();
        DatabaseMetaData md = c.getMetaData();
        Map<String, Set<String>> identity = identityColumns(c, schema, warnings);
        Map<String, List<String>> pgEnums = support.dialect() == SqlDialect.POSTGRESQL ? postgresEnums(c, warnings) : Map.of();
        Map<String, List<String>> checkClauses = checkClauses(c, schema, warnings);

        List<Table> tables = new ArrayList<>();
        for (TableRef ref : wanted) {
            List<Column> columns = columns(md, schema, ref.name(), identity.getOrDefault(ref.name(), Set.of()));
            if (ref.kind() == Kind.VIEW) {
                tables.add(new Table(schema, ref.name(), Kind.VIEW, columns, List.of(), List.of(), List.of(), List.of()));
                continue;
            }
            List<String> pk = primaryKey(md, schema, ref.name());
            List<ForeignKey> fks = foreignKeys(md, schema, ref.name());
            List<List<String>> uniques = uniqueKeys(md, schema, ref.name(), pk, warnings);
            List<CheckIn> checks = new ArrayList<>();
            Map<String, String> byLower = new HashMap<>();
            for (Column col : columns) byLower.put(col.name().toLowerCase(Locale.ROOT), col.name());
            for (String clause : checkClauses.getOrDefault(ref.name(), List.of())) {
                CheckIn in = CheckClauses.inList(clause, n -> byLower.get(n.toLowerCase(Locale.ROOT)));
                if (in != null && checks.stream().noneMatch(x -> x.column().equals(in.column()))) checks.add(in);
            }
            // A PostgreSQL enum type reads as the list of its labels.
            for (Column col : columns) {
                List<String> labels = pgEnums.get(col.typeName());
                if (labels != null && checks.stream().noneMatch(x -> x.column().equals(col.name()))) {
                    checks.add(new CheckIn(col.name(), labels));
                }
            }
            tables.add(new Table(schema, ref.name(), Kind.TABLE, columns, pk, fks, uniques, checks));
        }
        return new LiveSchemaModel.Schema(schema, tables, warnings);
    }

    private List<Column> columns(DatabaseMetaData md, String schema, String table, Set<String> identity) throws SQLException {
        List<Column> out = new ArrayList<>();
        try (ResultSet rs = md.getColumns(null, schema, escape(md, table), "%")) {
            while (rs.next()) {
                if (!table.equals(rs.getString("TABLE_NAME"))) continue;
                String name = rs.getString("COLUMN_NAME");
                String type = rs.getString("TYPE_NAME");
                Integer size = intOrNull(rs, "COLUMN_SIZE");
                Integer digits = intOrNull(rs, "DECIMAL_DIGITS");
                boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                String auto = optionalString(rs, "IS_AUTOINCREMENT");
                boolean autoIncrement = "YES".equalsIgnoreCase(auto) || identity.contains(name)
                        || support.identityInTypeName(type)
                        || (type != null && type.toLowerCase(Locale.ROOT).endsWith("serial"));
                out.add(new Column(name, type, size, digits, nullable, autoIncrement));
            }
        }
        return out;
    }

    private static List<String> primaryKey(DatabaseMetaData md, String schema, String table) throws SQLException {
        TreeMap<Integer, String> bySeq = new TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(null, schema, table)) {
            while (rs.next()) bySeq.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(bySeq.values());
    }

    private static List<ForeignKey> foreignKeys(DatabaseMetaData md, String schema, String table) throws SQLException {
        // Grouped by constraint (FK_NAME, else the referenced table), columns in KEY_SEQ order.
        Map<String, TreeMap<Integer, String[]>> byKey = new LinkedHashMap<>();
        Map<String, String[]> target = new HashMap<>();
        try (ResultSet rs = md.getImportedKeys(null, schema, table)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                String pkTable = rs.getString("PKTABLE_NAME");
                String pkSchema = rs.getString("PKTABLE_SCHEM");
                String key = (fkName != null ? fkName : "") + "|" + pkSchema + "." + pkTable;
                byKey.computeIfAbsent(key, k -> new TreeMap<>())
                        .put(rs.getInt("KEY_SEQ"), new String[]{rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME")});
                target.put(key, new String[]{pkSchema, pkTable});
            }
        }
        List<ForeignKey> out = new ArrayList<>();
        for (var e : byKey.entrySet()) {
            List<String> cols = new ArrayList<>();
            List<String> refCols = new ArrayList<>();
            for (String[] pair : e.getValue().values()) {
                cols.add(pair[0]);
                refCols.add(pair[1]);
            }
            String[] t = target.get(e.getKey());
            out.add(new ForeignKey(cols, t[0], t[1], refCols));
        }
        return out;
    }

    /** Unique indexes and constraints other than the primary key, each a list of columns. */
    private static List<List<String>> uniqueKeys(DatabaseMetaData md, String schema, String table, List<String> pk,
                                                 List<String> warnings) {
        Map<String, TreeMap<Integer, String>> byIndex = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(null, schema, table, true, true)) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                String column = rs.getString("COLUMN_NAME");
                String index = rs.getString("INDEX_NAME");
                if (column == null || index == null) continue; // an expression index
                byIndex.computeIfAbsent(index, k -> new TreeMap<>()).put((int) rs.getShort("ORDINAL_POSITION"), column);
            }
        } catch (SQLException e) {
            warnings.add("Unique constraints of " + table + " could not be read: " + e.getMessage());
            return List.of();
        }
        List<List<String>> out = new ArrayList<>();
        for (TreeMap<Integer, String> cols : byIndex.values()) {
            List<String> list = new ArrayList<>(cols.values());
            if (sameColumns(list, pk)) continue;
            if (out.stream().noneMatch(u -> sameColumns(u, list))) out.add(list);
        }
        return out;
    }

    private static boolean sameColumns(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        s.addAll(a);
        return s.containsAll(b);
    }

    private Map<String, List<String>> checkClauses(Connection c, String schema, List<String> warnings) {
        Map<String, List<String>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String query = support.checkQuery();
        if (query == null) return out;
        try (PreparedStatement ps = prepare(c, query)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String clause = rs.getString(2);
                    if (table != null && clause != null) out.computeIfAbsent(table, k -> new ArrayList<>()).add(clause);
                }
            }
        } catch (SQLException e) {
            warnings.add("CHECK constraints could not be read, so no column became an enum: " + e.getMessage());
        }
        return out;
    }

    private Map<String, Set<String>> identityColumns(Connection c, String schema, List<String> warnings) {
        Map<String, Set<String>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String query = support.identityQuery();
        if (query == null) return out;
        try (PreparedStatement ps = prepare(c, query)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.computeIfAbsent(rs.getString(1), k -> new TreeSet<>()).add(rs.getString(2));
            }
        } catch (SQLException e) {
            warnings.add("Identity columns could not be read: " + e.getMessage());
        }
        return out;
    }

    private static Map<String, List<String>> postgresEnums(Connection c, List<String> warnings) {
        Map<String, List<String>> out = new HashMap<>();
        try (PreparedStatement ps = prepare(c, DialectSupport.Postgres.ENUM_QUERY); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
        } catch (SQLException e) {
            warnings.add("Enum types could not be read: " + e.getMessage());
        }
        return out;
    }

    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        ps.setQueryTimeout(DialectSupport.QUERY_TIMEOUT_SECONDS);
        return ps;
    }

    /** A table name as a metadata pattern: {@code _} and {@code %} are wildcards there. */
    private static String escape(DatabaseMetaData md, String name) throws SQLException {
        String esc = md.getSearchStringEscape();
        if (esc == null || esc.isEmpty()) return name;
        return name.replace(esc, esc + esc).replace("_", esc + "_").replace("%", esc + "%");
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static String optionalString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    /** A driver's message with the password taken out, should a driver ever echo it. */
    public static String describe(SQLException e, ConnectionSpec spec) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (spec.password() != null && spec.password().length() >= 3) {
            message = message.replace(spec.password(), "****");
        }
        return message.length() > 500 ? message.substring(0, 500) + "…" : message;
    }

    public static String label(SqlDialect d) {
        return switch (d) {
            case POSTGRESQL -> "PostgreSQL";
            case MSSQL -> "SQL Server";
            case ORACLE -> "Oracle";
            case DB2 -> "DB2 for IBM i";
            case H2 -> "H2";
            case MYSQL -> "MySQL";
        };
    }
}
