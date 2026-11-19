package com.menora.initializr.liveschema;

import com.menora.initializr.liveschema.LiveSchemaModel.CheckIn;
import com.menora.initializr.liveschema.LiveSchemaModel.Column;
import com.menora.initializr.liveschema.LiveSchemaModel.ForeignKey;
import com.menora.initializr.liveschema.LiveSchemaModel.Index;
import com.menora.initializr.liveschema.LiveSchemaModel.IndexColumn;
import com.menora.initializr.liveschema.LiveSchemaModel.Kind;
import com.menora.initializr.liveschema.LiveSchemaModel.Table;
import com.menora.initializr.liveschema.LiveSchemaModel.TableRef;
import com.menora.initializr.sql.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Reads a schema through JDBC: the driver's {@link DatabaseMetaData} for tables and columns
 * (type, default, comment), and the dialect's catalog queries — one per schema — for primary
 * keys, foreign keys, indexes, CHECK constraints and comments the driver does not report (and
 * Oracle's identity columns, PostgreSQL's enum types). A dialect without such a query, or a
 * database that refuses it, is asked through the driver table by table, as every read used to
 * be. Every connection is read-only, opened for one call and closed at its end; every statement
 * has a timeout.
 *
 * <p>Only {@code java.sql} is used, so a driver missing from the classpath (one a build left out)
 * is a 400 naming it, not a startup failure.
 */
public class LiveSchemaReader {

    /** At most this many names in a schema's table list. */
    public static final int MAX_LISTED = 5000;

    private static final Logger log = LoggerFactory.getLogger(LiveSchemaReader.class);

    private final DialectSupport support;
    private final boolean catalogQueries;
    /** The catalog queries a database refused in this reader's reads (each was answered by the driver instead). */
    private final List<String> refused = new ArrayList<>();

    public LiveSchemaReader(DialectSupport support) {
        this(support, true);
    }

    /** @param catalogQueries false reads keys and indexes through the driver only ({@code menora.live-schema.catalog-queries}) */
    public LiveSchemaReader(DialectSupport support, boolean catalogQueries) {
        this.support = support;
        this.catalogQueries = catalogQueries;
    }

    public DialectSupport support() { return support; }

    /** What a read fell back to the driver for, and why: empty when every catalog query ran. */
    public List<String> refusedQueries() { return List.copyOf(refused); }

    /** Opens a read-only connection; a failure is a 400 in the driver's words, without the password. */
    public Connection connect(ConnectionSpec spec) {
        return connect(spec, true);
    }

