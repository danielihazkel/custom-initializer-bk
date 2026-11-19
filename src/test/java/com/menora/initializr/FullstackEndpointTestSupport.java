package com.menora.initializr;

import com.menora.initializr.db.VersionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** What the classes that test {@code /starter-fullstack.zip} share: the HTTP client, and the request and zip helpers. */
abstract class FullstackEndpointTestSupport {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    VersionService versionService;

    /** POSTs a fullstack request and returns the unzipped (path → text) generated tree. */
    Map<String, String> generateZip(Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return unzip(response.getBody());
    }

    /** POSTs a fullstack request built by the given mutator and returns the raw response. */
    ResponseEntity<String> postFullstack(java.util.function.Consumer<Map<String, Object>> mutator) {
        Map<String, Object> body = new LinkedHashMap<>();
        mutator.accept(body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    static Map<String, Object> pkField() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "id");
        m.put("type", "Long");
        m.put("primaryKey", true);
        m.put("generated", true);
        return m;
    }

    /** Content of the single entry whose path ends with the given suffix. */
    static String contentEndingWith(Map<String, String> entries, String suffix) {
        return entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith(suffix))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
    }

    /** Returns paths as keys (string contents) for text files. */
    static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zin.transferTo(out);
                // Generated files are UTF-8 (Hebrew chrome strings); never the platform default.
                result.put(entry.getName(), out.toString(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    static Map<String, Object> bodyWithField(Map<String, Object> field) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "ops");
        body.put("packageName", "com.menora.ops");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "Task", "fields", List.of(pkField(), field))));
        return body;
    }
}
