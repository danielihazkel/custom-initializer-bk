package com.menora.initializr.liveschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.FullstackExampleRequests;
import com.menora.initializr.TestInvokerConfiguration;
import com.menora.initializr.db.entity.SchemaSourceEntity;
import com.menora.initializr.db.repository.SchemaSourceRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /starter-fullstack.create-tables}: a fullstack model's tables created on a schema
 * source an admin allowed it for — here an in-memory H2 database, which only the tests may use
 * ({@code menora.live-schema.allow-h2}). The same context as {@link LiveSchemaIntegrationTests}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "menora.live-schema.allow-h2=true",
        "menora.live-schema.secrets.test-db=from-the-environment"})
@Import(TestInvokerConfiguration.class)
class FullstackCreateTablesTests {

    private static final String DATABASE = "createtables";
    private static Connection keepAlive;

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;
    @Autowired private SchemaSourceRepository sources;

    @BeforeAll
    static void createDatabase() throws Exception {
        keepAlive = DriverManager.getConnection("jdbc:h2:mem:" + DATABASE + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement s = keepAlive.createStatement()) {
            for (String schema : List.of("CHECKED", "MADE", "TAKEN", "FAILS")) s.execute("CREATE SCHEMA " + schema);
            // In the way of the model's own table of the same name.
            s.execute("CREATE TABLE TAKEN.TAGS (ID BIGINT PRIMARY KEY)");
            // An index name the model's statements use too: the run fails after making its tables.
            s.execute("CREATE TABLE FAILS.OTHER (AUTHOR_ID BIGINT)");
            s.execute("CREATE INDEX FAILS.IX_POSTS_AUTHOR_ID ON FAILS.OTHER (AUTHOR_ID)");
        }
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        keepAlive.close();
    }

    @AfterEach
    void removeSources() {
        sources.deleteAll();
    }

    private void source(String name, boolean allowCreateTables) {
        SchemaSourceEntity s = new SchemaSourceEntity();
        s.setName(name);
        s.setDialect("H2");
        s.setHost("localhost");
        s.setDatabaseName(DATABASE);
        s.setUsername("sa");
        s.setAllowCreateTables(allowCreateTables);
        sources.save(s);
    }

    private static Map<String, Object> model() {
        Map<String, Object> body = FullstackExampleRequests.manyToManyBody("react-tailwind-crud");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        return body;
    }

