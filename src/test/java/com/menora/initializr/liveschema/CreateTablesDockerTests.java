package com.menora.initializr.liveschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.FullstackExampleRequests;
import com.menora.initializr.TestInvokerConfiguration;
import com.menora.initializr.db.entity.SchemaSourceEntity;
import com.menora.initializr.db.repository.SchemaSourceRepository;
import com.menora.initializr.sql.SqlDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /starter-fullstack.create-tables} against real PostgreSQL, SQL Server and Oracle
 * servers in Docker — the first time the fullstack baseline's statements run on those databases.
 * Every seeded example, and the field-types, many-to-many and one-to-many models, are created in a
 * schema of their own through an admin source; a second run is refused; a run that fails half way
 * (a constraint name already taken) leaves nothing behind.
 *
 * <p>Opt in with {@code -Dsmoke.docker=true}; one database per run with {@code -Dsmoke.db=ORACLE}
 * (the three at once need a lot of memory). Needs Docker running and the images reachable.
 */
@EnabledIfSystemProperty(named = "smoke.docker", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "menora.live-schema.allow-h2=true",
        "menora.live-schema.secrets.test-db=from-the-environment"})
@Import(TestInvokerConfiguration.class)
class CreateTablesDockerTests {

    private static final String SECRET = "menora.live-schema.secrets.docker";

    enum Db {
        POSTGRESQL("postgres:16-alpine", 5432, "postgres", "Creat0r-pw!", "postgres",
                List.of("-e", "POSTGRES_PASSWORD=Creat0r-pw!")),
        MSSQL("mcr.microsoft.com/mssql/server:2022-latest", 1433, "sa", "Creat0r-pw!", "master",
                List.of("-e", "ACCEPT_EULA=Y", "-e", "MSSQL_SA_PASSWORD=Creat0r-pw!")),
        // SYSTEM, which may create tables in the users made below (one per model).
        ORACLE("gvenzl/oracle-free:23-slim-faststart", 1521, "SYSTEM", "Creat0r_pw", "FREEPDB1",
                List.of("-e", "ORACLE_PASSWORD=Creat0r_pw"));

        final String image;
        final int port;
        final String user;
        final String password;
        final String database;
        final List<String> env;

        Db(String image, int port, String user, String password, String database, List<String> env) {
            this.image = image;
            this.port = port;
            this.user = user;
            this.password = password;
            this.database = database;
            this.env = env;
        }

        /** The statements that make an empty schema to create a model's tables in. */
        List<String> createSchema(String name) {
            return switch (this) {
                case POSTGRESQL, MSSQL -> List.of("CREATE SCHEMA " + name);
                case ORACLE -> List.of("CREATE USER " + name + " IDENTIFIED BY \"Schema_pw1\" DEFAULT TABLESPACE USERS"
                        + " QUOTA UNLIMITED ON USERS");
            };
        }

        String intType() {
            return this == ORACLE ? "NUMBER(10)" : "INTEGER";
        }
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;
    @Autowired private SchemaSourceRepository sources;

    @AfterEach
    void removeSources() {
        sources.deleteAll();
        System.clearProperty(SECRET);
    }

    /** Every model this run creates, by the schema it goes to. */
    private static Map<String, Map<String, Object>> models() throws Exception {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        out.put("m_types", FullstackExampleRequests.fieldTypesBody("react-tailwind-crud"));
        out.put("m_press", FullstackExampleRequests.manyToManyBody("react-tailwind-crud"));
        out.put("m_shop", FullstackExampleRequests.oneToManyBody("react-tailwind-crud"));
        int i = 0;
        for (Map<String, Object> example : FullstackExampleRequests.examples()) {
            // No dependencies named: the set's defaults (ldap-auth-rest among them), as the editor starts.
            out.put("m_ex" + (i++), FullstackExampleRequests.body(example, "react-tailwind-crud", "en",
                    FullstackExampleRequests.hasPages(example)));
        }
        return out;
    }

