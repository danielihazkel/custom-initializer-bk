package com.menora.initializr.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two things the Round 11 UI asks of the generation audit: a public list of the most used
 * dependencies ({@code /metadata/popular-deps}), and previews the UI fetches by itself
 * ({@code X-Preview-Intent: live}) staying out of the audit. Against a real embedded server, like
 * {@link GenerationAuditUsernameTests}, because the audit filter is a servlet filter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PopularDependenciesAndLivePreviewTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper json;

    private HttpStatus get(String url, String previewIntent) {
        HttpHeaders headers = new HttpHeaders();
        if (previewIntent != null) headers.set("X-Preview-Intent", previewIntent);
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    /**
     * A preview URL as the UI sends it. Unlike {@code /starter.zip}, the preview endpoint does not
     * fill in what a caller leaves out, so every parameter is named.
     */
    private static String previewUrl(String artifactId, String dependencies) {
        return "/starter.preview?type=maven-project&language=java&bootVersion=3.2.1&groupId=com.menora"
                + "&artifactId=" + artifactId + "&name=" + artifactId + "&description=Demo"
                + "&packageName=com.menora." + artifactId.replace("-", "") + "&packaging=jar&javaVersion=21"
                + "&dependencies=" + dependencies;
    }

    private JsonNode popular(String kind) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/metadata/popular-deps?kind=" + kind, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(response.getBody());
    }

    private long countOf(JsonNode list, String depId) {
        for (JsonNode row : list) {
            if (depId.equals(row.path("depId").asText())) return row.path("count").asLong();
        }
        return 0;
    }

    private List<String> auditedArtifacts() throws Exception {
        HttpHeaders login = new HttpHeaders();
        login.setContentType(MediaType.APPLICATION_JSON);
        String token = json.readTree(restTemplate.exchange("/admin/login", HttpMethod.POST,
                new HttpEntity<>("{\"password\":\"test\"}", login), String.class).getBody()).get("token").asText();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        ResponseEntity<String> response = restTemplate.exchange(
                "/admin/activity/recent?limit=200", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> artifacts = new ArrayList<>();
        for (JsonNode event : json.readTree(response.getBody())) {
            artifacts.add(event.path("endpoint").asText() + ":" + event.path("artifactId").asText());
        }
        return artifacts;
    }

    @Test
    void popularDependenciesCountSuccessfulBackendGenerationsOnly() throws Exception {
        long kafkaBefore = countOf(popular("BACKEND"), "kafka");
        long webBefore = countOf(popular("BACKEND"), "web");

        // Two generations with kafka, one of them with web too.
        assertThat(get("/starter.zip?dependencies=kafka&artifactId=pop-a&type=maven-project", null)).isEqualTo(HttpStatus.OK);
        assertThat(get("/starter.zip?dependencies=kafka,web&artifactId=pop-b&type=maven-project", null)).isEqualTo(HttpStatus.OK);
        // A preview is someone looking, not a project: it does not count.
        assertThat(get(previewUrl("pop-c", "kafka"), null)).isEqualTo(HttpStatus.OK);
        // Neither does a generation that failed.
        assertThat(get("/starter.zip?dependencies=kafka,no-such-dependency&artifactId=pop-d&type=maven-project", null).is2xxSuccessful()).isFalse();

        JsonNode backend = popular("BACKEND");
        assertThat(countOf(backend, "kafka")).isEqualTo(kafkaBefore + 2);
        assertThat(countOf(backend, "web")).isEqualTo(webBefore + 1);
        assertThat(countOf(backend, "no-such-dependency")).isZero();
        assertThat(backend.size()).isLessThanOrEqualTo(10);
        // Most used first.
        for (int i = 1; i < backend.size(); i++) {
            assertThat(backend.get(i - 1).path("count").asLong()).isGreaterThanOrEqualTo(backend.get(i).path("count").asLong());
        }
        // The frontend generator's list is its own.
        assertThat(countOf(popular("FRONTEND"), "kafka")).isZero();
    }

    @Test
    void aLivePreviewIsNotAuditedButAnAskedForOneAndEveryGenerationAre() throws Exception {
        assertThat(get(previewUrl("live-tree", "web"), "live")).isEqualTo(HttpStatus.OK);
        assertThat(get(previewUrl("explore-click", "web"), null)).isEqualTo(HttpStatus.OK);
        // The header cannot hide a real generation.
        assertThat(get("/starter.zip?dependencies=web&artifactId=live-zip&type=maven-project", "live")).isEqualTo(HttpStatus.OK);

        List<String> audited = auditedArtifacts();
        assertThat(audited).doesNotContain("starter.preview:live-tree");
        assertThat(audited).contains("starter.preview:explore-click", "starter.zip:live-zip");
    }
}