    /**
     * Opens a connection; {@code readOnly} false only for creating a model's tables on a source an
     * admin allowed it for ({@link LiveSchemaService#writableSource}).
     */
    public Connection connect(ConnectionSpec spec, boolean readOnly) {
        if (!support.driverPresent()) {
            throw new LiveSchemaException(HttpStatus.BAD_REQUEST, "No driver",
                    "The " + label(support.dialect()) + " driver is not installed on this server.");
        }
        String url = support.url(spec, readOnly);
        try {
            Driver driver = DriverManager.getDriver(url);
            Connection c = driver.connect(url, support.properties(spec, readOnly));
            if (c == null) throw new SQLException("The driver did not accept the connection");
            if (readOnly) {
                try {
                    c.setReadOnly(true);
                } catch (SQLException ignored) {
                    // Some drivers refuse once connected; the connection still only runs queries.
                }
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
        return tables(c, schema, null);
    }

    /** {@link #tables(Connection, String)}, also putting each table's comment in {@code remarks} when given. */
    private Tables tables(Connection c, String schema, Map<String, String> remarks) throws SQLException {
        List<TableRef> out = new ArrayList<>();
        boolean truncated = false;
        try (ResultSet rs = c.getMetaData().getTables(null, schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                if (out.size() >= MAX_LISTED) {
                    truncated = true;
                    break;
                }
                String type = rs.getString("TABLE_TYPE");
                String name = rs.getString("TABLE_NAME");
                out.add(new TableRef(name,
                        type != null && type.toUpperCase(Locale.ROOT).contains("VIEW") ? Kind.VIEW : Kind.TABLE));
                String remark = remarks == null ? null : optionalString(rs, "REMARKS");
                if (remark != null && !remark.isBlank()) remarks.put(name, remark);
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new Tables(out, truncated);
    }

    /** Reads the named tables and views of {@code schema}; a name the schema does not have is a 400. */
    public LiveSchemaModel.Schema read(Connection c, String schema, Collection<String> names) throws SQLException {
        Map<String, TableRef> available = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> tableComments = new HashMap<>();
        for (TableRef t : tables(c, schema, tableComments).tables()) available.put(t.name(), t);
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
        // One query per schema where the dialect has one; null where it has none or the database refused it.
        Map<String, List<String>> allKeys = catalogQueries ? primaryKeys(c, schema) : null;
        Map<String, List<ForeignKey>> allForeignKeys = catalogQueries ? foreignKeys(c, schema) : null;
        Map<String, List<RawIndex>> allIndexes = catalogQueries ? indexes(c, schema) : null;
        Map<String, Map<String, String>> comments = comments(c, schema, tableComments);

        List<Table> tables = new ArrayList<>();
        for (TableRef ref : wanted) {
            Map<String, String> columnComments = comments.getOrDefault(ref.name(), Map.of());
            List<Column> columns = columns(md, schema, ref.name(), identity.getOrDefault(ref.name(), Set.of()), columnComments);
            String comment = tableComments.get(ref.name());
            if (ref.kind() == Kind.VIEW) {
                tables.add(new Table(schema, ref.name(), Kind.VIEW, columns, List.of(), List.of(), List.of(), List.of(),
                        List.of(), comment));
                continue;
            }
            List<String> pk = allKeys != null ? allKeys.getOrDefault(ref.name(), List.of()) : primaryKey(md, schema, ref.name());
            List<ForeignKey> fks = allForeignKeys != null ? allForeignKeys.getOrDefault(ref.name(), List.of())
                    : foreignKeys(md, schema, ref.name());
            List<RawIndex> raw = allIndexes != null ? allIndexes.getOrDefault(ref.name(), List.of())
                    : indexInfo(md, schema, ref.name(), warnings);
            List<List<String>> uniques = new ArrayList<>();
            List<Index> indexes = new ArrayList<>();
            sortIndexes(raw, columns, pk, uniques, indexes);
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
            tables.add(new Table(schema, ref.name(), Kind.TABLE, columns, pk, fks, uniques, checks, indexes, comment));
        }
        return new LiveSchemaModel.Schema(schema, tables, warnings);
    }

    /**
     * Columns in the order the driver's result has them: Oracle streams COLUMN_DEF (a LONG), and a
     * column read after one further along is gone.
     */
    private List<Column> columns(DatabaseMetaData md, String schema, String table, Set<String> identity,
                                 Map<String, String> comments) throws SQLException {
        List<Column> out = new ArrayList<>();
        try (ResultSet rs = md.getColumns(null, schema, escape(md, table), "%")) {
            while (rs.next()) {
                if (!table.equals(rs.getString("TABLE_NAME"))) continue;
                String name = rs.getString("COLUMN_NAME");
                String type = rs.getString("TYPE_NAME");
                Integer size = intOrNull(rs, "COLUMN_SIZE");
                Integer digits = intOrNull(rs, "DECIMAL_DIGITS");
                boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                String remark = optionalString(rs, "REMARKS");
                String defaultValue = optionalString(rs, "COLUMN_DEF");
                String auto = optionalString(rs, "IS_AUTOINCREMENT");
                boolean autoIncrement = "YES".equalsIgnoreCase(auto) || identity.contains(name)
                        || support.identityInTypeName(type)
                        || (type != null && type.toLowerCase(Locale.ROOT).endsWith("serial"));
                String comment = comments.containsKey(name) ? comments.get(name) : remark;
                out.add(new Column(name, type, size, digits, nullable, autoIncrement,
                        defaultValue == null || defaultValue.isBlank() ? null : defaultValue.trim(),
                        comment == null || comment.isBlank() ? null : comment.trim()));
            }
        }
        return out;
    }

    // ── Keys and indexes of a whole schema ─────────────────────────────────

    private Map<String, List<String>> primaryKeys(Connection c, String schema) {
        String query = support.primaryKeysQuery();
        if (query == null) return null;
        Map<String, TreeMap<Integer, String>> byTable = new HashMap<>();
        try (PreparedStatement ps = prepare(c, query, schema); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byTable.computeIfAbsent(rs.getString(1), k -> new TreeMap<>()).put(rs.getInt(3), rs.getString(2));
            }
        } catch (SQLException e) {
            log.warn("Primary keys of {} are read table by table: the catalog query failed: {}", schema, e.getMessage());
            refused.add("primary keys: " + e.getMessage());
            return null;
        }
        Map<String, List<String>> out = new HashMap<>();
        byTable.forEach((table, columns) -> out.put(table, new ArrayList<>(columns.values())));
        return out;
    }

    private Map<String, List<ForeignKey>> foreignKeys(Connection c, String schema) {
        String query = support.foreignKeysQuery();
        if (query == null) return null;
        // By table, then by constraint in the order met; columns in key order.
        Map<String, Map<String, TreeMap<Integer, String[]>>> byTable = new HashMap<>();
        Map<String, String[]> target = new HashMap<>();
        try (PreparedStatement ps = prepare(c, query, schema); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1);
                String constraint = rs.getString(2);
                byTable.computeIfAbsent(table, k -> new LinkedHashMap<>())
                        .computeIfAbsent(constraint, k -> new TreeMap<>())
                        .put(rs.getInt(4), new String[]{rs.getString(3), rs.getString(7)});
                target.put(table + "|" + constraint, new String[]{rs.getString(5), rs.getString(6)});
            }
        } catch (SQLException e) {
            log.warn("Foreign keys of {} are read table by table: the catalog query failed: {}", schema, e.getMessage());
            refused.add("foreign keys: " + e.getMessage());
            return null;
        }
        Map<String, List<ForeignKey>> out = new HashMap<>();
        byTable.forEach((table, constraints) -> {
            List<ForeignKey> list = new ArrayList<>();
            constraints.forEach((constraint, pairs) -> {
                List<String> cols = new ArrayList<>();
                List<String> refCols = new ArrayList<>();
                for (String[] pair : pairs.values()) {
                    cols.add(pair[0]);
                    refCols.add(pair[1]);
                }
                String[] t = target.get(table + "|" + constraint);
                list.add(new ForeignKey(cols, t[0], t[1], refCols));
            });
            out.put(table, list);
        });
        return out;
    }

    /** An index as a catalog describes it, before it is known to be a key, a constraint or an index of its own. */
    private static final class RawIndex {
        final String name;
        final boolean unique;
        final TreeMap<Integer, IndexColumn> columns = new TreeMap<>();
        boolean partial;
        boolean expression;
        String method;

        RawIndex(String name, boolean unique) {
            this.name = name;
            this.unique = unique;
        }
    }

    private Map<String, List<RawIndex>> indexes(Connection c, String schema) {
        String query = support.indexesQuery();
        if (query == null) return null;
        Map<String, Map<String, RawIndex>> byTable = new HashMap<>();
        try (PreparedStatement ps = prepare(c, query, schema); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString(2);
                if (name == null) continue;
                boolean unique = rs.getBoolean(3);
                RawIndex index = byTable.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                        .computeIfAbsent(name, k -> new RawIndex(name, unique));
                String column = rs.getString(4);
                int position = rs.getInt(5);
                boolean descending = rs.getBoolean(6);
                if (column == null) index.expression = true;
                else index.columns.put(position, new IndexColumn(column, descending));
                if (rs.getBoolean(7)) index.partial = true;
                String method = rs.getString(8);
                if (method != null && !method.isBlank()) index.method = method.trim();
            }
        } catch (SQLException e) {
            log.warn("Indexes of {} are read table by table: the catalog query failed: {}", schema, e.getMessage());
            refused.add("indexes: " + e.getMessage());
            return null;
        }
        Map<String, List<RawIndex>> out = new HashMap<>();
        byTable.forEach((table, indexes) -> out.put(table, new ArrayList<>(indexes.values())));
        return out;
    }

