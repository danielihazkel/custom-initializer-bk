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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies frontend project generation ({@code GET /frontend/starter.zip}) is recorded in
 * the generation audit. The audit is written solely by {@link GenerationAuditFilter}, whose
 * endpoint gate historically matched only root-level {@code /starter*} paths and so silently
 * skipped the {@code /frontend}-namespaced frontend endpoint. Runs against a real embedded
 * server ({@code RANDOM_PORT}) because the filter is registered via a
 * {@code FilterRegistrationBean} whose servlet URL patterns MockMvc does not honor.
 *
 * <p>Admin password is {@code test} (see {@code src/test/resources/application.properties}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrontendGenerationAuditTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper json;

    private JsonNode recentEventForArtifact(String artifactId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + login());
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response = restTemplate.exchange(
                "/admin/activity/recent?limit=50", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode event : json.readTree(response.getBody())) {
            if (artifactId.equals(event.path("artifactId").asText())) {
                return event;
            }
        }
        return null;
    }

    private String login() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                "/admin/login", HttpMethod.POST,
                new HttpEntity<>("{\"password\":\"test\"}", headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(response.getBody()).get("token").asText();
    }

    @Test
    void frontendGenerationIsAuditedWithMappedIdentityFields() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("userinfo", "fe-audit-tester");
        ResponseEntity<byte[]> gen = restTemplate.exchange(
                "/frontend/starter.zip?projectName=fe-audit-demo&scope=@acme&dependencies=router-react-router",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(gen.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode event = recentEventForArtifact("fe-audit-demo");
        assertThat(event).as("expected an audit event for the frontend project").isNotNull();
        // Endpoint label distinguishes frontend rows from backend/fullstack.
        assertThat(event.path("endpoint").asText()).isEqualTo("frontend/starter.zip");
        // Identity columns mapped from the frontend param names.
        assertThat(event.path("groupId").asText()).isEqualTo("@acme");
        assertThat(event.path("dependencyIds").asText()).contains("router-react-router");
        // Username still resolves from the userinfo header on the frontend path.
        assertThat(event.path("username").asText()).isEqualTo("fe-audit-tester");
        // Frontend has no Spring Boot / Java version — those stay null.
        assertThat(event.path("bootVersion").isNull()).isTrue();
    }
}
