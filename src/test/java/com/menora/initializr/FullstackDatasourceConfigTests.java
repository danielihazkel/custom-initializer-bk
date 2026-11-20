package com.menora.initializr;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /starter-fullstack.zip}: the datasource config class each database dependency brings. */
@IntegrationTest
@Tag("slow") // left out of mvn test -Pquick
class FullstackDatasourceConfigTests extends FullstackEndpointTestSupport {

    @Test
    void fullstackEndpoint_dbDependencyEmitsConfigClassWithoutDatasourceOpt() throws Exception {
        // Regression: the fullstack request never carries a db datasource role sub-option
        // (db2-primary/db2-secondary). The controller must default to the primary datasource so
        // the gated Db2Config class is generated — otherwise the backend has no DataSource bean
        // (the YAML uses the custom `db2.datasource` prefix, not `spring.datasource`) and JPA
        // autoconfig fails to start.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "db2app");
        body.put("packageName", "com.menora.db2app");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "db2"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        // The config class is present and binds the custom-prefix datasource as @Primary.
        assertThat(entries.keySet()).anyMatch(p ->
                p.equals("db2app/backend/src/main/java/com/menora/db2app/config/Db2Config.java"));
        assertThat(contentEndingWith(entries, "/config/Db2Config.java"))
                .contains("@Primary")
                .contains("@ConfigurationProperties(prefix = \"db2.datasource\")")
                .contains("public DataSource db2DataSource()")
                // The config must scan where the scaffolded entities/repos actually land
                // (com.menora.db2app.entity / .repository), not the legacy .db2 subpackage.
                .contains("basePackages = \"com.menora.db2app.repository\"")
                .contains("em.setPackagesToScan(\"com.menora.db2app.entity\")")
                .doesNotContain(".db2.repository")
                .doesNotContain("\"com.menora.db2app.db2\"");
        // The YAML datasource block is still written too.
        assertThat(entries.get("db2app/backend/src/main/resources/application.yaml"))
                .contains("db2:")
                .contains("driver-class-name: com.ibm.as400.access.AS400JDBCDriver");
    }

    @Test
    void fullstackEndpoint_oracleKeepsBooleansNumericOnEveryServerVersion() throws Exception {
        // Found by running a generated project on Oracle 23: Hibernate asked for the server's own
        // BOOLEAN there and, validating, refused the number(1,0) columns of the Flyway baseline.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "oraapp");
        body.put("packageName", "com.menora.oraapp");
        body.put("dependencies", List.of("data-jpa", "web", "oracle", "flyway"));
        body.put("entities", List.of(Map.of("name", "Customer", "fields", List.of(pkField(),
                Map.of("name", "active", "type", "BOOLEAN")))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/config/OracleConfig.java"))
                .contains("properties.put(\"" + com.menora.initializr.fullstack.schema.SchemaDialect.ORACLE_BOOLEAN_SETTING
                        + "\", \"" + com.menora.initializr.fullstack.schema.SchemaDialect.ORACLE_BOOLEAN_JDBC_TYPE + "\");");
        // The column the setting is there for, as the baseline writes it.
        assertThat(contentEndingWith(entries, "/db/migration/V1__init.sql")).contains("active number(1,0)");
    }

    @Test
    void fullstackEndpoint_explicitSecondaryDatasourceOptIsNotOverridden() throws Exception {
        // When the caller explicitly picks the secondary datasource role, the default-to-primary
        // must not fire — the secondary variant (no @Primary) is rendered.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "db2app");
        body.put("packageName", "com.menora.db2app");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "db2"));
        body.put("opts", Map.of("db2", List.of("db2-secondary")));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/config/Db2Config.java"))
                .contains("@ConfigurationProperties(prefix = \"db2.datasource\")")
                .doesNotContain("@Primary")
                // A secondary datasource is not the owner of the scaffolded entities — it keeps
                // its legacy .db2 subpackage convention (primary owns generated entities).
                .contains("basePackages = \"com.menora.db2app.db2.repository\"")
                .contains("em.setPackagesToScan(\"com.menora.db2app.db2\")");
    }

    @Test
    void fullstackEndpoint_dbConfigScansCustomDomainPackage() throws Exception {
        // When the entities live under a custom domainPackage, the datasource config must scan
        // that package's .entity/.repository — not packageName's.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "db2app");
        body.put("packageName", "com.menora.db2app");
        body.put("domainPackage", "com.menora.db2app.catalog");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "db2"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/config/Db2Config.java"))
                .contains("basePackages = \"com.menora.db2app.catalog.repository\"")
                .contains("em.setPackagesToScan(\"com.menora.db2app.catalog.entity\")");
        // And the entity actually lands there.
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/catalog/entity/User.java"));
    }

    @Test
    void fullstackEndpoint_mongoConfigScansScaffoldedRepositoryPackage() throws Exception {
        // MongoDB has no setPackagesToScan, but @EnableMongoRepositories must point at the
        // scaffolded .repository package rather than the legacy .mongodb.repository.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "mongoapp");
        body.put("packageName", "com.menora.mongoapp");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "mongodb"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/config/MongoConfig.java"))
                .contains("basePackages = \"com.menora.mongoapp.repository\"")
                // not the legacy driver subpackage (the Spring import naturally contains
                // ".mongodb.repository", so assert on the full legacy basePackages literal).
                .doesNotContain("\"com.menora.mongoapp.mongodb.repository\"");
    }

    @Test
    void fullstackEndpoint_h2DefaultOmitsConfigClass() throws Exception {
        // h2 is excluded from the default-to-primary: its ungated YAML ships a real
        // spring.datasource block, so it is runnable with no config class. Defaulting it to
        // primary would wrongly emit H2Config + the gated h2.datasource mirror.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "h2app");
        body.put("packageName", "com.menora.h2app");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "h2"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        assertThat(entries.keySet()).noneMatch(p -> p.endsWith("/config/H2Config.java"));
        String yaml = entries.get("h2app/backend/src/main/resources/application.yaml");
        assertThat(yaml)
                .contains("driver-class-name: org.h2.Driver")
                .doesNotContain("hbm2ddl-auto");   // the gated custom h2.datasource mirror
    }
}
