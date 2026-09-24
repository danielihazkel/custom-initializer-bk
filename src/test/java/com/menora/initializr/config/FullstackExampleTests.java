package com.menora.initializr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.admin.FullstackExampleAdminController;
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
 * Pins the admin-managed Fullstack "Start from → Examples": the seeded catalog is exposed in
 * order at {@code /metadata/fullstack/examples}, every seeded example passes the generator's
 * own validator, and the {@code /admin/fullstack-examples} CRUD guards its input (auth, 400
 * for an entity model the generator would reject, 409 on a duplicate id, disabled rows hidden).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullstackExampleTests {

    private static final String ADMIN = "/admin/fullstack-examples";
    private static final String PUBLIC = "/metadata/fullstack/examples";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @Test
    void publicEndpointListsTheSeededExamplesInOrder() {
        JsonNode list = rest.getForEntity(PUBLIC, JsonNode.class).getBody();
        assertThat(list).isNotNull();
        assertThat(publicIds()).startsWith("blog", "orders", "tickets", "inventory", "enrolments", "reporting");
        JsonNode blog = list.get(0);
        assertThat(blog.get("name").asText()).isEqualTo("Blog");
        assertThat(blog.get("icon").asText()).isEqualTo("article");
        assertThat(blog.get("entities").isArray()).as("entities are JSON, not a string").isTrue();
        assertThat(blog.get("entities").get(0).get("name").asText()).isEqualTo("Author");
    }

    @Test
    void everySeededExamplePassesTheFullstackValidator() {
        JsonNode list = rest.getForEntity(PUBLIC, JsonNode.class).getBody();
        assertThat(list).isNotNull();
        for (JsonNode example : list) {
            assertThat(FullstackExampleAdminController.validateEntities(example.get("entities"), json))
                    .as(example.get("id").asText())
                    .isNotBlank();
        }
    }

    @Test
    void seededLayoutsPassThePageValidatorAndTravelWithTheExample() {
        JsonNode list = rest.getForEntity(PUBLIC, JsonNode.class).getBody();
        assertThat(list).isNotNull();
        int withPages = 0;
        for (JsonNode example : list) {
            JsonNode pages = example.get("pages");
            if (pages == null || pages.isNull()) continue;
            withPages++;
            assertThat(FullstackExampleAdminController.validatePages(example.get("entities"), pages, example.get("settings"), json))
                    .as(example.get("id").asText())
                    .isNotBlank();
            assertThat(FullstackExampleAdminController.validateSettings(example.get("settings"), json))
                    .as(example.get("id").asText() + " settings")
                    .isNotBlank();
        }
        assertThat(withPages).as("examples that showcase page layouts").isGreaterThanOrEqualTo(3);
        // One example stays layout-free, to show the generator's default shell.
        JsonNode enrolments = list.get(4);
        assertThat(enrolments.get("id").asText()).isEqualTo("enrolments");
        assertThat(enrolments.get("pages").isNull()).isTrue();
        assertThat(enrolments.get("settings").isNull()).isTrue();
    }

    @Test
    void adminCrudValidatesPagesAndSettings() throws Exception {
        HttpHeaders auth = adminHeaders();

        // A page that points at an entity the example doesn't have — 400 from the page validator.
        Map<String, Object> badPage = example("bad-pages", validEntities(), true);
        badPage.put("pages", List.of(Map.of("id", "things", "type", "entity-list", "entity", "Nope")));
        ResponseEntity<JsonNode> bad = exchange(HttpMethod.POST, ADMIN, badPage, auth);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("detail").asText()).contains("unknown entity 'Nope'");

        // An unknown settings key — 400 naming it.
        Map<String, Object> badSettings = example("bad-settings", validEntities(), true);
        badSettings.put("settings", Map.of("theme", "dark"));
        ResponseEntity<JsonNode> bad2 = exchange(HttpMethod.POST, ADMIN, badSettings, auth);
        assertThat(bad2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad2.getBody().get("detail").asText()).contains("settings.theme is not a known setting");

        // A list page's presentation is checked against the entity — and its settings: the audit
        // columns exist only with the audit scaffold option on.
        Map<String, Object> badView = example("bad-view", validEntities(), true);
        badView.put("pages", List.of(Map.of("id", "things", "type", "entity-list", "entity", "Thing", "view", "kanban")));
        ResponseEntity<JsonNode> bad3 = exchange(HttpMethod.POST, ADMIN, badView, auth);
        assertThat(bad3.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad3.getBody().get("detail").asText()).contains("view 'kanban' is not enabled on Thing");
        Map<String, Object> noAudit = example("no-audit", validEntities(), true);
        noAudit.put("pages", List.of(Map.of("id", "things", "type", "entity-list", "entity", "Thing",
                "columns", List.of("title", "createdAt"))));
        ResponseEntity<JsonNode> bad4 = exchange(HttpMethod.POST, ADMIN, noAudit, auth);
        assertThat(bad4.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad4.getBody().get("detail").asText()).contains("'createdAt' is not a column of Thing — the audit scaffold option is off");
        Map<String, Object> withAudit = example("with-audit", validEntities(), true);
        withAudit.put("pages", List.of(Map.of("id", "things", "type", "entity-list", "entity", "Thing",
                "columns", List.of("title", "createdAt"), "sort", Map.of("field", "createdAt", "dir", "desc"))));
        withAudit.put("settings", Map.of("scaffold", List.of("audit")));
        ResponseEntity<JsonNode> ok = exchange(HttpMethod.POST, ADMIN, withAudit, auth);
        assertThat(ok.getStatusCode()).as(String.valueOf(ok.getBody())).isEqualTo(HttpStatus.CREATED);
        rest.exchange(ADMIN + "/" + ok.getBody().get("id").asLong(), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);

        // A valid layout + settings is stored and served on the public list as JSON.
        Map<String, Object> good = example("with-layout", validEntities(), true);
        good.put("pages", List.of(
                Map.of("id", "home", "type", "dashboard", "widgets", List.of(Map.of("kind", "kpi", "entity", "Thing"))),
                Map.of("id", "things", "type", "entity-list", "entity", "Thing")));
        good.put("settings", Map.of("locale", "he", "scaffold", List.of("csvExport")));
        ResponseEntity<JsonNode> created = exchange(HttpMethod.POST, ADMIN, good, auth);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode served = java.util.stream.StreamSupport.stream(
                        rest.getForEntity(PUBLIC, JsonNode.class).getBody().spliterator(), false)
                .filter(n -> n.get("id").asText().equals("with-layout")).findFirst().orElseThrow();
        assertThat(served.get("pages").get(1).get("entity").asText()).isEqualTo("Thing");
        assertThat(served.get("settings").get("locale").asText()).isEqualTo("he");

        rest.exchange(ADMIN + "/" + created.getBody().get("id").asLong(), HttpMethod.DELETE,
                new HttpEntity<>(auth), Void.class);
    }

    @Test
    void adminEndpointRequiresAuth() {
        assertThat(rest.getForEntity(ADMIN, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminCrudValidatesAndHidesDisabledExamples() throws Exception {
        HttpHeaders auth = adminHeaders();

        // An entity model the generator rejects (no primary key) — 400 naming the problem.
        Map<String, Object> noPk = example("bad-one", List.of(Map.of(
                "name", "Thing", "fields", List.of(Map.of("name", "title", "type", "STRING")))), true);
        ResponseEntity<JsonNode> bad = exchange(HttpMethod.POST, ADMIN, noPk, auth);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("error").asText()).isEqualTo("Invalid example");

        // Bad slug — 400.
        ResponseEntity<JsonNode> badId = exchange(HttpMethod.POST, ADMIN, example("Not A Slug", validEntities(), true), auth);
        assertThat(badId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Duplicate of a seeded id — 409.
        ResponseEntity<JsonNode> dup = exchange(HttpMethod.POST, ADMIN, example("blog", validEntities(), true), auth);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // A disabled example is stored but not offered on the public list.
        ResponseEntity<JsonNode> created = exchange(HttpMethod.POST, ADMIN, example("hidden-one", validEntities(), false), auth);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = created.getBody().get("id").asLong();
        assertThat(created.getBody().get("entities").isArray()).isTrue();
        assertThat(publicIds()).doesNotContain("hidden-one");

        // Enabling it through PUT publishes it.
        ResponseEntity<JsonNode> updated = exchange(HttpMethod.PUT, ADMIN + "/" + id, example("hidden-one", validEntities(), true), auth);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicIds()).contains("hidden-one");

        ResponseEntity<Void> deleted = rest.exchange(ADMIN + "/" + id, HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(publicIds()).doesNotContain("hidden-one");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<String> publicIds() {
        JsonNode list = rest.getForEntity(PUBLIC, JsonNode.class).getBody();
        return java.util.stream.StreamSupport.stream(list.spliterator(), false)
                .map(n -> n.get("id").asText()).toList();
    }

    private static List<Map<String, Object>> validEntities() {
        return List.of(Map.of("name", "Thing", "fields", List.of(
                Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "title", "type", "STRING", "required", true))));
    }

    private static Map<String, Object> example(String exampleId, Object entities, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exampleId", exampleId);
        body.put("name", "Example " + exampleId);
        body.put("description", "test");
        body.put("icon", "star");
        body.put("entities", entities);
        body.put("sortOrder", 99);
        body.put("enabled", enabled);
        return body;
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String url, Object body, HttpHeaders auth) {
        return rest.exchange(url, method, new HttpEntity<>(body, auth), JsonNode.class);
    }

    private HttpHeaders adminHeaders() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> login = rest.exchange("/admin/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("password", "test"), headers), String.class);
        headers.setBearerAuth(json.readTree(login.getBody()).get("token").asText());
        return headers;
    }
}