    /** The driver's own list of a table's indexes: what {@link #indexes} gives where a dialect has no query. */
    private static List<RawIndex> indexInfo(DatabaseMetaData md, String schema, String table, List<String> warnings) {
        Map<String, RawIndex> byIndex = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(null, schema, table, false, true)) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                String name = rs.getString("INDEX_NAME");
                if (name == null) continue;
                boolean unique = !rs.getBoolean("NON_UNIQUE");
                RawIndex index = byIndex.computeIfAbsent(name, k -> new RawIndex(name, unique));
                String column = rs.getString("COLUMN_NAME");
                if (column == null) index.expression = true;
                else index.columns.put((int) rs.getShort("ORDINAL_POSITION"),
                        new IndexColumn(column, "D".equalsIgnoreCase(optionalString(rs, "ASC_OR_DESC"))));
                String filter = optionalString(rs, "FILTER_CONDITION");
                if (filter != null && !filter.isBlank()) index.partial = true;
            }
        } catch (SQLException e) {
            warnings.add("Unique constraints and indexes of " + table + " could not be read: " + e.getMessage());
            return List.of();
        }
        return new ArrayList<>(byIndex.values());
    }

    /**
     * Sorts a table's indexes into what they are to the script: the primary key's own (dropped),
     * a unique constraint (a list of columns), or an index — plain, or one the script cannot
     * carry, with the reason. A partial unique index is not a unique constraint: it allows
     * duplicates outside its condition. A key column the table does not have is an expression
     * (PostgreSQL's driver reports the expression's text, Oracle a hidden column).
     */
    private static void sortIndexes(List<RawIndex> raw, List<Column> columns, List<String> pk,
                                    List<List<String>> uniques, List<Index> indexes) {
        Set<String> known = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Column col : columns) known.add(col.name());
        for (RawIndex index : raw) {
            List<IndexColumn> keys = new ArrayList<>(index.columns.values());
            List<String> names = keys.stream().map(IndexColumn::name).toList();
            boolean expression = index.expression || keys.isEmpty() || !known.containsAll(names);
            if (index.unique && !expression && !index.partial && sameColumns(names, pk)) continue;
            String skipped = expression ? "it is over an expression"
                    : index.partial ? "it is partial"
                    : index.method != null ? "it is a " + index.method.toLowerCase(Locale.ROOT) + " index" : null;
            // A unique index of another kind still keeps its columns unique.
            if (index.unique && !expression && !index.partial) {
                if (uniques.stream().noneMatch(u -> sameColumns(u, names))) uniques.add(names);
                continue;
            }
            indexes.add(new Index(index.name, index.unique, expression ? List.of() : keys, skipped));
        }
    }

    /**
     * Comments by table then column, from the dialect's query where the driver's REMARKS are
     * empty; a table's own comment goes to {@code tableComments}.
     */
    private Map<String, Map<String, String>> comments(Connection c, String schema, Map<String, String> tableComments) {
        Map<String, Map<String, String>> out = new HashMap<>();
        String query = support.commentsQuery();
        if (query == null) return out;
        try (PreparedStatement ps = prepare(c, query, schema); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1);
                String column = rs.getString(2);
                String text = rs.getString(3);
                if (table == null || text == null || text.isBlank()) continue;
                if (column == null) tableComments.put(table, text);
                else out.computeIfAbsent(table, k -> new HashMap<>()).put(column, text);
            }
        } catch (SQLException e) {
            log.warn("Comments of {} could not be read: {}", schema, e.getMessage());
            refused.add("comments: " + e.getMessage());
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

    /** {@link #prepare(Connection, String)} with the schema at every {@code ?} of the query. */
    private static PreparedStatement prepare(Connection c, String sql, String schema) throws SQLException {
        PreparedStatement ps = prepare(c, sql);
        try {
            int n = (int) sql.chars().filter(ch -> ch == '?').count();
            for (int i = 1; i <= n; i++) ps.setString(i, schema);
            return ps;
        } catch (SQLException e) {
            ps.close();
            throw e;
        }
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
