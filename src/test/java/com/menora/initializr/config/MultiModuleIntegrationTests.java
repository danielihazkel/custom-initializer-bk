package com.menora.initializr.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for {@link MultiModuleController}. Until this file
 * landed, the multi-module pipeline (parent POM assembly, per-module
 * dependency resolution, entry-point class placement) had zero automated
 * coverage and a refactor could break it silently.
 *
 * <p>Asserts structure only — dependency-correctness assertions would couple
 * the test too tightly to the specific seed data in {@code DataSeeder}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiModuleIntegrationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void multiModuleZipHasParentPomAndAllModules() throws Exception {
        ResponseEntity<byte[]> resp = restTemplate.getForEntity(
                "/starter-multimodule.zip?modules=api,core,persistence"
                        + "&groupId=com.menora&artifactId=demo&packageName=com.menora.demo",
                byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> files = unzip(resp.getBody());

        // Parent POM at the top of the archive.
        String parentPom = files.get("demo/pom.xml");
        assertThat(parentPom)
                .as("parent pom.xml")
                .isNotNull()
                .contains("<packaging>pom</packaging>")
                .contains("<module>demo-api</module>")
                .contains("<module>demo-core</module>")
                .contains("<module>demo-persistence</module>");

        // Each sub-module has its own POM.
        assertThat(files)
                .containsKey("demo/demo-api/pom.xml")
                .containsKey("demo/demo-core/pom.xml")
                .containsKey("demo/demo-persistence/pom.xml");

        // Only the api module has the @SpringBootApplication entry point
        // (per the seeded ModuleTemplateEntity flags). The other two have
        // their main class deleted by MultiModuleController.
        assertThat(files.entrySet().stream()
                .filter(e -> e.getKey().startsWith("demo/demo-api/src/main/java/")
                        && e.getKey().endsWith(".java"))
                .map(Map.Entry::getValue)
                .anyMatch(content -> content.contains("@SpringBootApplication")))
                .as("api module should contain a @SpringBootApplication class")
                .isTrue();

        assertThat(files.keySet())
                .as("non-entry modules should not have a main application class")
                .noneMatch(p -> p.startsWith("demo/demo-core/src/main/java/")
                        && p.endsWith("Application.java"))
                .noneMatch(p -> p.startsWith("demo/demo-persistence/src/main/java/")
                        && p.endsWith("Application.java"));
    }

    @Test
    void multiModuleWithEmptyModulesParamReturns400() {
        ResponseEntity<byte[]> resp = restTemplate.getForEntity(
                "/starter-multimodule.zip?modules=&groupId=com.menora&artifactId=demo",
                byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void multiModuleWithUnknownModuleReturns400() {
        ResponseEntity<byte[]> resp = restTemplate.getForEntity(
                "/starter-multimodule.zip?modules=this-module-does-not-exist"
                        + "&groupId=com.menora&artifactId=demo",
                byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
