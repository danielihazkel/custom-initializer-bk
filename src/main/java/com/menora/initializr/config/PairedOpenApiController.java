package com.menora.initializr.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper endpoints for the Paired UI's OpenAPI source input.
 *
 * <p>{@code POST /paired/openapi/fetch} server-side fetches a spec URL — needed
 * because the browser can't reach a backend on a different port without CORS,
 * and the expected target ({@code http://localhost:8080/v3/api-docs}) won't
 * have it configured for the initializr origin.
 *
 * <p>{@code POST /paired/openapi/validate} parses the supplied spec text via
 * swagger-parser and returns a small summary so the UI can render
 * "12 operations, 8 schemas" feedback instead of just trusting raw input.
 *
 * <p>This is a single-user internal dev tool that's expected to target
 * arbitrary internal services, so we don't allow-list the URL — but we cap
 * timeout (30 s) and response size (5 MB) to avoid runaway requests.
 */
@RestController
@RequestMapping("/paired/openapi")
public class PairedOpenApiController {

    private static final Logger LOG = LoggerFactory.getLogger(PairedOpenApiController.class);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_RESPONSE_BYTES = 5L * 1024 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @PostMapping("/fetch")
    public ResponseEntity<FetchResponse> fetch(@RequestBody FetchRequest body) {
        if (body == null || body.url() == null || body.url().isBlank()) {
            return ResponseEntity.badRequest().body(new FetchResponse(null, "URL is required"));
        }
        URI uri;
        try {
            uri = new URI(body.url().trim());
        } catch (URISyntaxException e) {
            return ResponseEntity.badRequest().body(new FetchResponse(null, "Invalid URL: " + e.getMessage()));
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return ResponseEntity.badRequest().body(new FetchResponse(null, "Only http:// and https:// URLs are allowed"));
        }

        HttpRequest req = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(FETCH_TIMEOUT)
                .header("Accept", "application/yaml, application/x-yaml, application/json, text/yaml, text/plain, */*")
                .build();
        try {
            HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(new FetchResponse(null, "Upstream returned HTTP " + res.statusCode()));
            }
            byte[] bytes = res.body();
            if (bytes.length > MAX_RESPONSE_BYTES) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                        .body(new FetchResponse(null,
                                "Response exceeds " + (MAX_RESPONSE_BYTES / (1024 * 1024)) + " MB"));
            }
            return ResponseEntity.ok(new FetchResponse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), null));
        } catch (java.net.http.HttpTimeoutException e) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(new FetchResponse(null, "Timed out after " + FETCH_TIMEOUT.toSeconds() + "s"));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("Failed to fetch OpenAPI spec from {}: {}", uri, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new FetchResponse(null, "Fetch failed: " + e.getMessage()));
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidateResponse> validate(@RequestBody ValidateRequest body) {
        if (body == null || body.spec() == null || body.spec().isBlank()) {
            return ResponseEntity.ok(new ValidateResponse(false, List.of("Spec is empty"), null));
        }
        try {
            SwaggerParseResult result = new OpenAPIV3Parser().readContents(body.spec());
            List<String> messages = result.getMessages() == null ? List.of() : new ArrayList<>(result.getMessages());
            OpenAPI api = result.getOpenAPI();
            if (api == null) {
                return ResponseEntity.ok(new ValidateResponse(false,
                        messages.isEmpty() ? List.of("Could not parse spec") : messages, null));
            }
            int operations = countOperations(api);
            int schemas = api.getComponents() != null && api.getComponents().getSchemas() != null
                    ? api.getComponents().getSchemas().size() : 0;
            String title = api.getInfo() != null ? api.getInfo().getTitle() : null;
            String version = api.getInfo() != null ? api.getInfo().getVersion() : null;
            ValidateSummary summary = new ValidateSummary(operations, schemas, title, version);
            return ResponseEntity.ok(new ValidateResponse(true, messages, summary));
        } catch (Exception e) {
            // swagger-parser can leak Jackson MismatchedInputException for malformed
            // YAML — catch broadly so the UI gets a clean error response.
            return ResponseEntity.ok(new ValidateResponse(false,
                    List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), null));
        }
    }

    private static int countOperations(OpenAPI api) {
        if (api.getPaths() == null) return 0;
        int n = 0;
        for (var pathItem : api.getPaths().values()) {
            if (pathItem.getGet() != null) n++;
            if (pathItem.getPost() != null) n++;
            if (pathItem.getPut() != null) n++;
            if (pathItem.getDelete() != null) n++;
            if (pathItem.getPatch() != null) n++;
            if (pathItem.getHead() != null) n++;
            if (pathItem.getOptions() != null) n++;
            if (pathItem.getTrace() != null) n++;
        }
        return n;
    }

    public record FetchRequest(String url) {}
    public record FetchResponse(String spec, String error) {}
    public record ValidateRequest(String spec) {}
    public record ValidateResponse(boolean valid, List<String> errors, ValidateSummary summary) {}
    public record ValidateSummary(int operations, int schemas, String title, String version) {}
}
