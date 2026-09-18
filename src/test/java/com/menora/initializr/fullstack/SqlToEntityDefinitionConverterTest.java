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
    void schemaQualifiedTablePreservesSchema() {
        // `CREATE TABLE entv.test` must keep the schema so the generated @Table carries schema="entv",
        // matching the standalone SQL wizard. A non-qualified table leaves schema null.
        String sql = "CREATE TABLE entv.test (id BIGINT PRIMARY KEY, name VARCHAR(50));";
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        assertThat(entities).hasSize(1);
        EntityDefinition e = entities.get(0);
        assertThat(e.tableName()).isEqualTo("test");
        assertThat(e.schema()).isEqualTo("entv");
    }

    @Test
    void unqualifiedTableHasNullSchema() {
        String sql = "CREATE TABLE widgets (id BIGINT PRIMARY KEY);";
        EntityDefinition e = converter.convert(sql, SqlDialect.H2).get(0);
        assertThat(e.schema()).isNull();
    }

    @Test
    void importsSqlServerBracketQuotedDdl() {
        // SSMS "Script Table As CREATE" output — the fullstack Import-from-DDL regression.
        String sql = """
                CREATE TABLE [dbo].[clearing_request_status_history](
                    [id] [nvarchar](36) NOT NULL,
                    [status_code] [int] NULL,
                    [created_date] [datetime2](7) NOT NULL,
                PRIMARY KEY CLUSTERED ([id] ASC) WITH (PAD_INDEX = OFF) ON [PRIMARY]
                ) ON [PRIMARY]
                """;
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.MSSQL);
        assertThat(entities).hasSize(1);
        EntityDefinition e = entities.get(0);
        assertThat(e.name()).isEqualTo("ClearingRequestStatusHistory");
        assertThat(e.tableName()).isEqualTo("clearing_request_status_history");
        assertThat(e.schema()).isEqualTo("dbo");

        FieldDefinition id = e.fields().get(0);
        assertThat(id.name()).isEqualTo("id");
        assertThat(id.primaryKey()).isTrue();
        assertThat(id.required()).isTrue();
        assertThat(e.fields().get(1).name()).isEqualTo("statusCode");
        assertThat(e.fields().get(1).type()).isEqualTo(FieldType.INTEGER);
        assertThat(e.fields().get(2).type()).isEqualTo(FieldType.LOCAL_DATE_TIME);
    }

    @Test
    void importsDb2ForIDdlWithAtSystemNamesUnderDefaultH2Dialect() {
        // IBM i export pasted into the fullstack Import-from-DDL drawer with the
        // dropdown left on its H2 default: '@'/'#' system names + CCSID must be
        // auto-detected and stripped, and the composite PK must survive.
        String sql = """
                CREATE TABLE VNRF.VNPSXN (
                --  SQL150B   10   REUSEDLT(*NO) in table VNPSXN in VNRF ignored.
                    FRF_ZIHUY_HEVRAT_BITUAH FOR COLUMN @ZIHUY_HVR NUMERIC(1, 0) NOT NULL DEFAULT 0 ,
                --  SQL150D   10   EDTCDE in column FRF_ZIHUY_HEVRAT_BITUAH ignored.
                    FRF_MISPAR_SOXEN FOR COLUMN @SOXEN#    NUMERIC(6, 0) NOT NULL DEFAULT 0 ,
                    FRF_SHEM_SOXEN FOR COLUMN @SXN_SHEM  CHAR(22) CCSID 424 NOT NULL DEFAULT '' ,
                    FRF_KAMUT_PLS_HAYIM_SOXEN FOR COLUMN @#PLS_SXNH DECIMAL(5, 0) NOT NULL DEFAULT 0 ,
                    PRIMARY KEY( FRF_ZIHUY_HEVRAT_BITUAH , FRF_MISPAR_SOXEN ) )
                """;
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        assertThat(entities).hasSize(1);
        EntityDefinition e = entities.get(0);
        assertThat(e.name()).isEqualTo("Vnpsxn");
        assertThat(e.tableName()).isEqualTo("VNPSXN");
        assertThat(e.schema()).isEqualTo("VNRF");
        assertThat(e.fields()).extracting(FieldDefinition::name)
                .containsExactly("frfZihuyHevratBituah", "frfMisparSoxen", "frfShemSoxen", "frfKamutPlsHayimSoxen");
        assertThat(e.fields().get(0).primaryKey()).isTrue();
        assertThat(e.fields().get(1).primaryKey()).isTrue();
        assertThat(e.fields().get(2).primaryKey()).isFalse();
        assertThat(e.fields().get(2).type()).isEqualTo(FieldType.STRING);
        assertThat(e.fields().get(2).length()).isEqualTo(22);
        assertThat(e.fields().get(2).required()).isTrue();
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
    void convertCapturesSourceDdlPerEntity() {
        String sql = """
                CREATE TABLE orders (id BIGINT PRIMARY KEY, total DECIMAL(12,2));
                CREATE TABLE order_items (id BIGINT PRIMARY KEY, qty INT);
                """;
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        assertThat(entities).hasSize(2);

        // Each entity remembers its own originating CREATE TABLE statement.
        assertThat(entities.get(0).sourceSql())
                .startsWith("CREATE TABLE orders")
                .contains("total DECIMAL(12,2)")
                .doesNotContain("order_items");
        assertThat(entities.get(1).sourceSql())
                .startsWith("CREATE TABLE order_items")
                .contains("qty INT")
                .doesNotContain("orders (");
    }

    @Test
    void convertSelectKeepsViewQueryAndLeavesSourceSqlNull() {
        // A SELECT-backed view carries its source in viewQuery, not sourceSql.
        String sql = "SELECT id AS id, name AS name FROM customers";
        SqlToEntityDefinitionConverter.SelectImportResult result =
                converter.convertSelect(sql, SqlDialect.H2);
        assertThat(result.entities()).hasSize(1);
        EntityDefinition view = result.entities().get(0);
        assertThat(view.viewQuery()).isEqualTo(sql);
        assertThat(view.sourceSql()).isNull();
        assertThat(view.readOnly()).isTrue();
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

    @Test
    void singleColumnForeignKeyBecomesManyToOneAndDropsTheColumn() {
        String sql = """
                CREATE TABLE customers (id BIGINT PRIMARY KEY, name VARCHAR(50));
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    customer_id BIGINT NOT NULL,
                    approver_id BIGINT,
                    total NUMERIC(10,2),
                    FOREIGN KEY (customer_id) REFERENCES customers (id),
                    FOREIGN KEY (approver_id) REFERENCES users (id)
                );
                """;
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        EntityDefinition order = entities.get(1);
        assertThat(order.fields()).extracting(FieldDefinition::name).containsExactly("id", "approverId", "total");
        assertThat(order.relations()).hasSize(1);
        RelationDefinition rel = order.relations().get(0);
        assertThat(rel.type()).isEqualTo(RelationType.MANY_TO_ONE);
        assertThat(rel.fieldName()).isEqualTo("customer");
        assertThat(rel.targetEntity()).isEqualTo("Customer");
        assertThat(rel.required()).isTrue();
        // FK to a table outside the paste (users) stays a plain, optional field.
        assertThat(order.fields().get(1).required()).isFalse();
        assertThat(entities.get(0).relations()).isEmpty();
    }

    @Test
    void foreignKeysToCompositeKeyTablesAndCompositeForeignKeysStayFields() {
        String sql = """
                CREATE TABLE parts (make VARCHAR(10), code VARCHAR(10), PRIMARY KEY (make, code));
                CREATE TABLE usages (
                    id BIGINT PRIMARY KEY,
                    part_make VARCHAR(10),
                    part_code VARCHAR(10),
                    FOREIGN KEY (part_make, part_code) REFERENCES parts (make, code)
                );
                CREATE TABLE notes (
                    id BIGINT PRIMARY KEY,
                    part_id VARCHAR(10),
                    FOREIGN KEY (part_id) REFERENCES parts (make)
                );
                """;
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        assertThat(entities.get(1).relations()).isEmpty();
        assertThat(entities.get(1).fields()).hasSize(3);
        assertThat(entities.get(2).relations()).isEmpty();
        assertThat(entities.get(2).fields()).extracting(FieldDefinition::name).containsExactly("id", "partId");
    }

    @Test
    void foreignKeyOnAKeyColumnStaysAField() {
        String sql = """
                CREATE TABLE users (id BIGINT PRIMARY KEY);
                CREATE TABLE profiles (
                    user_id BIGINT PRIMARY KEY,
                    bio VARCHAR(200),
                    FOREIGN KEY (user_id) REFERENCES users (id)
                );
                """;
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        assertThat(entities.get(1).relations()).isEmpty();
        assertThat(entities.get(1).fields().get(0).name()).isEqualTo("userId");
        assertThat(entities.get(1).fields().get(0).primaryKey()).isTrue();
    }

    @Test
    void relationNameFallsBackToTargetWhenTheColumnNameIsTakenOrUnusable() {
        java.util.Set<String> taken = new java.util.HashSet<>(List.of("customer", "id"));
        assertThat(SqlToEntityDefinitionConverter.relationFieldName("customer_id", "Customer", taken)).isNull();
        assertThat(SqlToEntityDefinitionConverter.relationFieldName("owner_id", "Customer", taken)).isEqualTo("owner");
        assertThat(SqlToEntityDefinitionConverter.relationFieldName("ownerId", "Customer", taken)).isEqualTo("owner");
        assertThat(SqlToEntityDefinitionConverter.relationFieldName("id", "Customer", java.util.Set.of())).isEqualTo("customer");
        assertThat(SqlToEntityDefinitionConverter.relationFieldName("class_id", "Course", java.util.Set.of())).isEqualTo("course");
        assertThat(SqlToEntityDefinitionConverter.relationFieldName("paid", "Invoice", java.util.Set.of())).isEqualTo("paid");
    }

    @Test
    void checkInConstraintsBecomeEnums() {
        String sql = """
                CREATE TABLE tickets (
                    id BIGINT PRIMARY KEY,
                    status VARCHAR(20) NOT NULL CHECK (status IN ('open', 'in progress', 'closed')),
                    priority VARCHAR(10),
                    kind VARCHAR(10) CHECK (kind IN ('a', 'A')),
                    CONSTRAINT chk_priority CHECK (priority IN ('LOW', 'HIGH'))
                );
                """;
        List<EntityDefinition> entities = converter.convert(sql, SqlDialect.H2);
        EntityDefinition ticket = entities.get(0);
        FieldDefinition status = ticket.fields().get(1);
        assertThat(status.type()).isEqualTo(FieldType.ENUM);
        assertThat(status.enumValues()).containsExactly("OPEN", "IN_PROGRESS", "CLOSED");
        assertThat(status.length()).isNull();
        assertThat(status.required()).isTrue();
        FieldDefinition priority = ticket.fields().get(2);
        assertThat(priority.type()).isEqualTo(FieldType.ENUM);
        assertThat(priority.enumValues()).containsExactly("LOW", "HIGH");
        // 'a' and 'A' collapse to the same constant — the column is left as a string.
        FieldDefinition kind = ticket.fields().get(3);
        assertThat(kind.type()).isEqualTo(FieldType.STRING);
        assertThat(kind.enumValues()).isEmpty();
    }

    @Test
    void enumConstantSanitizing() {
        assertThat(SqlToEntityDefinitionConverter.toEnumConstant("in progress")).isEqualTo("IN_PROGRESS");
        assertThat(SqlToEntityDefinitionConverter.toEnumConstant("2fa")).isEqualTo("_2FA");
        assertThat(SqlToEntityDefinitionConverter.toEnumConstant("new")).isEqualTo("NEW");
        assertThat(SqlToEntityDefinitionConverter.toEnumConstant("---")).isNull();
        assertThat(SqlToEntityDefinitionConverter.toEnumConstant("")).isNull();
    }
}
