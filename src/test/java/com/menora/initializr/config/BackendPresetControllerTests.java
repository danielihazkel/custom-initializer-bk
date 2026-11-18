package com.menora.initializr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.IntegrationTest;
import com.menora.initializr.db.repository.BackendPresetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * The Backend tab's team presets at {@code /metadata/backend/presets} over HTTP: the wire shapes
 * the UI hook reads, the figures kept for the list, the snapshot coming back as it was stored,
 * and the 400/404/409 answers.
 */
@IntegrationTest
class BackendPresetControllerTests {

    private static final String BASE = "/metadata/backend/presets";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BackendPresetRepository repo;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTable() {
        repo.deleteAll();
    }

    @Test
    void crudRoundTrip() throws Exception {
        Map<String, Object> snapshot = snapshot("shop", List.of("web", "data-jpa", "postgresql"), true, List.of("api", "core"));

        ResponseEntity<JsonNode> created = send(HttpMethod.POST, BASE, request("Shop service", "Our baseline", snapshot), "alice");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode summary = created.getBody();
        assertThat(summary.get("name").asText()).isEqualTo("Shop service");
        assertThat(summary.get("description").asText()).isEqualTo("Our baseline");
        assertThat(summary.get("coordinates").asText()).isEqualTo("com.menora:shop");
        assertThat(summary.get("dependencyCount").asInt()).isEqualTo(3);
        assertThat(summary.get("moduleCount").asInt()).isEqualTo(2);
        assertThat(summary.get("createdBy").asText()).isEqualTo("alice");
        assertThat(summary.has("snapshot")).isFalse();
        long id = summary.get("id").asLong();

        JsonNode list = rest.getForEntity(BASE, JsonNode.class).getBody();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).has("snapshot")).isFalse();

        JsonNode got = rest.getForEntity(BASE + "/" + id, JsonNode.class).getBody();
        assertThat(got.get("name").asText()).isEqualTo("Shop service");
        assertThat(got.get("snapshot")).isEqualTo(objectMapper.valueToTree(snapshot));

        // A setup without modules counts none, whatever selectedModules holds.
        Map<String, Object> single = snapshot("shop", List.of("web"), false, List.of("api"));
        ResponseEntity<JsonNode> updated = send(HttpMethod.PUT, BASE + "/" + id, request("Shop service v2", null, single), null);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("name").asText()).isEqualTo("Shop service v2");
        assertThat(updated.getBody().get("moduleCount").asInt()).isZero();
        assertThat(updated.getBody().get("createdBy").asText()).isEqualTo("alice");

        assertThat(send(HttpMethod.DELETE, BASE + "/" + id, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.getForEntity(BASE + "/" + id, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void namesAreUniqueIgnoringCase() {
        Map<String, Object> snapshot = snapshot("a", List.of(), false, List.of());
        assertThat(send(HttpMethod.POST, BASE, request("Baseline", null, snapshot), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> clash = send(HttpMethod.POST, BASE, request("baseline", null, snapshot), null);
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(clash.getBody().get("detail").asText()).contains("'Baseline'");
    }

    @Test
    void refusesWhatIsNotABackendSetup() {
        assertThat(send(HttpMethod.POST, BASE, request(" ", null, snapshot("a", List.of(), false, List.of())), null)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> noForm = new LinkedHashMap<>(Map.of("selected", List.of()));
        assertThat(send(HttpMethod.POST, BASE, request("x", null, noForm), null).getBody().get("detail").asText())
                .contains("snapshot.form");
        Map<String, Object> noSelection = new LinkedHashMap<>(Map.of("form", Map.of()));
        assertThat(send(HttpMethod.POST, BASE, request("y", null, noSelection), null).getBody().get("detail").asText())
                .contains("snapshot.selected");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> snapshot(String artifactId, List<String> selected, boolean multiModule, List<String> modules) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("groupId", "com.menora");
        form.put("artifactId", artifactId);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("form", form);
        s.put("selected", selected);
        s.put("selectedOptions", Map.of());
        s.put("sqlByDep", Map.of());
        s.put("openApiByDep", Map.of());
        s.put("soapByDep", Map.of());
        s.put("multiModuleEnabled", multiModule);
        s.put("selectedModules", modules);
        return s;
    }

    private static Map<String, Object> request(String name, String description, Map<String, Object> snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (description != null) body.put("description", description);
        body.put("snapshot", snapshot);
        return body;
    }

    private ResponseEntity<JsonNode> send(HttpMethod method, String url, Object body, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) headers.set("userinfo", user);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
