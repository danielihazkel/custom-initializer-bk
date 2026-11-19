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
            entity.remove("access"); // rows that belong to users need ldap-auth too
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
        body.put("opts", Map.of("scaffold", List.of("audit", "history", "softDelete", "inverseCollections", "csvExport",
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"spring-jpa-crud", "spring-jpa-crud-lombok"})
    void aManyToManyIsReadAndChangedFromBothSidesOverTheRealApi(String backendSet, @TempDir Path workDir) throws Exception {
        // Tag.posts is the other side of Post.tags: read, filtered and saved from the tag too, a
        // bulk change of a post's tags, a tag deleted out of the posts that had it, and the demo
        // rows' links — with the project's own generated tests in the same build. On both backend
        // sets: the Lombok one tells rows apart by their values, which a set of rows must survive.
        Map<String, Object> body = FullstackExampleRequests.twoSidedManyToManyBody("react-tailwind-crud");
        body.put("backendTemplateSet", backendSet);
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        Path backend = postAndExtractBackend(workDir, body);
        writeLiveTest(backend, "com.menora.press", LIVE_TWO_SIDED_TESTS);
        runMvn(backend, "-B", "package");
    }

    /** Test methods run against the generated press backend, whose tags know their posts. */
    private static final String LIVE_TWO_SIDED_TESTS = """
                private static String j(String json) {
                    return json.replace('\\'', '"');
                }

                private java.util.List<Long> ids(String path, String field) throws Exception {
                    java.util.List<Long> out = new java.util.ArrayList<>();
                    for (JsonNode id : get(path).get(field)) out.add(id.asLong());
                    return out;
                }

                @Test
                void aSetIsReadAndChangedFromEitherSide() throws Exception {
                    // The demo posts came with tags, and the tags know those posts.
                    long demoPost = firstId("/api/posts");
                    java.util.List<Long> demoTags = ids("/api/posts/" + demoPost, "tagsIds");
                    assertThat(demoTags).isNotEmpty();
                    assertThat(ids("/api/tags/" + demoTags.get(0), "postsIds")).contains(demoPost);

                    long tag = send(post("/api/tags"), null, j("{'name':'fresh'}"), 200).get("id").asLong();
                    long a = send(post("/api/posts"), null, j("{'title':'A'}"), 200).get("id").asLong();
                    long b = send(post("/api/posts"), null, j("{'title':'B'}"), 200).get("id").asLong();

                    // From the tag: both posts get it.
                    send(put("/api/tags/" + tag), null, j("{'name':'fresh','postsIds':[" + a + "," + b + "]}"), 200);
                    assertThat(ids("/api/posts/" + a, "tagsIds")).containsExactly(tag);
                    assertThat(ids("/api/posts/" + b, "tagsIds")).containsExactly(tag);
                    assertThat(ids("/api/tags/" + tag, "postsIds")).containsExactlyInAnyOrder(a, b);
                    assertThat(get("/api/tags/" + tag).get("postsLabels").toString()).contains("A").contains("B");
                    // Either end filters by the other.
                    assertThat(get("/api/tags?postsId=" + a).get("totalElements").asInt()).isEqualTo(1);
                    assertThat(get("/api/posts?tagsId=" + tag).get("totalElements").asInt()).isEqualTo(2);

                    // A post left out from the tag's side loses it; no list at all leaves the links alone.
                    send(put("/api/tags/" + tag), null, j("{'name':'fresh','postsIds':[" + b + "]}"), 200);
                    assertThat(ids("/api/posts/" + a, "tagsIds")).isEmpty();
                    send(put("/api/tags/" + tag), null, j("{'name':'renamed'}"), 200);
                    assertThat(ids("/api/tags/" + tag, "postsIds")).containsExactly(b);
                    // …and what the post's own side saves is what the tag reads.
                    send(put("/api/posts/" + a), null, j("{'title':'A','tagsIds':[" + tag + "]}"), 200);
                    assertThat(ids("/api/tags/" + tag, "postsIds")).containsExactlyInAnyOrder(a, b);

                    // A tag made with its posts; a post that is not there is refused.
                    long second = send(post("/api/tags"), null, j("{'name':'second','postsIds':[" + a + "]}"), 200).get("id").asLong();
                    assertThat(ids("/api/posts/" + a, "tagsIds")).containsExactlyInAnyOrder(tag, second);
                    send(put("/api/tags/" + second), null, j("{'name':'second','postsIds':[987654]}"), 400);
                    assertThat(ids("/api/tags/" + second, "postsIds")).containsExactly(a);

                    // A bulk change puts the same tags on every picked post (none: the set is emptied).
                    mvc.perform(patch("/api/posts/bulk").contentType(MediaType.APPLICATION_JSON)
                                    .content(j("{'ids':[" + a + "," + b + "],'field':'tags','value':[" + second + "]}")))
                            .andExpect(status().is2xxSuccessful());
                    assertThat(ids("/api/posts/" + a, "tagsIds")).containsExactly(second);
                    assertThat(ids("/api/posts/" + b, "tagsIds")).containsExactly(second);
                    assertThat(ids("/api/tags/" + tag, "postsIds")).isEmpty();
                    mvc.perform(patch("/api/posts/bulk").contentType(MediaType.APPLICATION_JSON)
                                    .content(j("{'ids':[" + a + "],'field':'tags','value':[987654]}")))
                            .andExpect(status().isBadRequest());
                    mvc.perform(patch("/api/posts/bulk").contentType(MediaType.APPLICATION_JSON)
                                    .content(j("{'ids':[" + b + "],'field':'tags','value':null}")))
                            .andExpect(status().is2xxSuccessful());
                    assertThat(ids("/api/posts/" + b, "tagsIds")).isEmpty();
                    assertThat(ids("/api/posts/" + a, "tagsIds")).containsExactly(second);

                    // A tag deleted leaves the posts that had it (it used to be refused by the join table's key).
                    mvc.perform(delete("/api/tags/" + second)).andExpect(status().is2xxSuccessful());
                    assertThat(ids("/api/posts/" + a, "tagsIds")).isEmpty();
                    mvc.perform(delete("/api/tags/bulk").contentType(MediaType.APPLICATION_JSON)
                                    .content("[" + demoTags.get(0) + "]"))
                            .andExpect(status().is2xxSuccessful());
                    assertThat(ids("/api/posts/" + demoPost, "tagsIds")).doesNotContain(demoTags.get(0));
                }

            """;

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
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"postgresql", "mssql", "oracle"})
    void aBackendOnAnotherDatabasePassesItsOwnTestsOnH2(String database, @TempDir Path workDir) throws Exception {
        // Soft delete among the options: its SQL says true and false as the project's database does
        // (1 and 0 on SQL Server and Oracle), and H2 has to run that same SQL in these tests.
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        body.put("dependencies", List.of("web", "data-jpa", database, "validation", "flyway"));
        body.put("opts", Map.of("scaffold", List.of("audit", "softDelete", "optimisticLocking", "tests")));
        Path backend = postAndExtractBackend(workDir, body);
        runMvn(backend, "-B", "package");
        Path reports = backend.resolve("target/surefire-reports");
        assertThat(reports.resolve("com.menora.support.SupportApplicationTests.txt")).isRegularFile();
        assertThat(reports.resolve("com.menora.support.controller.OrderApiTest.txt")).isRegularFile();
        assertThat(Files.readString(reports.resolve("com.menora.support.controller.OrderApiTest.txt")))
                .contains("Failures: 0, Errors: 0, Skipped: 0");
    }

    /**
     * The fullstack {@code db-tests} profile resolves — Testcontainers' JDBC driver and the
     * database's module, Flyway's module on Boot 3.5 — and the tests compile under it. Running it
     * needs Docker: {@code LocalRunDockerSmokeTests}.
     */
    @ParameterizedTest(name = "{0} on Spring Boot {1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "postgresql, 3.2.1", "postgresql, " + FullstackGoldenOutputTests.BOOT_35,
            "oracle, " + FullstackGoldenOutputTests.BOOT_35, "mssql, " + FullstackGoldenOutputTests.BOOT_35})
    void theDbTestsProfileResolvesAndCompiles(String db, String boot, @TempDir Path workDir) throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        body.put("bootVersion", boot);
        body.put("dependencies", List.of("web", "data-jpa", db, "validation", "flyway"));
        body.put("opts", Map.of("scaffold", List.of("tests")));
        Path backend = postAndExtractBackend(workDir, body);
        assertThat(Files.readString(backend.resolve("pom.xml"))).contains("<id>db-tests</id>");
        runMvn(backend, "-B", "test-compile", "-Pdb-tests");
    }

    /**
     * The {@code testcontainers} dependency's sample test compiles with each database's container
     * and is skipped where Docker is not running — as in the image build, which runs the tests.
     */
    @ParameterizedTest(name = "Spring Boot {0}")
    @ValueSource(strings = {"3.2.1", FullstackGoldenOutputTests.BOOT_35})
    void theTestcontainersSampleTestCompilesAndWaitsForDocker(String bootVersion, @TempDir Path workDir) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "tcdemo");
        body.put("packageName", "com.menora.tcdemo");
        body.put("bootVersion", bootVersion);
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("web", "data-jpa", "postgresql", "oracle", "mssql", "flyway", "testcontainers"));
        body.put("opts", Map.of("postgresql", List.of("pg-primary"), "oracle", List.of("oracle-secondary"),
                "mssql", List.of("mssql-secondary")));
        Path project = postAndExtractWizard(workDir, body);
        assertThat(project.resolve("compose.yaml")).isRegularFile();
        runMvn(project, "-B", "test", "-Dtest=ContainerDatabaseTests");
        String report = Files.readString(project.resolve("target/surefire-reports/com.menora.tcdemo.ContainerDatabaseTests.txt"));
        // Skipped without Docker, run with it: either way nothing fails.
        assertThat(report).contains("Tests run: 1, Failures: 0, Errors: 0");
        log.info("ContainerDatabaseTests: {}", report.lines().filter(l -> l.startsWith("Tests run")).findFirst().orElse(""));
    }

    /**
     * The Orders example with its lines composed into the order (whatever the catalog's copy says)
     * and nothing of the order read from them: no total over the lines, no rule that it has one.
     * What a composition does on its own is what these builds hold it to; an order that reads its
     * lines is {@code liveApiOfABackendWithEveryOptBehaves}.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> composedOrders(String frontendSet) throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", frontendSet);
        List<Map<String, Object>> plain = new ArrayList<>();
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(entity);
            if ("Order".equals(copy.get("name"))) {
                copy.remove("rules");
                List<Map<String, Object>> fields = new ArrayList<>();
                for (Map<String, Object> field : (List<Map<String, Object>>) copy.get("fields")) {
                    Map<String, Object> entered = new java.util.LinkedHashMap<>(field);
                    entered.remove("formula");
                    fields.add(entered);
                }
                copy.put("fields", fields);
            }
            plain.add(copy);
        }
        body.put("entities", plain);
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
                void aRowIsComputedAndCheckedByItsOwnRulesInItsParentsSave() throws Exception {
                    long product = firstId("/api/products");
                    // The line total is computed on the parent's save, as on the line's own.
                    JsonNode made = send(post("/api/orders"), null, order("R-8", ",'lines':[{'quantity':2,'unitPrice':5,'lineTotal':999,'productId':"
                            + product + "},{'quantity':3,'productId':" + product + "}]"), 200);
                    assertThat(made.get("lines").get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("10");
                    assertThat(made.get("lines").get(1).get("lineTotal").isNull()).isTrue();
                    long id = made.get("id").asLong();
                    long first = made.get("lines").get(0).get("id").asLong();

                    // A row breaking its rule (a unit price of zero) stops the save, named by its place…
                    JsonNode refused = send(post("/api/orders"), null, order("R-9", ",'lines':[{'quantity':1,'productId':" + product
                            + "},{'quantity':1,'unitPrice':0,'productId':" + product + "}]"), 400);
                    assertThat(refused.get("errors").get("lines[1].unitPrice").asText()).isEqualTo("A unit price cannot be zero");
                    assertThat(get("/api/orders?q=R-9").get("totalElements").asInt()).isZero();
                    // …on an update too, where a kept row is recomputed from its new values.
                    long version = get("/api/orders/" + id).get("version").asLong();
                    send(put("/api/orders/" + id), null, order("R-8", ",'version':" + version + ",'lines':[{'id':" + first
                            + ",'quantity':1,'unitPrice':0,'productId':" + product + "}]"), 400);
                    JsonNode changed = send(put("/api/orders/" + id), null, order("R-8", ",'version':" + version + ",'lines':[{'id':" + first
                            + ",'quantity':4,'unitPrice':2.5,'productId':" + product + "}]"), 200);
                    assertThat(changed.get("lines").get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("10");
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
     * Rows that belong to users, over a real API with ldap-auth-rest and dev roles: two users and
     * an admin against a client (its account manager's — a text field), a visit (its creator's)
     * and the visit's composed items. Once with users kept to their own visits ({@code OWN}), once
     * seeing every visit and changing their own ({@code OWN_WRITES}). The project's own generated
     * tests run in the same build ({@code ClientApiTest.aUsersRowIsTheirOwn}).
     */
    @SuppressWarnings("unchecked")
    @ParameterizedTest(name = "visits: {0}")
    @ValueSource(strings = {"OWN", "OWN_WRITES"})
    void rowsBelongToTheirUsersOverTheRealApi(String visitRows, @TempDir Path workDir) throws Exception {
        Map<String, Object> body = FullstackExampleRequests.rowAccessBody(visitRows);
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "ldap-auth-rest"));
        // A client's files — one, and a list of several — are read by whose the client is.
        Map<String, Object> client = ((List<Map<String, Object>>) body.get("entities")).get(0);
        List<Map<String, Object>> fields = new java.util.ArrayList<>((List<Map<String, Object>>) client.get("fields"));
        fields.add(Map.of("name", "contract", "type", "FILE"));
        fields.add(Map.of("name", "photos", "type", "IMAGE", "maxFiles", 3));
        client.put("fields", fields);
        Path backend = postAndExtractBackend(workDir, body);
        writeProjectTest(backend, "com.menora.visits", "RowAccessTests",
                ROW_ACCESS_TESTS.replace("__OWN__", String.valueOf("OWN".equals(visitRows))));
        runMvn(backend, "-B", "package");
        assertThat(Files.exists(backend.resolve("target/surefire-reports/com.menora.visits.RowAccessTests.txt")))
                .as("the row access tests ran in the generated build").isTrue();
    }

    /**
     * The Approvals example as it ships — a claim belongs to whoever created it or holds it, with
     * its workflow, roles and ldap-auth-rest — builds, and its own generated tests pass: the
     * workflow's moves as an admin, and a user's claim kept from another user.
     */
    @org.junit.jupiter.api.Test
    void approvalsWithItsOwnedClaimsBuildsAndPassesItsTests(@TempDir Path workDir) throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("approvals", "react-tailwind-crud");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "ldap-auth-rest"));
        body.put("opts", Map.of("scaffold", List.of("audit", "optimisticLocking", "csvExport", "seedData", "tests")));
        Path backend = postAndExtractBackend(workDir, body);
        assertThat(Files.readString(backend.resolve("src/test/java/com/menora/support/controller/ExpenseClaimApiTest.java")))
                .contains("void aUsersRowIsTheirOwn()");
        runMvn(backend, "-B", "package");
    }

    /** Run inside the generated backend: ann and ben are users, boss an admin. */
    private static final String ROW_ACCESS_TESTS = """
            package com.menora.visits;

            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.MediaType;
            import org.springframework.test.web.servlet.MockMvc;
            import org.springframework.test.web.servlet.MvcResult;
            import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

            import java.util.LinkedHashMap;
            import java.util.List;
            import java.util.Map;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            @SpringBootTest(properties = {
                    "app.security.dev-roles.boss=ADMIN",
                    "app.security.dev-roles.ann=USER",
                    "app.security.dev-roles.ben=USER"})
            @AutoConfigureMockMvc
            class RowAccessTests {

                /** Users see only their own visits (else: every visit, and change their own). */
                private static final boolean OWN = __OWN__;

                @Autowired MockMvc mvc;

                private final ObjectMapper json = new ObjectMapper();

                private JsonNode call(String user, MockHttpServletRequestBuilder call, Object body, int expected) throws Exception {
                    call.header("userinfo", user);
                    if (body != null) call.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
                    byte[] answer = mvc.perform(call).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray();
                    return answer.length == 0 ? json.nullNode() : json.readTree(answer);
                }

                /** An export is written after the request's thread has gone: the rows are read on another. */
                private String export(String user, String path) throws Exception {
                    MvcResult started = mvc.perform(get(path).header("userinfo", user))
                            .andExpect(request().asyncStarted()).andReturn();
                    return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                }

                private static long total(JsonNode page) {
                    return page.has("totalElements") ? page.get("totalElements").asLong() : page.get("page").get("totalElements").asLong();
                }

                private JsonNode client(String user, String name) throws Exception {
                    return call(user, post("/api/clients"), Map.of("name", name), 200);
                }

                private static Map<String, Object> bulk(long id, String field, Object value) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("ids", List.of(id));
                    change.put("field", field);
                    change.put("value", value);
                    return change;
                }

                @Test
                void aClientIsItsAccountManagersAlone() throws Exception {
                    JsonNode mine = client("ann", "Acme of Ann");
                    // Left empty, the owner field is whoever made the row.
                    assertThat(mine.get("accountManager").asText()).isEqualTo("ann");
                    long id = mine.get("id").asLong();
                    String path = "/api/clients/" + id;

                    call("ann", get(path), null, 200);
                    call("ben", get(path), null, 404);
                    call("boss", get(path), null, 200);

                    JsonNode annsList = call("ann", get("/api/clients?size=200"), null, 200);
                    assertThat(annsList.get("content")).isNotEmpty()
                            .allSatisfy(row -> assertThat(row.get("accountManager").asText()).isEqualTo("ann"));
                    assertThat(call("ben", get("/api/clients?size=200&q=Acme of Ann"), null, 200).get("content")).isEmpty();
                    // The admin's list has the demo rows too.
                    assertThat(total(call("boss", get("/api/clients"), null, 200))).isGreaterThan(total(annsList));

                    // A row a user makes is theirs: not somebody else's.
                    call("ann", post("/api/clients"), Map.of("name", "For Ben", "accountManager", "ben"), 403);
                    call("boss", post("/api/clients"), Map.of("name", "Given to Ben", "accountManager", "ben"), 200);

                    // To anyone else the row is not there to change either — one at a time or in bulk.
                    call("ben", put(path), mine, 404);
                    call("ben", delete(path), null, 404);
                    call("ben", patch("/api/clients/bulk"), bulk(id, "city", "Elsewhere"), 404);
                    call("ben", delete("/api/clients/bulk"), List.of(id), 404);
                    call("ann", patch("/api/clients/bulk"), bulk(id, "city", "Haifa"), 204);
                    assertThat(call("ann", get(path), null, 200).get("city").asText()).isEqualTo("Haifa");

                    // The exports are over the caller's rows, though they are read on another thread.
                    assertThat(export("ann", "/api/clients/export.csv")).contains("Acme of Ann");
                    assertThat(export("ben", "/api/clients/export.csv")).doesNotContain("Acme of Ann").doesNotContain("user3");
                    assertThat(export("boss", "/api/clients/export.csv")).contains("Acme of Ann").contains("Given to Ben");
                    assertThat(export("ben", "/api/clients/export.xlsx")).isNotEmpty();

                    // Deleted softly, it is its owner's to bring back.
                    call("ann", delete(path), null, 204);
                    call("ben", post(path + "/restore"), null, 404);
                    call("ann", post(path + "/restore"), null, 200);
                    call("ann", get(path), null, 200);
                }

                private String upload(String user, String name) throws Exception {
                    org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                            "file", name, "text/plain", ("File " + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    byte[] answer = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .multipart("/api/attachments").file(file).header("userinfo", user))
                            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
                    return json.readTree(answer).get("id").asText();
                }

                @Test
                void aClientsFilesAreReadByWhoseTheClientIs() throws Exception {
                    String contract = upload("ann", "contract.txt");
                    String photo = upload("ann", "photo.txt");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", "Client with files");
                    row.put("contract", contract);
                    row.put("photos", List.of(Map.of("id", photo)));
                    call("ann", post("/api/clients"), row, 200);
                    for (String file : List.of(contract, photo)) {
                        mvc.perform(get("/api/attachments/" + file).header("userinfo", "ann")).andExpect(status().isOk());
                        // Ben may read clients — his own: this one's files are not his to read.
                        mvc.perform(get("/api/attachments/" + file).header("userinfo", "ben")).andExpect(status().isForbidden());
                        mvc.perform(get("/api/attachments/" + file).header("userinfo", "boss")).andExpect(status().isOk());
                    }
                }

                @Test
                void anImportedRowIsItsImporters() throws Exception {
                    JsonNode refused = call("ann", post("/api/clients/import"),
                            List.of(Map.of("name", "Imported one"), Map.of("name", "Imported two", "accountManager", "ben")), 422);
                    assertThat(refused.get("errors")).hasSize(1);
                    assertThat(refused.get("errors").get(0).get("row").asInt()).isEqualTo(2);
                    call("ann", post("/api/clients/import"), List.of(Map.of("name", "Imported one"), Map.of("name", "Imported two")), 200);
                    assertThat(call("ann", get("/api/clients?q=Imported"), null, 200).get("content")).hasSize(2)
                            .allSatisfy(row -> assertThat(row.get("accountManager").asText()).isEqualTo("ann"));
                    assertThat(call("ben", get("/api/clients?q=Imported"), null, 200).get("content")).isEmpty();
                }

                @Test
                void aVisitIsItsCreators() throws Exception {
                    long annsClient = client("ann", "Client of Ann").get("id").asLong();
                    long bensClient = client("ben", "Client of Ben").get("id").asLong();

                    Map<String, Object> first = new LinkedHashMap<>();
                    first.put("summary", "First visit of Ann");
                    first.put("clientId", annsClient);
                    first.put("items", List.of(Map.of("note", "Bring samples")));
                    JsonNode visit = call("ann", post("/api/visits"), first, 200);
                    assertThat(visit.get("createdBy").asText()).isEqualTo("ann");
                    long id = visit.get("id").asLong();
                    String path = "/api/visits/" + id;

                    // A client the caller may not see is no client to link to — on a new visit or an old one.
                    JsonNode unseen = call("ann", post("/api/visits"), Map.of("summary", "Poaching", "clientId", bensClient), 400);
                    assertThat(unseen.get("detail").asText()).contains("clientId: no Client " + bensClient);
                    Map<String, Object> moved = new LinkedHashMap<>(first);
                    moved.remove("items");
                    moved.put("clientId", bensClient);
                    call("ann", put(path), moved, 400);
                    moved.put("clientId", annsClient);
                    moved.put("summary", "First visit of Ann, again");
                    call("ann", put(path), moved, 200);

                    JsonNode read = call("ann", get(path), null, 200);
                    long item = read.get("items").get(0).get("id").asLong();
                    String itemPath = "/api/visit-items/" + item;

                    if (OWN) {
                        call("ben", get(path), null, 404);
                        call("ben", put(path), moved, 404);
                        call("ben", delete(path), null, 404);
                        call("ben", get(path + "/history"), null, 404);
                        assertThat(call("ben", get("/api/visits?size=200"), null, 200).get("content")).isEmpty();
                        // A user's numbers are over their own rows.
                        assertThat(call("ben", get("/api/visits/stats"), null, 200).get("total").asLong()).isZero();
                        assertThat(call("ann", get("/api/visits/stats"), null, 200).get("total").asLong())
                                .isEqualTo(total(call("ann", get("/api/visits"), null, 200)));
                        assertThat(export("ben", "/api/visits/export.csv")).doesNotContain("First visit of Ann");
                        // The visit's items follow it.
                        call("ben", get(itemPath), null, 404);
                        assertThat(call("ben", get("/api/visit-items?size=200"), null, 200).get("content")).isEmpty();
                        call("ben", post("/api/visit-items"), Map.of("note", "Slipped in", "visitId", id), 400);
                    } else {
                        call("ben", get(path), null, 200);
                        assertThat(total(call("ben", get("/api/visits"), null, 200))).isGreaterThan(1);
                        call("ben", put(path), moved, 403);
                        call("ben", delete(path), null, 403);
                        call("ben", patch("/api/visits/bulk"), bulk(id, "outcome", "WON"), 403);
                        call("ben", delete("/api/visits/bulk"), List.of(id), 403);
                        call("ben", get(path + "/history"), null, 200);
                        // The visit's items: read by all, changed by the visit's owner.
                        call("ben", get(itemPath), null, 200);
                        call("ben", post("/api/visit-items"), Map.of("note", "Slipped in", "visitId", id), 403);
                        call("ben", put(itemPath), Map.of("note", "Rewritten", "visitId", id), 403);
                        call("ben", delete(itemPath), null, 403);
                    }
                    assertThat(call("ann", get(path + "/history"), null, 200)).isNotEmpty();
                    call("ann", post("/api/visit-items"), Map.of("note", "One more", "visitId", id), 200);
                    call("ann", put(itemPath), Map.of("note", "Bring two samples", "visitId", id), 200);
                    assertThat(call("ann", get(path), null, 200).get("items")).hasSize(2);

                    // An admin is not limited: reads, changes, deletes.
                    call("boss", get(path), null, 200);
                    call("boss", patch("/api/visits/bulk"), bulk(id, "outcome", "WON"), 204);
                    call("boss", delete(path), null, 204);
                    // Deleted softly: its owner brings it back, another user does not.
                    call("ben", post(path + "/restore"), null, 404);
                    call("ann", post(path + "/restore"), null, 200);
                }
            }
            """;

    /**
     * Uploaded files over a real API with ldap-auth-rest: a file is read with its record's read
     * level, a record takes only a free file its caller uploaded, a replaced file is gone, the
     * clean-up deletes what no record holds — once with the files in the database and once in a
     * folder, where the bytes on disk are checked too. The project's own API tests run alongside
     * (their rows upload files and attach them, through the same checks).
     */
    @org.junit.jupiter.api.Test
    void attachmentsFollowTheirRecordsOverTheRealApi(@TempDir Path workDir) throws Exception {
        Map<String, Object> id = Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true);
        Map<String, Object> payslip = new LinkedHashMap<>();
        payslip.put("name", "Payslip");
        payslip.put("fields", List.of(id, Map.of("name", "title", "type", "STRING", "required", true),
                Map.of("name", "slip", "type", "FILE")));
        payslip.put("access", Map.of("read", "ADMIN", "write", "ADMIN"));
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("name", "Note");
        note.put("fields", List.of(id, Map.of("name", "text", "type", "STRING"), Map.of("name", "picture", "type", "IMAGE")));
        note.put("access", Map.of("read", "SIGNED_IN", "write", "USER"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "files");
        body.put("packageName", "com.menora.files");
        body.put("bootVersion", "3.2.1");
        body.put("frontendTemplateSet", "react-tailwind-crud");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "ldap-auth-rest"));
        body.put("opts", Map.of("scaffold", List.of("seedData", "tests", "softDelete")));
        body.put("entities", List.of(payslip, note));

        Path backend = postAndExtractBackend(workDir, body);
        writeProjectTest(backend, "com.menora.files", "AttachmentAccessTests", ATTACHMENT_TESTS);
        writeProjectTest(backend, "com.menora.files", "AttachmentFolderTests", ATTACHMENT_FOLDER_TESTS);
        runMvn(backend, "-B", "package");
        try (var reports = Files.list(backend.resolve("target/surefire-reports"))) {
            List<String> ran = reports.map(p -> p.getFileName().toString()).toList();
            assertThat(ran).as("the tests that ran in the generated build")
                    .contains("com.menora.files.AttachmentAccessTests.txt", "com.menora.files.AttachmentFolderTests.txt")
                    .anyMatch(name -> name.endsWith("PayslipApiTest.txt"));
        }
    }

    /**
     * A field that holds several files, over a real API on the schema its Flyway baseline makes
     * ({@code ddl-auto: validate}, so the element collection must match the files table): files
     * attached in order and named, a removed one gone, too many refused, a required one asked for.
     */
    @org.junit.jupiter.api.Test
    void severalFilesInAFieldOverTheRealApi(@TempDir Path workDir) throws Exception {
        Path backend = postAndExtractBackend(workDir, FullstackMultiFileTests.body());
        writeProjectTest(backend, "com.menora.album", "SeveralFilesTests", SEVERAL_FILES_TESTS);
        runMvn(backend, "-B", "package");
        try (var reports = Files.list(backend.resolve("target/surefire-reports"))) {
            assertThat(reports.map(p -> p.getFileName().toString()).toList())
                    .contains("com.menora.album.SeveralFilesTests.txt")
                    .anyMatch(name -> name.endsWith("TripApiTest.txt"));
        }
    }

    private static final String SEVERAL_FILES_TESTS = """
            package com.menora.album;

            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.menora.album.attachment.Attachments;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.MediaType;
            import org.springframework.mock.web.MockMultipartFile;
            import org.springframework.test.web.servlet.MockMvc;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            @SpringBootTest
            @AutoConfigureMockMvc
            class SeveralFilesTests {

                @Autowired MockMvc mvc;
                @Autowired Attachments attachments;
                private final ObjectMapper json = new ObjectMapper();

                private String upload(String name) throws Exception {
                    MockMultipartFile file = new MockMultipartFile("file", name, "text/plain", ("bytes of " + name).getBytes());
                    String body = mvc.perform(multipart("/api/attachments").file(file))
                            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                    return json.readTree(body).get("id").asText();
                }

                private static String list(String... ids) {
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < ids.length; i++) sb.append(i == 0 ? "" : ",").append("{\\"id\\":\\"").append(ids[i]).append("\\"}");
                    return sb.append("]").toString();
                }

                @Test
                void filesAreAttachedInOrderNamedAndForgottenWhenRemoved() throws Exception {
                    String a = upload("a.txt"), b = upload("b.txt"), ticket = upload("ticket.txt");
                    String answer = mvc.perform(post("/api/trips").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"title\\":\\"Rome\\",\\"photos\\":" + list(b, a) + ",\\"tickets\\":" + list(ticket) + "}"))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                    JsonNode trip = json.readTree(answer);
                    long id = trip.get("id").asLong();
                    // In the order sent, with the names they were uploaded under (not sent by the client).
                    assertThat(trip.get("photos").get(0).get("id").asText()).isEqualTo(b);
                    assertThat(trip.get("photos").get(0).get("name").asText()).isEqualTo("b.txt");
                    assertThat(trip.get("photos").get(1).get("name").asText()).isEqualTo("a.txt");
                    mvc.perform(get("/api/trips/" + id)).andExpect(jsonPath("$.photos.length()").value(2));

                    // A file another record holds cannot be taken.
                    mvc.perform(post("/api/trips").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"title\\":\\"Copy\\",\\"photos\\":" + list(a) + ",\\"tickets\\":" + list(upload("t2.txt")) + "}"))
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.errors.photos").exists());

                    // Removed from the list: gone once the save commits.
                    mvc.perform(put("/api/trips/" + id).contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"id\\":" + id + ",\\"title\\":\\"Rome\\",\\"photos\\":" + list(a) + ",\\"tickets\\":" + list(ticket) + "}"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.photos.length()").value(1));
                    mvc.perform(get("/api/attachments/" + b)).andExpect(status().isNotFound());
                    mvc.perform(get("/api/attachments/" + a)).andExpect(status().isOk());

                    // No list: the files stay as they are.
                    mvc.perform(put("/api/trips/" + id).contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"id\\":" + id + ",\\"title\\":\\"Rome again\\"}"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.photos.length()").value(1));
                    // The clean-up leaves what a list holds alone.
                    attachments.removeUnattached();
                    mvc.perform(get("/api/attachments/" + a)).andExpect(status().isOk());
                }

                @Test
                void tooManyFilesAndNoneForARequiredFieldAreRefused() throws Exception {
                    String[] six = {upload("1"), upload("2"), upload("3"), upload("4"), upload("5"), upload("6")};
                    mvc.perform(post("/api/trips").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"title\\":\\"Many\\",\\"photos\\":" + list(six) + ",\\"tickets\\":" + list(upload("t")) + "}"))
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.errors.photos").value("At most 5 files"));
                    mvc.perform(post("/api/trips").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"title\\":\\"None\\",\\"tickets\\":[]}"))
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.errors.tickets").value("Add at least one file"));
                }
            }
            """;

    /** Run inside the generated backend, files in the database (the default storage). */
    private static final String ATTACHMENT_TESTS = """
            package com.menora.files;

            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.menora.files.attachment.Attachments;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.MediaType;
            import org.springframework.mock.web.MockMultipartFile;
            import org.springframework.test.web.servlet.MockMvc;
            import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            @SpringBootTest(properties = {
                    "app.security.dev-roles.boss=ADMIN",
                    "app.security.dev-roles.clerk=USER",
                    "app.security.dev-roles.guest=",
                    // Every upload no record holds is old enough for the clean-up at once.
                    "app.attachments.keep-unattached=1ms"})
            @AutoConfigureMockMvc
            class AttachmentAccessTests {

                @Autowired MockMvc mvc;
                @Autowired Attachments attachments;
                private final ObjectMapper json = new ObjectMapper();

                private static MockHttpServletRequestBuilder as(String user, MockHttpServletRequestBuilder request) {
                    return request.header("userinfo", user);
                }

                private String upload(String user, String name) throws Exception {
                    MockMultipartFile file = new MockMultipartFile("file", name, "text/plain", ("bytes of " + name).getBytes());
                    String body = mvc.perform(as(user, multipart("/api/attachments").file(file)))
                            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                    return json.readTree(body).get("id").asText();
                }

                private long create(String user, String path, String body) throws Exception {
                    String answer = mvc.perform(as(user, post(path).contentType(MediaType.APPLICATION_JSON).content(body)))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                    return json.readTree(answer).get("id").asLong();
                }

                @Test
                void aFileOnNoRecordIsItsUploadersAlone() throws Exception {
                    String file = upload("clerk", "draft.txt");
                    mvc.perform(as("clerk", get("/api/attachments/" + file))).andExpect(status().isOk());
                    mvc.perform(as("boss", get("/api/attachments/" + file))).andExpect(status().isNotFound());
                    mvc.perform(get("/api/attachments/" + file)).andExpect(status().isUnauthorized());
                }

                @Test
                void aFileOnARecordTakesTheRecordsReadLevel() throws Exception {
                    String slip = upload("boss", "slip.txt");
                    long payslip = create("boss", "/api/payslips", "{\\"title\\":\\"March\\",\\"slip\\":\\"" + slip + "\\"}");
                    mvc.perform(as("boss", get("/api/attachments/" + slip))).andExpect(status().isOk());
                    mvc.perform(as("clerk", get("/api/attachments/" + slip))).andExpect(status().isForbidden());
                    mvc.perform(as("clerk", get("/api/attachments/" + slip + "/info"))).andExpect(status().isForbidden());

                    // Notes are read by anyone signed in, so their pictures are too.
                    String picture = upload("clerk", "pic.txt");
                    create("clerk", "/api/notes", "{\\"text\\":\\"hi\\",\\"picture\\":\\"" + picture + "\\"}");
                    mvc.perform(as("guest", get("/api/attachments/" + picture))).andExpect(status().isOk());

                    // A soft-deleted payslip still holds its file, for a restore.
                    mvc.perform(as("boss", delete("/api/payslips/" + payslip))).andExpect(status().is2xxSuccessful());
                    attachments.removeUnattached();
                    mvc.perform(as("boss", get("/api/attachments/" + slip))).andExpect(status().isOk());
                }

                @Test
                void aRecordTakesOnlyAFreeFileItsCallerUploaded() throws Exception {
                    String bosses = upload("boss", "boss.txt");
                    mvc.perform(as("clerk", post("/api/notes").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"text\\":\\"mine\\",\\"picture\\":\\"" + bosses + "\\"}")))
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.errors.picture").value("Upload the file yourself to attach it"));

                    String slip = upload("boss", "april.txt");
                    create("boss", "/api/payslips", "{\\"title\\":\\"April\\",\\"slip\\":\\"" + slip + "\\"}");
                    mvc.perform(as("boss", post("/api/payslips").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"title\\":\\"Copy\\",\\"slip\\":\\"" + slip + "\\"}")))
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.errors.slip").value("This file belongs to another record: upload a copy of it"));

                    mvc.perform(as("boss", post("/api/payslips").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"title\\":\\"Gone\\",\\"slip\\":\\"00000000-0000-0000-0000-000000000001\\"}")))
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.errors.slip").value("The file is no longer there: upload it again"));
                }

                @Test
                void aPictureHasASmallVersionAndOtherFilesAreServedAsTheyAre() throws Exception {
                    java.awt.image.BufferedImage big = new java.awt.image.BufferedImage(1200, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(big, "png", png);
                    MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", png.toByteArray());
                    String id = json.readTree(mvc.perform(as("clerk", multipart("/api/attachments").file(file).param("kind", "image")))
                            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

                    for (int time = 0; time < 2; time++) { // made once, then the kept one
                        byte[] small = mvc.perform(as("clerk", get("/api/attachments/" + id + "/thumbnail")))
                                .andExpect(status().isOk())
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/png"))
                                .andReturn().getResponse().getContentAsByteArray();
                        java.awt.image.BufferedImage read = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(small));
                        assertThat(read.getWidth()).isEqualTo(320);
                        assertThat(read.getHeight()).isEqualTo(213);
                    }
                    // The same read check as the file: somebody else's upload on no record.
                    mvc.perform(as("boss", get("/api/attachments/" + id + "/thumbnail"))).andExpect(status().isNotFound());

                    // Not a picture: the file itself.
                    String text = upload("clerk", "notes.txt");
                    mvc.perform(as("clerk", get("/api/attachments/" + text + "/thumbnail")))
                            .andExpect(status().isOk())
                            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("bytes of notes.txt"));
                }

                @Test
                void aReplacedFileIsGoneAndTheCleanUpTakesWhatNoRecordHolds() throws Exception {
                    String first = upload("boss", "first.txt");
                    long id = create("boss", "/api/payslips", "{\\"title\\":\\"May\\",\\"slip\\":\\"" + first + "\\"}");
                    String second = upload("boss", "second.txt");
                    mvc.perform(as("boss", put("/api/payslips/" + id).contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"id\\":" + id + ",\\"title\\":\\"May\\",\\"slip\\":\\"" + second + "\\"}")))
                            .andExpect(status().isOk());
                    mvc.perform(as("boss", get("/api/attachments/" + first))).andExpect(status().isNotFound());
                    mvc.perform(as("boss", get("/api/attachments/" + second))).andExpect(status().isOk());

                    String abandoned = upload("boss", "abandoned.txt");
                    Thread.sleep(20);
                    assertThat(attachments.removeUnattached()).isGreaterThanOrEqualTo(1);
                    mvc.perform(as("boss", get("/api/attachments/" + abandoned))).andExpect(status().isNotFound());
                    mvc.perform(as("boss", get("/api/attachments/" + second))).andExpect(status().isOk());
                }
            }
            """;

    /** Run inside the generated backend, files in a folder: the bytes on disk come and go with them. */
    private static final String ATTACHMENT_FOLDER_TESTS = """
            package com.menora.files;

            import com.fasterxml.jackson.databind.ObjectMapper;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.MediaType;
            import org.springframework.mock.web.MockMultipartFile;
            import org.springframework.test.web.servlet.MockMvc;

            import java.nio.file.Files;
            import java.nio.file.Path;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            @SpringBootTest(properties = {
                    "app.security.dev-roles.boss=ADMIN",
                    "app.attachments.storage=filesystem",
                    "app.attachments.directory=target/attachment-folder"})
            @AutoConfigureMockMvc
            class AttachmentFolderTests {

                @Autowired MockMvc mvc;
                private final ObjectMapper json = new ObjectMapper();

                private String upload(String name, String text) throws Exception {
                    MockMultipartFile file = new MockMultipartFile("file", name, "text/plain", text.getBytes());
                    String body = mvc.perform(multipart("/api/attachments").file(file).header("userinfo", "boss"))
                            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                    return json.readTree(body).get("id").asText();
                }

                private static Path onDisk(String id) {
                    return Path.of("target/attachment-folder", id.substring(0, 2), id);
                }

                @Test
                void theBytesLiveInTheFolderAndGoWithTheFile() throws Exception {
                    String first = upload("one.txt", "first version");
                    assertThat(onDisk(first)).hasContent("first version");
                    String answer = mvc.perform(post("/api/payslips").header("userinfo", "boss").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"title\\":\\"June\\",\\"slip\\":\\"" + first + "\\"}"))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                    long id = json.readTree(answer).get("id").asLong();
                    mvc.perform(get("/api/attachments/" + first).header("userinfo", "boss"))
                            .andExpect(status().isOk()).andExpect(content().string("first version"));

                    // Replaced: the old bytes are deleted once the save commits.
                    String second = upload("two.txt", "second version");
                    mvc.perform(put("/api/payslips/" + id).header("userinfo", "boss").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"id\\":" + id + ",\\"title\\":\\"June\\",\\"slip\\":\\"" + second + "\\"}"))
                            .andExpect(status().isOk());
                    assertThat(Files.exists(onDisk(first))).isFalse();
                    assertThat(onDisk(second)).hasContent("second version");
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
            body.put("opts", Map.of("scaffold", List.of("audit", "history", "softDelete", "inverseCollections", "csvExport",
                    "xlsxExport", "savedViews", "csvImport", "bulkDelete", "bulkUpdate", "optimisticLocking",
                    "seedData", "tests")));
            liveTests = LIVE_ORDERS_TESTS;
        } else if (model.equals("approvals")) {
            body.put("opts", Map.of("scaffold", List.of("audit", "history", "events", "optimisticLocking", "csvExport", "csvImport",
                    "bulkUpdate", "seedData", "tests")));
            liveTests = LIVE_APPROVALS_TESTS + LIVE_HISTORY_TESTS;
        } else {
            body.put("opts", Map.of("scaffold", List.of("audit", "softDelete", "optimisticLocking", "seedData", "tests")));
        }
        // Roles need ldap-auth, whose artifact is in the company repository.
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            entity.remove("access"); // rows that belong to users need ldap-auth too
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
        body.put("opts", Map.of("scaffold", List.of("audit", "history", "softDelete", "inverseCollections", "csvExport", "xlsxExport", "savedViews",
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
        body.put("opts", Map.of("scaffold", List.of("audit", "history", "optimisticLocking", "csvExport", "csvImport", "bulkUpdate", "seedData", "tests")));
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            entity.remove("access"); // rows that belong to users need ldap-auth too
            Map<String, Object> workflow = (Map<String, Object>) entity.get("workflow");
            if (workflow == null) continue;
            for (Map<String, Object> t : (List<Map<String, Object>>) workflow.get("transitions")) t.remove("roles");
        }
        Path backend = postAndExtractBackend(workDir, body);
        writeLiveTest(backend, "com.menora.support", LIVE_APPROVALS_TESTS + LIVE_HISTORY_TESTS);
        runMvn(backend, "-B", "package");
    }

    @org.junit.jupiter.api.Test
    void liveApiOfTheFieldModelBehaves(@TempDir Path workDir) throws Exception {
        // Projects, whose tasks are unique per project and title, on its Flyway baseline (the
        // constraint is the migration's, and Hibernate validates against it), with demo rows and
        // the generated API tests — which make many tasks, and must not clash — and its rules
        // (dates in order, an estimate on a finished task), which the tests' rows must pass.
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("projects", "react-tailwind-crud");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "flyway"));
        body.put("opts", Map.of("scaffold", List.of("seedData", "tests")));
        Path backend = postAndExtractBackend(workDir, body);
        writeLiveTest(backend, "com.menora.support", FIELD_MODEL_LIVE_TESTS);
        runMvn(backend, "-B", "package");
    }

    @org.junit.jupiter.api.Test
    void liveApiOfTheFixSweepBehaves(@TempDir Path workDir) throws Exception {
        // Round 14's fix sweep, run: Projects with soft delete (a deleted task still holding its
        // unique key), the change history (a version put back, checked first) and the Excel export
        // (dates as date cells) — and the generated tests, over a project that now compiles all of it.
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("projects", "react-tailwind-crud");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("opts", Map.of("scaffold", List.of("audit", "history", "softDelete", "csvExport", "xlsxExport", "seedData", "tests")));
        Path backend = postAndExtractBackend(workDir, body);
        writeLiveTest(backend, "com.menora.support", FIX_SWEEP_LIVE_TESTS);
        runMvn(backend, "-B", "package");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"h2, schema by hibernate", "h2, schema by flyway", "postgresql, its tests on h2"})
    @SuppressWarnings("unchecked")
    void keywordNamedTablesAndColumnsBuildAndBehave(String variant, @TempDir Path workDir) throws Exception {
        // Tables called values and user, columns called key, order, user and value: quoted in the
        // mapping, in the SQL the mapping carries (soft delete, the restore, the deleted-row probe)
        // and — with Flyway — in the baseline Hibernate then validates against. The generated API
        // tests create, read, search, change, delete and restore rows of both entities.
        // On PostgreSQL fewer of the words are keywords: those stay bare, and the project's tests,
        // which run on H2, tell H2 so (NON_KEYWORDS) — the same tests have to pass there.
        Map<String, Object> body = FullstackExampleRequests.keywordNamesBody();
        List<String> deps = (List<String>) body.get("dependencies");
        if (variant.startsWith("postgresql")) deps.replaceAll(d -> d.equals("h2") ? "postgresql" : d);
        if (!variant.endsWith("hibernate")) deps.add("flyway");
        Path backend = postAndExtractBackend(workDir, body);
        if (variant.equals("h2, schema by flyway")) {
            assertThat(Files.readString(backend.resolve("src/main/resources/db/migration/V1__init.sql")))
                    .contains("create table \"VALUES\" (").contains("\"ORDER\" integer");
        }
        runMvn(backend, "-B", "package");
        Path reports = backend.resolve("target/surefire-reports");
        for (String test : List.of("SettingApiTest", "BatchApiTest")) {
            assertThat(Files.readString(reports.resolve("com.menora.words.controller." + test + ".txt")))
                    .contains("Failures: 0, Errors: 0, Skipped: 0");
        }
    }

    /** Added to the Projects backend with soft delete, history and the Excel export. */
    private static final String FIX_SWEEP_LIVE_TESTS = """
                @Test
                void aDeletedTaskThatHoldsAKeySaysSoAndComesBack() throws Exception {
                    long project = firstId("/api/projects");
                    String task = "{\\"title\\": \\"Held by a deleted one\\", \\"status\\": \\"TODO\\", \\"projectId\\": " + project + "}";
                    long id = send(post("/api/tasks"), null, task, 200).get("id").asLong();
                    mvc.perform(delete("/api/tasks/" + id)).andExpect(status().is2xxSuccessful());

                    JsonNode held = send(post("/api/tasks"), null, task, 409);
                    assertThat(held.get("detail").asText()).isEqualTo("A deleted Task has the same Project and Title");
                    assertThat(held.get("deleted").get("id").asLong()).isEqualTo(id);
                    assertThat(held.get("errors").get("title").asText()).isEqualTo("A deleted Task has the same Project and Title");
                    assertThat(held.get("errors").get("projectId").asText()).isEqualTo("A deleted Task has the same Project and Title");

                    // An edit into the deleted task's values is told the same.
                    long other = send(post("/api/tasks"), null, task.replace("Held by a deleted one", "Another"), 200).get("id").asLong();
                    assertThat(send(put("/api/tasks/" + other), null, task, 409).get("deleted").get("id").asLong()).isEqualTo(id);

                    // The deleted task comes back with its values; a second one is then a duplicate of a row that shows.
                    mvc.perform(post("/api/tasks/" + id + "/restore")).andExpect(status().isOk());
                    assertThat(send(post("/api/tasks"), null, task, 409).get("detail").asText())
                            .isEqualTo("Another Task has the same Project and Title");
                }

                @Test
                void aVersionPutBackAnswersTheRowAndWhatItCouldNotPutBack() throws Exception {
                    long project = firstId("/api/projects");
                    String task = "{\\"title\\": \\"%s\\", \\"status\\": \\"TODO\\", \\"projectId\\": " + project + "}";
                    long id = send(post("/api/tasks"), null, task.formatted("First name"), 200).get("id").asLong();
                    send(put("/api/tasks/" + id), null, task.formatted("Second name"), 200);
                    JsonNode changes = get("/api/tasks/" + id + "/history");
                    long created = changes.get(changes.size() - 1).get("id").asLong();
                    JsonNode restored = send(post("/api/tasks/" + id + "/history/" + created + "/restore"), null, "{}", 200);
                    assertThat(restored.get("row").get("title").asText()).isEqualTo("First name");
                    assertThat(restored.get("skipped")).isEmpty();
                    assertThat(get("/api/tasks/" + id).get("title").asText()).isEqualTo("First name");
                }

                @Test
                void theWorkbookKeepsADateAsADate() throws Exception {
                    long project = firstId("/api/projects");
                    send(post("/api/tasks"), null, "{\\"title\\": \\"Dated for the sheet\\", \\"status\\": \\"TODO\\", \\"dueOn\\": \\"2026-05-10\\", \\"projectId\\": " + project + "}", 200);
                    var started = mvc.perform(MockMvcRequestBuilders.get("/api/tasks/export.xlsx").param("q", "Dated for the sheet")).andReturn();
                    byte[] workbook = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                    java.util.Map<String, String> parts = new java.util.LinkedHashMap<>();
                    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(workbook))) {
                        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                            parts.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                    for (String xml : parts.values()) {
                        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                                .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
                    }
                    String sheet = parts.get("xl/worksheets/sheet1.xml").replace('"', '\\'');
                    // The widths come before the rows; 2026-05-10 is day 46152 as Excel counts, in the date format.
                    assertThat(sheet).contains("<cols><col min='1' max='1' width='16' customWidth='1'/>");
                    assertThat(sheet.indexOf("<cols>")).isLessThan(sheet.indexOf("<sheetData>"));
                    assertThat(sheet).containsPattern("<c r='[A-Z]+2' s='2'><v>46152</v></c>");
                    assertThat(parts.get("xl/styles.xml")).contains("numFmtId=" + '"' + "14" + '"');
                }

            """;

    /** Added to the Projects backend: a second task of one project and title is a 409 naming both. */
    private static final String FIELD_MODEL_LIVE_TESTS = """
                @Test
                void aTaskIsUniquePerProjectAndTitle() throws Exception {
                    JsonNode projects = get("/api/projects?size=2").get("content");
                    long one = projects.get(0).get("id").asLong();
                    long other = projects.get(1).get("id").asLong();
                    String task = "{\\"title\\": \\"Write the plan\\", \\"status\\": \\"TODO\\", \\"projectId\\": %d}";
                    send(post("/api/tasks"), null, task.formatted(one), 200);

                    JsonNode clash = send(post("/api/tasks"), null, task.formatted(one), 409);
                    assertThat(clash.get("detail").asText()).isEqualTo("Another Task has the same Project and Title");
                    assertThat(clash.get("errors").get("projectId").asText()).isEqualTo("Another Task has the same Project and Title");
                    assertThat(clash.get("errors").get("title").asText()).isEqualTo("Another Task has the same Project and Title");

                    // The same title in another project is another task; an edit into a clash is refused too.
                    JsonNode second = send(post("/api/tasks"), null, task.formatted(other), 200);
                    send(put("/api/tasks/" + second.get("id").asLong()), null, task.formatted(one), 409);
                }

                @Test
                void aSaveTheRulesRefuseIsA400OnTheField() throws Exception {
                    JsonNode refused = send(post("/api/projects"), null,
                            "{\\"name\\": \\"Backwards\\", \\"status\\": \\"ACTIVE\\", \\"startsOn\\": \\"2026-05-10\\", \\"endsOn\\": \\"2026-05-01\\"}", 400);
                    assertThat(refused.get("errors").get("endsOn").asText()).isEqualTo("A project ends on or after the day it starts");
                    // An empty value a rule reads makes it hold.
                    send(post("/api/projects"), null, "{\\"name\\": \\"Open-ended\\", \\"status\\": \\"ACTIVE\\", \\"startsOn\\": \\"2026-05-10\\"}", 200);

                    long project = firstId("/api/projects");
                    String done = "{\\"title\\": \\"Finished %s\\", \\"status\\": \\"DONE\\", \\"projectId\\": " + project + "%s}";
                    JsonNode noEstimate = send(post("/api/tasks"), null, done.formatted("a", ""), 400);
                    assertThat(noEstimate.get("errors").get("estimate").asText()).isEqualTo("Say how many hours it took");
                    JsonNode created = send(post("/api/tasks"), null, done.formatted("b", ", \\"estimate\\": 3"), 200);
                    // The rules hold on an edit too.
                    send(put("/api/tasks/" + created.get("id").asLong()), null, done.formatted("b", ""), 400);
                }

                @Test
                void aComputedFieldIsComputedAndAHiddenOneCleared() throws Exception {
                    // Days are computed from the dates, whatever the client sends — on a create and an edit.
                    JsonNode p = send(post("/api/projects"), null,
                            "{\\"name\\": \\"Ten days\\", \\"status\\": \\"ACTIVE\\", \\"startsOn\\": \\"2026-05-01\\", \\"endsOn\\": \\"2026-05-10\\", \\"durationDays\\": 99}", 200);
                    assertThat(p.get("durationDays").asInt()).isEqualTo(10);
                    JsonNode edited = send(put("/api/projects/" + p.get("id").asLong()), null,
                            "{\\"name\\": \\"Ten days\\", \\"status\\": \\"ACTIVE\\", \\"startsOn\\": \\"2026-05-01\\", \\"endsOn\\": \\"2026-05-02\\"}", 200);
                    assertThat(edited.get("durationDays").asInt()).isEqualTo(2);
                    assertThat(get("/api/projects/" + p.get("id").asLong()).get("durationDays").asInt()).isEqualTo(2);

                    // The estimate is shown once a task has started: on a task still to do it is cleared.
                    long project = p.get("id").asLong();
                    String task = "{\\"title\\": \\"%s\\", \\"status\\": \\"%s\\", \\"estimate\\": 5, \\"projectId\\": " + project + "}";
                    assertThat(send(post("/api/tasks"), null, task.formatted("Not started", "TODO"), 200).get("estimate").isNull()).isTrue();
                    assertThat(send(post("/api/tasks"), null, task.formatted("Started", "DOING"), 200).get("estimate").asInt()).isEqualTo(5);
                }

            """;

    @org.junit.jupiter.api.Test
    @SuppressWarnings("unchecked")
    void liveApiOfAnEventsBackendBehaves(@TempDir Path workDir) throws Exception {
        // Approvals with the events option, Kafka on the build (its channel off: no broker here),
        // soft delete for the restore event, and an approve that e-mails the claim's creator and
        // the admins. EVENTS_LIVE_TESTS sends to a webhook server of its own and catches the mail.
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("approvals", "react-tailwind-crud");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "kafka"));
        body.put("opts", Map.of("scaffold", List.of("audit", "events", "softDelete", "seedData", "tests")));
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            entity.remove("access"); // rows that belong to users need ldap-auth too
            Map<String, Object> workflow = (Map<String, Object>) entity.get("workflow");
            if (workflow == null) continue;
            for (Map<String, Object> t : (List<Map<String, Object>>) workflow.get("transitions")) {
                t.remove("roles");
                if ("approve".equals(t.get("name"))) t.put("notify", List.of("creator", "ADMIN"));
            }
        }
        Path backend = postAndExtractBackend(workDir, body);
        useOfflineMail(backend);
        writeProjectTest(backend, "com.menora.support", "EventsLiveTests", EVENTS_LIVE_TESTS);
        runMvn(backend, "-B", "package");
        assertThat(Files.exists(backend.resolve("target/surefire-reports/com.menora.support.EventsLiveTests.txt")))
                .as("the events tests ran in the generated build").isTrue();
    }

    /**
     * The offline repository has no {@code spring-boot-starter-mail} of this Boot line: the build
     * takes the mail classes from the versions it does have (a generated project on the company
     * mirror keeps the starter).
     */
    private static void useOfflineMail(Path backend) throws Exception {
        Path pom = backend.resolve("pom.xml");
        String text = Files.readString(pom);
        String swapped = text.replaceFirst(
                "<dependency>\\s*<groupId>org\\.springframework\\.boot</groupId>\\s*<artifactId>spring-boot-starter-mail</artifactId>\\s*</dependency>",
                "<dependency><groupId>org.springframework</groupId><artifactId>spring-context-support</artifactId></dependency>"
                        + "<dependency><groupId>jakarta.mail</groupId><artifactId>jakarta.mail-api</artifactId><version>2.1.1</version></dependency>"
                        + "<dependency><groupId>org.eclipse.angus</groupId><artifactId>angus-mail</artifactId><version>2.0.1</version></dependency>");
        assertThat(swapped).as("the generated pom asks for the mail starter").isNotEqualTo(text);
        Files.writeString(pom, swapped);
    }

    /** Added to the events backend: webhooks (a server of its own), the e-mail of a move, the live stream. */
    private static final String EVENTS_LIVE_TESTS = """
            package com.menora.support;

            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.fasterxml.jackson.databind.node.ObjectNode;
            import com.sun.net.httpserver.HttpServer;
            import org.junit.jupiter.api.AfterAll;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.boot.test.context.TestConfiguration;
            import org.springframework.context.annotation.Bean;
            import org.springframework.http.MediaType;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.mail.SimpleMailMessage;
            import org.springframework.mail.javamail.JavaMailSender;
            import org.springframework.mail.javamail.JavaMailSenderImpl;
            import org.springframework.test.context.DynamicPropertyRegistry;
            import org.springframework.test.context.DynamicPropertySource;
            import org.springframework.test.web.servlet.MockMvc;
            import org.springframework.test.web.servlet.MvcResult;
            import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

            import javax.crypto.Mac;
            import javax.crypto.spec.SecretKeySpec;
            import java.net.InetSocketAddress;
            import java.nio.charset.StandardCharsets;
            import java.util.Arrays;
            import java.util.HashMap;
            import java.util.HexFormat;
            import java.util.List;
            import java.util.Locale;
            import java.util.Map;
            import java.util.Queue;
            import java.util.concurrent.ConcurrentLinkedQueue;
            import java.util.function.Predicate;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            @SpringBootTest(properties = {
                    "app.events.relay.interval-ms=200",
                    "app.events.relay.initial-delay-ms=200",
                    "app.events.kafka.enabled=false",
                    "app.events.mail.enabled=true",
                    "app.events.mail.domain=example.com",
                    "app.events.mail.roles.ADMIN=boss@example.com",
                    "app.events.mail.app-url=http://approvals.example",
            })
            @AutoConfigureMockMvc
            class EventsLiveTests {

                record Call(String path, Map<String, String> headers, String body) {}

                static final Queue<Call> CALLS = new ConcurrentLinkedQueue<>();
                static final Queue<SimpleMailMessage> MAILS = new ConcurrentLinkedQueue<>();
                static final HttpServer HOOKS = start();

                static HttpServer start() {
                    try {
                        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                        server.createContext("/", exchange -> {
                            Map<String, String> headers = new HashMap<>();
                            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String path = exchange.getRequestURI().getPath();
                            CALLS.add(new Call(path, headers, body));
                            exchange.sendResponseHeaders(path.equals("/broken") ? 500 : 200, -1);
                            exchange.close();
                        });
                        server.start();
                        return server;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }

                // One source for the whole list: Boot binds a list from the first source that has it.
                @DynamicPropertySource
                static void hooks(DynamicPropertyRegistry registry) {
                    String base = "http://127.0.0.1:" + HOOKS.getAddress().getPort();
                    registry.add("app.events.webhooks[0].name", () -> "crm");
                    registry.add("app.events.webhooks[0].url", () -> base + "/crm");
                    registry.add("app.events.webhooks[0].secret", () -> "s3cret");
                    registry.add("app.events.webhooks[0].events", () -> "expense-claim.*");
                    registry.add("app.events.webhooks[1].name", () -> "broken");
                    registry.add("app.events.webhooks[1].url", () -> base + "/broken");
                    registry.add("app.events.webhooks[1].events", () -> "*.deleted");
                }

                @AfterAll
                static void stop() {
                    HOOKS.stop(0);
                }

                @TestConfiguration
                static class CatchMail {
                    @Bean
                    JavaMailSender mailSender() {
                        return new JavaMailSenderImpl() {
                            @Override
                            public void send(SimpleMailMessage... messages) {
                                MAILS.addAll(Arrays.asList(messages));
                            }
                        };
                    }
                }

                @Autowired MockMvc mvc;
                @Autowired JdbcTemplate jdbc;
                final ObjectMapper json = new ObjectMapper();

                private JsonNode send(MockHttpServletRequestBuilder call, String user, String body, int expected) throws Exception {
                    if (user != null) call.header("userinfo", user);
                    if (body != null) call.contentType(MediaType.APPLICATION_JSON).content(body);
                    byte[] answer = mvc.perform(call).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray();
                    return answer.length == 0 ? json.nullNode() : json.readTree(answer);
                }

                private JsonNode newClaim(String user) throws Exception {
                    JsonNode centers = json.readTree(mvc.perform(get("/api/cost-centers?size=1")).andReturn().getResponse().getContentAsByteArray());
                    long center = centers.get("content").get(0).get("id").asLong();
                    return send(post("/api/expense-claims"), user, "{\\"title\\":\\"Taxi\\",\\"amount\\":40,\\"category\\":\\"TRAVEL\\","
                            + "\\"spentOn\\":\\"2026-11-01\\",\\"costCenterId\\":" + center + "}", 200);
                }

                /** The first webhook call to {@code path} with an event of {@code type} about row {@code id}. */
                private static Call awaitCall(String path, String type, String id, String... also) throws InterruptedException {
                    Predicate<Call> wanted = c -> c.path().equals(path) && c.body().contains("\\"type\\":\\"" + type + "\\"")
                            && c.body().contains("\\"rowId\\":\\"" + id + "\\"") && Arrays.stream(also).allMatch(c.body()::contains);
                    long until = System.currentTimeMillis() + 20_000;
                    while (System.currentTimeMillis() < until) {
                        for (Call c : CALLS) if (wanted.test(c)) return c;
                        Thread.sleep(100);
                    }
                    throw new AssertionError("No " + type + " for row " + id + " reached " + path + " (" + CALLS.size() + " calls)");
                }

                private static List<SimpleMailMessage> mailsAbout(String id) {
                    return MAILS.stream().filter(m -> m.getSubject() != null && m.getSubject().startsWith("ExpenseClaim " + id + ":")).toList();
                }

                private static String hmac(String secret, String body) throws Exception {
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                    return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
                }

                @Test
                void eachChangeReachesTheWebhookSignedAndAMoveEmailsWhomItNames() throws Exception {
                    JsonNode made = newClaim("alice");
                    String id = made.get("id").asText();
                    String path = "/api/expense-claims/" + id;
                    Call created = awaitCall("/crm", "expense-claim.created", id);
                    JsonNode event = json.readTree(created.body());
                    assertThat(event.get("actor").asText()).isEqualTo("alice");
                    assertThat(event.get("entity").asText()).isEqualTo("expense-claim");
                    assertThat(event.get("data").get("title").asText()).isEqualTo("Taxi");
                    assertThat(event.get("data").get("createdBy").asText()).isEqualTo("alice");
                    assertThat(created.headers().get("x-event-id")).isEqualTo(event.get("id").asText());
                    assertThat(created.headers().get("x-event-type")).isEqualTo("expense-claim.created");
                    assertThat(created.headers().get("x-event-signature")).isEqualTo("sha256=" + hmac("s3cret", created.body()));

                    // An edit names what it changed and carries the row as it was.
                    ObjectNode edit = (ObjectNode) made.deepCopy();
                    edit.put("title", "Taxi home");
                    send(put(path), "bob", edit.toString(), 200);
                    JsonNode updated = json.readTree(awaitCall("/crm", "expense-claim.updated", id).body());
                    assertThat(updated.get("changedFields").toString()).isEqualTo("[\\"title\\"]");
                    assertThat(updated.get("before").get("title").asText()).isEqualTo("Taxi");
                    assertThat(updated.get("data").get("title").asText()).isEqualTo("Taxi home");

                    // A workflow move says which move, from where and to where.
                    send(post(path + "/transitions/submit"), "bob", null, 200);
                    JsonNode moved = json.readTree(awaitCall("/crm", "expense-claim.transitioned", id).body());
                    assertThat(moved.get("transition").asText()).isEqualTo("submit");
                    assertThat(moved.get("from").asText()).isEqualTo("DRAFT");
                    assertThat(moved.get("to").asText()).isEqualTo("SUBMITTED");

                    // Approving e-mails the claim's creator and the admins, never the one who approved.
                    send(post(path + "/transitions/approve"), "carol", "{\\"approvedAmount\\":35}", 200);
                    long until = System.currentTimeMillis() + 20_000;
                    while (mailsAbout(id).isEmpty() && System.currentTimeMillis() < until) Thread.sleep(100);
                    List<SimpleMailMessage> mails = mailsAbout(id);
                    assertThat(mails).hasSize(1);
                    assertThat(mails.get(0).getSubject()).isEqualTo("ExpenseClaim " + id + ": Approve");
                    assertThat(mails.get(0).getTo()).containsExactlyInAnyOrder("alice@example.com", "boss@example.com");
                    assertThat(mails.get(0).getText())
                            .isEqualTo("carol moved ExpenseClaim " + id + " from Waiting for approval to Approved.\\n\\nhttp://approvals.example");

                    // Paying names nobody: no second mail.
                    send(post(path + "/transitions/pay"), "carol", null, 200);
                    awaitCall("/crm", "expense-claim.transitioned", id, "\\"transition\\":\\"pay\\"");
                    Thread.sleep(1_000);
                    assertThat(mailsAbout(id)).hasSize(1);

                    // Every message of the row got through.
                    assertThat(jdbc.queryForObject("select count(*) from app_outbox where row_id = ? and entity_name = 'expense-claim'"
                            + " and status <> 'SENT' and channel <> 'webhook:broken'", Long.class, id)).isZero();
                }

                @Test
                void aWebhookThatFailsWaitsToBeTriedAgainWhileTheOthersGetTheirs() throws Exception {
                    String id = newClaim("alice").get("id").asText();
                    send(delete("/api/expense-claims/" + id), "alice", null, 204);
                    awaitCall("/broken", "expense-claim.deleted", id);
                    awaitCall("/crm", "expense-claim.deleted", id);
                    // The failure is on the row, with when to try again.
                    Map<String, Object> row = Map.of();
                    long until = System.currentTimeMillis() + 20_000;
                    while (System.currentTimeMillis() < until) {
                        row = jdbc.queryForMap("select status, attempts, last_error from app_outbox where row_id = ?"
                                + " and channel = 'webhook:broken' and event_type = 'expense-claim.deleted'", id);
                        if (((Number) row.get("ATTEMPTS")).intValue() >= 1) break;
                        Thread.sleep(100);
                    }
                    assertThat(row.get("STATUS")).isEqualTo("PENDING");
                    assertThat(((Number) row.get("ATTEMPTS")).intValue()).isGreaterThanOrEqualTo(1);
                    assertThat((String) row.get("LAST_ERROR")).contains("500");

                    // A soft-deleted claim brought back says so.
                    send(post("/api/expense-claims/" + id + "/restore"), "alice", null, 200);
                    JsonNode restored = json.readTree(awaitCall("/crm", "expense-claim.restored", id).body());
                    assertThat(restored.get("data").get("title").asText()).isEqualTo("Taxi");
                }

                @Test
                void anOpenPageHearsOfEachCommittedChange() throws Exception {
                    MvcResult stream = mvc.perform(get("/api/events/stream").header("userinfo", "alice"))
                            .andExpect(request().asyncStarted()).andReturn();
                    String id = newClaim("bob").get("id").asText();
                    String text = "";
                    long until = System.currentTimeMillis() + 10_000;
                    while (System.currentTimeMillis() < until) {
                        text = stream.getResponse().getContentAsString();
                        if (text.contains("\\"rowId\\":\\"" + id + "\\"")) break;
                        Thread.sleep(50);
                    }
                    assertThat(text).contains("event:ready").contains("event:change")
                            .contains("{\\"entity\\":\\"expense-claim\\",\\"rowId\\":\\"" + id + "\\",\\"kind\\":\\"created\\"}");
                    assertThat(stream.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
                }
            }
            """;

    /** Approvals with {@code audit} and {@code history}: who made each change, the log, a restore. */
    private static final String LIVE_HISTORY_TESTS = """

                @Test
                void everyChangeIsKeptWithWhoMadeItAndAnEarlierVersionComesBack() throws Exception {
                    JsonNode made = newClaim("");
                    String path = "/api/expense-claims/" + made.get("id").asLong();
                    // The audit columns name the caller.
                    assertThat(made.get("createdBy").asText()).isEqualTo("alice");
                    assertThat(made.get("updatedBy").asText()).isEqualTo("alice");
                    ObjectNode edit = (ObjectNode) made.deepCopy();
                    edit.put("title", "Taxi to the airport");
                    JsonNode saved = send(put(path), "bob", edit.toString(), 200);
                    assertThat(saved.get("createdBy").asText()).isEqualTo("alice");
                    assertThat(saved.get("updatedBy").asText()).isEqualTo("bob");
                    // Rows made outside a request (the demo data) are the system's.
                    assertThat(get("/api/cost-centers?size=1").get("content").get(0).get("createdBy").asText()).isEqualTo("system");

                    // The log, newest first: who, which fields, before and after — not the framework's columns.
                    JsonNode history = get(path + "/history");
                    assertThat(history).hasSize(2);
                    assertThat(history.get(0).get("action").asText()).isEqualTo("UPDATE");
                    assertThat(history.get(0).get("by").asText()).isEqualTo("bob");
                    assertThat(history.get(0).get("fields").toString()).isEqualTo("[\\"title\\"]");
                    assertThat(history.get(0).get("before").get("title").asText()).isEqualTo("Taxi");
                    assertThat(history.get(0).get("after").get("title").asText()).isEqualTo("Taxi to the airport");
                    assertThat(history.get(0).get("after").has("version")).isFalse();
                    assertThat(history.get(1).get("action").asText()).isEqualTo("CREATE");
                    assertThat(history.get(1).get("by").asText()).isEqualTo("alice");

                    // A transition is a change like any other.
                    send(post(path + "/transitions/submit"), "bob", null, 200);
                    history = get(path + "/history");
                    assertThat(history.get(0).get("fields").toString()).contains("status");
                    long created = history.get(2).get("id").asLong();
                    long newest = history.get(0).get("id").asLong();

                    // Restoring over a change the caller has not seen is refused, then done when told to.
                    send(post(path + "/history/" + created + "/restore"), "carol", "{\\"latest\\":" + (newest - 1) + "}", 409);
                    JsonNode restored = send(post(path + "/history/" + created + "/restore"), "carol", "{\\"latest\\":" + newest + "}", 200);
                    assertThat(restored.get("row").get("title").asText()).isEqualTo("Taxi");
                    // The workflow's status moves only by its transitions.
                    assertThat(restored.get("row").get("status").asText()).isEqualTo("SUBMITTED");
                    assertThat(restored.get("row").get("updatedBy").asText()).isEqualTo("carol");
                    // Every field the old version had is still a field of the row.
                    assertThat(restored.get("skipped")).isEmpty();
                    history = get(path + "/history");
                    assertThat(history.get(0).get("action").asText()).isEqualTo("RESTORE");
                    assertThat(history.get(0).get("by").asText()).isEqualTo("carol");
                    assertThat(history.get(0).get("fields").toString()).isEqualTo("[\\"title\\"]");
                    send(post(path + "/history/" + created + "/restore"), "carol", "{\\"latest\\":1,\\"force\\":true}", 200);

                    // "Changed by me": the rows the caller created or last changed.
                    JsonNode mine = json.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/expense-claims?changedBy=me&size=100")
                            .header("userinfo", "carol")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
                    assertThat(mine.get("content")).isNotEmpty();
                    for (JsonNode row : mine.get("content")) {
                        assertThat(java.util.List.of(row.get("createdBy").asText(), row.get("updatedBy").asText())).contains("carol");
                    }

                    // A deletion is logged; it leaves no version to restore.
                    long gone = newClaim("").get("id").asLong();
                    send(delete("/api/expense-claims/" + gone), "dave", null, 204);
                    JsonNode afterDelete = get("/api/expense-claims/" + gone + "/history");
                    assertThat(afterDelete.get(0).get("action").asText()).isEqualTo("DELETE");
                    assertThat(afterDelete.get(0).get("by").asText()).isEqualTo("dave");
                    send(post("/api/expense-claims/" + gone + "/history/" + afterDelete.get(0).get("id").asLong() + "/restore"),
                            "carol", "{}", 400);
                }
            """;

    /** Test methods run against the generated Approvals backend (no roles) and its demo rows. */
    private static final String LIVE_APPROVALS_TESTS = """
                private JsonNode newClaim(String extra) throws Exception {
                    long center = firstId("/api/cost-centers");
                    return send(post("/api/expense-claims"), "alice", "{\\"title\\":\\"Taxi\\",\\"amount\\":40,\\"category\\":\\"TRAVEL\\","
                            + "\\"spentOn\\":\\"2026-11-01\\",\\"costCenterId\\":" + center + extra + "}", 200);
                }

                @Test
                void aSubmittedClaimStaysWithinItsCostCentersBudget() throws Exception {
                    // The rule reads the claim's cost center: when SUBMITTED, amount <= costCenter.budget.
                    long small = send(post("/api/cost-centers"), "alice", "{\\"code\\":\\"CC-LIVE-1\\",\\"name\\":\\"Small\\",\\"budget\\":30}", 200)
                            .get("id").asLong();
                    long tiny = send(post("/api/cost-centers"), "alice", "{\\"code\\":\\"CC-LIVE-2\\",\\"name\\":\\"Tiny\\",\\"budget\\":10}", 200)
                            .get("id").asLong();
                    String claim = "{\\"title\\":\\"Hotel\\",\\"category\\":\\"TRAVEL\\",\\"spentOn\\":\\"2026-11-01\\",";
                    long id = send(post("/api/expense-claims"), "alice", claim + "\\"amount\\":40,\\"costCenterId\\":" + small + "}", 200)
                            .get("id").asLong();
                    // A draft may be over it; submitting it is refused, on the amount.
                    JsonNode refused = send(post("/api/expense-claims/" + id + "/transitions/submit"), "bob", null, 400);
                    assertThat(refused.get("errors").get("amount").asText()).isEqualTo("Over the cost center's budget");
                    // Within it, the move goes through.
                    send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/expense-claims/" + id), "alice",
                            claim + "\\"amount\\":25,\\"costCenterId\\":" + small + "}", 200);
                    send(post("/api/expense-claims/" + id + "/transitions/submit"), "bob", null, 200);
                    // A save naming another cost center by id is checked against that center's budget —
                    // the row is read, not the id alone.
                    JsonNode moved = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/expense-claims/" + id),
                            "alice", claim + "\\"amount\\":25,\\"costCenterId\\":" + tiny + "}", 400);
                    assertThat(moved.get("errors").has("amount")).isTrue();
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
                private static String j(String json) {
                    return json.replace('\\'', '"');
                }

                @Test
                void anOrdersTotalFollowsItsLinesHoweverTheyChange() throws Exception {
                    long customer = firstId("/api/customers");
                    long product = firstId("/api/products");
                    String head = "{'reference':'T-1','status':'OPEN','placedAt':'2026-11-01','customerId':" + customer;
                    // An order needs a line: refused, and said on the lines.
                    JsonNode refused = send(post("/api/orders"), null, j(head + "}"), 400);
                    assertThat(refused.get("errors").get("lines").asText()).isEqualTo("An order needs at least one line");
                    // Two lines: the total is theirs, whatever the client sent.
                    JsonNode made = send(post("/api/orders"), null, j(head + ",'total':999,'lines':[{'quantity':2,'unitPrice':5,'productId':"
                            + product + "},{'quantity':1,'unitPrice':2.5,'productId':" + product + "}]}"), 200);
                    long id = made.get("id").asLong();
                    assertThat(made.get("total").decimalValue()).isEqualByComparingTo("12.5");
                    long first = made.get("lines").get(0).get("id").asLong();
                    long second = made.get("lines").get(1).get("id").asLong();

                    // A line added, changed and removed through its own endpoints: the order's total follows each.
                    JsonNode added = send(post("/api/order-lines"), null,
                            j("{'quantity':3,'unitPrice':10,'productId':" + product + ",'orderId':" + id + "}"), 200);
                    long extra = added.get("id").asLong();
                    assertThat(get("/api/orders/" + id).get("total").decimalValue()).isEqualByComparingTo("42.5");
                    send(put("/api/order-lines/" + extra), null,
                            j("{'quantity':1,'unitPrice':10,'productId':" + product + ",'orderId':" + id + "}"), 200);
                    assertThat(get("/api/orders/" + id).get("total").decimalValue()).isEqualByComparingTo("22.5");
                    mvc.perform(patch("/api/order-lines/bulk").contentType(MediaType.APPLICATION_JSON)
                                    .content(j("{'ids':[" + extra + "],'field':'quantity','value':4}")))
                            .andExpect(status().is2xxSuccessful());
                    assertThat(get("/api/orders/" + id).get("total").decimalValue()).isEqualByComparingTo("52.5");
                    mvc.perform(delete("/api/order-lines/" + extra)).andExpect(status().is2xxSuccessful());
                    assertThat(get("/api/orders/" + id).get("total").decimalValue()).isEqualByComparingTo("12.5");
                    // Brought back (soft delete is on), it counts again.
                    mvc.perform(post("/api/order-lines/" + extra + "/restore")).andExpect(status().isOk());
                    assertThat(get("/api/orders/" + id).get("total").decimalValue()).isEqualByComparingTo("52.5");
                    mvc.perform(delete("/api/order-lines/bulk").contentType(MediaType.APPLICATION_JSON).content("[" + extra + "]"))
                            .andExpect(status().is2xxSuccessful());

                    // A change there that breaks the order's rule is refused, in the order's words — and undone.
                    mvc.perform(delete("/api/order-lines/" + first)).andExpect(status().is2xxSuccessful());
                    JsonNode last = send(delete("/api/order-lines/" + second), null, null, 400);
                    assertThat(last.get("detail").asText()).contains("An order needs at least one line");
                    assertThat(get("/api/orders/" + id).get("lines")).hasSize(1);
                    assertThat(get("/api/orders/" + id).get("total").decimalValue()).isEqualByComparingTo("2.5");

                    // The order's own save: no list leaves the lines (and the total) alone, an empty one is refused.
                    long version = get("/api/orders/" + id).get("version").asLong();
                    send(put("/api/orders/" + id), null, j(head.replace("T-1", "T-1b") + ",'version':" + version + "}"), 200);
                    assertThat(get("/api/orders/" + id).get("total").decimalValue()).isEqualByComparingTo("2.5");
                    send(put("/api/orders/" + id), null, j(head + ",'lines':[]}"), 400);
                    // Its lines are saved with it, so it carries the version check: an old version is told so.
                    send(put("/api/orders/" + id), null, j(head + ",'version':" + version + "}"), 409);

                    // The demo orders came with their two lines, added up.
                    JsonNode demo = get("/api/orders/" + firstId("/api/orders"));
                    assertThat(demo.get("lines").size()).isBetween(1, 2);
                    java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
                    for (JsonNode line : demo.get("lines")) {
                        if (!line.get("lineTotal").isNull()) sum = sum.add(line.get("lineTotal").decimalValue());
                    }
                    assertThat(demo.get("total").decimalValue()).isEqualByComparingTo(sum);
                }

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

    @ParameterizedTest(name = "Spring Boot {0}")
    @ValueSource(strings = {"3.2.1", FullstackGoldenOutputTests.BOOT_35})
    void openApiWizardInterfacesFormsAndSecurityWork(String bootVersion, @TempDir Path workDir) throws Exception {
        // The interface style on the secure-forms document, both sides, a RestClient client, with
        // validation and springdoc. The team's class is replaced by one that implements two
        // operations, as a team would: those answer, the others 501; the multipart and form bodies
        // bind; the client sends each operation's credentials; springdoc lists the schemes.
        Map<String, Object> body = openApiBody("claims", List.of("web", "validation", "openapi"),
                Map.of("mode", "BOTH", "apiStyle", "interfaces", "clientKind", "restClient"));
        body.put("bootVersion", bootVersion);
        body.put("specByDep", Map.of("web", WizardGoldenOutputTests.fixture("secure-forms.yaml")));
        Path project = postAndExtractWizard(workDir, body);
        Path impl = project.resolve("src/main/java/com/menora/petstore/api/ClaimsController.java");
        assertThat(Files.readString(impl)).contains("public class ClaimsController implements ClaimsApi {\n}");
        Files.writeString(impl, OPENAPI_TEAM_CLAIMS_CONTROLLER);
        writeProjectTest(project, "com.menora.petstore", "InterfaceTests", OPENAPI_INTERFACE_TESTS);
        writeProjectTest(project, "com.menora.petstore", "OAuth2Tests", OPENAPI_OAUTH2_TESTS);
        runMvn(project, "-B", "package");
        assertThat(project.resolve("target/surefire-reports/com.menora.petstore.OAuth2Tests.txt")).exists();
    }

    @org.junit.jupiter.api.Test
    void openApiWizardSecureFormsAsClassesCompile(@TempDir Path workDir) throws Exception {
        // The same document as classes with a RestTemplate client (springdoc comes with the
        // controllers): the forms bind in the classes, the client builds its parts — and fetches
        // its OAuth2 token through the RestTemplate's interceptor.
        Map<String, Object> body = openApiBody("claimsclasses", List.of("web", "validation"), Map.of("mode", "BOTH"));
        body.put("specByDep", Map.of("web", WizardGoldenOutputTests.fixture("secure-forms.yaml")));
        Path project = postAndExtractWizard(workDir, body);
        writeProjectTest(project, "com.menora.petstore", "OAuth2Tests", OPENAPI_OAUTH2_TESTS);
        runMvn(project, "-B", "package");
        assertThat(project.resolve("target/surefire-reports/com.menora.petstore.OAuth2Tests.txt")).exists();
    }

    /**
     * OAuth2 client credentials, for real: with a client id configured the client asks the
     * document's token endpoint once, sends the token on every call, and — refused with a 401 —
     * forgets it, asks again and repeats the call once. Either client kind.
     */
    private static final String OPENAPI_OAUTH2_TESTS = """
            package com.menora.petstore;

            import com.menora.petstore.client.OpenApiOAuth2Tokens;
            import com.menora.petstore.client.PartnersClient;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.HttpMethod;
            import org.springframework.http.MediaType;
            import org.springframework.test.web.client.MockRestServiceServer;

            import java.nio.charset.StandardCharsets;
            import java.util.Base64;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.hamcrest.Matchers.containsString;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
            import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
            import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

            @SpringBootTest(properties = {
                    "openapi.client.security.partner-key.api-key=p4r",
                    "openapi.client.security.oauth.client-id=app",
                    "openapi.client.security.oauth.client-secret=s3cret"})
            @AutoConfigureMockRestServiceServer
            class OAuth2Tests {

                @Autowired private MockRestServiceServer server;
                @Autowired private OpenApiOAuth2Tokens tokens;
                @Autowired private PartnersClient partners;

                private static String partner(String id) {
                    return "http://localhost:8080/partners/" + id + "?partner_key=p4r";
                }

                @Test
                void aTokenIsFetchedOnceSentOnEveryCallAndRenewedAfterA401() {
                    MockRestServiceServer idp = MockRestServiceServer.bindTo(tokens.tokenEndpoint()).build();
                    String basic = "Basic " + Base64.getEncoder().encodeToString("app:s3cret".getBytes(StandardCharsets.UTF_8));
                    idp.expect(requestTo("https://idp.example.com/token")).andExpect(method(HttpMethod.POST))
                            .andExpect(header("Authorization", basic))
                            .andExpect(content().string(containsString("grant_type=client_credentials")))
                            .andExpect(content().string(containsString("scope=read")))
                            .andRespond(withSuccess("{\\"access_token\\":\\"a1\\",\\"expires_in\\":3600}", MediaType.APPLICATION_JSON));
                    // The second token, asked for after the 401 below.
                    idp.expect(requestTo("https://idp.example.com/token"))
                            .andRespond(withSuccess("{\\"access_token\\":\\"a2\\",\\"expires_in\\":3600}", MediaType.APPLICATION_JSON));
                    server.expect(requestTo(partner("p1"))).andExpect(header("Authorization", "Bearer a1"))
                            .andRespond(withSuccess("{\\"description\\":\\"one\\"}", MediaType.APPLICATION_JSON));
                    server.expect(requestTo(partner("p2"))).andExpect(header("Authorization", "Bearer a1"))
                            .andRespond(withSuccess("{\\"description\\":\\"two\\"}", MediaType.APPLICATION_JSON));
                    // Revoked early: the server answers 401; a new token is fetched and the call sent again.
                    server.expect(requestTo(partner("p3"))).andExpect(header("Authorization", "Bearer a1"))
                            .andRespond(withUnauthorizedRequest());
                    server.expect(requestTo(partner("p3"))).andExpect(header("Authorization", "Bearer a2"))
                            .andRespond(withSuccess("{\\"description\\":\\"three\\"}", MediaType.APPLICATION_JSON));

                    assertThat(partners.getPartner("p1").description()).isEqualTo("one");
                    assertThat(partners.getPartner("p2").description()).isEqualTo("two");
                    assertThat(partners.getPartner("p3").description()).isEqualTo("three");
                    idp.verify();
                    server.verify();
                }
            }
            """;

    /** What a team writes in the generated ClaimsController: two of the operations, signatures only. */
    private static final String OPENAPI_TEAM_CLAIMS_CONTROLLER = """
            package com.menora.petstore.api;

            import com.menora.petstore.dto.Claim;
            import com.menora.petstore.dto.ClaimDetails;
            import org.springframework.validation.annotation.Validated;
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.web.multipart.MultipartFile;

            import java.time.LocalDate;
            import java.util.List;

            @RestController
            @Validated
            public class ClaimsController implements ClaimsApi {

                @Override
                public Claim fileClaim(String policyNumber, LocalDate incidentDate, MultipartFile receipt,
                                       List<MultipartFile> photos, ClaimDetails details) {
                    long size = receipt.getSize() + (photos == null ? 0 : photos.size());
                    return new Claim(size, policyNumber + "/" + incidentDate + "/" + (details == null ? "-" : details.description()), null);
                }

                @Override
                public void addNote(Long claimId, String text, List<String> visibleTo) {
                    if (!"hi".equals(text) || !List.of("agent", "owner").equals(visibleTo)) {
                        throw new IllegalArgumentException(text + " " + visibleTo);
                    }
                }
            }
            """;

    private static final String OPENAPI_INTERFACE_TESTS = """
            package com.menora.petstore;

            import com.menora.petstore.client.ClaimsClient;
            import com.menora.petstore.client.PartnersClient;
            import com.menora.petstore.dto.Claim;
            import com.menora.petstore.dto.ClaimDetails;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.core.io.ByteArrayResource;
            import org.springframework.http.HttpMethod;
            import org.springframework.http.MediaType;
            import org.springframework.mock.web.MockMultipartFile;
            import org.springframework.test.web.client.MockRestServiceServer;
            import org.springframework.test.web.servlet.MockMvc;

            import java.time.LocalDate;
            import java.util.List;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.hamcrest.Matchers.containsString;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
            import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
            import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
            import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            @SpringBootTest(properties = {
                    "openapi.client.security.bearer-auth.token=t0k",
                    "openapi.client.security.api-key.api-key=k3y",
                    "openapi.client.security.partner-key.api-key=p4r",
                    "openapi.client.security.oauth.token=0auth"})
            @AutoConfigureMockMvc
            @AutoConfigureMockRestServiceServer
            class InterfaceTests {

                @Autowired private MockMvc mvc;
                @Autowired private MockRestServiceServer server;
                @Autowired private ClaimsClient claims;
                @Autowired private PartnersClient partners;

                @Test
                void anOperationNobodyImplementedAnswers501() throws Exception {
                    mvc.perform(get("/claims")).andExpect(status().isNotImplemented());
                    mvc.perform(get("/partners/p1")).andExpect(status().isNotImplemented());
                }

                @Test
                void aMultipartBodyBindsItsFilesFieldsAndJsonPart() throws Exception {
                    mvc.perform(multipart("/claims")
                                    .file(new MockMultipartFile("receipt", "r.pdf", "application/pdf", new byte[10]))
                                    .file(new MockMultipartFile("photos", "a.jpg", "image/jpeg", new byte[1]))
                                    .file(new MockMultipartFile("photos", "b.jpg", "image/jpeg", new byte[1]))
                                    .file(new MockMultipartFile("details", "", "application/json", "{\\"description\\":\\"dent\\"}".getBytes()))
                                    .param("policyNumber", "P-1").param("incidentDate", "2026-11-01"))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.id").value(12))
                            .andExpect(jsonPath("$.policyNumber").value("P-1/2026-11-01/dent"));
                    // The receipt is required.
                    mvc.perform(multipart("/claims").param("policyNumber", "P-1")).andExpect(status().isBadRequest());
                }

                @Test
                void aFormBodyBindsItsFields() throws Exception {
                    mvc.perform(post("/claims/7/notes").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                    .param("text", "hi").param("visibleTo", "agent").param("visibleTo", "owner"))
                            .andExpect(status().isNoContent());
                }

                @Test
                void springdocListsTheSchemes() throws Exception {
                    String docs = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    assertThat(docs).contains("\\"bearerAuth\\"").contains("\\"X-API-Key\\"").contains("\\"partner_key\\"");
                }

                @Test
                void theClientSendsTheFormsAndTheCredentials() {
                    server.expect(requestTo("http://localhost:8080/claims")).andExpect(method(HttpMethod.POST))
                            .andExpect(header("Authorization", "Bearer t0k"))
                            .andExpect(header("Content-Type", containsString("multipart/form-data")))
                            .andExpect(content().string(containsString("name=\\"policyNumber\\"")))
                            .andExpect(content().string(containsString("2026-11-01")))
                            .andRespond(withSuccess("{\\"id\\":1,\\"policyNumber\\":\\"P-1\\"}", MediaType.APPLICATION_JSON));
                    // The first requirement: the API key, not basic.
                    server.expect(requestTo("http://localhost:8080/claims/7/notes")).andExpect(method(HttpMethod.POST))
                            .andExpect(header("X-API-Key", "k3y"))
                            .andExpect(content().string(containsString("text=hi")))
                            .andRespond(withNoContent());
                    // Both schemes of the one requirement.
                    server.expect(requestTo("http://localhost:8080/partners/p1?partner_key=p4r"))
                            .andExpect(header("Authorization", "Bearer 0auth"))
                            .andRespond(withSuccess("{\\"description\\":\\"x\\"}", MediaType.APPLICATION_JSON));
                    Claim filed = claims.fileClaim("P-1", LocalDate.of(2026, 11, 1),
                            new ByteArrayResource(new byte[3]) { @Override public String getFilename() { return "r.pdf"; } },
                            null, new ClaimDetails("dent", 10.0));
                    assertThat(filed.policyNumber()).isEqualTo("P-1");
                    claims.addNote(7L, "hi", List.of("agent"));
                    assertThat(partners.getPartner("p1").description()).isEqualTo("x");
                    server.verify();
                }
            }
            """;

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

    /**
     * Avro payloads, built for real: the avro-maven-plugin makes the classes from the .avsc files
     * (in the order the pom's imports give), the producers and listeners compile against them, and
     * the generated tests send each record through the single-object encoding and back. Needs
     * {@code org.apache.avro:avro} and {@code avro-maven-plugin} resolvable — the company mirror
     * has them, the offline repository does not — hence its own switch:
     * {@code -Dsmoke.be=true -Dsmoke.avro=true}.
     */
    @org.junit.jupiter.api.Test
    @EnabledIfSystemProperty(named = "smoke.avro", matches = "true")
    void eventsWizardAvroBackendCompilesAndPassesItsTests(@TempDir Path workDir) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "orders");
        body.put("packageName", "com.menora.orders");
        body.put("bootVersion", "3.2.1");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("kafka"));
        body.put("asyncApiByDep", Map.of("kafka", WizardGoldenOutputTests.fixture("orders-avro-asyncapi.yaml")));
        body.put("eventOptions", Map.of("kafka", Map.of("generateTests", true, "channels", List.of(
                Map.of("name", "orderPlaced", "produce", true, "consume", true),
                Map.of("name", "customerEvents", "produce", true, "consume", true),
                Map.of("name", "audit", "produce", true, "consume", true)))));
        Path project = postAndExtractWizard(workDir, body);
        assertThat(project.resolve("src/main/avro/com.acme.orders.OrderPlaced.avsc")).isRegularFile();
        runMvn(project, "-B", "package");
        assertThat(project.resolve("target/generated-sources/avro/com/acme/orders/OrderPlaced.java")).isRegularFile();
    }

    /**
     * Keys, headers (a trait's too) and correlation ids on a JSON channel, offline: the producer
     * writes the headers and a new correlation id, the listener reads them back typed — the
     * project's own tests and one written here against the generated classes.
     */
    @org.junit.jupiter.api.Test
    void eventsWizardJsonKeysAndHeadersCompileAndTravel(@TempDir Path workDir) throws Exception {
        String document = """
                asyncapi: 3.0.0
                info: { title: Shipments, version: 1.0.0 }
                channels:
                  shipments:
                    address: orders.shipments
                    messages:
                      shipped: { $ref: '#/components/messages/OrderShipped' }
                operations:
                  publishShipment: { action: send, channel: { $ref: '#/channels/shipments' } }
                  onShipment: { action: receive, channel: { $ref: '#/channels/shipments' } }
                components:
                  messages:
                    OrderShipped:
                      name: OrderShipped
                      traits:
                        - $ref: '#/components/messageTraits/Traced'
                      headers:
                        type: object
                        properties:
                          urgent: { type: boolean }
                          attempt: { type: integer }
                      correlationId: { location: '$message.header#/trace-id' }
                      bindings: { kafka: { key: { type: string, format: uuid } } }
                      payload:
                        type: object
                        properties:
                          orderId: { type: string }
                  messageTraits:
                    Traced:
                      headers:
                        type: object
                        properties:
                          tenant: { type: string }
                """;
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "shipments");
        body.put("packageName", "com.menora.shipments");
        body.put("bootVersion", "3.2.1");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("kafka"));
        body.put("asyncApiByDep", Map.of("kafka", document));
        body.put("eventOptions", Map.of("kafka", Map.of("generateTests", true)));
        Path project = postAndExtractWizard(workDir, body);
        writeProjectTest(project, "com.menora.shipments.events", "HeadersTravelTests", """
                package com.menora.shipments.events;

                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.menora.shipments.events.model.OrderShipped;
                import com.menora.shipments.events.model.OrderShippedHeaders;
                import org.apache.kafka.clients.consumer.ConsumerRecord;
                import org.apache.kafka.clients.producer.ProducerRecord;
                import org.junit.jupiter.api.Test;
                import org.mockito.ArgumentCaptor;
                import org.mockito.Mockito;
                import org.springframework.kafka.core.KafkaTemplate;

                import java.util.UUID;

                import static org.assertj.core.api.Assertions.assertThat;

                class HeadersTravelTests {

                    @Test
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    void theHeadersGoOutAndComeBackTyped() throws Exception {
                        KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
                        ObjectMapper mapper = new ObjectMapper();
                        ShipmentsProducer producer = new ShipmentsProducer(kafkaTemplate, mapper, "orders.shipments");
                        UUID key = UUID.randomUUID();
                        producer.send(key, new OrderShipped("o-1"), new OrderShippedHeaders(true, 3, "acme", null));

                        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass((Class) ProducerRecord.class);
                        Mockito.verify(kafkaTemplate).send(sent.capture());
                        ProducerRecord<String, String> out = sent.getValue();
                        assertThat(out.key()).isEqualTo(key.toString());
                        assertThat(new String(out.headers().lastHeader("attempt").value())).isEqualTo("3");
                        // No correlation id given: a new UUID.
                        String trace = new String(out.headers().lastHeader("trace-id").value());
                        assertThat(UUID.fromString(trace)).isNotNull();

                        ConsumerRecord<String, String> in = new ConsumerRecord<>("orders.shipments", 0, 0L, out.key(), out.value());
                        out.headers().forEach(h -> in.headers().add(h));
                        OrderShippedHeaders read = ShipmentsListener.headersOf(in);
                        assertThat(read).isEqualTo(new OrderShippedHeaders(true, 3, "acme", trace));
                    }
                }
                """);
        runMvn(project, "-B", "package");
        assertThat(project.resolve("target/surefire-reports/com.menora.shipments.events.HeadersTravelTests.txt")).exists();
    }

    /**
     * Keys, headers (a trait's too) and correlation ids on JSON and Avro channels, and the Avro ones
     * through Confluent's schema registry: the project compiles and its tests pass — the producers'
     * records and headers, the listeners' headers read back, and a record through an in-memory
     * registry ({@code mock://}). Needs the avro-maven-plugin and Confluent's serializer, which the
     * offline repository does not have: {@code -Dsmoke.be=true -Dsmoke.confluent=true}, online (or
     * through a mirror of packages.confluent.io).
     */
    @ParameterizedTest(name = "schema registry: {0}")
    @ValueSource(strings = {"none", "confluent"})
    @EnabledIfSystemProperty(named = "smoke.confluent", matches = "true")
    void eventsWizardKeysHeadersAndRegistryBackendCompilesAndPassesItsTests(String registry, @TempDir Path workDir) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "orders");
        body.put("packageName", "com.menora.orders");
        body.put("bootVersion", "3.2.1");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("kafka"));
        body.put("asyncApiByDep", Map.of("kafka", WizardGoldenOutputTests.fixture("orders-avro-registry-asyncapi.yaml")));
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("generateTests", true);
        if (!registry.equals("none")) options.put("schemaRegistry", registry);
        options.put("channels", List.of(
                Map.of("name", "orderPlaced", "produce", true, "consume", true),
                Map.of("name", "shipments", "produce", true, "consume", true),
                Map.of("name", "refunds", "produce", true, "consume", true)));
        body.put("eventOptions", Map.of("kafka", options));
        Path project = postAndExtractWizard(workDir, body);
        runMvn(project, "-B", "package");
        assertThat(project.resolve("target/surefire-reports/com.menora.orders.events.OrderPlacedProducerTest.txt")).exists();
        assertThat(project.resolve("target/surefire-reports/com.menora.orders.events.RefundsListenerTest.txt")).exists();
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
