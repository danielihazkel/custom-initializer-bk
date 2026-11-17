package com.menora.initializr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fullstack 400 says where in the request the problem is — {@code path} beside {@code detail} —
 * so the editor can put the message on the card, row or page that caused it instead of a toast.
 * One bad request per place in the tree; the message is checked only loosely, the path exactly.
 */
@IntegrationTest
class FullstackErrorPathTests {

    @Autowired
    private TestRestTemplate restTemplate;

    private static Map<String, Object> pk() {
        return Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true);
    }

    private static Map<String, Object> entity(String name, List<Map<String, Object>> fields) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", name);
        e.put("fields", fields);
        return e;
    }

    /** Customer (id, name) and Order (id, total, status) — valid until a test breaks one thing. */
    private static Map<String, Object> shop() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        List<Map<String, Object>> entities = new ArrayList<>();
        entities.add(entity("Customer", new ArrayList<>(List.of(pk(), Map.of("name", "name", "type", "STRING")))));
        entities.add(entity("Order", new ArrayList<>(List.of(pk(),
                Map.of("name", "total", "type", "BIG_DECIMAL"),
                Map.of("name", "status", "type", "ENUM", "enumValues", List.of("OPEN", "PAID"))))));
        body.put("entities", entities);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entities(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("entities");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> body, int entity) {
        return (List<Map<String, Object>>) entities(body).get(entity).get("fields");
    }

    private JsonNode reject(Map<String, Object> body, String endpoint) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        return new ObjectMapper().readTree(response.getBody());
    }

    private void assertPath(Map<String, Object> body, String path, String messagePart) throws Exception {
        JsonNode error = reject(body, "/starter-fullstack.zip");
        assertThat(error.path("detail").asText()).contains(messagePart);
        assertThat(error.path("path").asText(null)).as(error.path("detail").asText()).isEqualTo(path);
    }

    @Test
    void anEntityNameProblemPointsAtTheName() throws Exception {
        Map<String, Object> body = shop();
        entities(body).get(1).put("name", "class");
        assertPath(body, "entities[1].name", "reserved keyword");

        body = shop();
        entities(body).get(1).put("name", "Page");
        assertPath(body, "entities[1].name", "cannot be used");

        body = shop();
        entities(body).add(entity("OrderDto", List.of(pk())));
        assertPath(body, "entities[2].name", "already generates a class");
    }

    @Test
    void aFieldProblemPointsAtTheFieldAndItsProperty() throws Exception {
        Map<String, Object> body = shop();
        fields(body, 1).set(1, Map.of("name", "total", "type", "MONEYS"));
        assertPath(body, "entities[1].fields[1].type", "Unknown field type");

        body = shop();
        fields(body, 1).set(1, Map.of("name", "total", "type", "BIG_DECIMAL", "defaultValue", "lots"));
        assertPath(body, "entities[1].fields[1].defaultValue", "total");

        body = shop();
        fields(body, 0).set(1, Map.of("name", "new", "type", "STRING"));
        assertPath(body, "entities[0].fields[1].name", "reserved keyword");

        body = shop();
        fields(body, 0).set(1, Map.of("name", "name", "type", "STRING", "column", "NAME; DROP"));
        assertPath(body, "entities[0].fields[1].column", "not a valid column name");

        body = shop();
        fields(body, 1).set(2, Map.of("name", "status", "type", "ENUM", "enumValues", List.of("OPEN"),
                "enumLabels", Map.of("CLOSED", "Closed")));
        assertPath(body, "entities[1].fields[2].enumLabels", "CLOSED");
    }

    @Test
    void anEntityLevelProblemPointsAtTheEntity() throws Exception {
        Map<String, Object> body = shop();
        fields(body, 1).remove(0);
        assertPath(body, "entities[1]", "no primary key");

        body = shop();
        body.put("opts", Map.of("scaffold", List.of("audit")));
        fields(body, 0).add(Map.of("name", "createdAt", "type", "LOCAL_DATE_TIME"));
        assertPath(body, "entities[0]", "clashes with 'createdAt'");
    }

    @Test
    void aRelationProblemPointsAtTheRelation() throws Exception {
        Map<String, Object> body = shop();
        entities(body).get(1).put("relations", List.of(
                Map.of("type", "MANY_TO_ONE", "fieldName", "customer", "targetEntity", "Customer"),
                Map.of("type", "MANY_TO_ONE", "fieldName", "warehouse", "targetEntity", "Warehouse")));
        assertPath(body, "entities[1].relations[1]", "unknown entity");

        body = shop();
        entities(body).get(1).put("relations", List.of(
                Map.of("type", "MANY_TO_ONE", "fieldName", "total", "targetEntity", "Customer")));
        assertPath(body, "entities[1].relations[0].fieldName", "collides");
    }

    @Test
    void aWorkflowProblemPointsIntoTheWorkflow() throws Exception {
        Map<String, Object> body = shop();
        entities(body).get(1).put("workflow", Map.of("statusField", "status", "transitions", List.of(
                Map.of("name", "pay", "from", List.of("OPEN"), "to", "PAID"),
                Map.of("name", "ship", "from", List.of("PAID"), "to", "SHIPPED"))));
        assertPath(body, "entities[1].workflow.transitions[1]", "SHIPPED");

        body = shop();
        entities(body).get(1).put("workflow", Map.of("statusField", "total", "transitions", List.of(
                Map.of("name", "pay", "from", List.of("OPEN"), "to", "PAID"))));
        assertPath(body, "entities[1].workflow", "statusField");
    }

    @Test
    void aFormSectionProblemPointsAtTheSection() throws Exception {
        Map<String, Object> body = shop();
        entities(body).get(1).put("formSections", List.of(
                Map.of("title", "Money", "fields", List.of("total")),
                Map.of("title", "Other", "fields", List.of("colour"))));
        assertPath(body, "entities[1].formSections[1]", "colour");
    }

    @Test
    void aPageProblemPointsAtThePageAndThePartOfIt() throws Exception {
        Map<String, Object> body = shop();
        body.put("pages", List.of(
                Map.of("id", "orders", "type", "entity-list", "entity", "Order"),
                Map.of("id", "ghosts", "type", "entity-list", "entity", "Ghost")));
        assertPath(body, "pages[1]", "Ghost");

        body = shop();
        body.put("pages", List.of(
                Map.of("id", "orders", "type", "entity-list", "entity", "Order"),
                Map.of("id", "desk", "type", "dashboard", "widgets", List.of(
                        Map.of("kind", "kpi", "entity", "Order"),
                        Map.of("kind", "bar", "entity", "Order", "groupBy", "total")))));
        assertPath(body, "pages[1].widgets[1]", "total");

        body = shop();
        body.put("pages", List.of(Map.of("id", "Orders List", "type", "entity-list", "entity", "Order")));
        assertPath(body, "pages[0]", "Page id");
    }

    @Test
    void aProjectLevelProblemNamesItsField() throws Exception {
        Map<String, Object> body = shop();
        body.put("opts", Map.of("scaffold", List.of("audit", "softdelete")));
        assertPath(body, "opts.scaffold[1]", "Unknown scaffold option");

        body = shop();
        body.put("bootVersion", "9.9.9");
        assertPath(body, "bootVersion", "not a known version");

        body = shop();
        body.put("locale", "fr");
        assertPath(body, "locale", "not supported");

        body = shop();
        body.put("frontendTemplateSet", "no-such-set");
        assertPath(body, "frontendTemplateSet", "not found");
    }

    @Test
    void aProblemWithTheWholeRequestHasNoPath() throws Exception {
        Map<String, Object> body = shop();
        body.put("entities", List.of());
        JsonNode error = reject(body, "/starter-fullstack.zip");
        assertThat(error.path("detail").asText()).contains("At least one entity");
        assertThat(error.has("path")).isFalse();
    }

    @Test
    void thePreviewEndpointSaysTheSame() throws Exception {
        Map<String, Object> body = shop();
        fields(body, 1).set(1, Map.of("name", "total", "type", "MONEYS"));
        JsonNode error = reject(body, "/starter-fullstack.preview");
        assertThat(error.path("path").asText(null)).isEqualTo("entities[1].fields[1].type");
        assertThat(error.path("error").asText()).isEqualTo("Invalid request");
    }
}
