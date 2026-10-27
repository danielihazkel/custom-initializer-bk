package com.menora.initializr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.repository.FullstackTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the team-template CRUD at {@code /metadata/fullstack/templates} over HTTP: the wire shape
 * the designer binds to (content included in the list), names unique per kind, the kind filter,
 * and the 400/404/409 error paths.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullstackTemplateControllerTests {

    private static final String BASE = "/metadata/fullstack/templates";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FullstackTemplateRepository repo;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTable() {
        repo.deleteAll();
    }

    @Test
    void crudRoundTrip() {
        Map<String, Object> page = Map.of("id", "queue", "type", "entity-list", "entity", "Ticket",
                "presetFilter", Map.of("status", "OPEN"));

        ResponseEntity<JsonNode> created = post(request("page", "Open queue", "Tickets still open", page), "alice");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode view = created.getBody();
        assertThat(view).isNotNull();
        assertThat(view.get("kind").asText()).isEqualTo("page");
        assertThat(view.get("name").asText()).isEqualTo("Open queue");
        assertThat(view.get("description").asText()).isEqualTo("Tickets still open");
        assertThat(view.get("createdBy").asText()).isEqualTo("alice");
        assertThat(view.get("content")).isEqualTo(objectMapper.valueToTree(page));
        long id = view.get("id").asLong();

        // The same name is free for the other kind; the list carries every template's content.
        Map<String, Object> widget = Map.of("kind", "kpi", "entity", "Ticket", "title", "Open queue");
        assertThat(post(request("widget", "open queue", null, widget), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode all = rest.getForEntity(BASE, JsonNode.class).getBody();
        assertThat(all).isNotNull();
        assertThat(all.size()).isEqualTo(2);
        assertThat(all.findValues("content")).hasSize(2);
        JsonNode widgets = rest.getForEntity(BASE + "?kind=widget", JsonNode.class).getBody();
        assertThat(widgets).isNotNull();
        assertThat(widgets.size()).isEqualTo(1);
        assertThat(widgets.get(0).get("content").get("kind").asText()).isEqualTo("kpi");
        assertThat(widgets.get(0).has("createdBy")).isTrue();

        // Update keeps the kind; delete answers 204, then 404.
        ResponseEntity<JsonNode> updated = rest.exchange(BASE + "/" + id, HttpMethod.PUT,
                json(request("page", "Open tickets", null, Map.of("id", "queue", "type", "entity-list", "entity", "Ticket"))), JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("name").asText()).isEqualTo("Open tickets");
        assertThat(updated.getBody().get("content").has("presetFilter")).isFalse();
        assertThat(rest.exchange(BASE + "/" + id, HttpMethod.PUT, json(request("widget", "x", null, widget)), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(rest.exchange(BASE + "/" + id, HttpMethod.DELETE, null, Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.getForEntity(BASE + "/" + id, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void namesAreUniquePerKindIgnoringCase() {
        Map<String, Object> widget = Map.of("kind", "bar", "entity", "Order");
        assertThat(post(request("widget", "Orders by status", null, widget), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> clash = post(request("widget", "ORDERS BY STATUS", null, widget), null);
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(clash.getBody().get("detail").asText()).contains("already exists");
    }

    @Test
    void rejectsMalformedTemplates() {
        Map<String, Object> page = Map.of("type", "dashboard");
        for (Map<String, Object> body : List.of(
                request("layout", "x", null, page),                         // unknown kind
                request("page", "  ", null, page),                          // blank name
                request("page", "x", null, Map.of("entity", "Order")),      // a page without a type
                request("widget", "x", null, page),                         // a widget without a kind
                request("page", "x".repeat(151), null, page),               // name too long
                request("page", "x", null, Map.of("type", "content", "body", "x".repeat(70_000))))) {
            ResponseEntity<JsonNode> res = post(body, null);
            assertThat(res.getStatusCode()).as(body.toString().substring(0, Math.min(80, body.toString().length())))
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().get("error").asText()).isEqualTo("Invalid team template");
        }
        Map<String, Object> notAnObject = new LinkedHashMap<>(request("page", "x", null, page));
        notAnObject.put("content", List.of(1, 2));
        assertThat(post(notAnObject, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity(BASE + "?kind=nope", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repo.count()).isZero();
    }

    private static Map<String, Object> request(String kind, String name, String description, Object content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("name", name);
        if (description != null) body.put("description", description);
        body.put("content", content);
        return body;
    }

    private ResponseEntity<JsonNode> post(Map<String, Object> body, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) headers.set("userinfo", user);
        return rest.postForEntity(BASE, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
