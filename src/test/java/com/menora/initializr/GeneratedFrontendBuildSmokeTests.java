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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke tests that actually compile a generated frontend project.
 *
 * <p>The other FE tests assert that the right files appear in the ZIP; this
 * one runs {@code pnpm install --prefer-offline} and {@code pnpm run build}
 * against the unpacked output, so a busted version pin in {@code DataSeeder}
 * (e.g. a Vite plugin major that requires a newer Vite) is caught before
 * users see it.
 *
 * <p>Gated by {@code -Dsmoke.fe=true} so the default {@code mvn test} stays
 * fast and Node-free. CI runs:
 * <pre>{@code mvn test -Dsmoke.fe=true}</pre>
 * The runner needs {@code pnpm} on {@code PATH} (corepack is fine). On
 * Windows we invoke {@code pnpm.cmd}; everywhere else {@code pnpm}.
 */
@EnabledIfSystemProperty(named = "smoke.fe", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GeneratedFrontendBuildSmokeTests {

    private static final Logger log = LoggerFactory.getLogger(GeneratedFrontendBuildSmokeTests.class);
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String PNPM = IS_WINDOWS ? "pnpm.cmd" : "pnpm";
    private static final File NULL_DEVICE = new File(IS_WINDOWS ? "NUL" : "/dev/null");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void baselineFrontendProjectInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        Path project = fetchAndExtract(workDir, "/frontend/starter.zip?projectName=smoke-baseline");
        runPnpm(project, "install", "--prefer-offline");
        runPnpm(project, "run", "build");
    }

    @Test
    void richFrontendProjectInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        // Catches version conflicts between the libs people pick together most.
        // The build script is `tsc --noEmit && vite build` — that's the
        // signal we want: type-check + bundle both succeed end-to-end. Skipping
        // `pnpm run test` here because no spec files ship by default, vitest's
        // own CLI is verified elsewhere, and forwarding flags through `pnpm run`
        // is brittle across pnpm versions.
        String deps = "style-tailwind,design-shadcn,data-tanstack-query,test-vitest-rtl,router-react-router";
        Path project = fetchAndExtract(workDir,
                "/frontend/starter.zip?projectName=smoke-rich&dependencies=" + deps);
        runPnpm(project, "install", "--prefer-offline");
        runPnpm(project, "run", "build");
    }

    @Test
    void menoraDigitalFrontendProjectInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        // The Menora Digital design system: plain-CSS tokens + React component ports + the
        // Assistant font (needs @fontsource/assistant on the npm mirror). RTL on, as the brand is.
        Path project = fetchAndExtract(workDir,
                "/frontend/starter.zip?projectName=smoke-menora&dependencies=design-menora-digital&rtl=true");
        runPnpm(project, "install", "--prefer-offline");
        runPnpm(project, "run", "build");
    }

    @Test
    void menoraDigitalWithTailwindProjectInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        // Co-selected with Tailwind: the Menora index.css keeps the @tailwind directives.
        Path project = fetchAndExtract(workDir,
                "/frontend/starter.zip?projectName=smoke-menora-tw&dependencies=style-tailwind,design-menora-digital");
        runPnpm(project, "install", "--prefer-offline");
        runPnpm(project, "run", "build");
    }

    @Test
    void advertisedFeaturesProjectInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        // Pulls together Playwright + Storybook + shadcn components + MSAL with all
        // of their sub-options selected — the combo the "finish what's advertised"
        // work shipped. Catches regressions in any sub-option-gated file
        // (component imports, MsalProvider wrap, .storybook config) that
        // type-checking alone misses without the matching npm dep installed.
        String deps = "style-tailwind,design-shadcn,test-playwright,storybook,auth-msal";
        String url = "/frontend/starter.zip?projectName=smoke-advertised"
                + "&dependencies=" + deps
                + "&opts-test-playwright=sample-config,sample-spec,ci-config"
                + "&opts-storybook=init-config,sample-story"
                + "&opts-design-shadcn=comp-button,comp-card,comp-input,comp-dialog,comp-toast"
                + "&opts-auth-msal=init-config,sample-login";
        Path project = fetchAndExtract(workDir, url);
        runPnpm(project, "install", "--prefer-offline");
        runPnpm(project, "run", "build");
    }

    @Test
    void fullstackFrontendEnglishInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        // The fullstack overlay (per-entity CRUD pages + shared UI + i18n strings module), default
        // English chrome. Exercises every view mode, the filter bar, bulk actions and audit columns
        // so each t()/LOCALE call site is type-checked.
        Path project = fetchFullstackFrontend(workDir, "en", "react-tailwind-crud", false);
        runPnpm(project, "install", "--prefer-offline");
        lintAll(project);
        runPnpm(project, "run", "build");
    }

    @Test
    void fullstackFrontendHebrewInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        // Same overlay with the Hebrew table selected and the Menora Digital set (its own App/Dashboard
        // templates also read t()), RTL on as the brand is.
        Path project = fetchFullstackFrontend(workDir, "he", "react-menora-digital-crud", true);
        runPnpm(project, "install", "--prefer-offline");
        lintAll(project);
        runPnpm(project, "run", "build");
    }

    @Test
    void fullstackFrontendMenoraEnglishInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        // The Menora set authors its own shared UI + entity page around the design-system ports;
        // type-check and lint them in the LTR/English configuration too.
        Path project = fetchFullstackFrontend(workDir, "en", "react-menora-digital-crud", false);
        runPnpm(project, "install", "--prefer-offline");
        lintAll(project);
        runPnpm(project, "run", "build");
    }

    @Test
    void fullstackFrontendPageLayoutsInstallAndBuild(@TempDir Path workDir) throws Exception {
        // The Orders example's page layout: a dashboard with every widget kind, plain list pages,
        // a master-detail page and a record page with a related list (the two id-driven screens).
        Path project = postAndExtractFrontend(workDir,
                FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud"));
        runPnpm(project, "install", "--prefer-offline");
        lintAll(project);
        runPnpm(project, "run", "build");
    }

    @Test
    void fullstackFrontendMenoraPageLayoutsInstallAndBuild(@TempDir Path workDir) throws Exception {
        // The Tickets layout on the Menora set: its own Tabs-port screen over preset-filtered list
        // pages, plus the borrowed dashboard and master-detail screens.
        Path project = postAndExtractFrontend(workDir,
                FullstackPagesIntegrationTests.exampleBody("tickets", "react-menora-digital-crud"));
        runPnpm(project, "install", "--prefer-offline");
        lintAll(project);
        runPnpm(project, "run", "build");
    }

    @Test
    void fullstackFrontendListPresentationInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        // The Inventory layout: its items list opens as cards with a column subset, a sort and a
        // page size, so the keyed-column type and the seeded props are type-checked and linted.
        Path project = postAndExtractFrontend(workDir,
                FullstackPagesIntegrationTests.exampleBody("inventory", "react-tailwind-crud"));
        runPnpm(project, "install", "--prefer-offline");
        lintAll(project);
        runPnpm(project, "run", "build");
    }

    @Test
    void fullstackFrontendMenoraListPresentationInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        // The same layout on the Menora set, whose own entity page carries the same props.
        Path project = postAndExtractFrontend(workDir,
                FullstackPagesIntegrationTests.exampleBody("inventory", "react-menora-digital-crud"));
        runPnpm(project, "install", "--prefer-offline");
        lintAll(project);
        runPnpm(project, "run", "build");
    }

    @Test
    void fullstackFrontendReportLayoutInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        // The Sales reporting layout: aggregate tiles, a reducing breakdown, the hand-rolled SVG
        // trend chart and the report screen (filter bar + chart + totals + CSV export).
        Path project = postAndExtractFrontend(workDir,
                FullstackPagesIntegrationTests.exampleBody("reporting", "react-tailwind-crud"));
        runPnpm(project, "install", "--prefer-offline");
        lintAll(project);
        runPnpm(project, "run", "build");
    }

    @Test
    void fullstackFrontendMenoraReportLayoutInstallsAndBuilds(@TempDir Path workDir) throws Exception {
        // The same layout on the Menora set, which borrows the report screen and the widgets but
        // renders them against its own FilterBar.
        Path project = postAndExtractFrontend(workDir,
                FullstackPagesIntegrationTests.exampleBody("reporting", "react-menora-digital-crud"));
        runPnpm(project, "install", "--prefer-offline");
        lintAll(project);
        runPnpm(project, "run", "build");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Lints the whole generated frontend with no warnings allowed: the screens gate their imports
     * (t, the enum label consts, onNavigate) on use, which only eslint's no-unused-vars catches, and
     * the generated pre-commit hook runs the same lint.
     */
    private void lintAll(Path project) throws Exception {
        runPnpm(project, "exec", "eslint", "--max-warnings", "0", ".");
    }

    /** POSTs a fullstack request, unpacks the ZIP and returns the generated {@code frontend/}. */
    private Path postAndExtractFrontend(Path workDir, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> r = rest.exchange("/starter-fullstack.zip", HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Path frontend = extract(workDir, r.getBody()).resolve("frontend");
        assertThat(Files.isDirectory(frontend)).as("generated frontend/ directory").isTrue();
        return frontend;
    }

    /**
     * POSTs a rich fullstack request (two related entities with every field kind, all list views,
     * audit/soft-delete/CSV/bulk opts) with the given chrome locale and frontend set, unpacks the
     * ZIP and returns the generated {@code frontend/} directory.
     */
    private Path fetchFullstackFrontend(Path workDir, String locale, String frontendSet, boolean rtl) throws Exception {
        Map<String, Object> pk = new LinkedHashMap<>();
        pk.put("name", "id"); pk.put("type", "Long"); pk.put("primaryKey", true); pk.put("generated", true);
        // Customer: a boolean-only breakdown, so its kanban lanes take the i18n true/false headings.
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "Customer");
        customer.put("listViews", List.of("table", "kanban"));
        customer.put("fields", List.of(pk,
                Map.of("name", "name", "type", "String", "required", true, "length", 80),
                Map.of("name", "email", "type", "String", "email", true),
                Map.of("name", "active", "type", "Boolean")));
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("name", "Order");
        order.put("listViews", List.of("table", "cards", "kanban", "calendar"));
        order.put("fields", List.of(pk,
                Map.of("name", "title", "type", "String", "required", true),
                Map.of("name", "notes", "type", "Text"),
                // One labelled constant (with an apostrophe) and one humanized — both enum lane branches.
                Map.of("name", "status", "type", "Enum", "enumValues", List.of("OPEN", "DONE"),
                        "enumLabels", Map.of("OPEN", "Open / פתוח", "DONE", "Won't fix")),
                Map.of("name", "quantity", "type", "Integer", "min", 1, "max", 999),
                Map.of("name", "total", "type", "BigDecimal"),
                Map.of("name", "shippedOn", "type", "LocalDate")));
        order.put("relations", List.of(Map.of("type", "MANY_TO_ONE", "fieldName", "customer",
                "targetEntity", "Customer", "required", true)));
        List<String> scaffold = new java.util.ArrayList<>(List.of("audit", "softDelete", "csvExport", "bulkDelete", "bulkUpdate", "inverseCollections"));
        if (rtl) scaffold.add("rtl");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "smoke-fullstack-" + locale);
        body.put("packageName", "com.menora.smoke");
        body.put("bootVersion", "3.2.1");
        body.put("locale", locale);
        body.put("frontendTemplateSet", frontendSet);
        body.put("opts", Map.of("scaffold", scaffold));
        body.put("entities", List.of(customer, order));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> r = rest.exchange("/starter-fullstack.zip", HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);
        assertThat(r.getStatusCode())
                .as("POST /starter-fullstack.zip: " + (r.getStatusCode().is2xxSuccessful() || r.getBody() == null
                        ? "" : new String(r.getBody(), StandardCharsets.UTF_8)))
                .isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNotNull();
        Path root = extract(workDir, r.getBody());
        Path frontend = root.resolve("frontend");
        assertThat(Files.isDirectory(frontend)).as("generated frontend/ directory").isTrue();
        return frontend;
    }

    /**
     * Hits {@code /frontend/starter.zip} with the supplied query string,
     * unpacks the ZIP under {@code workDir}, and returns the resolved project
     * root (the single top-level directory inside the ZIP).
     */
    private Path fetchAndExtract(Path workDir, String url) throws Exception {
        ResponseEntity<byte[]> r = rest.getForEntity(url, byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNotNull();
        return extract(workDir, r.getBody());
    }

    /** Unpacks a ZIP under {@code workDir} and returns its single top-level directory. */
    private static Path extract(Path workDir, byte[] zipBytes) throws Exception {
        String topLevel = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                Path out = workDir.resolve(e.getName()).normalize();
                if (!out.startsWith(workDir)) throw new SecurityException("zip slip: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.write(out, zip.readAllBytes());
                }
                int slash = e.getName().indexOf('/');
                if (slash > 0 && topLevel == null) topLevel = e.getName().substring(0, slash);
            }
        }
        assertThat(topLevel).as("ZIP top-level project directory").isNotNull();
        return workDir.resolve(topLevel);
    }

    /**
     * Runs {@code pnpm <args...>} in {@code cwd}, streams stdout+stderr through
     * SLF4J so JUnit captures it in the test log, and fails fast if the
     * subprocess returns non-zero or times out.
     */
    private void runPnpm(Path cwd, String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(PNPM);
        cmd.addAll(List.of(args));
        log.info("[{}] $ {}", cwd.getFileName(), String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                // Critical: redirect stdin to the OS null device. With the
                // default PIPE redirect pnpm reads stdin from a pipe we never
                // write to, so any interactive prompt (pnpm v10's
                // "approve-builds" follow-up, "modules dir from a different
                // PM, continue?", etc.) blocks forever.
                .redirectInput(NULL_DEVICE);
        // Belt-and-suspenders: pnpm/npm both respect CI=true to disable any
        // remaining interactive paths.
        pb.environment().put("CI", "true");
        Process proc = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[{}]   {}", cwd.getFileName(), line);
            }
        }

        boolean done = proc.waitFor(15, TimeUnit.MINUTES);
        if (!done) {
            proc.destroyForcibly();
            throw new AssertionError("pnpm " + String.join(" ", args) + " timed out after 15 minutes");
        }
        int exit = proc.exitValue();
        assertThat(exit)
                .as("pnpm " + String.join(" ", args) + " in " + cwd.getFileName())
                .isZero();
    }
}
