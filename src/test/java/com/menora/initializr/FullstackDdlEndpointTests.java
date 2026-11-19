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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /starter-fullstack.ddl}: the model's tables as a script, without generating the
 * project. The script must be the project's Flyway baseline byte for byte — the endpoint builds
 * the schema through the same context builder ({@code BackendScaffoldContext}) and the same
 * request checks as generation, and this is what fails when the two drift apart.
 */
@IntegrationTest
class FullstackDdlEndpointTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    private ResponseEntity<String> ddl(Map<String, Object> body, String dialect) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/starter-fullstack.ddl" + (dialect == null ? "" : "?dialect=" + dialect),
                HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private JsonNode ok(ResponseEntity<String> r) throws Exception {
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        return json.readTree(r.getBody());
    }

    /** The backend set's default dependencies — what the editor starts a model with — plus {@code extra}. */
    private List<String> defaultsPlus(String... extra) throws Exception {
        JsonNode sets = json.readTree(rest.getForObject("/metadata/entity-template-sets", String.class));
        Set<String> deps = new LinkedHashSet<>();
        for (JsonNode set : sets) {
            if (set.path("setKey").asText().equals("spring-jpa-crud")) {
                set.path("defaultDeps").forEach(d -> deps.add(d.asText()));
            }
        }
        assertThat(deps).isNotEmpty();
        deps.addAll(List.of(extra));
        return new ArrayList<>(deps);
    }

    @Test
    void theScriptIsTheProjectsBaselineForEveryExample() throws Exception {
        int compared = 0;
        for (Map<String, Object> example : FullstackExampleRequests.examples()) {
            // One generation per example; the database alternates (it names the types, not the tables).
            for (String database : List.of(compared % 2 == 0 ? "h2" : "postgresql")) {
                Map<String, Object> body = FullstackExampleRequests.body(example, "react-tailwind-crud", "en",
                        FullstackExampleRequests.hasPages(example));
                body.put("dependencies", defaultsPlus("flyway", database));
                String artifact = (String) body.get("artifactId");

                ResponseEntity<byte[]> zip = FullstackExampleRequests.post(rest, body);
                assertThat(zip.getStatusCode()).as(example.get("exampleId") + " " + database).isEqualTo(HttpStatus.OK);
                byte[] baseline = FullstackExampleRequests.unzip(zip.getBody())
                        .get(artifact + "/backend/src/main/resources/db/migration/V1__init.sql");
                assertThat(baseline).as(example.get("exampleId") + " has a baseline").isNotNull();

                JsonNode d = ok(ddl(body, null));
                assertThat(d.path("sql").asText())
                        .as(example.get("exampleId") + " on " + database)
                        .isEqualTo(new String(baseline, StandardCharsets.UTF_8));
                assertThat(d.path("flyway").asBoolean()).isTrue();
                assertThat(d.path("dialect").asText()).isEqualTo(database.equals("h2") ? "H2" : "POSTGRESQL");
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(4);
    }

    @Test
    void anyDialectCanBeAskedForAndFlywayIsNotNeeded() throws Exception {
        Map<String, Object> body = FullstackExampleRequests.manyToManyBody("react-tailwind-crud");
        body.put("dependencies", List.of("web", "data-jpa", "postgresql", "validation"));

        JsonNode project = ok(ddl(body, null));
        assertThat(project.path("dialect").asText()).isEqualTo("POSTGRESQL");
        assertThat(project.path("projectDialect").asText()).isEqualTo("POSTGRESQL");
        assertThat(project.path("flyway").asBoolean()).isFalse();
        assertThat(project.path("sql").asText()).contains("for PostgreSQL.").contains("create table posts (");
        assertThat(project.path("tables")).extracting(JsonNode::asText).contains("authors", "posts", "tags", "post_tags");

        JsonNode oracle = ok(ddl(body, "ORACLE"));
        assertThat(oracle.path("dialect").asText()).isEqualTo("ORACLE");
        assertThat(oracle.path("label").asText()).isEqualTo("Oracle");
        assertThat(oracle.path("projectDialect").asText()).isEqualTo("POSTGRESQL");
        assertThat(oracle.path("sql").asText()).contains("id number(19,0) generated by default as identity not null");

        // A dependency id names the database too.
        assertThat(ok(ddl(body, "mssql")).path("sql").asText()).contains("for SQL Server.").contains("id bigint identity not null");
    }

    @Test
    void anUnknownDialectOrABadModelIsA400() throws Exception {
        Map<String, Object> body = FullstackExampleRequests.manyToManyBody("react-tailwind-crud");
        ResponseEntity<String> dialect = ddl(body, "mysql");
        assertThat(dialect.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json.readTree(dialect.getBody()).path("path").asText()).isEqualTo("dialect");

        // The generator's own checks: a field the model names twice, where it is.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) body.get("entities");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = new ArrayList<>((List<Map<String, Object>>) entities.get(0).get("fields"));
        fields.add(fields.get(fields.size() - 1));
        entities.get(0).put("fields", fields);
        ResponseEntity<String> model = ddl(body, null);
        assertThat(model.getStatusCode()).as(model.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json.readTree(model.getBody()).path("path").asText()).startsWith("entities[0]");
    }
}
