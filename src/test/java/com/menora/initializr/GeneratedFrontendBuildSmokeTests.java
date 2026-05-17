package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Hits {@code /frontend/starter.zip} with the supplied query string,
     * unpacks the ZIP under {@code workDir}, and returns the resolved project
     * root (the single top-level directory inside the ZIP).
     */
    private Path fetchAndExtract(Path workDir, String url) throws Exception {
        ResponseEntity<byte[]> r = rest.getForEntity(url, byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNotNull();

        String topLevel = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(r.getBody()))) {
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
