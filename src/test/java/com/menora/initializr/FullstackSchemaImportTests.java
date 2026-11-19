package com.menora.initializr;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The DDL and SELECT imports, and what {@code /starter-fullstack.zip} makes of imported names, schemas and views. */
@IntegrationTest
@Tag("slow") // left out of mvn test -Pquick
class FullstackSchemaImportTests extends FullstackEndpointTestSupport {

    @Test
    void importDdlEndpoint_returnsEntitiesInWireFormat() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dialect", "H2");
        body.put("sql", """
                CREATE TABLE inv.products (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    sku VARCHAR(64) NOT NULL,
                    price NUMERIC(10,2)
                );
                """);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/metadata/fullstack/import-ddl", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) response.getBody().get("entities");
        assertThat(entities).hasSize(1);
        Map<String, Object> product = entities.get(0);
        assertThat(product).containsEntry("name", "Product");
        assertThat(product).containsEntry("tableName", "products");
        assertThat(product).containsEntry("schema", "inv");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) product.get("fields");
        assertThat(fields).hasSize(3);
        assertThat(fields.get(0)).containsEntry("type", "LONG").containsEntry("primaryKey", true);
        assertThat(fields.get(1)).containsEntry("type", "STRING").containsEntry("length", 64);
        assertThat(fields.get(2)).containsEntry("type", "BIG_DECIMAL");
        assertThat(product.get("relations")).isEqualTo(List.of());
    }

    @Test
    void importDdlEndpoint_turnsForeignKeysIntoRelationsAndCheckInIntoEnums() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dialect", "H2");
        body.put("sql", """
                CREATE TABLE customers (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(120) NOT NULL
                );
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    customer_id BIGINT NOT NULL,
                    status VARCHAR(20) NOT NULL CHECK (status IN ('NEW', 'PAID', 'SHIPPED')),
                    total NUMERIC(10,2),
                    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
                );
                """);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/metadata/fullstack/import-ddl", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) response.getBody().get("entities");
        assertThat(entities).hasSize(2);
        Map<String, Object> order = entities.get(1);
        assertThat(order).containsEntry("name", "Order");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) order.get("fields");
        // The FK column is gone from the fields — it lives on as the relation's join column.
        assertThat(fields).extracting(f -> f.get("name")).containsExactly("id", "status", "total");
        assertThat(fields.get(1)).containsEntry("type", "ENUM")
                .containsEntry("enumValues", List.of("NEW", "PAID", "SHIPPED"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relations = (List<Map<String, Object>>) order.get("relations");
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0))
                .containsEntry("type", "MANY_TO_ONE")
                .containsEntry("fieldName", "customer")
                .containsEntry("targetEntity", "Customer")
                .containsEntry("required", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void importedDdl_generatesEntitiesOnTheSchemasOwnColumnNames() throws Exception {
        // A DB2 column with an underscore before a digit used to come out as ..._use8 (derived back
        // from the camelCase field name), and an FK without an _id suffix as <field>_id — both
        // queries then failed. The import now carries the DDL names into every SQL name.
        Map<String, Object> importBody = new LinkedHashMap<>();
        importBody.put("dialect", "DB2");
        importBody.put("sql", """
                CREATE TABLE VNRF.CUSTOMERS (
                    CUST_NO NUMERIC(6, 0) NOT NULL PRIMARY KEY,
                    CUST_SHEM_2 CHAR(20)
                );
                CREATE TABLE VNRF.ORDERS (
                    ORD_NO NUMERIC(6, 0) NOT NULL PRIMARY KEY,
                    FRF_QOD_MISHLOAH_FUTURE_USE_8 CHAR(1),
                    CUST_REF NUMERIC(6, 0) NOT NULL,
                    FOREIGN KEY (CUST_REF) REFERENCES VNRF.CUSTOMERS (CUST_NO)
                );
                """);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> imported = restTemplate.exchange(
                "/metadata/fullstack/import-ddl", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(importBody, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> entities = (List<Map<String, Object>>) imported.getBody().get("entities");
        Map<String, Object> order = entities.get(1);
        assertThat((List<Map<String, Object>>) order.get("fields"))
                .anySatisfy(f -> assertThat(f).containsEntry("column", "FRF_QOD_MISHLOAH_FUTURE_USE_8"));
        assertThat(((List<Map<String, Object>>) order.get("relations")).get(0)).containsEntry("joinColumn", "CUST_REF");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "frf");
        body.put("packageName", "com.menora.frf");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", entities);
        body.put("opts", Map.of("scaffold", List.of("softDelete")));
        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/entity/Order.java"))
                .contains("@Column(name = \"FRF_QOD_MISHLOAH_FUTURE_USE_8\"")
                .contains("private String frfQodMishloahFutureUse8;")
                .contains("@JoinColumn(name = \"CUST_REF\", nullable = false)")
                .contains("@Formula(\"(select t.CUST_SHEM_2 from VNRF.CUSTOMERS t where t.CUST_NO = CUST_REF)\")")
                .contains("UPDATE VNRF.ORDERS SET deleted = true WHERE ORD_NO = ?")
                .doesNotContain("future_use8")
                .doesNotContain("cust_ref_id");
        assertThat(contentEndingWith(entries, "/repository/OrderRepository.java"))
                .contains("UPDATE VNRF.ORDERS SET deleted = false WHERE ORD_NO = :id");
        // All-caps names pass Boot's naming strategy untouched: no extra strategy in the project.
        assertThat(entries.keySet()).noneMatch(k -> k.endsWith("QuotedNamesNamingStrategy.java"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importedDdl_keepsMixedCaseAndQuotedNamesThroughTheNamingStrategy() throws Exception {
        Map<String, Object> importBody = new LinkedHashMap<>();
        importBody.put("dialect", "POSTGRESQL");
        importBody.put("sql", """
                CREATE TABLE app."OrderItems" (
                    id BIGINT PRIMARY KEY,
                    "UnitPrice" NUMERIC(10, 2),
                    orderDate DATE
                );
                """);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> imported = restTemplate.exchange(
                "/metadata/fullstack/import-ddl", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(importBody, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> entities = (List<Map<String, Object>>) imported.getBody().get("entities");
        assertThat(entities.get(0)).containsEntry("tableName", "\"OrderItems\"").containsEntry("schema", "app");
        assertThat((List<Map<String, Object>>) entities.get(0).get("fields"))
                .extracting(f -> f.get("column")).containsExactly("id", "\"UnitPrice\"", "ORDERDATE");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", entities);
        body.put("opts", Map.of("scaffold", List.of("softDelete")));
        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/entity/OrderItem.java"))
                .contains("@Table(name = \"\\\"OrderItems\\\"\", schema = \"app\")")
                .contains("@Column(name = \"\\\"UnitPrice\\\"\"")
                .contains("@Column(name = \"ORDERDATE\"")
                // Native SQL is not touched by the naming strategy, and @Table's schema does not reach it.
                .contains("UPDATE app.\\\"OrderItems\\\" SET deleted = true WHERE id = ?");
        assertThat(contentEndingWith(entries, "/config/QuotedNamesNamingStrategy.java"))
                .contains("package com.menora.shop.config;")
                .contains("@Component");
    }

    @Test
    void fullstackEndpoint_mapsCamelCaseViewAliasesThroughTheNamingStrategy() throws Exception {
        // A SELECT view maps a field by its projected label; a camelCase alias is folded so the
        // strategy does not look for customer_name instead of customerName.
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", "CustomerSummary");
        view.put("readOnly", true);
        view.put("viewQuery", "SELECT id, customer_name AS customerName FROM customers");
        view.put("fields", List.of(
                Map.of("name", "id", "type", "Long", "primaryKey", true),
                Map.of("name", "customerName", "type", "String")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "crm");
        body.put("packageName", "com.menora.crm");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", List.of(view));
        Map<String, String> entries = generateZip(body);
        assertThat(contentEndingWith(entries, "/entity/CustomerSummary.java"))
                .contains("@Column(name = \"CUSTOMERNAME\"")
                .contains("@Column(name = \"id\"");
        assertThat(entries.keySet()).noneMatch(k -> k.endsWith("QuotedNamesNamingStrategy.java"));
    }

    @Test
    void fullstackEndpoint_rejectsBadOrClashingColumnNames() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Column names are spliced into native SQL, so only identifier characters pass.
        ResponseEntity<String> bad = restTemplate.exchange("/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(bodyWithField(Map.of("name", "title", "type", "String", "column", "TITLE; DROP")), headers),
                String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).contains("column 'TITLE; DROP' of 'title' on entity 'Task' is not a valid column name");

        // An explicit column may not reuse another member's (derived) column.
        ResponseEntity<String> clash = restTemplate.exchange("/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(bodyWithField(Map.of("name", "title", "type", "String", "column", "ID")), headers),
                String.class);
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(clash.getBody()).contains("'id' and 'title' on entity 'Task' both map to column 'id'");
    }

    @Test
    void importDdlEndpoint_returns400OnParseError() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", "CREATE TABLE bad ( this is not valid sql );");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/metadata/fullstack/import-ddl", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Invalid SQL");
    }

    @Test
    void importSelectEndpoint_returnsReadOnlyViewEntity() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", "SELECT u.id AS id, u.full_name AS fullName FROM users u");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/metadata/fullstack/import-select", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) response.getBody().get("entities");
        assertThat(entities).hasSize(1);
        Map<String, Object> view = entities.get(0);
        assertThat(view).containsEntry("name", "User");       // singularized FROM table
        assertThat(view).containsEntry("readOnly", true);
        assertThat(view.get("viewQuery").toString()).contains("SELECT").contains("users");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) view.get("fields");
        assertThat(fields).hasSize(2);
        // No types in a SELECT → everything defaults to STRING, first column is the PK.
        assertThat(fields.get(0)).containsEntry("name", "id").containsEntry("type", "STRING").containsEntry("primaryKey", true);
        assertThat(fields.get(1)).containsEntry("name", "fullName").containsEntry("type", "STRING").containsEntry("primaryKey", false);
    }

    @Test
    void importSelectEndpoint_fallsBackForNativeSqlWithNote() {
        Map<String, Object> body = new LinkedHashMap<>();
        // Oracle MODEL clause: valid native SQL JSqlParser can't parse — should still import.
        body.put("sql", "SELECT id AS id, name AS name FROM t MODEL DIMENSION BY (id) MEASURES (name) RULES ()");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/metadata/fullstack/import-select", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) response.getBody().get("entities");
        assertThat(entities).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) entities.get(0).get("fields");
        assertThat(fields).extracting(f -> f.get("name")).containsExactly("id", "name");
        // A heuristic detection carries an advisory note.
        assertThat(response.getBody().get("note").toString()).contains("heuristically");
    }

    @Test
    void importSelectEndpoint_returns400OnSelectStar() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", "SELECT * FROM users");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/metadata/fullstack/import-select", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Invalid SQL");
        assertThat(response.getBody().get("detail").toString()).contains("*");
    }

    @Test
    void fullstackEndpoint_generatesReadOnlyViewScaffolding() throws Exception {
        // A SELECT-backed read-only view alongside a normal CRUD entity in one request.
        Map<String, Object> viewId = Map.of("name", "id", "type", "Long", "primaryKey", true);
        Map<String, Object> viewName = Map.of("name", "name", "type", "String");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", "UserSummary");
        view.put("readOnly", true);
        view.put("viewQuery", "select id, name from users");
        view.put("fields", List.of(viewId, viewName));

        Map<String, Object> order = Map.of(
                "name", "Order",
                "fields", List.of(pkField(), Map.of("name", "total", "type", "BigDecimal")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(view, order));

        Map<String, String> entries = generateZip(body);

        // View entity maps to a @Subselect view, not a @Table.
        String entity = contentEndingWith(entries, "/UserSummary.java");
        assertThat(entity).contains("@Immutable").contains("@Subselect(").contains("select id, name from users");
        assertThat(entity).doesNotContain("@Table(");

        // View service/controller are GET-only.
        String service = contentEndingWith(entries, "/UserSummaryService.java");
        assertThat(service).contains("findAll").contains("findById");
        assertThat(service).doesNotContain("repository.save").doesNotContain("deleteById");

        String controller = contentEndingWith(entries, "/UserSummaryController.java");
        assertThat(controller).contains("@GetMapping");
        assertThat(controller).doesNotContain("@PostMapping").doesNotContain("@PutMapping").doesNotContain("@DeleteMapping");

        // The normal entity still gets full CRUD.
        String orderController = contentEndingWith(entries, "/OrderController.java");
        assertThat(orderController).contains("@PostMapping").contains("@DeleteMapping");

        // Read-only view frontend page has no New/Edit/Delete surface.
        String viewPage = contentEndingWith(entries, "/UserSummaryPage.tsx");
        assertThat(viewPage).doesNotContain("t('newX'").doesNotContain("onDelete=");
    }

    @Test
    void fullstackEndpoint_rejectsGeneratedPkOnView() {
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "demo");
            b.put("bootVersion", "3.2.1");
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", "UserSummary");
            view.put("viewQuery", "select id from users");
            view.put("fields", List.of(pkField()));   // pkField() is generated=true
            b.put("entities", List.of(view));
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("view").contains("generated");
    }

    @Test
    void fullstackEndpoint_rendersTableSchema() throws Exception {
        // A schema-qualified entity (e.g. imported from `CREATE TABLE entv.test`) must render
        // @Table(name = "test", schema = "entv"), matching the standalone SQL wizard.
        Map<String, Object> id = Map.of("name", "id", "type", "Long", "primaryKey", true, "generated", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "schemaapp");
        body.put("packageName", "com.menora.schemaapp");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of(
                "name", "Test", "tableName", "test", "schema", "entv", "fields", List.of(id))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/entity/Test.java"))
                .contains("@Table(name = \"test\", schema = \"entv\")");
    }

    @Test
    void fullstackEndpoint_schemaQualifiedEntityBootsOnH2() throws Exception {
        // A schema-qualified entity (e.g. imported from `CREATE TABLE entv.td_app_stp`) emits
        // @Table(schema="ENTV"), which crashes the bundled H2 ("Schema ENTV not found") unless
        // Hibernate is told to create namespaces first. The H2 dev config must carry the flag.
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("name", "TdAppStp");
        entity.put("tableName", "TD_APP_STP");
        entity.put("schema", "ENTV");
        entity.put("fields", List.of(pkField()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "h2app");
        body.put("packageName", "com.menora.h2app");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "h2"));
        body.put("entities", List.of(entity));

        Map<String, String> entries = generateZip(body);

        // The entity keeps its schema (real-DB fidelity)…
        assertThat(contentEndingWith(entries, "/entity/TdAppStp.java"))
                .contains("schema = \"ENTV\"");
        // …and the H2 dev config auto-creates it so the app boots.
        assertThat(entries.get("h2app/backend/src/main/resources/application.yaml"))
                .contains("create_namespaces: true");
    }
}
