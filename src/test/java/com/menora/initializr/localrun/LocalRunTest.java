package com.menora.initializr.localrun;

import io.spring.initializr.generator.version.Version;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** What {@link LocalRun} writes for a set of dependencies, read as YAML where it is YAML. */
class LocalRunTest {

    private static final ContainerImages MENORA = new ContainerImages("repo.menora.co.il/dockerhub", "mcr.microsoft.com/");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String text) {
        Map<String, Object> map = new Yaml().load(text);
        return map == null ? Map.of() : map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services(String compose) {
        return (Map<String, Object>) yaml(compose).get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String compose, String name) {
        return (Map<String, Object>) services(compose).get(name);
    }

    @Test
    void servicesOnlyForThePlainProjectThroughTheProxy() {
        String compose = LocalRun.compose("Demo.App", Set.of("web", "postgresql", "kafka", "redis"), MENORA, false);
        assertThat(compose).doesNotContain("{{").contains("mvn spring-boot:run -Dspring-boot.run.profiles=compose")
                .contains("(src/main/resources/application-compose.yaml)");
        assertThat(yaml(compose).get("name")).isEqualTo("demo-app");
        assertThat(services(compose)).containsOnlyKeys("postgres", "kafka", "redis");
        assertThat(service(compose, "postgres").get("image")).isEqualTo("repo.menora.co.il/dockerhub/postgres:16-alpine");
        assertThat(service(compose, "kafka").get("image")).isEqualTo("repo.menora.co.il/dockerhub/apache/kafka:3.8.0");
        assertThat((List<String>) service(compose, "postgres").get("ports")).containsExactly("5432:5432");
    }

    @Test
    void everyServiceIsValidYamlWithAHealthCheck() {
        String compose = LocalRun.compose("demo", Set.copyOf(LocalRun.SERVICES), ContainerImages.direct(), false);
        assertThat(services(compose)).containsOnlyKeys("postgres", "oracle", "mssql", "mongo", "redis", "kafka");
        for (String name : services(compose).keySet()) {
            assertThat(service(compose, name)).as(name).containsKeys("image", "ports", "healthcheck");
        }
        assertThat(service(compose, "mssql").get("image")).isEqualTo("mcr.microsoft.com/mssql/server:2022-latest");
        assertThat(service(compose, "oracle").get("image")).isEqualTo("gvenzl/oracle-free:23-slim-faststart");
        // The SQL Server check makes the database the application's URL names.
        assertThat(compose).contains("IF DB_ID('appdb') IS NULL CREATE DATABASE appdb").contains("$$MSSQL_SA_PASSWORD");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFullstackComposeBuildsBothHalvesAndWiresThemToTheServices() {
        String compose = LocalRun.compose("shop", Set.of("web", "postgresql", "kafka", "ldap-auth-rest", "flyway"),
                MENORA, true);
        assertThat(compose).doesNotContain("{{").contains("docker compose up --build")
                .contains("(backend/src/main/resources/application-compose.yaml)");
        Map<String, Object> backend = service(compose, "backend");
        assertThat(backend.get("build")).isEqualTo("./backend");
        assertThat((List<String>) backend.get("command")).contains("--spring.profiles.active=compose,local");
        assertThat((Map<String, Object>) backend.get("environment"))
                .containsEntry("PG_DB_URL", "jdbc:postgresql://postgres:5432/appdb")
                .containsEntry("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092");
        assertThat((Map<String, Object>) backend.get("depends_on")).containsOnlyKeys("postgres", "kafka");
        assertThat((List<String>) backend.get("ports")).containsExactly("8080:3000");
        Map<String, Object> frontend = service(compose, "frontend");
        assertThat((Map<String, Object>) frontend.get("environment"))
                .containsEntry("API_UPSTREAM", "http://backend:3000")
                .containsEntry("DEV_USERINFO_ENABLED", "on").containsEntry("DEV_USERINFO_DEFAULT", "dev-user");
        assertThat((List<String>) frontend.get("ports")).containsExactly("3000:3000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFullstackProjectOnH2HasOnlyItsTwoHalves() {
        String compose = LocalRun.compose("shop", Set.of("web", "h2", "data-jpa"), MENORA, true);
        assertThat(services(compose)).containsOnlyKeys("backend", "frontend");
        assertThat(service(compose, "backend")).doesNotContainKeys("environment", "depends_on");
        assertThat((List<String>) service(compose, "backend").get("command")).contains("--spring.profiles.active=compose");
        assertThat((Map<String, Object>) service(compose, "frontend").get("environment")).doesNotContainKey("DEV_USERINFO_ENABLED");
    }

    @Test
    void ibmIHasNoImageAndSaysSo() {
        assertThat(LocalRun.hasServices(Set.of("web", "db2"))).isFalse();
        assertThat(LocalRun.hasServices(Set.of("web", "h2"))).isFalse();
        String compose = LocalRun.compose("x", Set.of("db2", "postgresql"), MENORA, false);
        assertThat(compose).contains("# IBM i (the db2 dependency, jt400) has no container image");
        assertThat(services(compose)).containsOnlyKeys("postgres");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theProfileGivesWhatTheDefaultsDoNot() {
        Map<String, Object> profile = yaml(LocalRun.profile(Set.of("oracle", "mssql", "kafka", "flyway"), false));
        Map<String, Object> oracle = (Map<String, Object>) ((Map<String, Object>) profile.get("oracle")).get("datasource");
        assertThat(oracle).containsEntry("jdbc-url", "${ORACLE_DB_URL:jdbc:oracle:thin:@//localhost:1521/FREEPDB1}")
                .containsEntry("username", "${ORACLE_DB_USERNAME:appuser}");
        Map<String, Object> mssql = (Map<String, Object>) ((Map<String, Object>) profile.get("mssql")).get("datasource");
        assertThat((String) mssql.get("jdbc-url")).contains("trustServerCertificate=true");
        assertThat(mssql).containsEntry("password", "${MSSQL_DB_PASSWORD:LocalDev-Passw0rd}");
        assertThat(profile.get("spring").toString()).contains("protocol=PLAINTEXT");
        // Flyway makes the tables: Hibernate is not asked to.
        assertThat(profile.toString()).doesNotContain("hbm2ddl");

        Map<String, Object> noMigration = yaml(LocalRun.profile(Set.of("postgresql"), true));
        assertThat(noMigration.toString()).contains("hbm2ddl-auto=update");
        assertThat(LocalRun.profile(Set.of("postgresql"), true)).contains("../compose.yaml");

        String nothing = LocalRun.profile(Set.of("postgresql", "flyway", "redis"), false);
        assertThat(nothing).contains("nothing to change");
        assertThat(yaml(nothing)).isEmpty();
        assertThat(LocalRun.profiles(Set.of("ldap-auth"))).isEqualTo("compose,local");
    }

    @Test
    void theSampleTestStartsTheApplicationOnEachDatabase() {
        String test = LocalRun.containerTests("com.menora.demo", Set.of("postgresql", "mssql", "flyway"), MENORA).orElseThrow();
        assertThat(test).doesNotContain("{{")
                .contains("package com.menora.demo;")
                .contains("@Testcontainers(disabledWithoutDocker = true)")
                .contains("new PostgreSQLContainer<>(\"postgres:16-alpine\")")
                .contains("DockerImageName.parse(\"mcr.microsoft.com/mssql/server:2022-latest\")")
                .contains("registry.add(\"mssql.datasource.jdbc-url\", MSSQL::getJdbcUrl);")
                .contains("Starts the application on PostgreSQL and SQL Server in Docker")
                .contains("and its Flyway migrations")
                .doesNotContain("OracleContainer");
        assertThat(LocalRun.containerTests("x", Set.of("h2", "db2"), MENORA)).isEmpty();
    }

    @Test
    void testcontainersFollowsTheProxy() {
        assertThat(LocalRun.testcontainersProperties(MENORA)).hasValue(
                "# Docker Hub images (the databases, Testcontainers' own) come through this registry.\n"
                        + "hub.image.name.prefix=repo.menora.co.il/dockerhub/\n");
        assertThat(LocalRun.testcontainersProperties(ContainerImages.direct())).isEmpty();
    }

    @Test
    void theDbTestsDatabaseIsTheMigrations() {
        assertThat(LocalRun.dbTestsDatabase(Set.of("mssql", "postgresql"))).hasValue("postgresql");
        assertThat(LocalRun.dbTestsDatabase(Set.of("mssql", "oracle"))).hasValue("oracle");
        assertThat(LocalRun.dbTestsDatabase(Set.of("db2"))).isEmpty();
        assertThat(LocalRun.dbTestsDatabase(Set.of("h2"))).isEmpty();
        assertThat(LocalRun.tcUrl("postgresql")).isEqualTo("jdbc:tc:postgresql:16-alpine:///appdb");
        assertThat(LocalRun.tcUrl("oracle")).isEqualTo("jdbc:tc:oracle:23-slim-faststart:///appdb");
        assertThat(LocalRun.tcUrl("mssql")).isEqualTo("jdbc:tc:sqlserver:2022-latest:///appdb");
        assertThat(LocalRun.licenceAcceptance("mssql")).hasValue("mcr.microsoft.com/mssql/server:2022-latest\n");
        assertThat(LocalRun.licenceAcceptance("postgresql")).isEmpty();
    }

    @Test
    void flywayGetsTheModuleItsDatabaseNeeds() {
        Version boot32 = Version.parse("3.2.1");
        Version boot35 = Version.parse("3.5.16");
        assertThat(LocalRun.flywayModules(Set.of("flyway", "postgresql", "oracle"), boot32)).isEmpty();
        assertThat(LocalRun.flywayModules(Set.of("flyway", "mssql"), boot32)).containsExactly("flyway-sqlserver");
        assertThat(LocalRun.flywayModules(Set.of("flyway", "postgresql", "oracle", "mssql"), boot35))
                .containsExactly("flyway-database-postgresql", "flyway-database-oracle", "flyway-sqlserver");
        assertThat(LocalRun.flywayModules(Set.of("postgresql"), boot35)).isEmpty();
    }

    @Test
    void aComposeNameIsAlwaysOne() {
        assertThat(LocalRun.composeName("My_App-2")).isEqualTo("my_app-2");
        assertThat(LocalRun.composeName("--x")).isEqualTo("x");
        assertThat(LocalRun.composeName("")).isEqualTo("app");
    }
}
