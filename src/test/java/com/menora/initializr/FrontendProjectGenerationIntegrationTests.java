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

    // ── Tier 1.1: starter code for package.json-only deps ──────────────────

    @Test
    void selectingReactRouterWritesRoutesAndWiresProvider() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("router-react-router");
        Map<String, String> files = generator.generateFileMap(desc);

        assertThat(files).containsKey("src/app/routes.tsx");
        assertThat(files.get("src/app/routes.tsx")).contains("createBrowserRouter");

        String app = files.get("src/app/App.tsx");
        assertThat(app).contains("import { RouterProvider } from 'react-router-dom'");
        assertThat(app).contains("import { router } from '@app/routes'");
        assertThat(app).contains("<RouterProvider router={router} />");
        // When router is selected the bare HomePage import is no longer needed.
        assertThat(app).doesNotContain("import { HomePage }");
    }

    @Test
    void selectingZustandWithSampleStoreWritesCounterStore() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("state-zustand");
        optionsContext.populate(java.util.Map.of("state-zustand", java.util.List.of("sample-store")));
        Map<String, String> files = generator.generateFileMap(desc);

        assertThat(files).containsKey("src/entities/counter/model/store.ts");
        assertThat(files.get("src/entities/counter/model/store.ts"))
                .contains("import { create } from 'zustand'")
                .contains("useCounterStore");
    }

    @Test
    void selectingZustandWithoutSampleStoreOmitsCounterStore() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("state-zustand");
        Map<String, String> files = generator.generateFileMap(desc);

        // Zustand npm dep present, but the sample file is gated on sample-store.
        assertThat(files).doesNotContainKey("src/entities/counter/model/store.ts");
        assertThat(files.get("package.json")).contains("\"zustand\"");
    }

    @Test
    void zustandDevtoolsSubOptionFlipsMiddlewareImport() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("state-zustand");
        optionsContext.populate(java.util.Map.of(
                "state-zustand", java.util.List.of("sample-store", "devtools")));
        Map<String, String> files = generator.generateFileMap(desc);

        String store = files.get("src/entities/counter/model/store.ts");
        assertThat(store).contains("import { devtools } from 'zustand/middleware'");
        assertThat(store).contains("devtools(");
    }

    @Test
    void selectingReduxToolkitWithSampleStoreWritesStoreAndProvider() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("state-redux-toolkit");
        optionsContext.populate(java.util.Map.of(
                "state-redux-toolkit", java.util.List.of("sample-store")));
        Map<String, String> files = generator.generateFileMap(desc);

        assertThat(files).containsKey("src/app/store.ts");
        assertThat(files).containsKey("src/entities/counter/model/counterSlice.ts");
        assertThat(files).containsKey("src/shared/lib/hooks.ts");

        String app = files.get("src/app/App.tsx");
        assertThat(app).contains("import { Provider as ReduxProvider } from 'react-redux'");
        assertThat(app).contains("import { store } from '@app/store'");
        assertThat(app).contains("<ReduxProvider store={store}>");
    }

    @Test
    void selectingReduxToolkitWithoutSampleStoreSkipsStoreFilesAndProvider() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("state-redux-toolkit");
        Map<String, String> files = generator.generateFileMap(desc);

        assertThat(files).doesNotContainKey("src/app/store.ts");
        assertThat(files).doesNotContainKey("src/entities/counter/model/counterSlice.ts");
        assertThat(files).doesNotContainKey("src/shared/lib/hooks.ts");
        // App.tsx should not reference a non-existent @app/store.
        String app = files.get("src/app/App.tsx");
        assertThat(app).doesNotContain("ReduxProvider");
        assertThat(app).doesNotContain("@app/store");
    }

    @Test
    void tailwindDarkModeSubOptionAddsDarkModeKey() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("style-tailwind");
        optionsContext.populate(java.util.Map.of(
                "style-tailwind", java.util.List.of("dark-mode")));
        Map<String, String> files = generator.generateFileMap(desc);

        String cfg = files.get("tailwind.config.js");
        assertThat(cfg).contains("darkMode: 'class'");
    }

    @Test
    void tailwindWithoutDarkModeOmitsDarkModeKey() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("style-tailwind");
        Map<String, String> files = generator.generateFileMap(desc);

        String cfg = files.get("tailwind.config.js");
        assertThat(cfg).doesNotContain("darkMode");
    }

    @Test
    void vitestCiConfigSubOptionWritesWorkflow() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("test-vitest-rtl");
        optionsContext.populate(java.util.Map.of(
                "test-vitest-rtl", java.util.List.of("ci-config")));
        Map<String, String> files = generator.generateFileMap(desc);

        assertThat(files).containsKey(".github/workflows/ci.yml");
        assertThat(files.get(".github/workflows/ci.yml")).contains("pnpm install");
    }

    @Test
    void selectingTanstackQueryWritesClientAndWiresProvider() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("data-tanstack-query");
        Map<String, String> files = generator.generateFileMap(desc);

        assertThat(files).containsKey("src/shared/api/queryClient.ts");
        assertThat(files.get("src/shared/api/queryClient.ts"))
                .contains("new QueryClient");

        String app = files.get("src/app/App.tsx");
        assertThat(app).contains("import { QueryClientProvider } from '@tanstack/react-query'");
        assertThat(app).contains("import { queryClient } from '@shared/api/queryClient'");
        assertThat(app).contains("<QueryClientProvider client={queryClient}>");
    }

    // ── Tier 1.2: palette injection for shadcn + styled-components ─────────

    @Test
    void shadcnInjectsPaletteAsHslCssVariables() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("style-tailwind");
        desc.getDependencies().add("design-shadcn");
        // menora-default: primary=#1976d2 → 210 80% 46% (rounded)
        Map<String, String> files = generator.generateFileMap(desc);

        String css = files.get("src/index.css");
        assertThat(css).isNotNull();
        // shadcn TEMPLATE overwrites the bare style-tailwind index.css (sortOrder 10 > 2).
        assertThat(css).contains("@tailwind base;");
        assertThat(css).contains("--primary:");
        assertThat(css).contains("--secondary:");
        assertThat(css).contains("--ring:");
        // Concrete HSL value derived from #1976d2 — 210 deg hue.
        assertThat(css).contains("210 ");
    }

    @Test
    void styledComponentsWritesThemeAndWrapsApp() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("style-styled");
        desc.setColorPaletteId("forest");
        Map<String, String> files = generator.generateFileMap(desc);

        String theme = files.get("src/shared/theme/theme.ts");
        assertThat(theme).isNotNull();
        assertThat(theme).contains("import type { DefaultTheme } from 'styled-components'");
        // forest seed: primary=#2e7d32
        assertThat(theme).contains("primary: '#2e7d32'");

        assertThat(files).containsKey("src/shared/theme/styled.d.ts");

        String app = files.get("src/app/App.tsx");
        assertThat(app).contains("import { ThemeProvider } from 'styled-components'");
        assertThat(app).contains("<ThemeProvider theme={theme}>");
    }

    // ── Tier 1.3: ADD_NPM_SCRIPT customizations ────────────────────────────

    @Test
    void seededNpmScriptsLandInPackageJson() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        Map<String, String> files = generator.generateFileMap(desc);
        String pkg = files.get("package.json");
        // Seeded against __common__ via ADD_NPM_SCRIPT
        assertThat(pkg).contains("\"lint:fix\"");
        assertThat(pkg).contains("\"format:check\"");
        assertThat(pkg).contains("\"typecheck\"");
    }

    @Test
    void playwrightAddsItsExtraScripts() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("test-playwright");
        Map<String, String> files = generator.generateFileMap(desc);
        String pkg = files.get("package.json");
        assertThat(pkg).contains("\"e2e:install\"");
        assertThat(pkg).contains("\"e2e:report\"");
    }

    // ── Tier 1.4: React-version-keyed compatibility filtering ──────────────

    @Test
    void chakraIncludedForReact18Default() {
        ResponseEntity<String> r = rest.getForEntity("/frontend/metadata", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"id\":\"design-chakra\"");
        // The range is exposed for UI badging when set.
        assertThat(r.getBody()).contains("\"versionRange\":\"[18.0.0,19.0.0)\"");
    }

    @Test
    void chakraFilteredOutForReact19() {
        ResponseEntity<String> r = rest.getForEntity(
                "/frontend/metadata?reactVersion=19", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).doesNotContain("\"id\":\"design-chakra\"");
        // Other deps without ranges still appear.
        assertThat(r.getBody()).contains("\"id\":\"state-zustand\"");
    }

    @Test
    void incompatibleDepsDroppedFromGeneration() throws Exception {
        // Using the HTTP endpoint so the controller's filter runs.
        ResponseEntity<byte[]> r = rest.getForEntity(
                "/frontend/starter.zip?reactVersion=19&dependencies=design-chakra,state-zustand",
                byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Inspect package.json — chakra deps should not appear.
        String pkg = readZipEntry(r.getBody(), "demo/package.json");
        assertThat(pkg).doesNotContain("@chakra-ui/react");
        assertThat(pkg).contains("\"zustand\"");
    }

    @Test
    void previewEndpointPropagatesSubOptionFromOptsQueryParam() {
        // Verifies the full HTTP path: opts-* param → filter → ProjectOptionsContext
        // → generator gating. Without this end-to-end check we'd only know the
        // service-layer wiring works, not the route from the UI.
        String url = "/frontend/starter.preview?dependencies=state-zustand"
                + "&opts-state-zustand=sample-store,devtools";
        ResponseEntity<String> r = rest.getForEntity(url, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"path\":\"src/entities/counter/model/store.ts\"");
        assertThat(body).contains("zustand/middleware");
        assertThat(body).contains("devtools(");
    }

    @Test
    void previewEndpointWithoutSubOptionOmitsGatedFile() {
        ResponseEntity<String> r = rest.getForEntity(
                "/frontend/starter.preview?dependencies=state-zustand", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).isNotNull();
        assertThat(body).doesNotContain("\"path\":\"src/entities/counter/model/store.ts\"");
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

    // ── Tier 1.5: REQUIRES/CONFLICTS resolved server-side ──────────────────

    @Test
    void shadcnAutoAddsTailwindViaRequiresRule() throws Exception {
        // design-shadcn REQUIRES style-tailwind (seeded). Hitting the endpoint with
        // only shadcn should produce a project that also includes tailwind, so
        // direct API hits (curl, IntelliJ) get the same UX the UI surfaces.
        ResponseEntity<byte[]> r = rest.getForEntity(
                "/frontend/starter.zip?dependencies=design-shadcn", byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String pkg = readZipEntry(r.getBody(), "demo/package.json");
        assertThat(pkg).contains("\"tailwindcss\"");
        // And the shadcn-emitted index.css overwrite still runs.
        String css = readZipEntry(r.getBody(), "demo/src/index.css");
        assertThat(css).contains("--primary:");
    }

    @Test
    void conflictingDesignSystemsDropTheLaterSelection() throws Exception {
        // design-mui CONFLICTS design-chakra (seeded). With mui first, chakra is dropped.
        ResponseEntity<byte[]> r = rest.getForEntity(
                "/frontend/starter.zip?dependencies=design-mui,design-chakra", byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String pkg = readZipEntry(r.getBody(), "demo/package.json");
        assertThat(pkg).contains("@mui/material");
        assertThat(pkg).doesNotContain("@chakra-ui/react");
    }

    @Test
    void conflictResolutionRespectsRequestOrder() throws Exception {
        // Same conflict, opposite selection order — chakra is earlier so MUI is dropped.
        ResponseEntity<byte[]> r = rest.getForEntity(
                "/frontend/starter.zip?dependencies=design-chakra,design-mui", byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String pkg = readZipEntry(r.getBody(), "demo/package.json");
        assertThat(pkg).contains("@chakra-ui/react");
        assertThat(pkg).doesNotContain("@mui/material");
    }

    // ── Tier 1.6: FE starter templates surface and apply end-to-end ────────

    @Test
    void feStarterTemplatesEndpointReturnsSeededBundles() {
        ResponseEntity<String> r = rest.getForEntity(
                "/metadata/starter-templates?projectKind=FRONTEND", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).isNotNull();

        // All three FE bundles present.
        assertThat(body).contains("\"id\":\"fe-dashboard\"");
        assertThat(body).contains("\"id\":\"fe-marketing\"");
        assertThat(body).contains("\"id\":\"fe-saas-app\"");

        // A representative dep per bundle proves the templateDep rows landed.
        assertThat(body).contains("\"depId\":\"design-shadcn\"");
        assertThat(body).contains("\"depId\":\"auth-msal\"");
        // Sub-options come through as a string array.
        assertThat(body).contains("\"sample-store\"");
        assertThat(body).contains("\"rhf-zod-resolver\"");

        // Backend templates must not leak into the FRONTEND-filtered list.
        assertThat(body).doesNotContain("\"id\":\"rest-api\"");
        assertThat(body).doesNotContain("\"id\":\"event-driven\"");
    }

    @Test
    void feStarterTemplatesAreFilteredOutOfBackendListing() {
        ResponseEntity<String> r = rest.getForEntity(
                "/metadata/starter-templates?projectKind=BACKEND", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).isNotNull();
        assertThat(body).doesNotContain("\"id\":\"fe-dashboard\"");
        assertThat(body).doesNotContain("\"id\":\"fe-marketing\"");
        assertThat(body).doesNotContain("\"id\":\"fe-saas-app\"");
        // Sanity: BACKEND bundles still show up.
        assertThat(body).contains("\"id\":\"rest-api\"");
    }

    @Test
    void feDashboardTemplateGeneratesExpectedFiles() throws Exception {
        // Mirrors the dependency + sub-option payload that applying the
        // fe-dashboard card in the UI would send. Verifies the bundle wires
        // up Tailwind/shadcn theming, router, sample stores and a sample form.
        FrontendProjectDescription desc = baseDescription("demo");
        desc.getDependencies().add("style-tailwind");
        desc.getDependencies().add("design-shadcn");
        desc.getDependencies().add("router-react-router");
        desc.getDependencies().add("state-zustand");
        desc.getDependencies().add("data-tanstack-query");
        desc.getDependencies().add("form-react-hook-form");
        optionsContext.populate(java.util.Map.of(
                "style-tailwind",       java.util.List.of("dark-mode"),
                "router-react-router",  java.util.List.of("sample-routes", "lazy-routes"),
                "state-zustand",        java.util.List.of("sample-store", "devtools"),
                "data-tanstack-query",  java.util.List.of("sample-query", "axios-base", "devtools"),
                "form-react-hook-form", java.util.List.of("rhf-zod-resolver")));

        Map<String, String> files = generator.generateFileMap(desc);

        // Tailwind + shadcn theming
        assertThat(files).containsKey("tailwind.config.js");
        assertThat(files.get("tailwind.config.js")).contains("darkMode: 'class'");
        assertThat(files.get("src/index.css")).contains("--primary:");

        // Router + sample routes
        assertThat(files).containsKey("src/app/routes.tsx");
        assertThat(files).containsKey("src/pages/about/ui/AboutPage.tsx");

        // Sample stores + query + axios + sample-query hook
        assertThat(files).containsKey("src/entities/counter/model/store.ts");
        assertThat(files.get("src/entities/counter/model/store.ts"))
                .contains("import { devtools } from 'zustand/middleware'");
        assertThat(files).containsKey("src/shared/api/queryClient.ts");
        assertThat(files).containsKey("src/shared/api/axios.ts");
        assertThat(files).containsKey("src/features/users/api/useUsers.ts");

        // package.json carries every selected library
        String pkg = files.get("package.json");
        assertThat(pkg).contains("\"tailwindcss\"");
        assertThat(pkg).contains("\"react-router-dom\"");
        assertThat(pkg).contains("\"zustand\"");
        assertThat(pkg).contains("\"@tanstack/react-query\"");
        assertThat(pkg).contains("\"react-hook-form\"");
    }

    // ── Tier 1.7: Paired-backend wiring (apiBaseUrl / backendArtifactId) ────

    @Test
    void pairedBackendWritesEnvAndProxy() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        desc.setApiBaseUrl("http://localhost:8080");
        desc.setBackendArtifactId("demo-api");
        Map<String, String> files = generator.generateFileMap(desc);

        // .env.development carries the runtime base URL the axios.ts baseline reads.
        String envDev = files.get(".env.development");
        assertThat(envDev).isNotNull();
        assertThat(envDev).contains("VITE_API_BASE_URL=http://localhost:8080");

        // .env.example documents the override.
        String envExample = files.get(".env.example");
        assertThat(envExample).isNotNull();
        assertThat(envExample).contains("VITE_API_BASE_URL=http://localhost:8080");

        // vite.config.ts gets a /api → backend proxy.
        String vite = files.get("vite.config.ts");
        assertThat(vite).contains("proxy: {");
        assertThat(vite).contains("'/api'");
        assertThat(vite).contains("target: 'http://localhost:8080'");
        assertThat(vite).contains("changeOrigin: true");

        // README mentions the paired backend artifact.
        String readme = files.get("README.md");
        assertThat(readme).contains("Paired backend");
        assertThat(readme).contains("demo-api");
        assertThat(readme).contains("http://localhost:8080");
    }

    @Test
    void withoutBackendPairOmitsEnvFiles() throws Exception {
        FrontendProjectDescription desc = baseDescription("demo");
        Map<String, String> files = generator.generateFileMap(desc);

        // No paired-backend hint → no env files dropped into the project.
        assertThat(files).doesNotContainKey(".env.development");
        assertThat(files).doesNotContainKey(".env.example");

        // vite.config.ts has no proxy block.
        String vite = files.get("vite.config.ts");
        assertThat(vite).doesNotContain("proxy:");

        // README does not mention the pairing section.
        String readme = files.get("README.md");
        assertThat(readme).doesNotContain("Paired backend");
    }

    @Test
    void pairedBackendWiringFlowsThroughZipEndpoint() throws Exception {
        ResponseEntity<byte[]> r = rest.getForEntity(
                "/frontend/starter.zip?projectName=demo&apiBaseUrl=http://localhost:8080&backendArtifactId=demo-api",
                byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        String envDev = readZipEntry(r.getBody(), "demo/.env.development");
        assertThat(envDev).contains("VITE_API_BASE_URL=http://localhost:8080");

        String vite = readZipEntry(r.getBody(), "demo/vite.config.ts");
        assertThat(vite).contains("target: 'http://localhost:8080'");
    }

    @Test
    void apiBaseUrlMustBeHttpUrl() {
        FrontendProjectDescription desc = baseDescription("demo");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> desc.setApiBaseUrl("javascript:alert(1)"));
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

    private static String readZipEntry(byte[] bytes, String entryName) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals(entryName)) {
                    return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
