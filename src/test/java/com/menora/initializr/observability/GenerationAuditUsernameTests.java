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
 * Verifies the generation audit attributes each event to the caller resolved from the
 * {@code userinfo} request header (injected by the SSO gateway in production). Runs against
 * a real embedded server ({@code RANDOM_PORT}) so the {@link GenerationAuditFilter}
 * — registered for {@code /starter*} via a {@code FilterRegistrationBean} — is applied
 * exactly as in production (MockMvc does not honor the servlet URL patterns). The filter's
 * {@code finally}, which persists the event, runs before Tomcat flushes the response, so the
 * event is durably recorded by the time {@code /starter.zip} returns.
 *
 * <p>Admin password is {@code test} (see {@code src/test/resources/application.properties}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenerationAuditUsernameTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper json;

    private void generate(String artifactId, String userinfo) {
        HttpHeaders headers = new HttpHeaders();
        if (userinfo != null) {
            headers.set("userinfo", userinfo);
        }
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter.zip?dependencies=web&groupId=com.menora&artifactId=" + artifactId + "&type=maven-project",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

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
    void generationEventCapturesUsernameFromUserinfoHeader() throws Exception {
        generate("audit-user-demo", "audit-test-user");

        JsonNode event = recentEventForArtifact("audit-user-demo");
        assertThat(event).as("expected an audit event for the generated project").isNotNull();
        assertThat(event.path("username").asText()).isEqualTo("audit-test-user");
    }

    @Test
    void generationEventWithoutUserinfoHeaderHasNullUsername() throws Exception {
        generate("audit-anon-demo", null);

        JsonNode event = recentEventForArtifact("audit-anon-demo");
        assertThat(event).as("expected an audit event for the generated project").isNotNull();
        assertThat(event.path("username").isNull()).isTrue();
    }
}
