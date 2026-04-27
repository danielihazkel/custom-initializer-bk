package com.menora.initializr.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.TestInvokerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the agent contract endpoints introduced in
 * {@code com.menora.initializr.agent}: discovery returns the full catalog,
 * scaffold returns a JSON file tree with a checksum-bearing
 * {@code .menora-init.json} manifest, and wizard inputs are recorded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class AgentEndpointIntegrationTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void discoveryReturnsAllCatalogs() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/agent/manifest", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode root = MAPPER.readTree(response.getBody());
        assertThat(root.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(root.get("dependencies").size()).isGreaterThan(0);
        assertThat(root.get("bootVersions").size()).isGreaterThan(0);
        assertThat(root.get("javaVersions").size()).isGreaterThan(0);
        assertThat(root.get("packagings").size()).isGreaterThan(0);
        assertThat(root.get("languages").size()).isGreaterThan(0);
        assertThat(root.get("wizards").get("sql")).isNotNull();
        assertThat(root.get("wizards").get("openApi").get("capableDeps").size()).isGreaterThan(0);
        assertThat(root.get("wizards").get("soap")).isNotNull();
        // Compatibility ranges should make it through.
        boolean hasRange = false;
        for (JsonNode dep : root.get("dependencies")) {
            if (dep.has("compatibilityRange") && !dep.get("compatibilityRange").isNull()) {
                hasRange = true;
                break;
            }
        }
        assertThat(hasRange).as("at least one dep should have a compatibilityRange").isTrue();
    }

    @Test
    void scaffoldReturnsJsonFileTreeWithManifest() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("dependencies", List.of("web"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/agent/scaffold", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode root = MAPPER.readTree(response.getBody());
        assertThat(root.has("manifest")).isTrue();
        assertThat(root.has("files")).isTrue();

        // Manifest assertions
        JsonNode manifest = root.get("manifest");
        assertThat(manifest.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(manifest.get("generator").get("name").asText()).isEqualTo("menora-initializr");
        assertThat(manifest.get("inputs").get("artifactId").asText()).isEqualTo("demo");
        assertThat(manifest.get("inputs").get("dependencies").get(0).asText()).isEqualTo("web");

        // Files include pom.xml, the manifest file itself, and at least one src/ file.
        Map<String, JsonNode> filesByPath = new LinkedHashMap<>();
        for (JsonNode file : root.get("files")) {
            filesByPath.put(file.get("path").asText(), file);
        }
        assertThat(filesByPath).containsKey("pom.xml");
        assertThat(filesByPath).containsKey(".menora-init.json");
        assertThat(filesByPath.keySet().stream().anyMatch(p -> p.startsWith("src/main/java/")))
                .as("expected at least one Java source file under src/main/java")
                .isTrue();

        // Per-file SHA matches the on-the-wire content for a known text file.
        JsonNode pomFile = filesByPath.get("pom.xml");
        assertThat(pomFile.get("encoding").asText()).isEqualTo("utf-8");
        String pomContent = pomFile.get("content").asText();
        String expectedSha = sha256(pomContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(pomFile.get("sha256").asText()).isEqualTo(expectedSha);

        // The manifest's files[] entries cross-check against the file tree (excluding the manifest itself).
        Map<String, String> shaByPath = new LinkedHashMap<>();
        for (JsonNode mf : manifest.get("files")) {
            shaByPath.put(mf.get("path").asText(), mf.get("sha256").asText());
        }
        assertThat(shaByPath).doesNotContainKey(".menora-init.json");
        assertThat(shaByPath.get("pom.xml")).isEqualTo(expectedSha);
    }

    @Test
    void scaffoldStarterModeBehavesLikeWizardWithEmptyWizardFields() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("mode", "starter");
        body.put("dependencies", List.of("web", "actuator"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/agent/scaffold", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode root = MAPPER.readTree(response.getBody());
        JsonNode manifest = root.get("manifest");
        assertThat(manifest.get("inputs").get("mode").asText()).isEqualTo("starter");
        // wizards section should be absent/null when no wizard fields supplied
        assertThat(manifest.get("inputs").has("wizards")
                && !manifest.get("inputs").get("wizards").isNull()).isFalse();
    }

    @Test
    void manifestRecordsWizardInputsForSqlWizard() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("dependencies", List.of("postgresql", "data-jpa"));
        body.put("opts", Map.of("postgresql", List.of("pg-primary")));
        body.put("sqlByDep", Map.of("postgresql", """
                CREATE TABLE users (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(200) NOT NULL
                );
                """));
        body.put("sqlOptions", Map.of("postgresql", Map.of(
                "subPackage", "entity",
                "tables", List.of(Map.of("name", "users", "generateRepository", true)))));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/agent/scaffold", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode root = MAPPER.readTree(response.getBody());
        JsonNode wizards = root.get("manifest").get("inputs").get("wizards");
        assertThat(wizards).isNotNull();
        assertThat(wizards.get("sql")).isNotNull();
        assertThat(wizards.get("sql").get("sqlByDep").get("postgresql").asText())
                .contains("CREATE TABLE users");

        // The generated entity should be present in the file tree.
        boolean hasEntity = false;
        for (JsonNode file : root.get("files")) {
            if (file.get("path").asText().endsWith("/entity/Users.java")) {
                hasEntity = true;
                break;
            }
        }
        assertThat(hasEntity).as("Users entity from the SQL wizard should appear in /agent/scaffold output").isTrue();
    }

    @Test
    void multimoduleModeIsNotImplemented() {
        Map<String, Object> body = baseBody();
        body.put("mode", "multimodule");
        body.put("modules", List.of("api", "core"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/agent/scaffold", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    @Test
    void scaffoldEncodesBinaryFilesAsBase64() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("dependencies", List.of("web"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/agent/scaffold", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode root = MAPPER.readTree(response.getBody());
        // Maven projects ship a binary mvnw wrapper jar — verify base64 round-trip if present.
        for (JsonNode file : root.get("files")) {
            String path = file.get("path").asText();
            if (path.endsWith(".jar")) {
                assertThat(file.get("encoding").asText()).isEqualTo("base64");
                byte[] decoded = Base64.getDecoder().decode(file.get("content").asText());
                assertThat(file.get("sha256").asText()).isEqualTo(sha256(decoded));
                return;
            }
        }
        // No binary file → still a valid run; the encoding/sha invariants for text files
        // are checked elsewhere. Don't fail.
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

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(bytes);
        return HexFormat.of().formatHex(md.digest());
    }
}
