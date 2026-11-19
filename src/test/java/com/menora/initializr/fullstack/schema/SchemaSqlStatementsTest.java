package com.menora.initializr.fullstack.schema;

import com.menora.initializr.fullstack.schema.Schema.Column;
import com.menora.initializr.fullstack.schema.Schema.ColumnType;
import com.menora.initializr.fullstack.schema.Schema.ForeignKey;
import com.menora.initializr.fullstack.schema.Schema.Index;
import com.menora.initializr.fullstack.schema.Schema.Table;
import com.menora.initializr.fullstack.schema.Schema.Unique;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The baseline as statements a driver runs one by one ({@link SchemaSql#statements}), and the
 * schema moved into the one a person picked ({@link SchemaSql#inSchema}) — what
 * {@code /starter-fullstack.create-tables} runs.
 */
class SchemaSqlStatementsTest {

    private static Column id() {
        return new Column("id", ColumnType.BIGINT, null, false, true, false);
    }

    /** Customers, and orders that point at them (a foreign key, its index and a nullable unique). */
    private static Schema shop(String ordersSchema) {
        Table customers = new Table(null, "customers", List.of(id(), Column.varchar("name", 100, false)),
                List.of("id"), List.of(), List.of(), List.of());
        Table orders = new Table(ordersSchema, "orders",
                List.of(id(), Column.of("customer_id", ColumnType.BIGINT, true), Column.varchar("ref", 20, true)),
                List.of("id"),
                List.of(new ForeignKey("fk_orders_customer_id", List.of("customer_id"), null, "customers", List.of("id"))),
                List.of(new Unique("uk_orders_ref", List.of("ref"))),
                List.of(new Index("ix_orders_customer_id", List.of("customer_id"))));
        return new Schema(List.of(customers, orders));
    }

    @Test
    void theStatementsAreTheBaselinesWithoutCommentsOrSemicolons() {
        for (SchemaDialect dialect : SchemaDialect.values()) {
            Schema schema = shop(null);
            String baseline = SchemaSql.baseline(schema, dialect);
            List<String> statements = SchemaSql.statements(schema, dialect, Set.of());
            assertThat(statements).as(dialect.name()).hasSizeGreaterThanOrEqualTo(4);
            for (String statement : statements) {
                assertThat(statement).as(dialect.name()).doesNotEndWith(";").doesNotStartWith("--");
                // Every statement is in the baseline as written, with its semicolon.
                assertThat(baseline).as(dialect.name()).contains(statement + ";");
            }
        }
    }

    @Test
    void inSchemaPutsTheUnqualifiedTablesAndTheirKeysThere() {
        Schema placed = SchemaSql.inSchema(shop(null), "\"HR\"");
        assertThat(placed.tables()).extracting(Table::ref).containsExactly("\"HR\".customers", "\"HR\".orders");
        assertThat(placed.tables().get(1).foreignKeys().get(0).refSchema()).isEqualTo("\"HR\"");

        List<String> pg = SchemaSql.statements(placed, SchemaDialect.POSTGRESQL, Set.of("HR"));
        assertThat(pg).noneMatch(s -> s.startsWith("create schema"));
        assertThat(pg).contains("alter table \"HR\".orders add constraint fk_orders_customer_id foreign key (customer_id)"
                + " references \"HR\".customers (id)");
        // PostgreSQL makes an index in its table's schema; its name may not be qualified.
        assertThat(pg).contains("create index ix_orders_customer_id on \"HR\".orders (customer_id)");
    }

    @Test
    void oracleAndDb2QualifyAnIndexWithItsTablesSchema() {
        Schema placed = SchemaSql.inSchema(shop(null), "\"HR\"");
        assertThat(SchemaSql.statements(placed, SchemaDialect.ORACLE, Set.of("HR")))
                .contains("create index \"HR\".ix_orders_customer_id on \"HR\".orders (customer_id)")
                // A unique constraint stays inside its table, under its own name.
                .anyMatch(s -> s.startsWith("create table \"HR\".orders") && s.contains("constraint uk_orders_ref unique (ref)"));
        // DB2 writes a unique over a nullable column as an index, which is qualified too.
        assertThat(SchemaSql.statements(placed, SchemaDialect.DB2, Set.of("HR")))
                .contains("create unique index \"HR\".uk_orders_ref on \"HR\".orders (ref) exclude null keys")
                .contains("create index \"HR\".ix_orders_customer_id on \"HR\".orders (customer_id)");
    }

    @Test
    void aSchemaTheModelNamesIsCreatedUnlessItIsThere() {
        Schema schema = SchemaSql.inSchema(shop("sales"), "\"public\"");
        // The model's own schema keeps its name; the rest go to the one picked.
        assertThat(schema.tables()).extracting(Table::ref).containsExactly("\"public\".customers", "sales.orders");
        assertThat(SchemaSql.statements(schema, SchemaDialect.POSTGRESQL, Set.of("public")))
                .startsWith("create schema if not exists sales");
        assertThat(SchemaSql.statements(schema, SchemaDialect.POSTGRESQL, Set.of("public", "SALES")))
                .noneMatch(s -> s.startsWith("create schema"));
        // Oracle cannot: the schema has to exist, and no statement pretends otherwise.
        assertThat(SchemaSql.statements(schema, SchemaDialect.ORACLE, Set.of()))
                .noneMatch(s -> s.startsWith("create schema"));
    }
}
