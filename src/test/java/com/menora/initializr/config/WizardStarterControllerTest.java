package com.menora.initializr.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * Targets gaps in {@code ProjectGenerationIntegrationTests}:
 * <ul>
 *   <li>Sub-option selection ({@code opts}) through the wizard endpoint</li>
 *   <li>SQL parse error → 400 (existing tests cover OpenAPI parse error only)</li>
 *   <li>Threadlocal isolation across requests — regression pin for Wave 3.1
 *       (commit e5b0955), which moved {@code populateContexts} inside the
 *       try block to prevent {@code ProjectOptionsContext} state from
 *       leaking onto the request thread when a populate later throws.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WizardStarterControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void subOptionsThroughWizardGateConsumerExampleFile() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("dependencies", List.of("kafka"));
        body.put("opts", Map.of("kafka", List.of("consumer-example")));

        ResponseEntity<byte[]> resp = restTemplate.postForEntity(
                "/starter-wizard.zip", body, byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);

        Map<String, String> files = unzip(resp.getBody());
        assertThat(files)
                .as("consumer-example sub-option must produce KafkaConsumerExample.java")
                .containsKey("demo/src/main/java/com/menora/demo/config/KafkaConsumerExample.java");
        assertThat(files)
                .as("producer-example sub-option was not selected")
                .doesNotContainKey("demo/src/main/java/com/menora/demo/config/KafkaProducerExample.java");
    }

    @Test
    void invalidSqlReturns400WithDepEchoedBack() {
        Map<String, Object> body = baseBody();
        body.put("dependencies", List.of("postgresql", "data-jpa"));
        body.put("opts", Map.of("postgresql", List.of("pg-primary")));
        body.put("sqlByDep", Map.of("postgresql", "NOT VALID SQL ;"));

        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/starter-wizard.zip", body, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody())
                .containsEntry("error", "Invalid SQL")
                .containsEntry("dep", "postgresql")
                .containsKey("detail");
    }

    @Test
    void contextsAreClearedAfterParseFailure() throws Exception {
        // First request triggers an SQL parse error mid-flight. With Wave 3.1,
        // the SqlParseException surfaces *before* populateContexts runs (it's
        // thrown by validateSql), so contexts shouldn't be populated at all.
        // But the broader invariant holds: any exception path must leave the
        // contexts clean for the next request on the same JVM thread.
        Map<String, Object> failing = baseBody();
        failing.put("dependencies", List.of("postgresql", "data-jpa"));
        failing.put("opts", Map.of("postgresql", List.of("pg-primary")));
        failing.put("sqlByDep", Map.of("postgresql", "NOT VALID SQL ;"));
        failing.put("sqlOptions", Map.of("postgresql", Map.of(
                "subPackage", "leaked",
                "tables", List.of(Map.of("name", "users", "generateRepository", true)))));

        ResponseEntity<Map> bad = restTemplate.postForEntity(
                "/starter-wizard.zip", failing, Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Now a clean request: no sqlByDep, no opts. If contexts leaked,
        // we'd see the previous request's SQL options (entity classes under
        // the "leaked" sub-package, or kafka consumer-example) in the output.
        Map<String, Object> clean = baseBody();
        clean.put("dependencies", List.of("web"));

        ResponseEntity<byte[]> ok = restTemplate.postForEntity(
                "/starter-wizard.zip", clean, byte[].class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> files = unzip(ok.getBody());
        assertThat(files.keySet())
                .as("no SQL-generated entity classes should leak from previous failed request")
                .noneMatch(p -> p.contains("/leaked/"))
                .noneMatch(p -> p.endsWith("/Users.java"));
    }

    private Map<String, Object> baseBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("name", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        return body;
    }

    private Map<String, String> unzip(byte[] zipBytes) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                out.put(e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
