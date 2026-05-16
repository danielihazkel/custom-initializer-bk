package com.menora.initializr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for {@code POST /starter-paired.zip} and
 * {@code POST /agent/scaffold/paired}. Verifies the monorepo layout, auto-wired
 * apiBaseUrl + CORS, and the extended paired manifest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PairedStarterIntegrationTests {

    @Autowired
    private TestRestTemplate rest;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── /starter-paired.zip ──────────────────────────────────────────────────

    @Test
    void pairedZipContainsBothProjectsAndRootFiles() throws Exception {
        Map<String, Object> body = pairedBody(
                backendBody("demo-api", List.of("web")),
                frontendBody("demo-ui", List.of("style-tailwind"), null));

        ResponseEntity<byte[]> r = rest.postForEntity("/starter-paired.zip", body, byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> entries = readZipEntries(r.getBody());
        // Both halves present with expected entry points
        assertThat(entries).containsKey("backend/pom.xml");
        assertThat(entries).containsKey("frontend/package.json");
        // Frontend essentials still come through
        assertThat(entries).containsKey("frontend/vite.config.ts");
        assertThat(entries).containsKey("frontend/src/main.tsx");
        // Tailwind dep contribution lands in the frontend half
        assertThat(entries).containsKey("frontend/tailwind.config.js");
        // Root files
        assertThat(entries).containsKey("README.md");
        assertThat(entries).containsKey("docker-compose.yml");
        assertThat(entries).containsKey(".menora-init.json");

        // Root README mentions both artifacts and the resolved apiBaseUrl
        String readme = entries.get("README.md");
        assertThat(readme).contains("demo-api");
        assertThat(readme).contains("demo-ui");
        assertThat(readme).contains("http://localhost:8080");
    }

    @Test
    void pairedZipAutoSetsFrontendApiBaseUrl() throws Exception {
        // Frontend half omits apiBaseUrl entirely — paired endpoint defaults to localhost:8080.
        Map<String, Object> body = pairedBody(
                backendBody("demo-api", List.of("web")),
                frontendBody("demo-ui", List.of(), null));

        ResponseEntity<byte[]> r = rest.postForEntity("/starter-paired.zip", body, byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> entries = readZipEntries(r.getBody());
        String envDev = entries.get("frontend/.env.development");
        assertThat(envDev).isNotNull();
        assertThat(envDev).contains("VITE_API_BASE_URL=http://localhost:8080");

        String vite = entries.get("frontend/vite.config.ts");
        assertThat(vite).contains("target: 'http://localhost:8080'");
    }

    @Test
    void pairedZipRespectsExplicitFrontendApiBaseUrl() throws Exception {
        Map<String, Object> body = pairedBody(
                backendBody("demo-api", List.of("web")),
                frontendBody("demo-ui", List.of(), "http://localhost:9090"));

        ResponseEntity<byte[]> r = rest.postForEntity("/starter-paired.zip", body, byte[].class);
        Map<String, String> entries = readZipEntries(r.getBody());

        assertThat(entries.get("frontend/.env.development"))
                .contains("VITE_API_BASE_URL=http://localhost:9090");
    }

    @Test
    void pairedZipAddsCorsConfigWhenBackendHasWebDep() throws Exception {
        Map<String, Object> body = pairedBody(
                backendBody("demo-api", List.of("web")),
                frontendBody("demo-ui", List.of(), null));

        ResponseEntity<byte[]> r = rest.postForEntity("/starter-paired.zip", body, byte[].class);
        Map<String, String> entries = readZipEntries(r.getBody());

        // The web dep + auto-added paired-cors sub-option lands the CORS config.
        String corsConfig = entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith("config/CorsConfig.java"))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(null);
        assertThat(corsConfig).isNotNull();
        assertThat(corsConfig).contains("http://localhost:5173");
        assertThat(corsConfig).contains("addCorsMappings");
    }

    @Test
    void pairedZipWithoutWebDepSkipsCorsConfig() throws Exception {
        // No `web` in BE deps → no CORS file is written even though FE half is present.
        Map<String, Object> body = pairedBody(
                backendBody("demo-api", List.of()),
                frontendBody("demo-ui", List.of(), null));

        ResponseEntity<byte[]> r = rest.postForEntity("/starter-paired.zip", body, byte[].class);
        Map<String, String> entries = readZipEntries(r.getBody());

        assertThat(entries.keySet().stream())
                .noneMatch(k -> k.endsWith("config/CorsConfig.java"));
    }

    // ── /agent/scaffold/paired ───────────────────────────────────────────────

    @Test
    void agentScaffoldPairedReturnsTwoTreesAndPairedManifest() throws Exception {
        Map<String, Object> body = pairedBody(
                backendBody("demo-api", List.of("web")),
                frontendBody("demo-ui", List.of("style-tailwind"), null));

        ResponseEntity<String> r = rest.postForEntity("/agent/scaffold/paired", body, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode root = MAPPER.readTree(r.getBody());

        // Manifest has the paired block, no inputs block
        assertThat(root.has("manifest")).isTrue();
        JsonNode manifest = root.get("manifest");
        assertThat(manifest.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(manifest.has("paired")).isTrue();
        assertThat(manifest.has("inputs")).isFalse();

        JsonNode paired = manifest.get("paired");
        assertThat(paired.get("apiBaseUrl").asText()).isEqualTo("http://localhost:8080");
        assertThat(paired.get("backend").get("artifactId").asText()).isEqualTo("demo-api");
        assertThat(paired.get("frontend").get("projectName").asText()).isEqualTo("demo-ui");
        assertThat(paired.get("frontend").get("apiBaseUrl").asText()).isEqualTo("http://localhost:8080");

        // Files array carries entries from both halves
        JsonNode files = root.get("files");
        assertThat(files.isArray()).isTrue();
        List<String> paths = new java.util.ArrayList<>();
        files.forEach(f -> paths.add(f.get("path").asText()));
        assertThat(paths).contains("backend/pom.xml");
        assertThat(paths).contains("frontend/package.json");
        assertThat(paths).contains("frontend/tailwind.config.js");

        // Every file entry has a sha256
        for (JsonNode f : files) {
            assertThat(f.get("sha256").asText()).hasSize(64);
        }
    }

    // ── Phase 3: OpenAPI-driven FE client ────────────────────────────────────

    @Test
    void pairedRequestWithOpenApiSpecGeneratesFrontendTypes() throws Exception {
        Map<String, Object> backend = backendBody("demo-api", List.of("web", "openapi"));
        backend.put("specByDep", Map.of("openapi", SAMPLE_OPENAPI_SPEC));

        Map<String, Object> frontend = frontendBody("demo-ui",
                List.of("data-tanstack-query", "api-client-openapi"), null);

        ResponseEntity<byte[]> r = rest.postForEntity("/starter-paired.zip",
                pairedBody(backend, frontend), byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> entries = readZipEntries(r.getBody());

        // The paired backend's spec lands at frontend/openapi.yaml verbatim.
        String yaml = entries.get("frontend/openapi.yaml");
        assertThat(yaml).isNotNull();
        assertThat(yaml).contains("openapi: 3.0.0");
        assertThat(yaml).contains("/api/hello");

        // package.json carries the openapi-typescript devDep + the gen:api script.
        String pkg = entries.get("frontend/package.json");
        assertThat(pkg).contains("\"openapi-typescript\"");
        assertThat(pkg).contains("\"gen:api\"");
        assertThat(pkg).contains("openapi.yaml");

        // Spec is present → pure-Java codegen pre-fills the generated dir, so
        // the project type-checks out of the box. The seed .gitkeep is replaced
        // by the real generated files.
        assertThat(entries).doesNotContainKey("frontend/src/shared/api/generated/.gitkeep");
        assertThat(entries).containsKey("frontend/src/shared/api/generated/schema.ts");
        assertThat(entries).containsKey("frontend/src/shared/api/generated/paths.ts");
        assertThat(entries).containsKey("frontend/src/shared/api/generated/errors.ts");
        assertThat(entries).containsKey("frontend/src/shared/api/generated/client.ts");
        // data-tanstack-query is selected → hooks.ts is companion-generated.
        assertThat(entries).containsKey("frontend/src/shared/api/generated/hooks.ts");

        // The schema export names the spec's component schema.
        String schemaTs = entries.get("frontend/src/shared/api/generated/schema.ts");
        assertThat(schemaTs).contains("export type Hello");
        // The paths export carries the spec's path + method.
        String pathsTs = entries.get("frontend/src/shared/api/generated/paths.ts");
        assertThat(pathsTs).contains("'/api/hello': {");
        assertThat(pathsTs).contains("get: {");
        assertThat(pathsTs).contains("Schema.Hello");
        // The client wires path/method typing and exposes the typed requestJson helper.
        String clientTs = entries.get("frontend/src/shared/api/generated/client.ts");
        assertThat(clientTs).contains("createApiClient");
        assertThat(clientTs).contains("paths");
        assertThat(clientTs).contains("requestJson");
        // errors.ts exposes the per-op narrowing types.
        String errorsTs = entries.get("frontend/src/shared/api/generated/errors.ts");
        assertThat(errorsTs).contains("export type SuccessBody");
        assertThat(errorsTs).contains("export function isApiError");
        // hooks.ts emits a useHello hook from the spec's operationId.
        String hooksTs = entries.get("frontend/src/shared/api/generated/hooks.ts");
        assertThat(hooksTs).contains("export function useHello");
        assertThat(hooksTs).contains("@tanstack/react-query");
    }

    @Test
    void pairedZipOmitsHooksTsWhenTanstackUnselected() throws Exception {
        // api-client-openapi selected, data-tanstack-query NOT selected → no hooks.ts.
        Map<String, Object> backend = backendBody("demo-api", List.of("web", "openapi"));
        backend.put("specByDep", Map.of("openapi", SAMPLE_OPENAPI_SPEC));

        Map<String, Object> frontend = frontendBody("demo-ui",
                List.of("api-client-openapi"), null);

        ResponseEntity<byte[]> r = rest.postForEntity("/starter-paired.zip",
                pairedBody(backend, frontend), byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> entries = readZipEntries(r.getBody());
        assertThat(entries).containsKey("frontend/src/shared/api/generated/client.ts");
        assertThat(entries).doesNotContainKey("frontend/src/shared/api/generated/hooks.ts");
    }

    @Test
    void pairedZipIncludesMswWhenVitestSelected() throws Exception {
        Map<String, Object> backend = backendBody("demo-api", List.of("web", "openapi"));
        backend.put("specByDep", Map.of("openapi", SAMPLE_OPENAPI_SPEC));

        Map<String, Object> frontend = frontendBody("demo-ui",
                List.of("api-client-openapi", "test-vitest-rtl"), null);

        ResponseEntity<byte[]> r = rest.postForEntity("/starter-paired.zip",
                pairedBody(backend, frontend), byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> entries = readZipEntries(r.getBody());
        String mswTs = entries.get("frontend/src/shared/api/generated/msw.ts");
        assertThat(mswTs).isNotNull();
        assertThat(mswTs).contains("import { http, HttpResponse } from 'msw'");
        assertThat(mswTs).contains("http.get('/api/hello'");
    }

    @Test
    void apiClientOpenApiDepWithoutSpecIsHarmless() throws Exception {
        // Frontend wants the OpenAPI client but no spec is supplied — generation
        // should still succeed; openapi.yaml is simply absent.
        Map<String, Object> backend = backendBody("demo-api", List.of("web"));
        Map<String, Object> frontend = frontendBody("demo-ui",
                List.of("api-client-openapi"), null);

        ResponseEntity<byte[]> r = rest.postForEntity("/starter-paired.zip",
                pairedBody(backend, frontend), byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> entries = readZipEntries(r.getBody());
        // No spec → no openapi.yaml at the FE root.
        assertThat(entries).doesNotContainKey("frontend/openapi.yaml");
        // But the dep's other contributions (script + devDep + .gitkeep) still come through.
        assertThat(entries.get("frontend/package.json")).contains("\"openapi-typescript\"");
        assertThat(entries.get("frontend/package.json")).contains("\"gen:api\"");
        assertThat(entries.keySet().stream())
                .anyMatch(k -> k.equals("frontend/src/shared/api/generated/.gitkeep"));
    }

    @Test
    void reactQueryHooksSubOptionEmitsOrvalConfig() throws Exception {
        Map<String, Object> backend = backendBody("demo-api", List.of("web", "openapi"));
        backend.put("specByDep", Map.of("openapi", SAMPLE_OPENAPI_SPEC));

        Map<String, Object> frontend = frontendBody("demo-ui",
                List.of("data-tanstack-query", "api-client-openapi"), null);
        frontend.put("opts", Map.of("api-client-openapi", List.of("react-query-hooks")));

        ResponseEntity<byte[]> r = rest.postForEntity("/starter-paired.zip",
                pairedBody(backend, frontend), byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> entries = readZipEntries(r.getBody());
        String orval = entries.get("frontend/orval.config.ts");
        assertThat(orval).isNotNull();
        assertThat(orval).contains("from 'orval'");
        assertThat(orval).contains("input: './openapi.yaml'");
        assertThat(orval).contains("client: 'react-query'");
    }

    @Test
    void metadataExposesApiIntegrationGroup() {
        ResponseEntity<String> r = rest.getForEntity("/frontend/metadata", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"name\":\"API Integration\"");
        assertThat(body).contains("\"id\":\"api-client-openapi\"");
        // Sub-option exposed for the UI picker.
        assertThat(body).contains("\"id\":\"react-query-hooks\"");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static final String SAMPLE_OPENAPI_SPEC = """
            openapi: 3.0.0
            info:
              title: Demo API
              version: 1.0.0
            paths:
              /api/hello:
                get:
                  operationId: hello
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Hello'
            components:
              schemas:
                Hello:
                  type: object
                  properties:
                    message:
                      type: string
            """;

    private static Map<String, Object> pairedBody(Map<String, Object> backend, Map<String, Object> frontend) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("backend", backend);
        m.put("frontend", frontend);
        return m;
    }

    private static Map<String, Object> backendBody(String artifactId, List<String> deps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("artifactId", artifactId);
        m.put("bootVersion", "3.2.1");
        m.put("dependencies", deps);
        return m;
    }

    private static Map<String, Object> frontendBody(String projectName, List<String> deps, String apiBaseUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectName", projectName);
        m.put("dependencies", deps);
        if (apiBaseUrl != null) m.put("apiBaseUrl", apiBaseUrl);
        return m;
    }

    private static Map<String, String> readZipEntries(byte[] bytes) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                out.put(e.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
