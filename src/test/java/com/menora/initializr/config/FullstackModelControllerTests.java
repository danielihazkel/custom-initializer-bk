package com.menora.initializr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.repository.FullstackModelRepository;
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
 * Pins the team-model CRUD at {@code /metadata/fullstack/models} over HTTP: the wire shapes
 * the UI hook binds to, the 400/404/409 error paths, and that the stored snapshot comes back
 * byte-for-byte equal as JSON.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullstackModelControllerTests {

    private static final String BASE = "/metadata/fullstack/models";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FullstackModelRepository repo;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTable() {
        repo.deleteAll();
    }

    @Test
    void crudRoundTrip() throws Exception {
        Map<String, Object> snapshot = sampleSnapshot("shop", 2);

        // create — 201 with the summary, createdBy from the SSO header
        ResponseEntity<JsonNode> created = post(request("Shop model", "Orders + customers", snapshot), "alice");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode summary = created.getBody();
        assertThat(summary).isNotNull();
        assertThat(summary.get("id").isIntegralNumber()).isTrue();
        assertThat(summary.get("name").asText()).isEqualTo("Shop model");
        assertThat(summary.get("description").asText()).isEqualTo("Orders + customers");
        assertThat(summary.get("entityCount").asInt()).isEqualTo(2);
        assertThat(summary.get("createdBy").asText()).isEqualTo("alice");
        assertThat(summary.get("createdAt").asText()).isNotBlank();
        assertThat(summary.get("updatedAt").asText()).isEqualTo(summary.get("createdAt").asText());
        assertThat(summary.has("snapshot")).as("summary must not carry the snapshot").isFalse();
        long id = summary.get("id").asLong();

        // list — one summary, same shape
        ResponseEntity<JsonNode> list = rest.getForEntity(BASE, JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().isArray()).isTrue();
        assertThat(list.getBody().size()).isEqualTo(1);
        assertThat(list.getBody().get(0).get("id").asLong()).isEqualTo(id);
        assertThat(list.getBody().get(0).has("snapshot")).isFalse();

        // get — summary + the snapshot round-trips as equal JSON
        ResponseEntity<JsonNode> got = rest.getForEntity(BASE + "/" + id, JsonNode.class);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(got.getBody()).isNotNull();
        assertThat(got.getBody().get("name").asText()).isEqualTo("Shop model");
        assertThat(got.getBody().get("snapshot")).isEqualTo(objectMapper.valueToTree(snapshot));

        // duplicate name (case-insensitive) — 409
        ResponseEntity<JsonNode> dup = post(request("shop MODEL", null, snapshot), null);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).isNotNull();
        assertThat(dup.getBody().get("error").asText()).isEqualTo("Name already in use");
        assertThat(dup.getBody().get("detail").asText()).contains("Shop model");

        // update — new name/description/snapshot, entityCount follows the snapshot, createdBy kept
        Map<String, Object> bigger = sampleSnapshot("shop", 3);
        ResponseEntity<JsonNode> updated = rest.exchange(BASE + "/" + id, HttpMethod.PUT,
                json(request("Shop model v2", "", bigger)), JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().get("name").asText()).isEqualTo("Shop model v2");
        assertThat(updated.getBody().get("description").isNull()).as("blank description stored as null").isTrue();
        assertThat(updated.getBody().get("entityCount").asInt()).isEqualTo(3);
        assertThat(updated.getBody().get("createdBy").asText()).isEqualTo("alice");
        JsonNode after = rest.getForEntity(BASE + "/" + id, JsonNode.class).getBody();
        assertThat(after).isNotNull();
        assertThat(after.get("snapshot")).isEqualTo(objectMapper.valueToTree(bigger));

        // renaming onto another model's name — 409; renaming onto its own name — fine
        long otherId = post(request("Other", null, snapshot), null).getBody().get("id").asLong();
        ResponseEntity<JsonNode> clash = rest.exchange(BASE + "/" + otherId, HttpMethod.PUT,
                json(request("SHOP MODEL V2", null, snapshot)), JsonNode.class);
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> sameName = rest.exchange(BASE + "/" + id, HttpMethod.PUT,
                json(request("Shop model v2", "again", bigger)), JsonNode.class);
        assertThat(sameName.getStatusCode()).isEqualTo(HttpStatus.OK);

        // delete — 204, then 404 on get/put/delete
        ResponseEntity<Void> deleted = rest.exchange(BASE + "/" + id, HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<JsonNode> gone = rest.getForEntity(BASE + "/" + id, JsonNode.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(gone.getBody()).isNotNull();
        assertThat(gone.getBody().get("error").asText()).isEqualTo("Not found");
        assertThat(rest.exchange(BASE + "/" + id, HttpMethod.PUT, json(request("x", null, snapshot)), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange(BASE + "/" + id, HttpMethod.DELETE, null, Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void createWithoutUserHeaderLeavesCreatedByNull() {
        ResponseEntity<JsonNode> created = post(request("Anonymous", null, sampleSnapshot("a", 1)), null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().get("createdBy").isNull()).isTrue();
    }

    @Test
    void rejectsBlankName() {
        ResponseEntity<JsonNode> res = post(request("   ", null, sampleSnapshot("a", 1)), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("error").asText()).isEqualTo("Invalid team model");
        assertThat(res.getBody().get("detail").asText()).isEqualTo("name is required");
    }

    @Test
    void rejectsSnapshotWithoutEntitiesArray() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Bad");
        body.put("snapshot", Map.of("meta", Map.of("artifactId", "x")));
        ResponseEntity<JsonNode> noEntities = post(body, null);
        assertThat(noEntities.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noEntities.getBody()).isNotNull();
        assertThat(noEntities.getBody().get("detail").asText()).isEqualTo("snapshot.entities must be an array");

        body.put("snapshot", "not an object");
        ResponseEntity<JsonNode> notObject = post(body, null);
        assertThat(notObject.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(notObject.getBody()).isNotNull();
        assertThat(notObject.getBody().get("detail").asText()).isEqualTo("snapshot must be a JSON object");

        body.remove("snapshot");
        ResponseEntity<JsonNode> missing = post(body, null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repo.count()).isZero();
    }

    @Test
    void rejectsOversizedSnapshot() {
        Map<String, Object> snapshot = sampleSnapshot("big", 1);
        snapshot.put("padding", "x".repeat(FullstackModelController.MAX_SNAPSHOT_BYTES + 1));
        ResponseEntity<JsonNode> res = post(request("Too big", null, snapshot), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("detail").asText()).contains("exceeds");
        assertThat(repo.count()).isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> request(String name, String description, Object snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (description != null) body.put("description", description);
        body.put("snapshot", snapshot);
        return body;
    }

    /** Mirrors the UI's {@code menora-fullstack-model/1} export shape closely enough for the server's checks. */
    private static Map<String, Object> sampleSnapshot(String artifactId, int entityCount) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("groupId", "com.menora");
        meta.put("artifactId", artifactId);
        meta.put("packageName", "com.menora." + artifactId);
        List<Map<String, Object>> entities = new java.util.ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            entities.add(Map.of(
                    "name", "Entity" + i,
                    "fields", List.of(Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true))));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("format", "menora-fullstack-model/1");
        snapshot.put("meta", meta);
        snapshot.put("entities", entities);
        snapshot.put("selectedDeps", List.of("web", "data-jpa"));
        snapshot.put("scaffoldOpts", List.of("audit"));
        snapshot.put("backendSet", "spring-jpa-crud");
        snapshot.put("frontendSet", "react-tailwind-crud");
        return snapshot;
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
