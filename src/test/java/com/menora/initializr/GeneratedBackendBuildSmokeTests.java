package com.menora.initializr;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke tests that build a generated fullstack backend with Maven — compile, then run its
 * own tests (the controller slice tests and the application context with the demo data).
 *
 * <p>The other generation tests only assert the generated Java as text, so a template that emits
 * code which does not compile (a missing import, a type that does not match) passes them. These
 * catch it, at the price of a Maven build per test.
 *
 * <p>Gated by {@code -Dsmoke.be=true}:
 * <pre>{@code mvn test -Dtest=GeneratedBackendBuildSmokeTests -Dsmoke.be=true}</pre>
 * Needs {@code mvn} on the PATH and the generated project's dependencies resolvable. The
 * requests name their dependencies and leave out ldap-auth (its artifact lives in the company
 * repository), so the workflow's role checks are left out too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "smoke.be", matches = "true")
class GeneratedBackendBuildSmokeTests {

    private static final Logger log = LoggerFactory.getLogger(GeneratedBackendBuildSmokeTests.class);

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String MVN = IS_WINDOWS ? "mvn.cmd" : "mvn";
    private static final File NULL_DEVICE = new File(IS_WINDOWS ? "NUL" : "/dev/null");

    @Autowired
    private TestRestTemplate rest;

