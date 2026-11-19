package com.menora.initializr.fullstack.schema;

import com.menora.initializr.fullstack.schema.Schema.Table;
import com.menora.initializr.liveschema.ConnectionSpec;
import com.menora.initializr.liveschema.LiveSchemaException;
import com.menora.initializr.liveschema.LiveSchemaModel;
import com.menora.initializr.liveschema.LiveSchemaReader;
import com.menora.initializr.liveschema.LiveSchemaService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates a fullstack model's tables on a database an admin set up as a schema source and allowed
 * it for: the statements of the model's Flyway baseline ({@link SchemaSql#statements}), written for
 * the source's database and placed in the schema the caller picked.
 *
 * <p>Nothing runs when a table the model makes is already there (a 409 listing them). PostgreSQL
 * and SQL Server run the statements in one transaction, so a failure leaves nothing behind; on
 * Oracle, DB2 and H2 a CREATE commits at once, so a failure drops the tables this run made, newest
 * first, and says which it could not.
 */
@Service
public class SchemaInstaller {

    private final LiveSchemaService live;

    public SchemaInstaller(LiveSchemaService live) {
        this.live = live;
    }

    /**
     * What was (or, for a check, would be) done.
     *
     * @param sql        the statements as one script, as they run
     * @param tables     every table the model makes, as written in the statements
     * @param created    the tables made; empty for a check
     * @param transactional whether a failure rolls back (else the tables made are dropped again)
     */
    public record Result(SchemaDialect dialect, String schema, String sql, List<String> tables, int statements,
                         List<String> created, boolean transactional) {}

    /**
     * @param source the schema source, by name (must allow creating tables)
     * @param schemaName the schema to create the tables in, as the source's schema list spells it
     * @param build  the model's schema; the dialect is the source's
     * @param check  read and plan only: nothing is created
     */
    /** The database of a source tables may be created on (403 or 404 as {@link #install} answers). */
    public SchemaDialect dialectOf(String source) {
        return dialectOf(live.writableSource(source));
    }

    private static SchemaDialect dialectOf(ConnectionSpec spec) {
        SchemaDialect dialect = SchemaDialect.ofDependency(spec.dialect().depId());
        if (dialect == null) {
            throw LiveSchemaException.badRequest("Tables cannot be created on " + LiveSchemaReader.label(spec.dialect()));
        }
        return dialect;
    }

    public Result install(String source, String schemaName, Schema build, boolean check) {
        ConnectionSpec spec = live.writableSource(source);
        SchemaDialect dialect = dialectOf(spec);
        if (schemaName == null || schemaName.isBlank()) throw LiveSchemaException.badRequest("Pick a schema");
        return live.withConnection(spec, check, (reader, c) -> {
            List<String> schemas = reader.schemas(c).schemas();
            String target = pick(schemas, schemaName.trim());
            if (target == null) {
                throw LiveSchemaException.badRequest("Source " + spec.label() + " has no schema " + schemaName.trim());
            }
            Schema placed = SchemaSql.inSchema(build, quoted(target));
            List<String> existing = existingTables(reader, c, placed, schemas);
            if (!existing.isEmpty()) {
                throw new LiveSchemaException(HttpStatus.CONFLICT, "Tables exist",
                        existing.size() + (existing.size() == 1 ? " table" : " tables")
                                + " of this model already exist in " + target + ": " + String.join(", ", existing)
                                + ". Nothing was created.", existing);
            }
            List<String> statements = SchemaSql.statements(placed, dialect, new LinkedHashSet<>(schemas));
            List<String> tables = placed.tables().stream().map(Table::ref).toList();
            boolean transactional = transactional(dialect);
            String sql = String.join(";\n\n", statements) + (statements.isEmpty() ? "" : ";\n");
            if (check) return new Result(dialect, target, sql, tables, statements.size(), List.of(), transactional);
            List<String> created = run(c, spec, dialect, placed, statements);
            return new Result(dialect, target, sql, tables, statements.size(), created, transactional);
        });
    }

    /** DDL that a rollback undoes: PostgreSQL and SQL Server. Oracle, DB2 and H2 commit each CREATE. */
    static boolean transactional(SchemaDialect dialect) {
        return dialect == SchemaDialect.POSTGRESQL || dialect == SchemaDialect.MSSQL;
    }

