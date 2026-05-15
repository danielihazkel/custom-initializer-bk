package com.menora.initializr;

import com.menora.initializr.config.PairedOpenApiController.FetchRequest;
import com.menora.initializr.config.PairedOpenApiController.FetchResponse;
import com.menora.initializr.config.PairedOpenApiController.ValidateRequest;
import com.menora.initializr.config.PairedOpenApiController.ValidateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@code /paired/openapi/*} helpers used by the Paired UI's
 * OpenAPI source input.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PairedOpenApiControllerTests {

    @Autowired
    private TestRestTemplate rest;

    // ── /paired/openapi/validate ────────────────────────────────────────────

    @Test
    void validateAcceptsMinimalOpenApiSpec() {
        String spec = """
                openapi: 3.0.0
                info:
                  title: Pet Store
                  version: 1.0.0
                paths:
                  /pets:
                    get:
                      responses:
                        '200':
                          description: ok
                    post:
                      responses:
                        '201':
                          description: created
                components:
                  schemas:
                    Pet:
                      type: object
                      properties:
                        id:
                          type: integer
                        name:
                          type: string
                """;
        ResponseEntity<ValidateResponse> r = rest.postForEntity(
                "/paired/openapi/validate", new ValidateRequest(spec), ValidateResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().valid()).isTrue();
        assertThat(r.getBody().summary()).isNotNull();
        assertThat(r.getBody().summary().operations()).isEqualTo(2);
        assertThat(r.getBody().summary().schemas()).isEqualTo(1);
        assertThat(r.getBody().summary().title()).isEqualTo("Pet Store");
        assertThat(r.getBody().summary().version()).isEqualTo("1.0.0");
    }

    @Test
    void validateRejectsEmptySpec() {
        ResponseEntity<ValidateResponse> r = rest.postForEntity(
                "/paired/openapi/validate", new ValidateRequest(""), ValidateResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().valid()).isFalse();
        assertThat(r.getBody().errors()).isNotEmpty();
    }

    @Test
    void validateRejectsGibberish() {
        ResponseEntity<ValidateResponse> r = rest.postForEntity(
                "/paired/openapi/validate", new ValidateRequest("not an openapi spec at all"),
                ValidateResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().valid()).isFalse();
    }

    // ── /paired/openapi/fetch ───────────────────────────────────────────────

    @Test
    void fetchRejectsBlankUrl() {
        ResponseEntity<FetchResponse> r = rest.postForEntity(
                "/paired/openapi/fetch", new FetchRequest(""), FetchResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().error()).contains("URL is required");
    }

    @Test
    void fetchRejectsNonHttpScheme() {
        ResponseEntity<FetchResponse> r = rest.postForEntity(
                "/paired/openapi/fetch", new FetchRequest("file:///etc/passwd"), FetchResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().error()).contains("http://");
    }

    @Test
    void fetchRejectsMalformedUrl() {
        ResponseEntity<FetchResponse> r = rest.postForEntity(
                "/paired/openapi/fetch", new FetchRequest("not a url"), FetchResponse.class);
        // URI parsing tolerates a lot — this is an opaque URI with no scheme,
        // so we hit the scheme check, not the parse error path.
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void fetchReturnsBadGatewayForUnreachableHost() {
        // RFC-2606 reserved TLD that's guaranteed not to resolve.
        ResponseEntity<FetchResponse> r = rest.postForEntity(
                "/paired/openapi/fetch",
                new FetchRequest("http://does-not-exist.invalid/openapi.yaml"),
                FetchResponse.class);
        assertThat(r.getStatusCode()).isIn(HttpStatus.BAD_GATEWAY, HttpStatus.GATEWAY_TIMEOUT);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().spec()).isNull();
    }

    @Test
    void fetchSucceedsAgainstOwnSpringdoc() {
        // Self-host hit: the same backend ships springdoc, so /v3/api-docs is local.
        ResponseEntity<FetchResponse> r = rest.postForEntity(
                "/paired/openapi/fetch",
                new FetchRequest(rest.getRootUri() + "/v3/api-docs"),
                FetchResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().spec()).isNotNull();
        assertThat(r.getBody().spec()).contains("openapi");
    }
}
