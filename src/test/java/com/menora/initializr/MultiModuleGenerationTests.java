package com.menora.initializr;

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
 * End-to-end HTTP coverage for {@code /starter-multimodule.zip}.
 *
 * <p>This endpoint builds its {@code WebProjectRequest} by hand from {@code @RequestParam}s,
 * so the {@code configurationFileFormat} default that {@link com.menora.initializr.config.InitializrWebConfiguration}
 * injects at the <em>parameter</em> level never reached it — the framework then threw
 * "Unrecognized configuration file format id 'null'" and every call returned 500. The other
 * generation tests build the request in-process and set the format themselves, so they never
 * caught it; this one goes over HTTP like a real client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiModuleGenerationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void multiModuleZipGeneratesParentAndModulePoms() throws Exception {
        ResponseEntity<byte[]> response = restTemplate.getForEntity(
                "/starter-multimodule.zip?modules=api,core&artifactId=myapp&groupId=com.menora"
                        + "&packageName=com.menora.myapp&bootVersion=3.2.1&javaVersion=21"
                        + "&type=maven-project&language=java&packaging=jar&dependencies=web",
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> entries = entryNames(response.getBody());
        assertThat(entries).contains("myapp/pom.xml", "myapp/myapp-api/pom.xml", "myapp/myapp-core/pom.xml");
        // Every module runs through __common__, so each carries the local Vault connection.
        assertThat(entries).contains("myapp/myapp-api/.env", "myapp/myapp-core/.env",
                "myapp/myapp-api/src/main/resources/application-vault-local.yaml");
    }

    @Test
    void multiModulePreviewReturnsAFileTree() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/starter-multimodule.preview?modules=api&artifactId=myapp&groupId=com.menora"
                        + "&packageName=com.menora.myapp&bootVersion=3.2.1&javaVersion=21"
                        + "&type=maven-project&language=java&packaging=jar",
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("myapp-api");
    }

    @Test
    void whatACallerLeavesOutComesFromTheMetadata() throws Exception {
        // Only the modules named: group, artifact, package, versions, type and language are the
        // metadata's defaults — the same ones /starter.zip uses, not a second set hard-coded here
        // (the controller used to default to "3.2.1" and "21" on its own, so an admin changing
        // the default version moved /starter.zip and not this endpoint).
        ResponseEntity<byte[]> response = restTemplate.getForEntity("/starter-multimodule.zip?modules=core", byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, byte[]> files = FullstackExampleRequests.unzip(response.getBody());
        String parent = new String(files.get("demo/pom.xml"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(parent)
                .contains("<groupId>com.menora</groupId>").contains("<artifactId>demo</artifactId>")
                .contains("<version>3.2.1</version>").contains("<java.version>21</java.version>")
                .contains("<module>demo-core</module>");
        assertThat(files).containsKey("demo/demo-core/pom.xml");
    }

    @Test
    void aRequestItCannotServeIsA400ThatSaysWhy() {
        // The zip used to answer a bare 400 for unknown modules, and the preview a 200 with only
        // a parent POM that lists no module.
        for (String endpoint : List.of("/starter-multimodule.zip", "/starter-multimodule.preview")) {
            ResponseEntity<String> unknown = restTemplate.getForEntity(endpoint + "?modules=nope,missing", String.class);
            assertThat(unknown.getStatusCode()).as(endpoint).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(unknown.getBody()).contains("none of [nope, missing] is a module template");

            ResponseEntity<String> version = restTemplate.getForEntity(endpoint + "?modules=api&bootVersion=9.9.9", String.class);
            assertThat(version.getStatusCode()).as(endpoint).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(version.getBody()).contains("bootVersion '9.9.9' is not a known version");

            ResponseEntity<String> java = restTemplate.getForEntity(endpoint + "?modules=api&javaVersion=7", String.class);
            assertThat(java.getStatusCode()).as(endpoint).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(java.getBody()).contains("javaVersion '7' is not a known version");
        }
        // No module named at all: the zip says so; the preview (asked while the user is still
        // ticking modules) is simply empty.
        ResponseEntity<String> none = restTemplate.getForEntity("/starter-multimodule.zip?modules=", String.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(none.getBody()).contains("modules is required");
        ResponseEntity<String> empty = restTemplate.getForEntity("/starter-multimodule.preview?modules=", String.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(empty.getBody()).contains("\"files\":[]");
    }

    @Test
    void theWizardEndpointTakesItsDefaultsAndItsVersionCheckFromTheSameBuilder() {
        // No version named: the metadata's default. An unknown one: a 400, not a failure deep in generation.
        ResponseEntity<byte[]> defaults = restTemplate.postForEntity("/starter-wizard.zip",
                Map.of("artifactId", "plain", "dependencies", List.of("web")), byte[].class);
        assertThat(defaults.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> unknown = restTemplate.postForEntity("/starter-wizard.zip",
                Map.of("artifactId", "plain", "bootVersion", "9.9.9"), String.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody()).contains("bootVersion '9.9.9' is not a known version");
    }

    @Test
    void metadataOffersOnlyWhatTheGeneratorsCanBuild() {
        // Kotlin and Gradle were listed and could not work (every build customizer is Maven's,
        // every template Java). The metadata no longer offers them, and a request naming one is turned away.
        String metadata = restTemplate.getForObject("/metadata/client", String.class);
        assertThat(metadata).contains("maven-project").contains("\"java\"")
                .doesNotContain("gradle-project").doesNotContain("kotlin");
        assertThat(restTemplate.getForEntity("/starter.zip?type=gradle-project", String.class).getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(restTemplate.getForEntity("/starter.zip?language=kotlin", String.class).getStatusCode().is2xxSuccessful()).isFalse();
    }

    private List<String> entryNames(byte[] zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
