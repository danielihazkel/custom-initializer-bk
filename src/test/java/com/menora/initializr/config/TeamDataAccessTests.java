package com.menora.initializr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.menora.initializr.IntegrationTest;
import com.menora.initializr.db.repository.BackendPresetRepository;
import com.menora.initializr.db.repository.FullstackModelRepository;
import com.menora.initializr.db.repository.FullstackTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Who may change what the team shares ({@link TeamDataAccess}), over HTTP and on each of the
 * three kinds of it — team models, team presets, team templates: only whoever saved a row, or a
 * signed-in admin; a row nobody is named for stays open; each answer says whether the caller may
 * edit and who changed it last.
 */
@IntegrationTest
class TeamDataAccessTests {

    private static final String MODELS = "/metadata/fullstack/models";
    private static final String PRESETS = "/metadata/backend/presets";
    private static final String TEMPLATES = "/metadata/fullstack/templates";

    @Autowired private TestRestTemplate rest;
    @Autowired private FullstackModelRepository models;
    @Autowired private BackendPresetRepository presets;
    @Autowired private FullstackTemplateRepository templates;

    @BeforeEach
    void cleanTables() {
        models.deleteAll();
        presets.deleteAll();
        templates.deleteAll();
    }

    /** A body each endpoint takes, under {@code name}. */
    private static Map<String, Object> body(String base, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        switch (base) {
            case MODELS -> body.put("snapshot", Map.of("entities", List.of(Map.of("name", "Order"))));
            case PRESETS -> body.put("snapshot", Map.of("form", Map.of("groupId", "com.menora", "artifactId", "shop"),
                    "selected", List.of("web")));
            default -> {
                body.put("kind", "page");
                body.put("content", Map.of("id", "queue", "type", "entity-list", "entity", "Ticket"));
            }
        }
        return body;
    }

    private ResponseEntity<JsonNode> send(HttpMethod method, String url, Object body, String user, String adminToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) headers.set("userinfo", user);
        if (adminToken != null) headers.setBearerAuth(adminToken);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private String adminToken() {
        return rest.postForEntity("/admin/login", Map.of("password", "test"), JsonNode.class).getBody().get("token").asText();
    }

    private JsonNode listed(String base, long id, String user, String adminToken) {
        for (JsonNode row : send(HttpMethod.GET, base, null, user, adminToken).getBody()) {
            if (row.get("id").asLong() == id) return row;
        }
        throw new AssertionError("row " + id + " is not listed");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {MODELS, PRESETS, TEMPLATES})
    void onlyWhoeverSavedARowOrAnAdminChangesOrDeletesIt(String base) {
        ResponseEntity<JsonNode> created = send(HttpMethod.POST, base, body(base, "Alice's"), "alice", null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = created.getBody().get("id").asLong();
        assertThat(created.getBody().get("createdBy").asText()).isEqualTo("alice");
        assertThat(created.getBody().get("updatedBy").asText()).isEqualTo("alice");
        assertThat(created.getBody().get("canEdit").asBoolean()).isTrue();

        // Everyone reads it; each caller is told whether it is theirs to change.
        assertThat(listed(base, id, "alice", null).get("canEdit").asBoolean()).isTrue();
        assertThat(listed(base, id, "ALICE", null).get("canEdit").asBoolean()).as("a login is one name however it is cased").isTrue();
        assertThat(listed(base, id, "bob", null).get("canEdit").asBoolean()).isFalse();
        assertThat(listed(base, id, null, null).get("canEdit").asBoolean()).isFalse();
        assertThat(send(HttpMethod.GET, base + "/" + id, null, "bob", null).getBody().get("canEdit").asBoolean()).isFalse();

        // Somebody else, and a caller nobody names: refused, and told whose it is.
        for (String other : new String[] {"bob", null}) {
            ResponseEntity<JsonNode> put = send(HttpMethod.PUT, base + "/" + id, body(base, "Bob's now"), other, null);
            assertThat(put.getStatusCode()).as("PUT as " + other).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(put.getBody().get("error").asText()).isEqualTo("Not yours to change");
            assertThat(put.getBody().get("detail").asText())
                    .isEqualTo("Saved by alice — only they or a signed-in admin can change or delete it");
            assertThat(send(HttpMethod.DELETE, base + "/" + id, null, other, null).getStatusCode())
                    .as("DELETE as " + other).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(listed(base, id, "alice", null).get("name").asText()).isEqualTo("Alice's");
        // A token that is no admin's is nobody's.
        assertThat(send(HttpMethod.PUT, base + "/" + id, body(base, "x"), "bob", "not-a-token").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Its creator changes it, and so does a signed-in admin — the change says who made it.
        ResponseEntity<JsonNode> hers = send(HttpMethod.PUT, base + "/" + id, body(base, "Alice's, again"), "Alice", null);
        assertThat(hers.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hers.getBody().get("updatedBy").asText()).isEqualTo("Alice");
        assertThat(hers.getBody().get("createdBy").asText()).isEqualTo("alice");
        String token = adminToken();
        assertThat(listed(base, id, "bob", token).get("canEdit").asBoolean()).isTrue();
        ResponseEntity<JsonNode> admins = send(HttpMethod.PUT, base + "/" + id, body(base, "Tidied by an admin"), "carol", token);
        assertThat(admins.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admins.getBody().get("updatedBy").asText()).isEqualTo("carol");
        assertThat(admins.getBody().get("createdBy").asText()).isEqualTo("alice");
        // An admin nobody names (the shared password, no gateway) is recorded as "admin".
        assertThat(send(HttpMethod.PUT, base + "/" + id, body(base, "Tidied again"), null, token).getBody()
                .get("updatedBy").asText()).isEqualTo("admin");
        assertThat(send(HttpMethod.DELETE, base + "/" + id, null, null, token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {MODELS, PRESETS, TEMPLATES})
    void aRowNobodyIsNamedForStaysOpen(String base) {
        // Saved without the gateway naming anyone — as every row was before users were recorded.
        ResponseEntity<JsonNode> created = send(HttpMethod.POST, base, body(base, "Anyone's"), null, null);
        long id = created.getBody().get("id").asLong();
        assertThat(created.getBody().get("createdBy").isNull()).isTrue();
        assertThat(created.getBody().get("updatedBy").isNull()).isTrue();
        assertThat(listed(base, id, "bob", null).get("canEdit").asBoolean()).isTrue();
        assertThat(listed(base, id, null, null).get("canEdit").asBoolean()).isTrue();

        // Whoever changes it is recorded, and it is no more theirs for that: it has no creator.
        ResponseEntity<JsonNode> bobs = send(HttpMethod.PUT, base + "/" + id, body(base, "Bob was here"), "bob", null);
        assertThat(bobs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobs.getBody().get("updatedBy").asText()).isEqualTo("bob");
        assertThat(bobs.getBody().get("createdBy").isNull()).isTrue();
        assertThat(send(HttpMethod.PUT, base + "/" + id, body(base, "And anyone"), null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.DELETE, base + "/" + id, null, "dave", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
