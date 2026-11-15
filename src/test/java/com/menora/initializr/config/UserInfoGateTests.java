package com.menora.initializr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access gate switched on ({@code menora.access.require-user-header=true}): nothing is
 * answered without the gateway's {@code userinfo} header except the open paths. Runs against a
 * real embedded server, like {@code GenerationAuditUsernameTests}, so the filter order is the
 * production one. That the gate is off by default is what every other suite relies on — none of
 * them sends the header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "menora.access.require-user-header=true")
class UserInfoGateTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper json;

    @LocalServerPort
    private int port;

    private ResponseEntity<String> get(String path, String userinfo) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (userinfo != null) {
            headers.set("userinfo", userinfo);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private void assertBlocked(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo(UserInfoGateFilter.BLOCKED_CODE);
        assertThat(body.path("detail").asText()).contains("userinfo");
    }

    @Test
    void requestWithoutTheHeaderIsRefused() throws Exception {
        assertBlocked(get("/metadata/client", null));
        assertBlocked(get("/starter.zip?dependencies=web&artifactId=gate-demo&type=maven-project", null));
    }

    @Test
    void blankHeaderIsRefused() throws Exception {
        assertBlocked(get("/metadata/client", "   "));
    }

    @Test
    void requestWithTheHeaderIsServed() {
        ResponseEntity<String> response = get("/metadata/client", "gate-test-user");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("dependencies");
    }

    @Test
    void openPathsAreServedWithoutTheHeader() {
        assertThat(get("/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theGateComesBeforeAdminAuth() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange("/admin/login", HttpMethod.POST,
                new HttpEntity<>("{\"password\":\"test\"}", headers), String.class);
        assertBlocked(response);
    }

    @Test
    void anOpenPrefixDoesNotOpenWhatLiesBehindDotSegments() {
        // Sent as written (a URI object is not normalized): the container serves /metadata/client,
        // so the gate must judge that path, not the one the request line starts with.
        URI uri = URI.create("http://localhost:" + port + "/actuator/../metadata/client");
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