    private ResponseEntity<String> create(Object model, String source, String schema, boolean check) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request", model);
        body.put("source", source);
        body.put("schema", schema);
        body.put("check", check);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("userinfo", "tester");
        return rest.exchange("/starter-fullstack.create-tables", HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private JsonNode body(ResponseEntity<String> r, HttpStatus status) throws Exception {
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(status);
        return json.readTree(r.getBody());
    }

    private static Set<String> tablesIn(String schema) throws Exception {
        Set<String> names = new TreeSet<>();
        try (ResultSet rs = keepAlive.getMetaData().getTables(null, schema, "%", new String[] {"TABLE"})) {
            while (rs.next()) names.add(rs.getString("TABLE_NAME"));
        }
        return names;
    }

    @Test
    void theOptionsSayWhichSourcesMayCreateTables() throws Exception {
        source("writable", true);
        source("readable", false);
        JsonNode options = json.readTree(rest.getForObject("/metadata/live-schema/options", String.class));
        Map<String, Boolean> flags = new LinkedHashMap<>();
        options.path("sources").forEach(s -> flags.put(s.path("name").asText(), s.path("createTables").asBoolean()));
        assertThat(flags).containsEntry("writable", true).containsEntry("readable", false);
    }

    @Test
    void aCheckSaysWhatWouldRunAndCreatesNothing() throws Exception {
        source("db", true);
        JsonNode r = body(create(model(), "db", "checked", true), HttpStatus.OK);

        assertThat(r.path("checked").asBoolean()).isTrue();
        assertThat(r.path("dialect").asText()).isEqualTo("H2");
        assertThat(r.path("projectDialect").asText()).isEqualTo("H2");
        // The schema as the source lists it, and every table put in it.
        assertThat(r.path("schema").asText()).isEqualTo("CHECKED");
        assertThat(r.path("tables")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("\"CHECKED\".authors", "\"CHECKED\".tags", "\"CHECKED\".posts", "\"CHECKED\".post_tags");
        assertThat(r.path("sql").asText())
                .contains("create table \"CHECKED\".posts (")
                .contains("references \"CHECKED\".authors (id)")
                // The schema is there already; the statements do not end in a semicolon of their own.
                .doesNotContain("create schema")
                .doesNotContain(";;");
        assertThat(r.path("created")).isEmpty();
        assertThat(tablesIn("CHECKED")).isEmpty();
    }

    @Test
    void theTablesAreCreatedAndASecondRunIsRefused() throws Exception {
        source("db", true);
        JsonNode r = body(create(model(), "db", "MADE", false), HttpStatus.OK);
        assertThat(r.path("checked").asBoolean()).isFalse();
        assertThat(r.path("created")).hasSize(4);
        assertThat(tablesIn("MADE")).containsExactlyInAnyOrder("AUTHORS", "TAGS", "POSTS", "POST_TAGS");
        // The foreign keys are there, pointing into the same schema.
        try (ResultSet fks = keepAlive.getMetaData().getImportedKeys(null, "MADE", "POSTS")) {
            assertThat(fks.next()).isTrue();
            assertThat(fks.getString("PKTABLE_SCHEM")).isEqualTo("MADE");
            assertThat(fks.getString("PKTABLE_NAME")).isEqualTo("AUTHORS");
        }

        JsonNode again = body(create(model(), "db", "MADE", false), HttpStatus.CONFLICT);
        assertThat(again.path("error").asText()).isEqualTo("Tables exist");
        assertThat(again.path("existing")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("MADE.authors", "MADE.tags", "MADE.posts", "MADE.post_tags");
    }

    @Test
    void oneTableInTheWayStopsTheWholeRun() throws Exception {
        source("db", true);
        JsonNode r = body(create(model(), "db", "TAKEN", false), HttpStatus.CONFLICT);
        assertThat(r.path("existing")).extracting(JsonNode::asText).containsExactly("TAKEN.tags");
        assertThat(r.path("detail").asText()).contains("Nothing was created");
        assertThat(tablesIn("TAKEN")).containsExactly("TAGS");
        // A check reports the same.
        assertThat(create(model(), "db", "TAKEN", true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aRunThatFailsDropsTheTablesItMade() throws Exception {
        source("db", true);
        JsonNode r = body(create(model(), "db", "FAILS", false), HttpStatus.BAD_REQUEST);
        assertThat(r.path("error").asText()).isEqualTo("Could not create the tables");
        assertThat(r.path("detail").asText()).contains("failed").contains("dropped again");
        assertThat(tablesIn("FAILS")).containsExactly("OTHER");
    }

    @Test
    void onlyASourceThatAllowsItMayBeWrittenTo() throws Exception {
        source("readable", false);
        JsonNode refused = body(create(model(), "readable", "CHECKED", true), HttpStatus.FORBIDDEN);
        assertThat(refused.path("detail").asText()).contains("does not allow creating tables");

        assertThat(create(model(), "nowhere", "CHECKED", true).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // No source named: a database typed in by hand is never written to.
        assertThat(create(model(), null, "CHECKED", true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        source("db", true);
        assertThat(body(create(model(), "db", "NO_SUCH_SCHEMA", true), HttpStatus.BAD_REQUEST)
                .path("detail").asText()).contains("has no schema");
    }

    @Test
    void aModelTheGeneratorRefusesIsItsA400UnderRequest() throws Exception {
        source("db", true);
        Map<String, Object> model = model();
        model.put("entities", List.of());
        JsonNode r = body(create(model, "db", "CHECKED", true), HttpStatus.BAD_REQUEST);
        assertThat(r.path("path").asText()).startsWith("request");
    }
}
