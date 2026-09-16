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
