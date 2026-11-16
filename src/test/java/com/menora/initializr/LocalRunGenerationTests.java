package com.menora.initializr;

import com.menora.initializr.localrun.LocalRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Running a generated project locally ({@link LocalRun}), through the endpoints: a plain project's
 * {@code compose.yaml} and {@code compose} profile, a fullstack project's compose file with both
 * halves and its backend's {@code db-tests} Maven profile, a multi-module project's, the
 * {@code testcontainers} dependency, and Flyway's database modules. What the files say is
 * {@code LocalRunTest}'s; this is where they land and when.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class LocalRunGenerationTests {

    @Autowired
    private TestRestTemplate rest;

    private Map<String, String> get(String url) throws Exception {
        ResponseEntity<byte[]> response = rest.getForEntity(url, byte[].class);
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        return text(FullstackExampleRequests.unzip(response.getBody()));
    }

    private Map<String, String> fullstack(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, body);
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        return text(FullstackExampleRequests.unzip(response.getBody()));
    }

    private static Map<String, String> text(Map<String, byte[]> zip) {
        Map<String, String> files = new LinkedHashMap<>();
        zip.forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
        return files;
    }

    /** -Dlocalrun.dump=dir writes what a test generated there, to read or to try. */
    private static void dump(String name, Map<String, String> files) throws Exception {
        String dir = System.getProperty("localrun.dump");
        if (dir == null) return;
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = Path.of(dir, name).resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
    }

    private static final String PLAIN = "/starter.zip?type=maven-project&groupId=com.menora&artifactId=demo"
            + "&packageName=com.menora.demo&bootVersion=3.5.16&javaVersion=21&dependencies=";

    @Test
    void aPlainProjectGetsTheServicesItsDependenciesNeed() throws Exception {
        Map<String, String> files = get(PLAIN + "web,data-jpa,postgresql,kafka,flyway");
        dump("plain", files);
        assertThat(files.get("compose.yaml"))
                .contains("  postgres:\n    image: repo.menora.co.il/dockerhub/postgres:16-alpine")
                .contains("  kafka:\n")
                .doesNotContain("backend:").doesNotContain("{{");
        assertThat(files.get("src/main/resources/application-compose.yaml")).contains("protocol: PLAINTEXT");
        assertThat(files.get("HELP.md")).contains("# Running it locally")
                .contains("mvn spring-boot:run -Dspring-boot.run.profiles=compose");
        // The client can be told the local broker has no SASL; the clusters' SASL_SSL is the default.
        assertThat(files.get("src/main/java/com/menora/demo/config/KafkaConfig.java"))
                .contains("@Value(\"${spring.kafka.security.protocol:SASL_SSL}\")")
                .contains("if (securityProtocol.startsWith(\"SASL\")) {");
        // Flyway 11 (Boot 3.5) reads PostgreSQL through a module of its own.
        assertThat(files.get("pom.xml")).contains("<artifactId>flyway-database-postgresql</artifactId>");
    }

    @Test
    void anH2ProjectNeedsNothingRunning() throws Exception {
        Map<String, String> files = get(PLAIN + "web,data-jpa,h2");
        assertThat(files).doesNotContainKeys("compose.yaml", "src/main/resources/application-compose.yaml");
        assertThat(files.get("HELP.md")).doesNotContain("Running it locally");
    }

    @Test
    void theTestcontainersDependencyGivesASampleTestOnEachDatabase() throws Exception {
        Map<String, String> files = get(PLAIN + "web,data-jpa,postgresql,mssql,testcontainers");
        dump("testcontainers", files);
        String pom = files.get("pom.xml");
        assertThat(pom).contains("<groupId>org.testcontainers</groupId>")
                .contains("<artifactId>junit-jupiter</artifactId>")
                .contains("<artifactId>postgresql</artifactId>")
                .contains("<artifactId>mssqlserver</artifactId>");
        assertThat(files.get("src/test/java/com/menora/demo/ContainerDatabaseTests.java"))
                .contains("new PostgreSQLContainer<>(\"postgres:16-alpine\")")
                .contains("MSSQLServerContainer");
        assertThat(files.get("src/test/resources/testcontainers.properties"))
                .contains("hub.image.name.prefix=repo.menora.co.il/dockerhub/");
        // The metadata offers it.
        assertThat(rest.getForObject("/metadata/client", String.class)).contains("\"id\":\"testcontainers\"");
    }

    @Test
    void aFullstackProjectRunsWholeInDockerAndTestsOnItsDatabase() throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        body.put("opts", Map.of("scaffold", List.of("tests")));
        body.put("dependencies", List.of("web", "data-jpa", "postgresql", "validation", "flyway", "ldap-auth-rest"));
        Map<String, String> files = fullstack(body);
        dump("fullstack", files);
        assertThat(files.get("support/compose.yaml"))
                .contains("  postgres:\n").contains("  backend:\n    build: ./backend")
                .contains("--spring.profiles.active=compose,local")
                .contains("PG_DB_URL: \"jdbc:postgresql://postgres:5432/appdb\"")
                .contains("  frontend:\n    build: ./frontend").contains("DEV_USERINFO_ENABLED: \"on\"");
        assertThat(files).containsKey("support/backend/src/main/resources/application-compose.yaml");
        assertThat(files).doesNotContainKey("support/backend/compose.yaml");
        assertThat(files.get("support/README.md")).contains("## Run it all in Docker")
                .contains("docker compose up -d postgres")
                .contains("mvn test -Pdb-tests");

        String pom = files.get("support/backend/pom.xml");
        assertThat(pom).contains("<id>db-tests</id>")
                .contains("<artifactId>jdbc</artifactId>")
                .contains("<postgresql.datasource.jdbc-url>jdbc:tc:postgresql:16-alpine:///appdb</postgresql.datasource.jdbc-url>")
                .contains("<postgresql.datasource.driver-class-name>org.testcontainers.jdbc.ContainerDatabaseDriver</postgresql.datasource.driver-class-name>")
                .contains("<postgresql.hibernate.hbm2ddl-auto>validate</postgresql.hibernate.hbm2ddl-auto>")
                .contains("<spring.flyway.enabled>true</spring.flyway.enabled>");
        assertThat(files.get("support/backend/src/test/resources/testcontainers.properties"))
                .contains("hub.image.name.prefix=repo.menora.co.il/dockerhub/");
        // The frontend's nginx can sign calls as the developer when compose asks it to.
        assertThat(files.get("support/frontend/nginx/nginx.conf"))
                .contains("map \"${DEV_USERINFO_ENABLED}:$http_userinfo\" $caller_userinfo {")
                .contains("proxy_set_header userinfo $caller_userinfo;");
    }

    @Test
    void aFullstackProjectOnH2HasItsTwoHalvesAndNoDbTests() throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        body.put("opts", Map.of("scaffold", List.of("tests")));
        Map<String, String> files = fullstack(body);
        assertThat(files.get("support/compose.yaml")).contains("  backend:\n").doesNotContain("  postgres:\n");
        assertThat(files.get("support/backend/pom.xml")).doesNotContain("db-tests");
        assertThat(files).doesNotContainKey("support/backend/src/test/resources/testcontainers.properties");
        assertThat(files.get("support/README.md")).contains("## Run it all in Docker").doesNotContain("-Pdb-tests");
    }

    @Test
    void aMultiModuleProjectKeepsOneComposeFileAndItsEntryModuleTheProfile() throws Exception {
        Map<String, String> files = get("/starter-multimodule.zip?modules=api,core&artifactId=myapp&groupId=com.menora"
                + "&packageName=com.menora.myapp&bootVersion=3.2.1&javaVersion=21"
                + "&type=maven-project&language=java&packaging=jar&dependencies=web,postgresql");
        dump("multimodule", files);
        assertThat(files.get("myapp/compose.yaml")).contains("  postgres:\n");
        List<String> profiles = files.keySet().stream().filter(p -> p.endsWith("application-compose.yaml")).toList();
        assertThat(profiles).hasSize(1);
        assertThat(files.get(profiles.get(0))).contains("../compose.yaml");
        assertThat(files.get("myapp/compose.yaml")).contains(profiles.get(0).substring("myapp/".length()));
        assertThat(files.keySet()).noneMatch(p -> p.matches("myapp/[^/]+/compose\\.yaml"));
    }
}
