package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * The {@code tracing} catalog dependency: Micrometer Tracing over OpenTelemetry, Actuator brought
 * along by its REQUIRES rule, a sampling rate in {@code application.yaml}, and the trace and span
 * ids in the JSON log layout every backend logs through — only when it is selected.
 */
@IntegrationTest
class TracingDependencyTests {

    private static final String PARAMS = "groupId=com.menora&artifactId=demo&packageName=com.menora.demo"
            + "&bootVersion=3.2.1&javaVersion=21&type=maven-project&language=java&packaging=jar";

    @Autowired
    private TestRestTemplate rest;

    @Test
    void tracingAddsTheBridgeTheExporterAndActuator() throws Exception {
        Map<String, String> files = unzip("/starter.zip?" + PARAMS + "&dependencies=web,tracing");
        assertThat(files.get("pom.xml"))
                .contains("<artifactId>micrometer-tracing-bridge-otel</artifactId>")
                .contains("<artifactId>opentelemetry-exporter-otlp</artifactId>")
                // tracing REQUIRES actuator
                .contains("<artifactId>spring-boot-starter-actuator</artifactId>");
        assertThat(files.get("src/main/resources/application.yaml"))
                .contains("tracing:\n    sampling:\n      probability: ${TRACING_SAMPLING_PROBABILITY:1.0}")
                // No collector is assumed: the exporter starts once an endpoint is given.
                .doesNotContain("otlp");
        assertThat(files.get("src/main/resources/logFormat.json"))
                .contains("  \"traceId\": {\n    \"$resolver\": \"mdc\",\n    \"key\": \"traceId\"\n  },\n")
                .contains("\"key\": \"spanId\"");
    }

    @Test
    void withoutTracingTheLogLayoutIsUnchanged() throws Exception {
        String layout = unzip("/starter.zip?" + PARAMS + "&dependencies=web").get("src/main/resources/logFormat.json");
        assertThat(layout)
                .doesNotContain("traceId")
                .contains("    \"key\": \"uuid\"\n  },\n  \"version\": \"1.1\",");
    }

    private Map<String, String> unzip(String url) throws Exception {
        ResponseEntity<byte[]> response = rest.getForEntity(url, byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) out.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
        return out;
    }
}
