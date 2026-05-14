package com.menora.initializr;

import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.extension.frontend.FrontendProjectDescription;
import com.menora.initializr.extension.frontend.FrontendProjectGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke tests for the React/Vite/FSD frontend generator. Mirrors the
 * shape of {@link ProjectGenerationIntegrationTests} for the backend pipeline.
 *
 * Asserts both the HTTP path (/frontend/metadata, /frontend/starter.zip) and the
 * service path (FrontendProjectGenerator directly via generateFileMap) so we
 * can inspect file contents without unzipping every time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrontendProjectGenerationIntegrationTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FrontendProjectGenerator generator;

    @Autowired
    private ProjectOptionsContext optionsContext;

    @Test
    void frontendMetadataEndpointReturnsCatalog() {
        ResponseEntity<String> r = rest.getForEntity("/frontend/metadata", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).isNotNull();
        // A couple of expected catalog entries — proves FRONTEND seeding ran and
        // that the kind filter returns FE-only rows.
        assertThat(body).contains("router-react-router");
        assertThat(body).contains("state-zustand");
        assertThat(body).contains("style-tailwind");
        // Versions block exposed
        assertThat(body).contains("reactVersions");
        assertThat(body).contains("packageManagers");
        // Should NOT leak backend catalog ids
        assertThat(body).doesNotContain("rqueue");
        assertThat(body).doesNotContain("postgresql");
    }

    @Test
    void backendMetadataDoesNotLeakFrontendEntries() {
        ResponseEntity<String> r = rest.getForEntity("/metadata/client", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).isNotNull();
        // Should NOT contain any frontend catalog ids
        assertThat(body).doesNotContain("router-react-router");
        assertThat(body).doesNotContain("state-zustand");
        assertThat(body).doesNotContain("style-tailwind");
    }

    @Test
    void starterZipEndpointReturnsValidZipWithProjectRoot() throws Exception {
        ResponseEntity<byte[]> r = rest.getForEntity(
                "/frontend/starter.zip?projectName=demo&appTitle=Demo", byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNotEmpty();

        List<String> entries = listZip(r.getBody());
        // Every entry under a top-level demo/ directory
        assertThat(entries).allMatch(e -> e.startsWith("demo/"));
        // Essentials present
        assertThat(entries).contains(
                "demo/package.json",
                "demo/vite.config.ts",
                "demo/index.html",
                "demo/tsconfig.json",
                "demo/.editorconfig",
                "demo/.gitignore",
                "demo/Dockerfile",
                "demo/eslint.config.js",
                "demo/src/main.tsx",
                "demo/src/app/App.tsx",
                "demo/src/app/index.ts",
                "demo/src/pages/index.ts",
                "demo/src/pages/home/index.ts",
                "demo/src/pages/home/ui/HomePage.tsx",
                "demo/src/widgets/index.ts",
                "demo/src/features/index.ts",
                "demo/src/entities/index.ts",
                "demo/src/shared/index.ts"
        );
    }

    @Test
    void baselinePackageJsonHasReactAndVite() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        Map<String, String> files = generator.generateFileMap(desc);
        String pkg = files.get("package.json");
        assertThat(pkg).isNotNull();
        // From __common__ npm deps
        assertThat(pkg).contains("\"react\"");
        assertThat(pkg).contains("\"react-dom\"");
        assertThat(pkg).contains("\"vite\"");
        assertThat(pkg).contains("\"@vitejs/plugin-react\"");
        assertThat(pkg).contains("\"typescript\"");
        assertThat(pkg).contains("\"eslint\"");
        assertThat(pkg).contains("\"prettier\"");
        assertThat(pkg).contains("\"husky\"");
        // Project name
        assertThat(pkg).contains("\"demo\"");
    }

    @Test
    void viteConfigContainsReactPluginCall() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        Map<String, String> files = generator.generateFileMap(desc);
        String vite = files.get("vite.config.ts");
        assertThat(vite).isNotNull();
        assertThat(vite).contains("import react from '@vitejs/plugin-react';");
        assertThat(vite).contains("plugins: [react()]");
        assertThat(vite).contains("'@app'");
        assertThat(vite).contains("'@shared'");
    }

    @Test
    void selectingTailwindAddsConfigsAndDeps() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("style-tailwind");
        Map<String, String> files = generator.generateFileMap(desc);

        // Tailwind-specific files appear
        assertThat(files).containsKey("tailwind.config.js");
        assertThat(files).containsKey("postcss.config.js");
        assertThat(files).containsKey("src/index.css");
        // Tailwind packages in package.json (devDependencies)
        String pkg = files.get("package.json");
        assertThat(pkg).contains("\"tailwindcss\"");
        assertThat(pkg).contains("\"postcss\"");
        assertThat(pkg).contains("\"autoprefixer\"");
        // main.tsx wired with index.css import
        assertThat(files.get("src/main.tsx")).contains("import './index.css'");
    }

    @Test
    void selectingZustandAddsNpmDepOnly() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("state-zustand");
        Map<String, String> files = generator.generateFileMap(desc);

        String pkg = files.get("package.json");
        assertThat(pkg).contains("\"zustand\"");
        // No tailwind drift
        assertThat(files).doesNotContainKey("tailwind.config.js");
    }

    @Test
    void selectingVitestAddsConfigAndTestScript() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("test-vitest-rtl");
        Map<String, String> files = generator.generateFileMap(desc);

        assertThat(files).containsKey("vitest.config.ts");
        assertThat(files).containsKey("src/test-setup.ts");
        String pkg = files.get("package.json");
        assertThat(pkg).contains("\"vitest\"");
        assertThat(pkg).contains("\"@testing-library/react\"");
        // Script gated on hasTestVitestRtl
        assertThat(pkg).contains("\"test\"");
    }

    @Test
    void readmeMentionsPackageManagerFromForm() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.setPackageManager("npm");
        Map<String, String> files = generator.generateFileMap(desc);
        String readme = files.get("README.md");
        assertThat(readme).contains("npm install");
        assertThat(readme).doesNotContain("pnpm install");
    }

    @Test
    void scopeChangesPackageJsonName() throws Exception {
        FrontendProjectDescription desc = baseDescription("my-app");
        desc.setScope("menora");
        Map<String, String> files = generator.generateFileMap(desc);
        assertThat(files.get("package.json")).contains("\"@menora/my-app\"");
    }

    @Test
    void appTitleAppearsInIndexHtml() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.setAppTitle("Menora Cool App");
        Map<String, String> files = generator.generateFileMap(desc);
        assertThat(files.get("index.html")).contains("<title>Menora Cool App</title>");
    }

    @Test
    void muiThemeUsesDefaultPaletteColorsWhenPaletteIdOmitted() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("design-mui");
        Map<String, String> files = generator.generateFileMap(desc);

        String theme = files.get("src/shared/theme/theme.ts");
        assertThat(theme).isNotNull();
        // menora-default seed: primary=#1976d2, secondary=#9c27b0
        assertThat(theme).contains("primary: { main: '#1976d2' }");
        assertThat(theme).contains("secondary: { main: '#9c27b0' }");
    }

    @Test
    void muiThemeUsesSelectedPalette() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("design-mui");
        desc.setColorPaletteId("forest");
        Map<String, String> files = generator.generateFileMap(desc);

        String theme = files.get("src/shared/theme/theme.ts");
        assertThat(theme).isNotNull();
        // forest seed: primary=#2e7d32, secondary=#8d6e63
        assertThat(theme).contains("primary: { main: '#2e7d32' }");
        assertThat(theme).contains("secondary: { main: '#8d6e63' }");
    }

    @Test
    void chakraThemeReceivesPalettePrimaryAndSecondary() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("design-chakra");
        desc.setColorPaletteId("slate");
        Map<String, String> files = generator.generateFileMap(desc);

        String theme = files.get("src/shared/theme/theme.ts");
        assertThat(theme).isNotNull();
        // slate seed: primary=#475569, secondary=#0ea5e9
        assertThat(theme).contains("500: '#475569'");
        assertThat(theme).contains("500: '#0ea5e9'");
    }

    @Test
    void mantineThemePopulatesBrandTuple() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("design-mantine");
        desc.setColorPaletteId("forest");
        Map<String, String> files = generator.generateFileMap(desc);

        String theme = files.get("src/shared/theme/theme.ts");
        assertThat(theme).isNotNull();
        assertThat(theme).contains("'#2e7d32'");
        assertThat(theme).contains("primaryColor: 'brand'");
    }

    @Test
    void designNoneIgnoresPalette() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("design-none");
        desc.setColorPaletteId("forest");
        Map<String, String> files = generator.generateFileMap(desc);

        // No theme file is written for design-none, palette is irrelevant
        assertThat(files).doesNotContainKey("src/shared/theme/theme.ts");
    }

    @Test
    void unknownPaletteIdFallsBackToDefault() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("design-mui");
        desc.setColorPaletteId("nonexistent-xyz");
        Map<String, String> files = generator.generateFileMap(desc);

        // Falls back to menora-default
        assertThat(files.get("src/shared/theme/theme.ts")).contains("primary: { main: '#1976d2' }");
    }

    @Test
    void metadataExposesColorPalettes() {
        ResponseEntity<String> r = rest.getForEntity("/frontend/metadata", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("colorPalettes");
        assertThat(body).contains("menora-default");
        assertThat(body).contains("\"primary\":\"#1976d2\"");
    }

    @Test
    void metadataExposesAllSeededPalettes() {
        ResponseEntity<String> r = rest.getForEntity("/frontend/metadata", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).isNotNull();
        // Original three
        assertThat(body).contains("\"id\":\"menora-default\"");
        assertThat(body).contains("\"id\":\"forest\"");
        assertThat(body).contains("\"id\":\"slate\"");
        // Brand-inspired additions
        assertThat(body).contains("\"id\":\"stripe-purple\"");
        assertThat(body).contains("\"id\":\"vercel-mono\"");
        assertThat(body).contains("\"id\":\"linear-violet\"");
        assertThat(body).contains("\"id\":\"github-blue\"");
        assertThat(body).contains("\"id\":\"notion-warm\"");
        assertThat(body).contains("\"id\":\"tailwind-sky\"");
    }

    @Test
    void starterPreviewEndpointReturnsFilesAndTree() {
        ResponseEntity<String> r = rest.getForEntity(
                "/frontend/starter.preview?projectName=demo&dependencies=style-tailwind", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).isNotNull();
        // No top-level project-name prefix on preview paths (unlike the zip)
        assertThat(body).contains("\"path\":\"package.json\"");
        assertThat(body).contains("\"path\":\"vite.config.ts\"");
        assertThat(body).contains("\"path\":\"src/main.tsx\"");
        // Tailwind dep contribution shows up
        assertThat(body).contains("\"path\":\"tailwind.config.js\"");
        // Tree structure present with a src directory node
        assertThat(body).contains("\"name\":\"src\"");
        assertThat(body).contains("\"type\":\"directory\"");
        // Both top-level keys present
        assertThat(body).contains("\"files\":");
        assertThat(body).contains("\"tree\":");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private FrontendProjectDescription baseDescription(String projectName) {
        optionsContext.clear();
        FrontendProjectDescription desc = new FrontendProjectDescription();
        desc.setProjectName(projectName);
        desc.setAppTitle("Demo");
        desc.setDescription("test");
        desc.setReactVersion("18");
        desc.setNodeVersion("20");
        desc.setPackageManager("pnpm");
        return desc;
    }

    private static List<String> listZip(byte[] bytes) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }
}
