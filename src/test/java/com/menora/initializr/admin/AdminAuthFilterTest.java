package com.menora.initializr.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract of {@link AdminAuthFilter} — the only thing
 * standing between an admin-net-exposed instance and unauthenticated
 * CRUD on the dependency catalog.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAuthFilterTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void requestWithoutAuthReturns401() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/admin/dependency-groups", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("Unauthorized");
    }

    @Test
    void requestWithBadTokenReturns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-real-token");
        ResponseEntity<String> resp = restTemplate.exchange("/admin/dependency-groups",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("Unauthorized");
    }

    @Test
    void requestWithValidTokenReturns200() {
        String token = login();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = restTemplate.exchange("/admin/dependency-groups",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void loginEndpointBypassesAuthFilter() {
        // No Authorization header — must still be allowed through.
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/admin/login", Map.of("password", "test"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("token");
    }

    @Test
    void tokenIsInvalidatedAfterLogout() {
        String token = login();
        HttpHeaders authed = new HttpHeaders();
        authed.setBearerAuth(token);

        restTemplate.exchange("/admin/logout", HttpMethod.POST,
                new HttpEntity<>(authed), Void.class);

        ResponseEntity<String> resp = restTemplate.exchange("/admin/dependency-groups",
                HttpMethod.GET, new HttpEntity<>(authed), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String login() {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/admin/login", Map.of("password", "test"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) resp.getBody().get("token");
    }
}