    /** The model's tables (and views — a name a view holds is taken too) the database already has. */
    private static List<String> existingTables(LiveSchemaReader reader, Connection c, Schema placed, List<String> schemas)
            throws SQLException {
        Map<String, Set<String>> bySchema = new LinkedHashMap<>();
        List<String> out = new ArrayList<>();
        for (Table table : placed.tables()) {
            String schema = pick(schemas, SchemaSql.unquoted(table.schema()));
            if (schema == null) continue;   // a schema the model names that is not there: no table in it either
            Set<String> names = bySchema.get(schema);
            if (names == null) {
                names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (LiveSchemaModel.TableRef ref : reader.tables(c, schema).tables()) names.add(ref.name());
                bySchema.put(schema, names);
            }
            // Ignoring case: a false clash refuses to run, which is safe; a missed one fails the CREATE.
            if (names.contains(SchemaSql.unquoted(table.name()))) out.add(schema + "." + SchemaSql.unquoted(table.name()));
        }
        return out;
    }

    private List<String> run(Connection c, ConnectionSpec spec, SchemaDialect dialect, Schema placed,
                             List<String> statements) throws SQLException {
        Map<String, String> tableOf = new LinkedHashMap<>();
        for (Table table : placed.tables()) {
            tableOf.put(SchemaSql.createTable(table, dialect).strip().replaceAll(";$", "").stripTrailing(), table.ref());
        }
        boolean transactional = transactional(dialect);
        if (transactional) c.setAutoCommit(false);
        List<String> created = new ArrayList<>();
        int index = 0;
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(30);
            for (String statement : statements) {
                index++;
                st.execute(statement);
                String table = tableOf.get(statement);
                if (table != null) created.add(table);
            }
            if (transactional) c.commit();
            return created;
        } catch (SQLException e) {
            String failed = "Statement " + index + " of " + statements.size() + " failed: "
                    + LiveSchemaReader.describe(e, spec);
            if (transactional) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    throw new LiveSchemaException(HttpStatus.BAD_REQUEST, "Could not create the tables",
                            failed + " The rollback failed too (" + LiveSchemaReader.describe(rollback, spec)
                                    + "): check the schema by hand.");
                }
                throw new LiveSchemaException(HttpStatus.BAD_REQUEST, "Could not create the tables",
                        failed + " Nothing was created: the whole run was rolled back.");
            }
            List<String> left = dropAgain(c, dialect, created);
            throw new LiveSchemaException(HttpStatus.BAD_REQUEST, "Could not create the tables",
                    failed + (created.isEmpty() ? " No table had been created."
                            : left.isEmpty() ? " The " + created.size() + " table" + (created.size() == 1 ? "" : "s")
                                    + " made before it " + (created.size() == 1 ? "was" : "were") + " dropped again."
                            : " These tables were made and could not be dropped again — drop them by hand: "
                                    + String.join(", ", left) + "."));
        }
    }

    /** Drops what a failed run made, newest first; returns the ones that would not go. */
    private static List<String> dropAgain(Connection c, SchemaDialect dialect, List<String> created) {
        List<String> left = new ArrayList<>();
        for (int i = created.size() - 1; i >= 0; i--) {
            String table = created.get(i);
            String drop = "drop table " + table + switch (dialect) {
                case ORACLE -> " cascade constraints";
                case H2, POSTGRESQL -> " cascade";
                case MSSQL, DB2 -> "";
            };
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(30);
                st.execute(drop);
            } catch (SQLException e) {
                left.add(table);
            }
        }
        return left;
    }

    /** {@code wanted} as the list spells it (ignoring case), or null. */
    private static String pick(List<String> names, String wanted) {
        if (wanted == null) return null;
        for (String n : names) if (n.equalsIgnoreCase(wanted)) return n;
        return null;
    }

    /**
     * The schema as the database stores it, in double quotes — which all five databases read — so
     * a mixed-case PostgreSQL schema and an upper-case Oracle one are both found as listed.
     */
    static String quoted(String schema) {
        return "\"" + schema.replace("\"", "\"\"") + "\"";
    }
}
