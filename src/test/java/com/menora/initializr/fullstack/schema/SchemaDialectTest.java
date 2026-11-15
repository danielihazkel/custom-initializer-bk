package com.menora.initializr.fullstack.schema;

import com.menora.initializr.fullstack.schema.Schema.Column;
import com.menora.initializr.fullstack.schema.Schema.ColumnType;
import dialectfixture.DialectFixture;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds {@link SchemaDialect}'s type table to what Hibernate itself writes.
 *
 * <p>Only H2 can be run on a developer's machine, so a migration for PostgreSQL, Oracle, SQL
 * Server or DB2 cannot be tried against its database here. What can be done without one is ask
 * Hibernate's dialect for that database to export a schema: {@link DialectFixture} maps one column of
 * every shape the entity templates emit, with the templates' own annotations, and each column's
 * exported type is compared with the table's. A migration written from the table then creates the
 * columns Hibernate would have created for the generated entities.
 */
class SchemaDialectTest {

    /** The fixture's columns as the schema model describes them. */
    private static final Map<String, Column> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("id", new Column("id", ColumnType.BIGINT, null, false, true, false));
        COLUMNS.put("c_varchar", Column.varchar("c_varchar", 80, false));
        COLUMNS.put("c_string", Column.varchar("c_string", 255, true));
        COLUMNS.put("c_text", Column.of("c_text", ColumnType.TEXT, true));
        COLUMNS.put("c_bigint", Column.of("c_bigint", ColumnType.BIGINT, true));
        COLUMNS.put("c_integer", Column.of("c_integer", ColumnType.INTEGER, true));
        COLUMNS.put("c_boolean", Column.of("c_boolean", ColumnType.BOOLEAN, true));
        COLUMNS.put("c_date", Column.of("c_date", ColumnType.DATE, true));
        COLUMNS.put("c_timestamp", Column.of("c_timestamp", ColumnType.TIMESTAMP, true));
        COLUMNS.put("c_time", Column.of("c_time", ColumnType.TIME, true));
        COLUMNS.put("c_instant", Column.of("c_instant", ColumnType.INSTANT, true));
        COLUMNS.put("c_decimal", Column.of("c_decimal", ColumnType.DECIMAL, true));
        COLUMNS.put("c_money", Column.of("c_money", ColumnType.MONEY, true));
        COLUMNS.put("c_uuid", Column.of("c_uuid", ColumnType.UUID, true));
        COLUMNS.put("c_enum", Column.varchar("c_enum", 255, true));
        COLUMNS.put("c_blob", Column.of("c_blob", ColumnType.BLOB, false));
        COLUMNS.put("deleted", Column.of("deleted", ColumnType.BOOLEAN, false));
        COLUMNS.put("version", Column.of("version", ColumnType.BIGINT, true));
    }

    /** The dialect class and the database version the comparison is made for. */
    private record Target(String dialectClass, String product, int major, int minor) {
    }

    private static final Map<SchemaDialect, Target> TARGETS = Map.of(
            SchemaDialect.H2, new Target("org.hibernate.dialect.H2Dialect", "H2", 2, 2),
            SchemaDialect.POSTGRESQL, new Target("org.hibernate.dialect.PostgreSQLDialect", "PostgreSQL", 14, 0),
            SchemaDialect.ORACLE, new Target("org.hibernate.dialect.OracleDialect", "Oracle", 19, 0),
            SchemaDialect.MSSQL, new Target("org.hibernate.dialect.SQLServerDialect", "Microsoft SQL Server", 15, 0),
            SchemaDialect.DB2, new Target("org.hibernate.dialect.DB2Dialect", "DB2/LINUXX8664", 11, 5));

    /**
     * Where Hibernate's identity column is not the table's type followed by the table's clause.
     * PostgreSQL: this Hibernate writes the pre-10 shorthand; the migration writes the standard
     * identity column, which {@code GenerationType.IDENTITY} inserts into the same way.
     */
    private static final Map<SchemaDialect, String> IDENTITY_SPELLED_OTHERWISE = Map.of(
            SchemaDialect.POSTGRESQL, "bigserial not null");

    @TempDir
    Path temp;

    @Test
    void everyColumnTypeIsTheOneHibernateWrites() throws IOException {
        List<String> mismatches = new ArrayList<>();
        for (SchemaDialect dialect : SchemaDialect.values()) {
            Map<String, String> exported = exportedColumns(dialect);
            assertThat(exported.keySet()).as(dialect + " columns").containsExactlyElementsOf(COLUMNS.keySet());
            for (Map.Entry<String, Column> entry : COLUMNS.entrySet()) {
                if (entry.getKey().equals("id") && IDENTITY_SPELLED_OTHERWISE.containsKey(dialect)) continue;
                String hibernate = typeOnly(exported.get(entry.getKey()), dialect);
                // H2's TEXT is its CLOB under another name, and TEXT is what the entity template
                // itself declares (columnDefinition = "TEXT") — so that is what the migration says.
                if (dialect == SchemaDialect.H2 && entry.getKey().equals("c_text") && hibernate.equals("clob")) {
                    hibernate = "text";
                }
                String ours = dialect.typeOf(entry.getValue());
                if (!hibernate.equals(ours)) {
                    mismatches.add(dialect + " " + entry.getKey() + ": Hibernate writes '" + hibernate
                            + "', the table says '" + ours + "'");
                }
            }
            // The identity clause, as Hibernate writes it after the type.
            String identity = exported.get("id").toLowerCase(Locale.ROOT);
            if (!identity.contains(dialect.identityClause()) && !IDENTITY_SPELLED_OTHERWISE.containsKey(dialect)) {
                mismatches.add(dialect + " id: Hibernate writes '" + identity + "', the table's identity clause is '"
                        + dialect.identityClause() + "'");
            }
            if (IDENTITY_SPELLED_OTHERWISE.containsKey(dialect)) {
                assertThat(identity).as(dialect + " identity").isEqualTo(IDENTITY_SPELLED_OTHERWISE.get(dialect));
            }
        }
        assertThat(mismatches).isEmpty();
    }

    /** Column name → everything Hibernate wrote after it, for the fixture's table on one dialect. */
    private Map<String, String> exportedColumns(SchemaDialect dialect) throws IOException {
        Target target = TARGETS.get(dialect);
        Path script = temp.resolve(dialect.name().toLowerCase(Locale.ROOT) + ".sql");
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", target.dialectClass())
                // No database: the dialect is told its version instead of asking one.
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", false)
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("jakarta.persistence.database-product-name", target.product())
                .applySetting("jakarta.persistence.database-major-version", String.valueOf(target.major()))
                .applySetting("jakarta.persistence.database-minor-version", String.valueOf(target.minor()))
                // The JPA way of having the schema written to a file instead of a database.
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target", script.toString())
                .applySetting("hibernate.hbm2ddl.delimiter", ";")
                .build();
        try {
            Metadata metadata = new MetadataSources(registry).addAnnotatedClass(DialectFixture.class).buildMetadata();
            SchemaManagementToolCoordinator.process(metadata, registry,
                    registry.getService(ConfigurationService.class).getSettings(), action -> { });
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
        String ddl = Files.readString(script);
        String lower = ddl.toLowerCase(Locale.ROOT);
        int start = lower.indexOf("create table fixture (");
        assertThat(start).as(dialect + " DDL: " + ddl).isNotNegative();
        String body = ddl.substring(start + "create table fixture (".length(), lower.indexOf(";", start));
        body = body.substring(0, body.lastIndexOf(')'));
        Map<String, String> columns = new LinkedHashMap<>();
        // Hibernate writes columns alphabetically; the model's order is kept for the comparison.
        Map<String, String> found = new LinkedHashMap<>();
        for (String part : splitTopLevel(body)) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("primary key")) continue;
            int space = trimmed.indexOf(' ');
            found.put(trimmed.substring(0, space).toLowerCase(Locale.ROOT), trimmed.substring(space + 1).trim());
        }
        for (String name : COLUMNS.keySet()) {
            if (found.containsKey(name)) columns.put(name, found.get(name));
        }
        return columns;
    }

    /** Splits on the commas that are not inside parentheses. */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(text.substring(from, i));
                from = i + 1;
            }
        }
        parts.add(text.substring(from));
        return parts;
    }

    /** The type alone: without NOT NULL, the identity clause and an enum's or boolean's CHECK. */
    private static String typeOnly(String definition, SchemaDialect dialect) {
        String type = definition.toLowerCase(Locale.ROOT);
        int check = type.indexOf(" check ");
        if (check >= 0) type = type.substring(0, check);
        type = type.replace(" not null", "").replace(" " + dialect.identityClause(), "");
        return type.trim();
    }
}
