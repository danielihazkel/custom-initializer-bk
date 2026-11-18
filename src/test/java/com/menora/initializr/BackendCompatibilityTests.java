package com.menora.initializr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backend catalog's compatibility rules hold for every backend endpoint, not only in the UI:
 * a dependency another one REQUIRES is added, two that CONFLICT are a 400 that names both and the
 * rule's reason, and a RECOMMENDS rule changes nothing. Over HTTP, since {@code /starter.zip} and
 * {@code /starter.preview} are bound by the framework and get the rules from the servlet filter,
 * the JSON endpoints from {@code ProjectRequestFactory}, and the multi-module one in its assembly.
 */
@IntegrationTest
class BackendCompatibilityTests {

    private static final String PARAMS = "groupId=com.menora&artifactId=demo&packageName=com.menora.demo"
            + "&bootVersion=3.2.1&javaVersion=21&type=maven-project&language=java&packaging=jar";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @Test
    void starterZipAddsWhatADependencyRequires() throws Exception {
        // security REQUIRES web
        Map<String, String> files = unzip(ok(rest.getForEntity("/starter.zip?" + PARAMS + "&dependencies=security", byte[].class)));
        assertThat(files.get("pom.xml"))
                .contains("<artifactId>spring-boot-starter-security</artifactId>")
                .contains("<artifactId>spring-boot-starter-web</artifactId>");
    }

    @Test
    void requirementsChainAndKeepTheDependenciesAsked() throws Exception {
        // ldap-auth-rest REQUIRES web; nothing is dropped, and a repeated parameter counts too.
        Map<String, String> files = unzip(ok(rest.getForEntity(
                "/starter.zip?" + PARAMS + "&dependencies=ldap-auth-rest&dependencies=logging", byte[].class)));
        assertThat(files.get("pom.xml")).contains("<artifactId>spring-boot-starter-web</artifactId>");
    }

    @Test
    void recommendationsChangeNothing() throws Exception {
        // data-jpa RECOMMENDS postgresql and h2: advice only.
        Map<String, String> files = unzip(ok(rest.getForEntity("/starter.zip?" + PARAMS + "&dependencies=data-jpa", byte[].class)));
        assertThat(files.get("pom.xml"))
                .contains("<artifactId>spring-boot-starter-data-jpa</artifactId>")
                .doesNotContain("<artifactId>postgresql</artifactId>")
                .doesNotContain("<artifactId>h2</artifactId>");
    }

    @Test
    void starterZipRefusesConflictingDependencies() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/starter.zip?" + PARAMS + "&dependencies=web,webflux", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("path").asText()).isEqualTo("dependencies");
        assertThat(body.get("detail").asText())
                .contains("('web')")
                .contains("('webflux')")
                .contains("cannot be used together")
                .contains("Spring MVC and WebFlux use incompatible server models");
    }

    @Test
    void aConflictCausedByARequirementSaysWhereItCameFrom() throws Exception {
        // security REQUIRES web, which CONFLICTS with the webflux asked for.
        ResponseEntity<String> response = rest.getForEntity("/starter.zip?" + PARAMS + "&dependencies=webflux,security", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json.readTree(response.getBody()).get("detail").asText())
                .contains("('web') is required by")
                .contains("('security')");
    }

    @Test
    void previewFollowsTheRules() throws Exception {
        ResponseEntity<String> preview = rest.getForEntity("/starter.preview?" + PARAMS + "&dependencies=security", String.class);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getBody()).contains("spring-boot-starter-web");
        assertThat(rest.getForEntity("/starter.preview?" + PARAMS + "&dependencies=web,webflux", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void wizardEndpointFollowsTheRules() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("security"));
        Map<String, String> files = unzip(ok(post("/starter-wizard.zip", body)));
        assertThat(files.get("demo/pom.xml")).contains("<artifactId>spring-boot-starter-web</artifactId>");

        body.put("dependencies", List.of("web", "webflux"));
        ResponseEntity<byte[]> refused = post("/starter-wizard.zip", body);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new String(refused.getBody(), StandardCharsets.UTF_8)).contains("cannot be used together");
    }

    @Test
    void multiModuleFollowsTheRulesForTheSelection() throws Exception {
        Map<String, String> files = unzip(ok(rest.getForEntity(
                "/starter-multimodule.zip?modules=api,core&" + PARAMS + "&dependencies=security", byte[].class)));
        // The selection goes into every module, with what it requires.
        assertThat(files.get("demo/demo-core/pom.xml"))
                .contains("<artifactId>spring-boot-starter-security</artifactId>")
                .contains("<artifactId>spring-boot-starter-web</artifactId>");

        ResponseEntity<String> refused = rest.getForEntity(
                "/starter-multimodule.zip?modules=api,core&" + PARAMS + "&dependencies=web,webflux", String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("cannot be used together");
    }

    @Test
    void theFrontendEndpointKeepsItsOwnRules() {
        // The filter applies the backend rules to backend endpoints only.
        ResponseEntity<byte[]> response = rest.getForEntity("/frontend/starter.zip?projectName=web&dependencies=web,webflux", byte[].class);
        assertThat(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .doesNotContain("cannot be used together");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> post(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
    }

    private static byte[] ok(ResponseEntity<byte[]> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) out.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
