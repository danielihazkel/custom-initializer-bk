package com.menora.initializr;

import org.junit.jupiter.api.Test;
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

    @Test
    @SuppressWarnings("unchecked")
    void approvalsBackendCompilesAndPassesItsTests(@TempDir Path workDir) throws Exception {
        // The Approvals example with every opt its backend reads: the workflow's transitions,
        // claim/release and inbox filters, stats, locking, audit, demo data, CSV export and the
        // generated controller tests.
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("approvals", "react-tailwind-crud");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("opts", Map.of("scaffold", List.of("audit", "optimisticLocking", "csvExport", "seedData", "tests")));
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            Map<String, Object> workflow = (Map<String, Object>) entity.get("workflow");
            if (workflow == null) continue;
            for (Map<String, Object> t : (List<Map<String, Object>>) workflow.get("transitions")) t.remove("roles");
        }
        runMvn(postAndExtractBackend(workDir, body), "-B", "package");
    }

    @Test
    void classicBackendWithEveryOptCompilesAndPassesItsTests(@TempDir Path workDir) throws Exception {
        // The Orders entities (relations, enums, booleans, dates and money, no workflow) with the
        // bulk, import, soft-delete and inverse-collection code paths on.
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("opts", Map.of("scaffold", List.of("audit", "softDelete", "inverseCollections", "csvExport",
                "csvImport", "bulkDelete", "bulkUpdate", "optimisticLocking", "seedData", "tests")));
        runMvn(postAndExtractBackend(workDir, body), "-B", "package");
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
