package com.menora.initializr.fullstack;

import com.menora.initializr.sql.SqlDialect;
import com.menora.initializr.sql.SqlEntityGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlToEntityDefinitionConverterTest {

    private final SqlToEntityDefinitionConverter converter =
            new SqlToEntityDefinitionConverter(new SqlEntityGenerator());

    @Test
    void convertsSingleTableWithMixedTypes() {
        String sql = """
                CREATE TABLE products (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    sku VARCHAR(64) NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    price NUMERIC(10,2) NOT NULL,
                    in_stock BOOLEAN,
                    created_at TIMESTAMP
                );
                """;
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        assertThat(entities).hasSize(1);

        EntityDefinition e = entities.get(0);
        assertThat(e.name()).isEqualTo("Product");
        assertThat(e.tableName()).isEqualTo("products");
        assertThat(e.fields()).hasSize(6);

        FieldDefinition id = e.fields().get(0);
        assertThat(id.name()).isEqualTo("id");
        assertThat(id.type()).isEqualTo(FieldType.LONG);
        assertThat(id.primaryKey()).isTrue();
        assertThat(id.generated()).isTrue();

        FieldDefinition sku = e.fields().get(1);
        assertThat(sku.name()).isEqualTo("sku");
        assertThat(sku.type()).isEqualTo(FieldType.STRING);
        assertThat(sku.required()).isTrue();
        assertThat(sku.length()).isEqualTo(64);

        FieldDefinition price = e.fields().get(3);
        assertThat(price.type()).isEqualTo(FieldType.BIG_DECIMAL);
        assertThat(price.length()).isNull();

        FieldDefinition inStock = e.fields().get(4);
        assertThat(inStock.name()).isEqualTo("inStock");
        assertThat(inStock.type()).isEqualTo(FieldType.BOOLEAN);
        assertThat(inStock.required()).isFalse();

        FieldDefinition createdAt = e.fields().get(5);
        assertThat(createdAt.name()).isEqualTo("createdAt");
        assertThat(createdAt.type()).isEqualTo(FieldType.LOCAL_DATE_TIME);
    }

    @Test
    void convertsMultipleTablesPreservingOrder() {
        String sql = """
                CREATE TABLE orders (id BIGINT PRIMARY KEY, total DECIMAL(12,2));
                CREATE TABLE order_items (id BIGINT PRIMARY KEY, qty INT);
                """;
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        assertThat(entities).hasSize(2);
        assertThat(entities.get(0).name()).isEqualTo("Order");
        assertThat(entities.get(0).tableName()).isEqualTo("orders");
        assertThat(entities.get(1).name()).isEqualTo("OrderItem");
        assertThat(entities.get(1).tableName()).isEqualTo("order_items");
    }

    @Test
    void pluralYWithIesGetsSingularized() {
        String sql = "CREATE TABLE categories (id BIGINT PRIMARY KEY, name VARCHAR(50));";
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        assertThat(entities.get(0).name()).isEqualTo("Category");
    }

    @Test
    void postgresDialectMapsSerialAndUuid() {
        String sql = """
                CREATE TABLE accounts (
                    id BIGSERIAL PRIMARY KEY,
                    external_ref UUID NOT NULL,
                    nickname VARCHAR(120)
                );
                """;
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.POSTGRESQL);
        assertThat(entities).hasSize(1);
        EntityDefinition e = entities.get(0);
        assertThat(e.fields().get(0).type()).isEqualTo(FieldType.LONG);
        // UUID degrades to STRING — see converter Javadoc
        assertThat(e.fields().get(1).type()).isEqualTo(FieldType.STRING);
        assertThat(e.fields().get(1).required()).isTrue();
    }

    @Test
    void inlineUniqueConstraintIsCarriedThrough() {
        String sql = """
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    email VARCHAR(200) NOT NULL UNIQUE,
                    nickname VARCHAR(50)
                );
                """;
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        EntityDefinition e = entities.get(0);
        assertThat(e.fields().get(1).name()).isEqualTo("email");
        assertThat(e.fields().get(1).unique()).isTrue();
        assertThat(e.fields().get(2).unique()).isFalse();
    }

    @Test
    void nullableColumnBecomesNotRequired() {
        String sql = "CREATE TABLE t (id BIGINT PRIMARY KEY, optional_col VARCHAR(10));";
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        assertThat(entities.get(0).fields().get(1).required()).isFalse();
    }

    @Test
    void emptyOrBlankSqlReturnsEmpty() {
        assertThat(converter.convert(null, SqlDialect.H2)).isEmpty();
        assertThat(converter.convert("   ", SqlDialect.H2)).isEmpty();
    }

    @Test
    void invalidSqlSurfacesAsSqlParseException() {
        String sql = "CREATE TABLE bad ( this is not valid sql );";
        assertThatThrownBy(() -> converter.convert(sql, SqlDialect.H2))
                .isInstanceOf(SqlEntityGenerator.SqlParseException.class);
    }

    @Test
    void mapTypeFallbacks() {
        assertThat(SqlToEntityDefinitionConverter.mapType(
                com.menora.initializr.sql.JavaType.langType("Short"))).isEqualTo(FieldType.INTEGER);
        assertThat(SqlToEntityDefinitionConverter.mapType(
                com.menora.initializr.sql.JavaType.langType("Double"))).isEqualTo(FieldType.BIG_DECIMAL);
        assertThat(SqlToEntityDefinitionConverter.mapType(
                com.menora.initializr.sql.JavaType.langType("byte[]"))).isEqualTo(FieldType.STRING);
    }

    @Test
    void singularizeEdgeCases() {
        assertThat(SqlToEntityDefinitionConverter.singularize("buses")).isEqualTo("bus");
        assertThat(SqlToEntityDefinitionConverter.singularize("addresses")).isEqualTo("address");
        assertThat(SqlToEntityDefinitionConverter.singularize("status")).isEqualTo("status");
        assertThat(SqlToEntityDefinitionConverter.singularize("class")).isEqualTo("class");
    }
}