    private ResponseEntity<String> create(Object model, String schema, boolean check) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request", model);
        body.put("source", "docker");
        body.put("schema", schema);
        body.put("check", check);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/starter-fullstack.create-tables", HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    @ParameterizedTest
    @EnumSource(Db.class)
    void createsEveryModelsTablesOnARealServer(Db db) throws Exception {
        String only = System.getProperty("smoke.db");
        Assumptions.assumeTrue(only == null || only.isBlank() || only.equalsIgnoreCase(db.name()),
                "smoke.db=" + only + " leaves " + db + " out");
        int hostPort;
        try (ServerSocket s = new ServerSocket(0)) {
            hostPort = s.getLocalPort();
        }
        List<String> run = new ArrayList<>(List.of("docker", "run", "-d", "--rm", "-p", hostPort + ":" + db.port));
        run.addAll(db.env);
        run.add(db.image);
        String container = exec(run, Duration.ofMinutes(10)).trim();
        try {
            SqlDialect dialect = SqlDialect.valueOf(db.name());
            ConnectionSpec spec = new ConnectionSpec(dialect, "127.0.0.1", hostPort, db.database, null, db.user,
                    db.password, true, "docker").validated();
            LiveSchemaReader reader = new LiveSchemaReader(DialectSupport.of(dialect));
            Map<String, Map<String, Object>> models = models();
            try (Connection c = waitFor(reader, spec); Statement st = c.createStatement()) {
                for (String schema : models.keySet()) for (String sql : db.createSchema(schema)) st.execute(sql);
                for (String sql : db.createSchema("m_fails")) st.execute(sql);
                // pk_posts is taken in m_fails: the third CREATE TABLE of the press model fails there.
                st.execute("CREATE TABLE m_fails.other (id " + db.intType() + " CONSTRAINT pk_posts PRIMARY KEY)");
            }

            SchemaSourceEntity source = new SchemaSourceEntity();
            source.setName("docker");
            source.setDialect(db.name());
            source.setHost("127.0.0.1");
            source.setPort(hostPort);
            source.setDatabaseName(db.database);
            source.setUsername(db.user);
            source.setPasswordRef(SECRET);
            source.setTrustServerCertificate(true);
            source.setAllowCreateTables(true);
            sources.save(source);
            System.setProperty(SECRET, db.password);

            List<String> failures = new ArrayList<>();
            int created = 0;
            for (Map.Entry<String, Map<String, Object>> model : models.entrySet()) {
                ResponseEntity<String> r = create(model.getValue(), model.getKey(), false);
                if (r.getStatusCode() != HttpStatus.OK) {
                    failures.add(model.getKey() + " (" + model.getValue().get("artifactId") + "): " + r.getBody());
                    continue;
                }
                JsonNode body = json.readTree(r.getBody());
                assertThat(body.path("created").size()).as(model.getKey()).isEqualTo(body.path("tables").size()).isPositive();
                try (Connection c = reader.connect(spec)) {
                    String schema = schemaAsListed(reader, c, model.getKey());
                    Set<String> there = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    reader.tables(c, schema).tables().forEach(t -> there.add(t.name()));
                    for (JsonNode t : body.path("tables")) {
                        String name = t.asText().substring(t.asText().indexOf('.') + 1).replace("\"", "");
                        assertThat(there).as(model.getKey() + " has " + name).contains(name);
                    }
                }
                created += body.path("created").size();
            }
            assertThat(failures).as("models whose tables " + db + " refused").isEmpty();
            assertThat(created).isGreaterThan(20);

            // A second run finds them and runs nothing.
            ResponseEntity<String> again = create(models.get("m_press"), "m_press", true);
            assertThat(again.getStatusCode()).as(again.getBody()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(json.readTree(again.getBody()).path("existing")).hasSize(4);

            // Half way and failed: rolled back (PostgreSQL, SQL Server) or dropped again (Oracle).
            ResponseEntity<String> fails = create(models.get("m_press"), "m_fails", false);
            assertThat(fails.getStatusCode()).as(fails.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(fails.getBody()).contains(db == Db.ORACLE ? "dropped again" : "rolled back");
            try (Connection c = reader.connect(spec)) {
                List<String> left = reader.tables(c, schemaAsListed(reader, c, "m_fails")).tables().stream()
                        .map(t -> t.name().toLowerCase(Locale.ROOT)).toList();
                assertThat(left).containsExactly("other");
            }
        } finally {
            exec(List.of("docker", "rm", "-f", container), Duration.ofMinutes(2));
        }
    }

    private static String schemaAsListed(LiveSchemaReader reader, Connection c, String name) throws Exception {
        return reader.schemas(c).schemas().stream().filter(s -> s.equalsIgnoreCase(name)).findFirst().orElseThrow();
    }

    private static Connection waitFor(LiveSchemaReader reader, ConnectionSpec spec) throws Exception {
        Instant until = Instant.now().plus(Duration.ofMinutes(4));
        while (true) {
            try {
                Connection c = reader.connect(spec, false);
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                        spec.dialect() == SqlDialect.ORACLE ? "SELECT 1 FROM DUAL" : "SELECT 1")) {
                    rs.next();
                }
                return c;
            } catch (LiveSchemaException | java.sql.SQLException e) {
                if (Instant.now().isAfter(until)) throw e;
                Thread.sleep(3000);
            }
        }
    }

    private static String exec(List<String> command, Duration timeout) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("Timed out: " + command);
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0) throw new AssertionError(command + " failed: " + out);
        return out;
    }
}