    @ParameterizedTest(name = "Spring Boot {0}")
    @ValueSource(strings = {"3.2.1", FullstackGoldenOutputTests.BOOT_35})
    @SuppressWarnings("unchecked")
    void approvalsBackendCompilesAndPassesItsTests(String bootVersion, @TempDir Path workDir) throws Exception {
        // The Approvals example with every opt its backend reads: the workflow's transitions,
        // claim/release and inbox filters, stats, locking, audit, demo data, CSV export and the
        // generated controller tests.
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("approvals", "react-tailwind-crud");
        body.put("bootVersion", bootVersion);
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("opts", Map.of("scaffold", List.of("audit", "optimisticLocking", "csvExport", "seedData", "tests")));
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            Map<String, Object> workflow = (Map<String, Object>) entity.get("workflow");
            if (workflow == null) continue;
            for (Map<String, Object> t : (List<Map<String, Object>>) workflow.get("transitions")) t.remove("roles");
        }
        runMvn(postAndExtractBackend(workDir, body), "-B", "package");
    }

    @ParameterizedTest(name = "Spring Boot {0}")
    @ValueSource(strings = {"3.2.1", FullstackGoldenOutputTests.BOOT_35})
    void classicBackendWithEveryOptCompilesAndPassesItsTests(String bootVersion, @TempDir Path workDir) throws Exception {
        // The Orders entities (relations, enums, booleans, dates and money, no workflow) with the
        // bulk, import, soft-delete and inverse-collection code paths on.
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        body.put("bootVersion", bootVersion);
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        // openapi too: springdoc's version follows the Boot line, and the wrong one fails at startup.
        body.put("opts", Map.of("scaffold", List.of("audit", "softDelete", "inverseCollections", "csvExport",
                "csvImport", "bulkDelete", "bulkUpdate", "optimisticLocking", "seedData", "tests", "openapi")));
        runMvn(postAndExtractBackend(workDir, body), "-B", "package");
    }

    @ParameterizedTest(name = "Spring Boot {0}")
    @ValueSource(strings = {"3.2.1", FullstackGoldenOutputTests.BOOT_35})
    void roundNineFieldTypesCompileAndPassTheirTests(String bootVersion, @TempDir Path workDir) throws Exception {
        // A time of day, an instant, money and Markdown through the entity, DTO, service (bulk
        // edit, CSV import), demo data and the controller test — and the context load, which
        // creates the schema, so the money column's precision and scale are really mapped.
        Map<String, Object> body = FullstackExampleRequests.fieldTypesBody("react-tailwind-crud");
        body.put("bootVersion", bootVersion);
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        runMvn(postAndExtractBackend(workDir, body), "-B", "package");
    }

    @ParameterizedTest(name = "Spring Boot {0}")
    @ValueSource(strings = {"3.2.1", FullstackGoldenOutputTests.BOOT_35})
    void manyToManyBackendCompilesAndPassesItsTests(String bootVersion, @TempDir Path workDir) throws Exception {
        // A MANY_TO_MANY through the entity (the join table Hibernate creates at context load), the
        // DTO, the service's link resolution and subquery filter, and the CSV export.
        Map<String, Object> body = FullstackExampleRequests.manyToManyBody("react-tailwind-crud");
        body.put("bootVersion", bootVersion);
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        runMvn(postAndExtractBackend(workDir, body), "-B", "package");
    }

    @ParameterizedTest(name = "Spring Boot {0}")
    @ValueSource(strings = {"3.2.1", FullstackGoldenOutputTests.BOOT_35})
    void oneToManyBackendCompilesAndPassesItsTests(String bootVersion, @TempDir Path workDir) throws Exception {
        // Declared ONE_TO_MANYs: the collection and its counting @Formula on each parent (validated
        // against the schema at context load), two over one child, and one of an entity's own rows.
        Map<String, Object> body = FullstackExampleRequests.oneToManyBody("react-tailwind-crud");
        body.put("bootVersion", bootVersion);
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        runMvn(postAndExtractBackend(workDir, body), "-B", "package");
    }

    @org.junit.jupiter.api.Test
    void lombokBackendCompilesAndPassesItsTests(@TempDir Path workDir) throws Exception {
        // The Lombok backend set authors its own entity (and key class); no build compiled it.
        // Relations of both kinds, audit and demo data, so the @Data entities are saved and read.
        Map<String, Object> body = FullstackExampleRequests.manyToManyBody("react-tailwind-crud");
        body.put("backendTemplateSet", "spring-jpa-crud-lombok");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        runMvn(postAndExtractBackend(workDir, body), "-B", "package");
    }

    @org.junit.jupiter.api.Test
    void attachmentsBehindAHandWrittenDatasourceConfigBootAndPassTheirTests(@TempDir Path workDir) throws Exception {
        // Every database but the default H2 gets a datasource config class that names the
        // packages it scans. H2's own "primary" role is that same class, so it stands in for
        // PostgreSQL/Oracle/MSSQL/DB2 here: the attachment entity and repository live outside
        // <domain>.entity / .repository and must be scanned too, or the context does not start.
        Map<String, Object> body = FullstackExampleRequests.fieldTypesBody("react-tailwind-crud");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        Map<String, Object> opts = new java.util.LinkedHashMap<>((Map<String, Object>) body.get("opts"));
        opts.put("h2", List.of("h2-primary"));
        body.put("opts", opts);
        runMvn(postAndExtractBackend(workDir, body), "-B", "package");
    }

    /**
     * The tests option on a backend whose database is not there: PostgreSQL with Flyway. Its own
     * tests — an API test per entity and the context-load test — run on the {@code test} profile's
     * in-memory H2, so {@code mvn package} passes on a machine with no database running.
     */
    @org.junit.jupiter.api.Test
    void aPostgresqlBackendPassesItsOwnTestsOnH2(@TempDir Path workDir) throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        body.put("dependencies", List.of("web", "data-jpa", "postgresql", "validation", "flyway"));
        body.put("opts", Map.of("scaffold", List.of("audit", "softDelete", "optimisticLocking", "tests")));
        Path backend = postAndExtractBackend(workDir, body);
        runMvn(backend, "-B", "package");
        Path reports = backend.resolve("target/surefire-reports");
        assertThat(reports.resolve("com.menora.support.SupportApplicationTests.txt")).isRegularFile();
        assertThat(reports.resolve("com.menora.support.controller.OrderApiTest.txt")).isRegularFile();
        assertThat(Files.readString(reports.resolve("com.menora.support.controller.OrderApiTest.txt")))
                .contains("Failures: 0, Errors: 0, Skipped: 0");
    }

    /** The Orders example with its lines composed into the order (whatever the catalog's copy says). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> composedOrders(String frontendSet) throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", frontendSet);
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            if (!"Order".equals(entity.get("name"))) continue;
            List<Map<String, Object>> relations = new ArrayList<>();
            for (Map<String, Object> relation : (List<Map<String, Object>>) entity.get("relations")) {
                Map<String, Object> copy = new java.util.LinkedHashMap<>(relation);
                if ("lines".equals(copy.get("fieldName"))) copy.put("composition", true);
                relations.add(copy);
            }
            entity.put("relations", relations);
        }
        return body;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"spring-jpa-crud", "spring-jpa-crud-lombok"})
    void liveApiOfAComposedParentBehaves(String backendSet, @TempDir Path workDir) throws Exception {
        // An order with its lines composed in: the lines are saved, read and removed with the
        // order, through the order's own endpoints. Soft delete and optimistic locking are on,
        // because both change what a save and a delete of the parent do to the rows.
        Map<String, Object> body = composedOrders("react-tailwind-crud");
        body.put("pages", List.of());
        body.put("backendTemplateSet", backendSet);
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("opts", Map.of("scaffold", List.of("audit", "softDelete", "csvImport", "optimisticLocking", "seedData", "tests")));
        Path backend = postAndExtractBackend(workDir, body);
        writeLiveTest(backend, "com.menora.support", LIVE_COMPOSITION_TESTS);
        runMvn(backend, "-B", "package");
    }

    /** Test methods run against the generated Orders backend with Order.lines composed. */
    private static final String LIVE_COMPOSITION_TESTS = """
                private static String q(String json) {
                    return json.replace('\\'', '"');
                }

                private String order(String reference, String rest) throws Exception {
                    return q("{'reference':'" + reference + "','status':'OPEN','placedAt':'2026-11-01','customerId':"
                            + firstId("/api/customers") + rest + "}");
                }

                @Test
                void rowsAreSavedReadAndRemovedWithTheirParent() throws Exception {
                    long product = firstId("/api/products");
                    // Created with two rows: no ids, no link — the order is the link.
                    JsonNode made = send(post("/api/orders"), null, order("R-1", ",'lines':[{'quantity':2,'unitPrice':5,'productId':"
                            + product + "},{'quantity':1,'productId':" + product + "}]"), 200);
                    long id = made.get("id").asLong();
                    assertThat(made.get("lines")).hasSize(2);
                    assertThat(made.get("lines").get(0).get("orderId").asLong()).isEqualTo(id);
                    long first = made.get("lines").get(0).get("id").asLong();
                    long second = made.get("lines").get(1).get("id").asLong();

                    // Read by id the order carries its rows; a page of orders does not.
                    JsonNode read = get("/api/orders/" + id);
                    assertThat(read.get("lines")).hasSize(2);
                    assertThat(read.get("linesCount").asInt()).isEqualTo(2);
                    for (JsonNode row : get("/api/orders?size=50").get("content")) assertThat(row.get("lines").isNull()).isTrue();
                    // …and each row is an ordinary order line.
                    assertThat(get("/api/order-lines/" + first).get("quantity").asInt()).isEqualTo(2);

                    // A save with the rows as they should be: the first changed, the second gone, one new.
                    long version = read.get("version").asLong();
                    JsonNode saved = send(put("/api/orders/" + id), null, order("R-1b", ",'version':" + version
                            + ",'lines':[{'id':" + first + ",'quantity':7,'productId':" + product + "},{'quantity':3,'productId':" + product + "}]"), 200);
                    assertThat(saved.get("reference").asText()).isEqualTo("R-1b");
                    JsonNode after = get("/api/orders/" + id);
                    assertThat(after.get("lines")).hasSize(2);
                    assertThat(after.get("lines").get(0).get("id").asLong()).isEqualTo(first);
                    assertThat(after.get("lines").get(0).get("quantity").asInt()).isEqualTo(7);
                    assertThat(after.get("lines").get(1).get("id").asLong()).isNotIn(first, second);
                    mvc.perform(MockMvcRequestBuilders.get("/api/order-lines/" + second)).andExpect(status().isNotFound());
                    // The rows changed, so the order did: a client holding the old version is told (409).
                    assertThat(after.get("version").asLong()).isGreaterThan(version);
                    send(put("/api/orders/" + id), null, order("R-1c", ",'version':" + version + ",'lines':[]"), 409);

                    // No list at all leaves the rows alone; an empty one removes them.
                    send(put("/api/orders/" + id), null, order("R-1d", ""), 200);
                    assertThat(get("/api/orders/" + id).get("lines")).hasSize(2);
                    send(put("/api/orders/" + id), null, order("R-1e", ",'lines':[]"), 200);
                    assertThat(get("/api/orders/" + id).get("lines")).isEmpty();
                }

                @Test
                void aRowIsCheckedAndNeverTakenFromAnotherParent() throws Exception {
                    long product = firstId("/api/products");
                    // A row that breaks its own rules stops the whole save, named by its place.
                    JsonNode refused = send(post("/api/orders"), null, order("R-2", ",'lines':[{'productId':" + product + "}]"), 400);
                    assertThat(refused.get("errors").has("lines[0].quantity")).isTrue();
                    assertThat(get("/api/orders?q=R-2").get("totalElements").asInt()).isZero();

                    JsonNode one = send(post("/api/orders"), null, order("R-3", ",'lines':[{'quantity':1,'productId':" + product + "}]"), 200);
                    long theirs = one.get("lines").get(0).get("id").asLong();
                    // Another order naming that row's id does not get it: the row is new there.
                    JsonNode two = send(post("/api/orders"), null, order("R-4", ""), 200);
                    send(put("/api/orders/" + two.get("id").asLong()), null,
                            order("R-4", ",'lines':[{'id':" + theirs + ",'quantity':9,'productId':" + product + "}]"), 200);
                    assertThat(get("/api/order-lines/" + theirs).get("quantity").asInt()).isEqualTo(1);
                    assertThat(get("/api/order-lines/" + theirs).get("orderId").asLong()).isEqualTo(one.get("id").asLong());
                    JsonNode mine = get("/api/orders/" + two.get("id").asLong()).get("lines");
                    assertThat(mine).hasSize(1);
                    assertThat(mine.get(0).get("id").asLong()).isNotEqualTo(theirs);

                    // More rows than one save takes.
                    StringBuilder many = new StringBuilder();
                    for (int i = 0; i < 201; i++) many.append(i == 0 ? "" : ",").append("{'quantity':1,'productId':").append(product).append("}");
                    send(post("/api/orders"), null, order("R-5", ",'lines':[" + many + "]"), 400);
                }

                @Test
                void aSoftDeletedParentKeepsItsRowsAndARowOnItsOwnSaysWhoseItIs() throws Exception {
                    long product = firstId("/api/products");
                    JsonNode made = send(post("/api/orders"), null, order("R-6", ",'lines':[{'quantity':4,'productId':" + product + "}]"), 200);
                    long id = made.get("id").asLong();
                    mvc.perform(delete("/api/orders/" + id)).andExpect(status().is2xxSuccessful());
                    mvc.perform(MockMvcRequestBuilders.get("/api/orders/" + id)).andExpect(status().isNotFound());
                    // Restored, the order has its rows: deleting it softly did not delete them.
                    mvc.perform(post("/api/orders/" + id + "/restore")).andExpect(status().isOk());
                    assertThat(get("/api/orders/" + id).get("lines")).hasSize(1);

                    // The line's own endpoint still works, and there the order has to be named.
                    send(post("/api/order-lines"), null, q("{'quantity':1,'productId':" + product + "}"), 400);
                    JsonNode alone = send(post("/api/order-lines"), null, q("{'quantity':1,'productId':" + product + ",'orderId':" + id + "}"), 200);
                    assertThat(get("/api/orders/" + id).get("lines")).hasSize(2);
                    assertThat(alone.get("orderId").asLong()).isEqualTo(id);
                }

            """;

    /**
     * Access, enforced: a backend with ldap-auth-rest — the generated security classes for real,
     * the AOP check on every endpoint — driven over its API as four callers: nobody, a caller
     * with no role, a user and an admin. Roles come from {@code app.security.dev-roles}, which is
     * what a developer's machine uses too; the directory is never asked.
     */
    @org.junit.jupiter.api.Test
    void eachEntitysAccessLevelsAreEnforcedOverTheRealApi(@TempDir Path workDir) throws Exception {
        Map<String, Object> id = Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true);
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "Product");
        product.put("fields", List.of(id, Map.of("name", "name", "type", "STRING", "required", true)));
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("name", "Note");
        note.put("fields", List.of(id, Map.of("name", "text", "type", "STRING")));
        note.put("access", Map.of("read", "SIGNED_IN", "write", "USER"));
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("name", "AuditEntry");
        audit.put("fields", List.of(id, Map.of("name", "what", "type", "STRING")));
        audit.put("access", Map.of("read", "ADMIN"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "desk");
        body.put("packageName", "com.menora.desk");
        body.put("bootVersion", "3.2.1");
        body.put("frontendTemplateSet", "react-tailwind-crud");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "ldap-auth-rest"));
        body.put("opts", Map.of("scaffold", List.of("seedData", "tests", "csvExport", "bulkDelete")));
        body.put("entities", List.of(product, note, audit));

        Path backend = postAndExtractBackend(workDir, body);
        writeProjectTest(backend, "com.menora.desk", "AccessTests", ACCESS_TESTS);
        runMvn(backend, "-B", "package");
        assertThat(Files.exists(backend.resolve("target/surefire-reports/com.menora.desk.AccessTests.txt")))
                .as("the access tests ran in the generated build").isTrue();
    }

    /** Run inside the generated backend: who gets 401, who 403, and who gets through. */
    private static final String ACCESS_TESTS = """
            package com.menora.desk;

            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.MediaType;
            import org.springframework.test.web.servlet.MockMvc;
            import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            @SpringBootTest(properties = {
                    "app.security.dev-roles.boss=ADMIN",
                    "app.security.dev-roles.clerk=USER",
                    "app.security.dev-roles.guest="})
            @AutoConfigureMockMvc
            class AccessTests {

                private static final String LAMP = "{\\"name\\":\\"Lamp\\"}";
                private static final String HELLO = "{\\"text\\":\\"hi\\"}";

                @Autowired MockMvc mvc;

                private static MockHttpServletRequestBuilder as(String user, MockHttpServletRequestBuilder request) {
                    return request.header("userinfo", user);
                }

                private static MockHttpServletRequestBuilder create(String path, String json) {
                    return post(path).contentType(MediaType.APPLICATION_JSON).content(json);
                }

                @Test
                void nobodyIsRefusedEverywhere() throws Exception {
                    mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/api/notes")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/api/products/export.csv")).andExpect(status().isUnauthorized());
                    mvc.perform(create("/api/products", LAMP)).andExpect(status().isUnauthorized());
                }

                @Test
                void theDefaultIsReadForUsersAndWriteForAdmins() throws Exception {
                    mvc.perform(as("guest", get("/api/products"))).andExpect(status().isForbidden());
                    mvc.perform(as("clerk", get("/api/products"))).andExpect(status().isOk());
                    mvc.perform(as("clerk", get("/api/products/export.csv"))).andExpect(status().isOk());
                    mvc.perform(as("clerk", create("/api/products", LAMP))).andExpect(status().isForbidden());
                    mvc.perform(as("clerk", delete("/api/products/1"))).andExpect(status().isForbidden());
                    mvc.perform(as("clerk", delete("/api/products/bulk").contentType(MediaType.APPLICATION_JSON).content("[1]")))
                            .andExpect(status().isForbidden());
                    mvc.perform(as("boss", create("/api/products", LAMP))).andExpect(status().is2xxSuccessful());
                    // An admin is a user too.
                    mvc.perform(as("boss", get("/api/products"))).andExpect(status().isOk());
                }

                @Test
                void anEntitysOwnLevelsAreTheOnesChecked() throws Exception {
                    // Notes: any signed-in caller reads, users write.
                    mvc.perform(as("guest", get("/api/notes"))).andExpect(status().isOk());
                    mvc.perform(as("guest", create("/api/notes", HELLO))).andExpect(status().isForbidden());
                    mvc.perform(as("clerk", create("/api/notes", HELLO))).andExpect(status().is2xxSuccessful());
                    // The audit trail: admins only, to read as well.
                    mvc.perform(as("clerk", get("/api/audit-entries"))).andExpect(status().isForbidden());
                    mvc.perform(as("boss", get("/api/audit-entries"))).andExpect(status().isOk());
                }

                @Test
                void theRolesEndpointSaysWhatTheChecksSee() throws Exception {
                    mvc.perform(as("boss", get("/api/me/roles"))).andExpect(status().isOk())
                            .andExpect(content().json("[\\"ADMIN\\",\\"USER\\"]"));
                    mvc.perform(as("clerk", get("/api/me/roles"))).andExpect(content().json("[\\"USER\\"]"));
                    mvc.perform(as("guest", get("/api/me/roles"))).andExpect(content().json("[]"));
                }
            }
            """;

    /**
     * With Flyway on the build, the entity model is written as the first migration and Hibernate
     * only validates. These builds start each backend on the schema the migration made — the
     * context-load test, the demo rows and, for Orders and Approvals, the live API tests — so a
     * column the migration spells differently from the entity fails here, at {@code validate}.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"orders", "approvals", "fieldTypes", "manyToMany", "oneToMany", "enrolments", "projects"})
    @SuppressWarnings("unchecked")
    void flywayBaselineIsTheSchemaTheEntitiesExpect(String model, @TempDir Path workDir) throws Exception {
        Map<String, Object> body = switch (model) {
            case "fieldTypes" -> FullstackExampleRequests.fieldTypesBody("react-tailwind-crud");
            case "manyToMany" -> FullstackExampleRequests.manyToManyBody("react-tailwind-crud");
            case "oneToMany" -> FullstackExampleRequests.oneToManyBody("react-tailwind-crud");
            default -> FullstackPagesIntegrationTests.exampleBody(model, "react-tailwind-crud");
        };
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "flyway"));
        String liveTests = null;
        if (model.equals("orders")) {
            body.put("pages", List.of());
            body.put("opts", Map.of("scaffold", List.of("audit", "softDelete", "inverseCollections", "csvExport",
                    "xlsxExport", "savedViews", "csvImport", "bulkDelete", "bulkUpdate", "optimisticLocking",
                    "seedData", "tests")));
            liveTests = LIVE_ORDERS_TESTS;
        } else if (model.equals("approvals")) {
            body.put("opts", Map.of("scaffold", List.of("audit", "optimisticLocking", "csvExport", "csvImport",
                    "bulkUpdate", "seedData", "tests")));
            liveTests = LIVE_APPROVALS_TESTS;
        } else {
            body.put("opts", Map.of("scaffold", List.of("audit", "softDelete", "optimisticLocking", "seedData", "tests")));
        }
        // Roles need ldap-auth, whose artifact is in the company repository.
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            Map<String, Object> workflow = (Map<String, Object>) entity.get("workflow");
            if (workflow == null) continue;
            workflow.put("trackMoves", true);
            for (Map<String, Object> t : (List<Map<String, Object>>) workflow.get("transitions")) t.remove("roles");
        }
        if (body.get("pages") instanceof List<?> pages) {
            for (Object page : pages) ((Map<String, Object>) page).remove("roles");
        }
        Path backend = postAndExtractBackend(workDir, body);
        assertThat(Files.readString(backend.resolve("src/main/resources/db/migration/V1__init.sql")))
                .contains("create table ").doesNotContain("app_metadata");
        assertThat(Files.readString(backend.resolve("src/main/resources/application.yaml")))
                .contains("ddl-auto: validate").doesNotContain("create-drop");
        if (liveTests != null) writeLiveTest(backend, "com.menora.support", liveTests);
        // That the tables are there says a schema was made; this says who made it.
        String packageName = (String) body.get("packageName");
        writeProjectTest(backend, packageName, "MigrationRanTests", MIGRATION_RAN_TESTS.formatted(packageName));
        runMvn(backend, "-B", "package");
        assertThat(Files.exists(backend.resolve("target/surefire-reports/" + packageName + ".MigrationRanTests.txt")))
                .as("the migration check ran in the generated build").isTrue();
    }

    /**
     * An update's migration, run: a database made by the old model's baseline, then the migration
     * the update carries — as generated, nothing uncommented — and the new model's backend started
     * on the result with {@code ddl-auto: validate}. What the model added has to be there for the
     * new entities; what it removed stays, commented out, and is nobody's problem.
     */
    @org.junit.jupiter.api.Test
    void anUpdatesMigrationTakesTheOldSchemaToTheNewEntities(@TempDir Path workDir) throws Exception {
        Map<String, Object> base = FullstackMigrationTests.press("web", "data-jpa", "h2", "validation", "flyway");
        Map<String, Object> target = FullstackMigrationTests.changed(base);
        Path backend = postAndExtractBackend(workDir, target);
        Path migrations = backend.resolve("src/main/resources/db/migration");

        // The project as it was: its first migration is the old model's.
        Map<String, byte[]> old = FullstackExampleRequests.unzip(FullstackExampleRequests.post(rest, base).getBody());
        Files.write(migrations.resolve("V1__init.sql"), old.get("press/backend/src/main/resources/db/migration/V1__init.sql"));
        assertThat(Files.readString(migrations.resolve("V1__init.sql"))).contains("create table tags (").doesNotContain("categories");

        // The update: what the model changed, as its own migration.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> update = rest.exchange("/starter-fullstack.patch", HttpMethod.POST,
                new HttpEntity<>(Map.of("base", base, "target", target), headers), byte[].class);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, byte[]> files = FullstackExampleRequests.unzip(update.getBody());
        String path = files.keySet().stream().filter(p -> p.endsWith("__model_update.sql")).findFirst().orElseThrow();
        String name = path.substring(path.lastIndexOf('/') + 1);
        Files.write(migrations.resolve(name), files.get(path));

        writeProjectTest(backend, "com.menora.press", "UpdateMigrationRanTests", UPDATE_MIGRATION_RAN_TESTS.formatted(name));
        runMvn(backend, "-B", "package");
        assertThat(Files.exists(backend.resolve("target/surefire-reports/com.menora.press.UpdateMigrationRanTests.txt")))
                .as("the migration check ran in the generated build").isTrue();
    }

    /** Both migrations applied, the new table and columns there, the removed table still there. */
    private static final String UPDATE_MIGRATION_RAN_TESTS = """
            package com.menora.press;

            import org.flywaydb.core.Flyway;
            import org.flywaydb.core.api.MigrationInfo;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.jdbc.core.JdbcTemplate;

            import java.util.Arrays;

            import static org.assertj.core.api.Assertions.assertThat;

            @SpringBootTest
            class UpdateMigrationRanTests {

                @Autowired Flyway flyway;
                @Autowired JdbcTemplate jdbc;

                @Test
                void theOldSchemaWasBroughtToTheNewModel() {
                    MigrationInfo[] applied = flyway.info().applied();
                    assertThat(Arrays.stream(applied).map(MigrationInfo::getScript)).containsExactly("V1__init.sql", "%s");
                    assertThat(Arrays.stream(applied).noneMatch(m -> m.getState().isFailed())).isTrue();
                    // What the model added.
                    assertThat(jdbc.queryForObject("select count(*) from categories", Long.class)).isNotNull();
                    jdbc.queryForList("select summary, slug, category_id from posts");
                    // What it removed is still there: dropping it was left to a person.
                    assertThat(jdbc.queryForObject("select count(*) from tags", Long.class)).isNotNull();
                    assertThat(jdbc.queryForObject("select count(*) from post_tags", Long.class)).isNotNull();
                }
            }
            """;

    /** Added to a generated backend with Flyway: its first migration is recorded as applied, and nothing else. */
    private static final String MIGRATION_RAN_TESTS = """
            package %s;

            import org.flywaydb.core.Flyway;
            import org.flywaydb.core.api.MigrationInfo;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.core.env.Environment;

            import static org.assertj.core.api.Assertions.assertThat;

            @SpringBootTest
            class MigrationRanTests {

                @Autowired Flyway flyway;
                @Autowired Environment environment;

                @Test
                void theSchemaIsTheMigrationsNotHibernates() {
                    MigrationInfo[] applied = flyway.info().applied();
                    assertThat(applied).hasSize(1);
                    assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
                    assertThat(applied[0].getScript()).isEqualTo("V1__init.sql");
                    assertThat(applied[0].getState().isFailed()).isFalse();
                    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
                }
            }
            """;

    @org.junit.jupiter.api.Test
    void liveApiOfABackendWithEveryOptBehaves(@TempDir Path workDir) throws Exception {
        // The generated tests mock the service, so no SQL the options add is ever executed by
        // them. This build adds a test that drives the real API over the demo rows — see
        // LIVE_ORDERS_TESTS for what it holds the generated code to.
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("opts", Map.of("scaffold", List.of("audit", "softDelete", "inverseCollections", "csvExport", "xlsxExport", "savedViews",
                "csvImport", "bulkDelete", "bulkUpdate", "optimisticLocking", "seedData", "tests")));
        Path backend = postAndExtractBackend(workDir, body);
        writeLiveTest(backend, "com.menora.support", LIVE_ORDERS_TESTS);
        runMvn(backend, "-B", "package");
    }

    @org.junit.jupiter.api.Test
    @SuppressWarnings("unchecked")
    void liveApiOfAWorkflowBackendBehaves(@TempDir Path workDir) throws Exception {
        // The Approvals workflow over its real API: who a claim is assigned to, and what a move
        // takes. Without ldap-auth (its artifact is in the company repository), so no roles.
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("approvals", "react-tailwind-crud");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("opts", Map.of("scaffold", List.of("audit", "optimisticLocking", "csvExport", "csvImport", "bulkUpdate", "seedData", "tests")));
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            Map<String, Object> workflow = (Map<String, Object>) entity.get("workflow");
            if (workflow == null) continue;
            for (Map<String, Object> t : (List<Map<String, Object>>) workflow.get("transitions")) t.remove("roles");
        }
        Path backend = postAndExtractBackend(workDir, body);
        writeLiveTest(backend, "com.menora.support", LIVE_APPROVALS_TESTS);
        runMvn(backend, "-B", "package");
    }

    /** Test methods run against the generated Approvals backend (no roles) and its demo rows. */
    private static final String LIVE_APPROVALS_TESTS = """
                private JsonNode newClaim(String extra) throws Exception {
                    long center = firstId("/api/cost-centers");
                    return send(post("/api/expense-claims"), "alice", "{\\"title\\":\\"Taxi\\",\\"amount\\":40,\\"category\\":\\"TRAVEL\\","
                            + "\\"spentOn\\":\\"2026-11-01\\",\\"costCenterId\\":" + center + extra + "}", 200);
                }

                @Test
                void everyStepOfAClaimIsLogged() throws Exception {
                    long id = newClaim("").get("id").asLong();
                    // Created: its first step, by whoever made the request.
                    JsonNode steps = get("/api/expense-claims/" + id + "/moves");
                    assertThat(steps).hasSize(1);
                    assertThat(steps.get(0).get("from").isNull()).isTrue();
                    assertThat(steps.get(0).get("to").asText()).isEqualTo("DRAFT");
                    assertThat(steps.get(0).get("actor").asText()).isEqualTo("alice");

                    // Each move is a step: from where, to where, by which transition and whom.
                    send(post("/api/expense-claims/" + id + "/transitions/submit"), "bob", null, 200);
                    send(post("/api/expense-claims/" + id + "/transitions/approve"), "carol", "{\\"approvedAmount\\":35}", 200);
                    steps = get("/api/expense-claims/" + id + "/moves");
                    assertThat(steps).hasSize(3);
                    assertThat(steps.get(1).get("from").asText()).isEqualTo("DRAFT");
                    assertThat(steps.get(1).get("to").asText()).isEqualTo("SUBMITTED");
                    assertThat(steps.get(1).get("transition").asText()).isEqualTo("submit");
                    assertThat(steps.get(1).get("actor").asText()).isEqualTo("bob");
                    assertThat(steps.get(2).get("to").asText()).isEqualTo("APPROVED");
                    assertThat(steps.get(2).get("actor").asText()).isEqualTo("carol");

                    // A move that is refused leaves no step behind.
                    send(post("/api/expense-claims/" + id + "/transitions/submit"), "bob", null, 409);
                    assertThat(get("/api/expense-claims/" + id + "/moves")).hasSize(3);

                    // How long rows stay in a state: an average per state rows have left — the demo
                    // rows' histories and the two moves above.
                    JsonNode times = get("/api/expense-claims/moves/time-in-state");
                    java.util.Map<String, JsonNode> byState = new java.util.HashMap<>();
                    for (JsonNode time : times) byState.put(time.get("state").asText(), time);
                    assertThat(byState).containsKeys("DRAFT", "SUBMITTED");
                    assertThat(byState.get("DRAFT").get("moves").asLong()).isGreaterThanOrEqualTo(2);
                    assertThat(byState.get("DRAFT").get("seconds").asDouble()).isGreaterThan(0);
                    assertThat(byState).doesNotContainKey("PAID");

                    // A demo row has a history too; a row that is not there has none to show.
                    assertThat(get("/api/expense-claims/" + firstId("/api/expense-claims") + "/moves")).isNotEmpty();
                    mvc.perform(MockMvcRequestBuilders.get("/api/expense-claims/999999/moves")).andExpect(status().isNotFound());
                }

                @Test
                void theAssigneeChangesOnlyByClaimReleaseAndReassign() throws Exception {
                    // The status is the workflow's: a client that sends none is not turned away (the
                    // field is required in the model — that is the column's NOT NULL, not the client's job).
                    assertThat(newClaim("").get("status").asText()).isEqualTo("DRAFT");
                    // A new claim is nobody's and starts as a draft, whatever the client sent.
                    JsonNode made = newClaim(",\\"assignee\\":\\"mallory\\",\\"status\\":\\"PAID\\"");
                    String path = "/api/expense-claims/" + made.get("id").asLong();
                    assertThat(made.get("assignee").isNull()).isTrue();
                    assertThat(made.get("status").asText()).isEqualTo("DRAFT");
                    // An ordinary save changes the title, not who has it.
                    ObjectNode edit = (ObjectNode) made.deepCopy();
                    edit.put("assignee", "mallory").put("title", "Taxi home");
                    JsonNode saved = send(put(path), "mallory", edit.toString(), 200);
                    assertThat(saved.get("title").asText()).isEqualTo("Taxi home");
                    assertThat(saved.get("assignee").isNull()).isTrue();
                    // …nor does a bulk edit: the field is not one it can set.
                    send(patch("/api/expense-claims/bulk"), "mallory",
                            "{\\"ids\\":[" + made.get("id").asLong() + "],\\"field\\":\\"assignee\\",\\"value\\":\\"mallory\\"}", 400);
                    // Claim and release are the holder's own.
                    assertThat(send(post(path + "/claim"), "alice", null, 200).get("assignee").asText()).isEqualTo("alice");
                    send(post(path + "/release"), "bob", null, 403);
                    send(post(path + "/claim"), "bob", null, 409);
                    // Reassigning hands it over whoever holds it; a blank assignee puts it back in the queue.
                    send(post(path + "/assign"), null, "{\\"assignee\\":\\"bob\\"}", 401);
                    assertThat(send(post(path + "/assign"), "alice", "{\\"assignee\\":\\" bob \\"}", 200).get("assignee").asText()).isEqualTo("bob");
                    assertThat(send(post(path + "/assign"), "alice", "{\\"assignee\\":\\"\\"}", 200).get("assignee").isNull()).isTrue();
                    assertThat(get(path).get("assignee").isNull()).isTrue();
                }

                @Test
                void aMoveHoldsItsValuesToWhatASaveHoldsThemTo() throws Exception {
                    String path = "/api/expense-claims/" + newClaim("").get("id").asLong();
                    assertThat(send(post(path + "/transitions/submit"), "alice", null, 200).get("status").asText()).isEqualTo("SUBMITTED");
                    // Approving asks for the approved amount: left out, or one the DTO refuses (>= 0), is a 400 naming it.
                    assertThat(send(post(path + "/transitions/approve"), "alice", "{}", 400).get("errors").has("approvedAmount")).isTrue();
                    assertThat(send(post(path + "/transitions/approve"), "alice", "{\\"approvedAmount\\":-5}", 400).get("errors").has("approvedAmount")).isTrue();
                    assertThat(get(path).get("status").asText()).isEqualTo("SUBMITTED");
                    JsonNode approved = send(post(path + "/transitions/approve"), "alice", "{\\"approvedAmount\\":35.5}", 200);
                    assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
                    assertThat(approved.get("approvedAmount").decimalValue()).isEqualByComparingTo("35.5");
                    // A finished claim has nothing left to hand over.
                    send(post(path + "/transitions/pay"), "alice", null, 200);
                    send(post(path + "/assign"), "alice", "{\\"assignee\\":\\"bob\\"}", 409);
                }
            """;

    /** Test methods run against the generated Orders backend (every option on) and its demo rows. */
    private static final String LIVE_ORDERS_TESTS = """
                @Test
                void aSavedViewBelongsToWhoeverSavedIt() throws Exception {
                    String state = "{\\"q\\":\\"acme\\",\\"sort\\":{\\"field\\":\\"name\\",\\"direction\\":\\"asc\\"},\\"filters\\":{\\"active\\":\\"true\\"}}";
                    String active = "{\\"list\\":\\"customer\\",\\"name\\":\\"Active\\",\\"state\\":" + state + "}";
                    JsonNode saved = send(post("/api/views"), "dana", active, 200);
                    long id = saved.get("id").asLong();
                    assertThat(saved.get("mine").asBoolean()).isTrue();
                    // The state comes back as it was sent: an object, not a string of one.
                    assertThat(saved.get("state").get("sort").get("field").asText()).isEqualTo("name");
                    assertThat(saved.get("state").get("filters").get("active").asText()).isEqualTo("true");

                    // The name is hers alone on this list: taken for her, free for someone else and on another list.
                    assertThat(send(post("/api/views"), "dana", active, 409).get("detail").asText()).contains("already have a view named 'Active'");
                    send(post("/api/views"), "omer", active, 200);
                    send(post("/api/views"), "dana", active.replace("\\"customer\\"", "\\"order\\""), 200);

                    // Hers until she shares it.
                    JsonNode forOmer = send(MockMvcRequestBuilders.get("/api/views").param("list", "customer"), "omer", null, 200);
                    assertThat(forOmer).hasSize(1);
                    send(put("/api/views/" + id), "dana", "{\\"shared\\":true,\\"defaultView\\":true}", 200);
                    forOmer = send(MockMvcRequestBuilders.get("/api/views").param("list", "customer"), "omer", null, 200);
                    assertThat(forOmer).hasSize(2);
                    JsonNode hers = null;
                    for (JsonNode view : forOmer) if (view.get("id").asLong() == id) hers = view;
                    // Shared, he can apply it — it is not his, and its being her default is nothing to him.
                    assertThat(hers.get("shared").asBoolean()).isTrue();
                    assertThat(hers.get("mine").asBoolean()).isFalse();
                    assertThat(hers.get("defaultView").asBoolean()).isFalse();
                    // To change or delete it he would have to own it: to him it is not there.
                    send(put("/api/views/" + id), "omer", "{\\"name\\":\\"Mine now\\"}", 404);
                    send(delete("/api/views/" + id), "omer", null, 404);

                    // One default per user and list: a second one takes over.
                    long all = send(post("/api/views"), "dana", "{\\"list\\":\\"customer\\",\\"name\\":\\"All\\",\\"state\\":{},\\"defaultView\\":true}", 200).get("id").asLong();
                    for (JsonNode view : send(MockMvcRequestBuilders.get("/api/views").param("list", "customer"), "dana", null, 200)) {
                        assertThat(view.get("defaultView").asBoolean()).as(view.get("name").asText()).isEqualTo(view.get("id").asLong() == all);
                    }
                    // A rename keeps the rest; onto a name she already has it is refused.
                    assertThat(send(put("/api/views/" + id), "dana", "{\\"name\\":\\"Active customers\\"}", 200).get("state").get("q").asText()).isEqualTo("acme");
                    send(put("/api/views/" + id), "dana", "{\\"name\\":\\"All\\"}", 409);

                    // Refused: a list that is not one of the app's, no name, a state that is not an object.
                    send(post("/api/views"), "dana", active.replace("\\"customer\\"", "\\"nope\\""), 400);
                    send(post("/api/views"), "dana", "{\\"list\\":\\"customer\\",\\"name\\":\\" \\",\\"state\\":{}}", 400);
                    send(post("/api/views"), "dana", "{\\"list\\":\\"customer\\",\\"name\\":\\"Text\\",\\"state\\":\\"q=acme\\"}", 400);
                    send(MockMvcRequestBuilders.get("/api/views").param("list", "nope"), "dana", null, 400);

                    // Without a signed-in user the views are everyone's: one shared owner.
                    send(post("/api/views"), null, "{\\"list\\":\\"customer\\",\\"name\\":\\"Open\\",\\"state\\":{}}", 200);
                    assertThat(send(MockMvcRequestBuilders.get("/api/views").param("list", "customer"), null, null, 200).findValuesAsText("name")).contains("Open");

                    send(delete("/api/views/" + id), "dana", null, 204);
                    assertThat(send(MockMvcRequestBuilders.get("/api/views").param("list", "customer"), "omer", null, 200)).hasSize(1);
                }

                @Test
                void theExcelExportIsAWorkbookOfTheListsRows() throws Exception {
                    send(post("/api/customers"), null, "{\\"name\\":\\"=SUM(A1) & <Sons>\\",\\"email\\":\\"sons@b.co\\",\\"active\\":true}", 200);
                    var started = mvc.perform(MockMvcRequestBuilders.get("/api/customers/export.xlsx").param("sort", "id,desc")).andReturn();
                    var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                            .andExpect(status().isOk()).andReturn().getResponse();
                    assertThat(response.getContentType()).contains("spreadsheetml.sheet");
                    assertThat(response.getHeader("Content-Disposition")).contains("customers.xlsx");

                    // A workbook is a zip of XML parts: the five fixed ones, then the sheet.
                    java.util.Map<String, String> parts = new java.util.LinkedHashMap<>();
                    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
                        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                            parts.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                    assertThat(parts.keySet()).containsExactly("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                            "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml");
                    for (String xml : parts.values()) {
                        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                                .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
                    }
                    assertThat(parts.get("xl/workbook.xml")).contains("<sheet name=" + '"' + "Customers" + '"');
                    String sheet = parts.get("xl/worksheets/sheet1.xml").replace('"', '\\'');
                    // The first row is the columns' names as the UI gives them, in bold.
                    assertThat(sheet).contains("<row r='1'><c r='A1' s='1' t='inlineStr'><is><t xml:space='preserve'>Id</t></is></c>")
                            .contains("<t xml:space='preserve'>Name</t>").contains("<t xml:space='preserve'>Email</t>");
                    // The newest row first (the sort is honoured): a number as a number, a boolean as a
                    // boolean, and text as text — escaped, and never a formula.
                    assertThat(sheet).containsPattern("<row r='2'><c r='A2'><v>\\\\d+</v></c>")
                            .contains("<c r='B2' t='inlineStr'><is><t xml:space='preserve'>=SUM(A1) &amp; &lt;Sons&gt;</t></is></c>")
                            .contains("t='b'><v>1</v></c>");
                    // Every row of the list, whatever the page size: the eight demo rows and this one.
                    assertThat(sheet.split("<row ", -1).length - 1).isGreaterThanOrEqualTo(10);
                    // The search narrows it like the list.
                    var narrowed = mvc.perform(MockMvcRequestBuilders.get("/api/customers/export.xlsx").param("q", "Sons")).andReturn();
                    byte[] one = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(narrowed))
                            .andReturn().getResponse().getContentAsByteArray();
                    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(one))) {
                        String last = null;
                        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                            last = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                        assertThat(last.split("<row ", -1).length - 1).isEqualTo(2);
                    }
                }

                @Test
                void aVersionedRowIsSoftDeletedAndRestored() throws Exception {
                    long id = firstId("/api/customers");
                    long version = get("/api/customers/" + id).get("version").asLong();
                    mvc.perform(delete("/api/customers/" + id)).andExpect(status().is2xxSuccessful());
                    mvc.perform(MockMvcRequestBuilders.get("/api/customers/" + id)).andExpect(status().isNotFound());
                    mvc.perform(post("/api/customers/" + id + "/restore")).andExpect(status().isOk());
                    JsonNode back = get("/api/customers/" + id);
                    // Restoring is a change: a client still holding the old version must be told.
                    assertThat(back.get("version").asLong()).isGreaterThan(version);
                }

                @Test
                void theCsvExportGuardsTextThatStartsLikeAFormula() throws Exception {
                    send(post("/api/customers"), null, "{\\"name\\":\\"=HYPERLINK(1)\\",\\"email\\":\\"+a@b.co\\"}", 200);
                    var started = mvc.perform(MockMvcRequestBuilders.get("/api/customers/export.csv")).andReturn();
                    String csv = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    // Text is led by an apostrophe; the demo rows' plain names are untouched.
                    assertThat(csv).contains("'=HYPERLINK(1)").contains("'+a@b.co").contains(",Name 1,");
                }

                @Test
                void bulkCallsKeepWhatASingleSaveKeeps() throws Exception {
                    long id = firstId("/api/products");
                    String name = get("/api/products/" + id).get("name").asText();
                    // An id that names no row: 404, and the row that does exist is left alone.
                    mvc.perform(patch("/api/products/bulk").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"ids\\":[" + id + ",987654],\\"field\\":\\"name\\",\\"value\\":\\"renamed\\"}"))
                            .andExpect(status().isNotFound());
                    assertThat(get("/api/products/" + id).get("name").asText()).isEqualTo(name);
                    mvc.perform(delete("/api/products/bulk").contentType(MediaType.APPLICATION_JSON)
                                    .content("[" + id + ",987654]"))
                            .andExpect(status().isNotFound());
                    get("/api/products/" + id);
                    // A required field cannot be cleared; a value the DTO refuses (price >= 0) is refused.
                    mvc.perform(patch("/api/products/bulk").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"ids\\":[" + id + "],\\"field\\":\\"name\\",\\"value\\":null}"))
                            .andExpect(status().isBadRequest());
                    mvc.perform(patch("/api/products/bulk").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"ids\\":[" + id + "],\\"field\\":\\"price\\",\\"value\\":-5}"))
                            .andExpect(status().isBadRequest());
                    assertThat(get("/api/products/" + id).get("name").asText()).isEqualTo(name);
                    assertThat(get("/api/products/" + id).get("price").decimalValue().signum()).isGreaterThanOrEqualTo(0);
                    // And a good edit still goes through.
                    mvc.perform(patch("/api/products/bulk").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"ids\\":[" + id + "],\\"field\\":\\"name\\",\\"value\\":\\"renamed\\"}"))
                            .andExpect(status().is2xxSuccessful());
                    assertThat(get("/api/products/" + id).get("name").asText()).isEqualTo("renamed");
                }
            """;

    /**
     * Adds {@code LiveApiTests} to a generated backend: a {@code @SpringBootTest} with MockMvc
     * over the real context (H2, demo rows), holding the given test methods. {@code get(path)}
     * reads a JSON answer and {@code firstId(listPath)} the id of the first listed row.
     */
    private static void writeLiveTest(Path backend, String packageName, String methods) throws Exception {
        String source = """
                package %s;

                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.fasterxml.jackson.databind.node.ObjectNode;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.http.MediaType;
                import org.springframework.test.web.servlet.MockMvc;
                import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

                import static org.assertj.core.api.Assertions.assertThat;
                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

                @SpringBootTest
                @AutoConfigureMockMvc
                class LiveApiTests {

                    @Autowired
                    private MockMvc mvc;

                    private final ObjectMapper json = new ObjectMapper();

                    private JsonNode get(String path) throws Exception {
                        return json.readTree(mvc.perform(MockMvcRequestBuilders.get(path))
                                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
                    }

                    private long firstId(String listPath) throws Exception {
                        return get(listPath + "?size=1").get("content").get(0).get("id").asLong();
                    }

                    /** Sends JSON as {@code user} (null: nobody signed in) and reads the JSON answer of the expected status. */
                    private JsonNode send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder call,
                                          String user, String body, int expected) throws Exception {
                        if (user != null) call.header("userinfo", user);
                        if (body != null) call.contentType(MediaType.APPLICATION_JSON).content(body);
                        byte[] answer = mvc.perform(call).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray();
                        return answer.length == 0 ? json.nullNode() : json.readTree(answer);
                    }

                %s}
                """.formatted(packageName, methods);
        Path file = backend.resolve("src/test/java/" + packageName.replace('.', '/') + "/LiveApiTests.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    @ParameterizedTest(name = "apiMode {0}")
    @ValueSource(strings = {"INLINE_DTO", "ENTITY_DIRECT"})
    void sqlWizardBackendCompilesAndCreatesItsSchema(String apiMode, @TempDir Path workDir) throws Exception {
        // What the SQL wizard writes had no build at all. The library script under the h2
        // dependency: the project compiles, and its context-load test starts Hibernate, which
        // creates the schema from the generated entities (ddl-auto is create-drop on H2) — so the
        // mappings are real: composite keys and foreign keys, a shared-key one-to-one, unique
        // constraints, the enum behind a CHECK, constraints added by ALTER TABLE, a view.
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "library");
        body.put("packageName", "com.menora.library");
        body.put("bootVersion", "3.2.1");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("sqlByDep", Map.of("h2", WizardGoldenOutputTests.fixture("library-postgresql.sql")));
        body.put("sqlOptions", Map.of("h2", Map.of("subPackage", "entity", "apiMode", apiMode)));
        runMvn(postAndExtractWizard(workDir, body), "-B", "package");
    }

    /** A script H2 runs as written: what the Flyway baseline case migrates with. */
    private static final String CLUB_SQL = """
            -- the club's schema; kept by the data team
            CREATE TABLE teams (
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                name VARCHAR(60) NOT NULL,
                city VARCHAR(40),
                founded DATE,
                budget DECIMAL(12, 2),
                UNIQUE (name, city)
            );
            CREATE TABLE players (
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                team_id BIGINT NOT NULL REFERENCES teams(id),
                nickname VARCHAR(30) NOT NULL UNIQUE,
                position VARCHAR(10) NOT NULL CHECK (position IN ('GOAL', 'FIELD')),
                shirt INTEGER NOT NULL,
                active BOOLEAN,
                joined_at TIMESTAMP
            );
            CREATE TABLE fixtures (
                home_id BIGINT NOT NULL,
                away_id BIGINT NOT NULL,
                played_on DATE NOT NULL,
                score VARCHAR(10),
                PRIMARY KEY (home_id, away_id, played_on)
            );
            ALTER TABLE fixtures ADD CONSTRAINT fixtures_home FOREIGN KEY (home_id) REFERENCES teams(id);
            CREATE TABLE match_log (message VARCHAR(500), logged_at TIMESTAMP);
            """;

    @org.junit.jupiter.api.Test
    void sqlWizardBackendMigratesWithItsBaselineAndPassesItsTests(@TempDir Path workDir) throws Exception {
        // Flyway, validation and generateTests together. The script is the project's first
        // migration, so the context-load test migrates an empty H2 with it; Hibernate is then told
        // to *validate* (not create) — the generated entities have to match the schema the DDL
        // itself made, column for column. The generated controller tests run too.
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "club");
        body.put("packageName", "com.menora.club");
        body.put("bootVersion", "3.2.1");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "flyway"));
        body.put("sqlByDep", Map.of("h2", CLUB_SQL));
        body.put("sqlOptions", Map.of("h2", Map.of("subPackage", "entity", "apiMode", "INLINE_DTO", "generateTests", true)));
        Path project = postAndExtractWizard(workDir, body);

        String baseline = Files.readString(project.resolve("src/main/resources/db/migration/V1__init.sql"));
        assertThat(baseline).contains("CREATE TABLE players (").contains("ALTER TABLE fixtures ADD CONSTRAINT fixtures_home")
                .doesNotContain("app_metadata");
        assertThat(project.resolve("src/test/java/com/menora/club/controller/PlayersControllerTest.java")).isRegularFile();
        // classpath:/config/ outranks the application.yaml at the root: only the tests validate.
        Path override = project.resolve("src/test/resources/config/application.yaml");
        Files.createDirectories(override.getParent());
        Files.writeString(override, "spring:\n  jpa:\n    hibernate:\n      ddl-auto: validate\n");
        runMvn(project, "-B", "package");
    }

    private static Map<String, Object> openApiBody(String artifactId, List<String> dependencies, Map<String, Object> options)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", artifactId);
        body.put("packageName", "com.menora.petstore");
        body.put("bootVersion", "3.2.1");
        body.put("javaVersion", "21");
        body.put("dependencies", dependencies);
        body.put("specByDep", Map.of("web", WizardGoldenOutputTests.fixture("petstore.yaml")));
        body.put("openApiOptions", Map.of("web", options));
        return body;
    }

    @org.junit.jupiter.api.Test
    void openApiWizardBackendCompilesAndItsContractBehaves(@TempDir Path workDir) throws Exception {
        // Controllers, a RestTemplate client and the DTO types from the Petstore document, with
        // the validation starter. The generated project compiles; the injected test then reads
        // and writes the types as JSON (the union, the enums, the renamed properties), checks the
        // constraints, and calls the controllers and the client the way a caller would.
        Path project = postAndExtractWizard(workDir, openApiBody("petstore", List.of("web", "validation"), Map.of("mode", "BOTH")));
        writeProjectTest(project, "com.menora.petstore", "ContractTests", OPENAPI_CONTRACT_TESTS);
        runMvn(project, "-B", "package");
    }

    @org.junit.jupiter.api.Test
    void openApiWizardRestClientCompilesAndCallsTheContract(@TempDir Path workDir) throws Exception {
        // The RestClient kind, client only, no web starter named (the wizard adds it), the
        // document kept in the project.
        Path project = postAndExtractWizard(workDir, openApiBody("petclient", List.of("validation"),
                Map.of("mode", "CLIENT", "clientKind", "restClient", "copySpec", true)));
        assertThat(project.resolve("src/main/resources/openapi/web.yaml")).isRegularFile();
        assertThat(Files.readString(project.resolve("pom.xml"))).contains("spring-boot-starter-web");
        writeProjectTest(project, "com.menora.petstore", "RestClientTests", OPENAPI_REST_CLIENT_TESTS);
        runMvn(project, "-B", "package");
    }

    @org.junit.jupiter.api.Test
    void eventsWizardBackendCompilesAndPassesItsTests(@TempDir Path workDir) throws Exception {
        // Producers, listeners and payload types from the AsyncAPI 3 fixture, every channel in both
        // directions, with the validation starter and the generated tests: the project compiles,
        // its producer and listener tests run (a payload out as JSON and back), and its context
        // starts with the Kafka dependency's own configuration (no broker is needed for that).
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "orders");
        body.put("packageName", "com.menora.orders");
        body.put("bootVersion", "3.2.1");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("kafka", "validation"));
        body.put("asyncApiByDep", Map.of("kafka", WizardGoldenOutputTests.fixture("orders-asyncapi-v3.yaml")));
        body.put("eventOptions", Map.of("kafka", Map.of("generateTests", true, "channels", List.of(
                Map.of("name", "orderCreated", "produce", true, "consume", true),
                Map.of("name", "orderEvents", "produce", true, "consume", true),
                Map.of("name", "stockLevels", "produce", true, "consume", true),
                Map.of("name", "heartbeat", "produce", true, "consume", true)))));
        Path project = postAndExtractWizard(workDir, body);
        assertThat(project.resolve("src/test/java/com/menora/orders/events/StockLevelsListenerTest.java")).isRegularFile();
        runMvn(project, "-B", "package");
    }

    /** Run inside the generated Petstore project (controllers + RestTemplate client + validation). */
    private static final String OPENAPI_CONTRACT_TESTS = """
            package com.menora.petstore;

            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.menora.petstore.client.PetsClient;
            import com.menora.petstore.dto.Animal;
            import com.menora.petstore.dto.Cat;
            import com.menora.petstore.dto.Dog;
            import com.menora.petstore.dto.Owner;
            import com.menora.petstore.dto.Pet;
            import com.menora.petstore.dto.PetOrOwner;
            import com.menora.petstore.dto.PetStatus;
            import com.menora.petstore.dto.RecordVisitRequest;
            import com.menora.petstore.dto.Shelter;
            import com.menora.petstore.dto.VisitKind;
            import jakarta.servlet.http.Cookie;
            import jakarta.validation.ConstraintViolation;
            import jakarta.validation.Validator;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.HttpMethod;
            import org.springframework.http.MediaType;
            import org.springframework.test.web.client.MockRestServiceServer;
            import org.springframework.test.web.servlet.MockMvc;

            import java.time.OffsetDateTime;
            import java.util.List;
            import java.util.Set;
            import java.util.UUID;
            import java.util.stream.Collectors;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.assertj.core.api.Assertions.assertThatThrownBy;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
            import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            @SpringBootTest
            @AutoConfigureMockMvc
            @AutoConfigureMockRestServiceServer
            class ContractTests {

                @Autowired private ObjectMapper mapper;
                @Autowired private Validator validator;
                @Autowired private MockMvc mockMvc;
                @Autowired private MockRestServiceServer server;
                @Autowired private PetsClient pets;

                @Test
                void aUnionIsReadByItsDiscriminatorAndWrittenWithIt() throws Exception {
                    Animal animal = mapper.readValue(
                            "{\\"kind\\":\\"hound\\",\\"breed\\":\\"beagle\\",\\"class\\":\\"A\\",\\"weight_kg\\":9.5}", Animal.class);
                    assertThat(animal).isInstanceOf(Dog.class);
                    Dog dog = (Dog) animal;
                    // Properties whose names are not Java names keep theirs on the wire.
                    assertThat(dog.class_()).isEqualTo("A");
                    assertThat(dog.weightKg()).isEqualTo(9.5);
                    assertThat(mapper.writeValueAsString(animal))
                            .contains("\\"kind\\":\\"hound\\"").contains("\\"class\\":\\"A\\"").contains("\\"weight_kg\\":9.5");
                    assertThat(mapper.readValue("{\\"kind\\":\\"cat\\",\\"name\\":\\"Tom\\"}", Animal.class)).isInstanceOf(Cat.class);
                    // Inside another type too.
                    Shelter shelter = mapper.readValue("{\\"code\\":\\"NORTH\\",\\"email\\":\\"a@b.co\\",\\"size\\":\\"large\\","
                            + "\\"residents\\":[{\\"kind\\":\\"cat\\",\\"name\\":\\"Tom\\"},{\\"kind\\":\\"hound\\"}],"
                            + "\\"ratings\\":{\\"food\\":5},\\"last_problem\\":{\\"code\\":7}}", Shelter.class);
                    assertThat(shelter.residents()).hasSize(2);
                    assertThat(shelter.residents().get(1)).isInstanceOf(Dog.class);
                    assertThat(shelter.ratings()).containsEntry("food", 5);
                    assertThat(shelter.lastProblem().code()).isEqualTo(7);
                    assertThat(shelter.size().getValue()).isEqualTo("large");
                }

                @Test
                void aMemberOfAUnionIsStillReadOnItsOwn() throws Exception {
                    // Pet is one of PetOrOwner. Asked for a Pet, any Pet will do — it is not "deduced".
                    Pet pet = mapper.readValue("{\\"id\\":1}", Pet.class);
                    assertThat(pet.id()).isEqualTo(1L);
                    // Asked for the union, the properties say which.
                    assertThat(mapper.readValue("{\\"id\\":1,\\"full_name\\":\\"Ada\\"}", PetOrOwner.class)).isInstanceOf(Owner.class);
                    assertThat(mapper.readValue("{\\"id\\":1,\\"name\\":\\"Rex\\",\\"status\\":\\"sold\\"}", PetOrOwner.class))
                            .isInstanceOf(Pet.class);
                    assertThat(mapper.writeValueAsString(new Owner(4L, "Ada", null))).contains("\\"full_name\\":\\"Ada\\"");
                }

                @Test
                void anEnumTravelsAsItsValue() throws Exception {
                    assertThat(mapper.readValue("\\"2nd-opinion\\"", VisitKind.class)).isEqualTo(VisitKind._2ND_OPINION);
                    assertThat(mapper.writeValueAsString(VisitKind.CHECK_UP)).isEqualTo("\\"check-up\\"");
                    Pet pet = mapper.readValue("{\\"id\\":1,\\"name\\":\\"Rex\\",\\"status\\":\\"sold\\","
                            + "\\"seenAt\\":\\"2026-01-02T03:04:05+02:00\\",\\"born\\":\\"2020-05-06\\"}", Pet.class);
                    assertThat(pet.status()).isEqualTo(PetStatus.SOLD);
                    // A date-time keeps the offset it came with.
                    assertThat(pet.seenAt().toInstant()).isEqualTo(OffsetDateTime.parse("2026-01-02T01:04:05Z").toInstant());
                    assertThatThrownBy(() -> mapper.readValue("\\"lost\\"", PetStatus.class)).hasMessageContaining("lost");
                }

                @Test
                void theSchemasConstraintsAreChecked() {
                    Shelter bad = new Shelter("abc", "not-an-email", -1L, null, List.of(), null, null, null, null);
                    Set<String> wrong = validator.validate(bad).stream()
                            .map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
                    assertThat(wrong).containsExactlyInAnyOrder("code", "email", "capacity", "residents");
                    // …and inside a nested object.
                    Shelter nested = new Shelter("NORTH", "a@b.co", 5L, null, List.of(new Cat("cat", "tom", null)), null, null, null, null);
                    assertThat(validator.validate(nested)).extracting(ConstraintViolation::getMessageTemplate)
                            .containsExactly("{jakarta.validation.constraints.Pattern.message}");
                    assertThat(validator.validate(new Shelter("NORTH", "a@b.co", 5000000000L, null,
                            List.of(new Cat("cat", "Tom", null)), null, null, null, null))).isEmpty();
                }

                @Test
                void aControllerBindsTheRequestsOwnNames() throws Exception {
                    // Everything bound: the call reaches the stub, which says what to implement.
                    assertThatThrownBy(() -> mockMvc.perform(get("/pets").param("status", "sold").param("limit", "5")
                            .header("X-Request-Id", UUID.randomUUID().toString()).cookie(new Cookie("session", "s1"))))
                            .hasRootCauseInstanceOf(UnsupportedOperationException.class)
                            .hasRootCauseMessage("TODO: implement listPets");
                    // A required query parameter that is missing never gets there.
                    mockMvc.perform(get("/pets")).andExpect(status().isBadRequest());
                    // Path variables by the path's names, an ISO date-time, no body (the document says it is optional).
                    assertThatThrownBy(() -> mockMvc.perform(put("/pets/5/visits/" + UUID.randomUUID())
                            .param("seen-after", "2026-01-02T03:04:05Z").param("page_size", "10")))
                            .hasRootCauseMessage("TODO: implement recordVisit");
                    // A body that breaks the schema is refused before the method runs.
                    mockMvc.perform(post("/pets").contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andExpect(status().isBadRequest());
                    mockMvc.perform(put("/pets/5/visits/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\\"reason\\":\\"x\\"}")).andExpect(status().isBadRequest());
                    assertThatThrownBy(() -> mockMvc.perform(post("/animals").contentType(MediaType.APPLICATION_JSON)
                            .content("{\\"kind\\":\\"cat\\",\\"name\\":\\"Tom\\"}")))
                            .hasRootCauseMessage("TODO: implement adopt");
                }

                @Test
                void theClientSendsTheRequestsOwnNames() {
                    UUID request = UUID.randomUUID();
                    server.expect(requestTo("http://localhost:8080/pets?limit=5&status=sold")).andExpect(method(HttpMethod.GET))
                            .andExpect(header("X-Request-Id", request.toString())).andExpect(header("Cookie", "session=s1"))
                            .andRespond(withSuccess("[{\\"id\\":1,\\"name\\":\\"Rex\\",\\"status\\":\\"sold\\"}]", MediaType.APPLICATION_JSON));
                    // The path names petId first; the document lists visit-id first.
                    UUID visit = UUID.randomUUID();
                    server.expect(requestTo("http://localhost:8080/pets/5/visits/" + visit + "?page_size=10"))
                            .andExpect(method(HttpMethod.PUT)).andExpect(content().json("{\\"reason\\":\\"check\\",\\"follow-up\\":true}"))
                            .andRespond(withSuccess("{\\"ticket\\":\\"T-1\\"}", MediaType.APPLICATION_JSON));
                    // (The mock server takes every expectation before the first call.)
                    List<Pet> found = pets.listPets(5, "sold", request, "s1");
                    assertThat(found).extracting(Pet::name).containsExactly("Rex");
                    assertThat(pets.recordVisit(visit, 5L, null, 10, null, null, new RecordVisitRequest("check", true)).ticket())
                            .isEqualTo("T-1");
                    server.verify();
                }
            }
            """;

    /** Run inside the generated client-only project (RestClient). */
    private static final String OPENAPI_REST_CLIENT_TESTS = """
            package com.menora.petstore;

            import com.menora.petstore.client.AnimalsClient;
            import com.menora.petstore.client.PetsClient;
            import com.menora.petstore.dto.Cat;
            import com.menora.petstore.dto.Pet;
            import com.menora.petstore.dto.RecordVisitRequest;
            import com.menora.petstore.dto.Shelter;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.HttpMethod;
            import org.springframework.http.MediaType;
            import org.springframework.test.web.client.MockRestServiceServer;

            import java.util.List;
            import java.util.UUID;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
            import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
            import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

            @SpringBootTest
            @AutoConfigureMockRestServiceServer
            class RestClientTests {

                @Autowired private MockRestServiceServer server;
                @Autowired private PetsClient pets;
                @Autowired private AnimalsClient animals;

                @Test
                void theClientCallsTheContract() {
                    UUID request = UUID.randomUUID();
                    server.expect(requestTo("http://localhost:8080/pets?limit=5&status=sold")).andExpect(method(HttpMethod.GET))
                            .andExpect(header("X-Request-Id", request.toString())).andExpect(header("Cookie", "session=s1"))
                            .andRespond(withSuccess("[{\\"id\\":1,\\"name\\":\\"Rex\\",\\"status\\":\\"sold\\"}]", MediaType.APPLICATION_JSON));
                    // An optional parameter left out is not sent; the path's order, not the document's.
                    UUID visit = UUID.randomUUID();
                    server.expect(requestTo("http://localhost:8080/pets/5/visits/" + visit + "?page_size=10"))
                            .andExpect(method(HttpMethod.PUT)).andExpect(content().json("{\\"reason\\":\\"check\\",\\"follow-up\\":true}"))
                            .andRespond(withSuccess("{\\"ticket\\":\\"T-1\\"}", MediaType.APPLICATION_JSON));
                    // No response body.
                    server.expect(requestTo("http://localhost:8080/pets/9")).andExpect(method(HttpMethod.DELETE))
                            .andRespond(withNoContent());
                    // A union goes out with its discriminator.
                    server.expect(requestTo("http://localhost:8080/animals")).andExpect(method(HttpMethod.POST))
                            .andExpect(content().json("{\\"kind\\":\\"cat\\",\\"name\\":\\"Tom\\"}"))
                            .andRespond(withSuccess("{\\"code\\":\\"NORTH\\",\\"email\\":\\"a@b.co\\"}", MediaType.APPLICATION_JSON));
                    // (The mock server takes every expectation before the first call.)
                    List<Pet> found = pets.listPets(5, "sold", request, "s1");
                    assertThat(found).extracting(Pet::name).containsExactly("Rex");
                    assertThat(pets.recordVisit(visit, 5L, null, 10, null, null, new RecordVisitRequest("check", true)).ticket())
                            .isEqualTo("T-1");
                    pets.deletePetsPetId(9L);
                    Shelter shelter = animals.adopt(new Cat("cat", "Tom", null));
                    assertThat(shelter.code()).isEqualTo("NORTH");
                    server.verify();
                }
            }
            """;

    /** Adds a test class to a generated project before it is built. */
    private static void writeProjectTest(Path project, String packageName, String className, String source) throws Exception {
        Path file = project.resolve("src/test/java/" + packageName.replace('.', '/') + "/" + className + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** A plain or wizard backend from {@code /starter-wizard.zip}, unpacked: the project's own directory. */
    private Path postAndExtractWizard(Path workDir, Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> r = rest.postForEntity("/starter-wizard.zip", body, byte[].class);
        assertThat(r.getStatusCode())
                .as(r.getBody() == null ? "" : new String(r.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        for (Map.Entry<String, byte[]> e : FullstackExampleRequests.unzip(r.getBody()).entrySet()) {
            Path target = workDir.resolve(e.getKey()).normalize();
            assertThat(target.startsWith(workDir)).as("zip entry stays inside the work dir").isTrue();
            Files.createDirectories(target.getParent());
            Files.write(target, e.getValue());
        }
        Path project = workDir.resolve((String) body.get("artifactId"));
        assertThat(Files.isRegularFile(project.resolve("pom.xml"))).as("generated pom.xml").isTrue();
        return project;
    }

    private Path postAndExtractBackend(Path workDir, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> r = rest.exchange("/starter-fullstack.zip", HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);
        assertThat(r.getStatusCode())
                .as(r.getBody() == null ? "" : new String(r.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Path root = null;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(r.getBody()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path target = workDir.resolve(entry.getName()).normalize();
                assertThat(target.startsWith(workDir)).as("zip entry stays inside the work dir").isTrue();
                if (root == null) root = workDir.resolve(Path.of(entry.getName()).getName(0));
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zin, target);
                }
            }
        }
        assertThat(root).as("a non-empty zip").isNotNull();
        Path backend = root.resolve("backend");
        assertThat(Files.isRegularFile(backend.resolve("pom.xml"))).as("generated backend/pom.xml").isTrue();
        return backend;
    }

    private void runMvn(Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(MVN);
        cmd.addAll(List.of(args));
        log.info("[{}] $ {}", cwd.getFileName(), String.join(" ", cmd));
        // stdin from the null device, so nothing can wait on a prompt.
        Process proc = new ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectInput(NULL_DEVICE)
                .start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[{}]   {}", cwd.getFileName(), line);
            }
        }
        boolean done = proc.waitFor(20, TimeUnit.MINUTES);
        if (!done) {
            proc.destroyForcibly();
            throw new AssertionError("mvn " + String.join(" ", args) + " timed out after 20 minutes");
        }
        assertThat(proc.exitValue())
                .as("mvn " + String.join(" ", args) + " in " + cwd)
                .isZero();
    }
}
