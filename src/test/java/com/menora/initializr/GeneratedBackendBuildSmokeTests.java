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

    @org.junit.jupiter.api.Test
    void liveApiOfABackendWithEveryOptBehaves(@TempDir Path workDir) throws Exception {
        // The generated tests mock the service, so no SQL the options add is ever executed by
        // them. This build adds a test that drives the real API over the demo rows — see
        // LIVE_ORDERS_TESTS for what it holds the generated code to.
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("opts", Map.of("scaffold", List.of("audit", "softDelete", "inverseCollections", "csvExport",
                "csvImport", "bulkDelete", "bulkUpdate", "optimisticLocking", "seedData", "tests")));
        Path backend = postAndExtractBackend(workDir, body);
        writeLiveTest(backend, "com.menora.support", LIVE_ORDERS_TESTS);
        runMvn(backend, "-B", "package");
    }

    /** Test methods run against the generated Orders backend (every option on) and its demo rows. */
    private static final String LIVE_ORDERS_TESTS = """
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

                    @SuppressWarnings("unused")
                    private static final Object[] KEEPS_IMPORTS_USED = {MediaType.APPLICATION_JSON,
                            (java.util.function.Function<String, Object>) p -> patch(p),
                            (java.util.function.Function<String, Object>) p -> put(p),
                            (java.util.function.Function<String, Object>) p -> post(p),
                            (java.util.function.Function<String, Object>) p -> delete(p)};

                %s}
                """.formatted(packageName, methods);
        Path file = backend.resolve("src/test/java/" + packageName.replace('.', '/') + "/LiveApiTests.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
