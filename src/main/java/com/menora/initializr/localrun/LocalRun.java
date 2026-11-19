package com.menora.initializr.localrun;

import com.menora.initializr.config.CompiledTemplates;
import io.spring.initializr.generator.buildsystem.Dependency;
import io.spring.initializr.generator.buildsystem.DependencyScope;
import io.spring.initializr.generator.buildsystem.maven.MavenBuild;
import io.spring.initializr.generator.buildsystem.maven.MavenProfile;
import io.spring.initializr.generator.version.Version;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Running a generated project on a developer's machine: {@code compose.yaml} with the services its
 * dependencies need (and, for a fullstack project, its backend and frontend), the {@code compose}
 * Spring profile that points the application at them, and Testcontainers — a sample test for the
 * {@code testcontainers} dependency and the fullstack {@code db-tests} Maven profile, which runs the
 * generated API tests on the application's real database.
 *
 * <p>The texts are templates under {@code templates/localrun/} on the classpath, written from Java
 * like the wizards' output, not catalog rows: what they hold follows the whole set of dependencies,
 * which no single row sees.
 */
public final class LocalRun {

    /** At a project's root (a fullstack project's and a multi-module one's too). */
    public static final String COMPOSE_FILE = "compose.yaml";
    /** The Spring profile that points the application at the compose services. */
    public static final String PROFILE = "compose";
    /** Where that profile lives, inside the application's (sub)project. */
    public static final String PROFILE_FILE = "src/main/resources/application-compose.yaml";
    /** The fullstack backend's Maven profile that runs the API tests on the real database. */
    public static final String DB_TESTS_PROFILE = "db-tests";
    /** The catalog dependency that gives a project Testcontainers and a sample test. */
    public static final String TESTCONTAINERS = "testcontainers";

    static final String POSTGRES_IMAGE = "postgres:16-alpine";
    static final String ORACLE_IMAGE_NAME = "gvenzl/oracle-free";
    static final String ORACLE_TAG = "23-slim-faststart";
    static final String ORACLE_IMAGE = ORACLE_IMAGE_NAME + ":" + ORACLE_TAG;
    static final String MSSQL_IMAGE = "mssql/server:2022-latest";
    static final String MONGO_IMAGE = "mongo:7";
    static final String REDIS_IMAGE = "redis:7-alpine";
    static final String KAFKA_IMAGE = "apache/kafka:3.8.0";
    static final String SCHEMA_REGISTRY_IMAGE = "confluentinc/cp-schema-registry:7.7.1";

    /**
     * Not a dependency: the events wizard's Avro channels use Confluent's schema registry. Beside
     * {@code kafka} it adds the registry's container, and the compose profile points the
     * application at it (and lets it register its schemas there).
     */
    public static final String SCHEMA_REGISTRY = "schema-registry";

    /**
     * The databases a container can stand in for, in the order a fullstack migration picks its
     * database ({@code SchemaDialect.of}). DB2 here is IBM i through jt400, which has no image.
     */
    public static final List<String> CONTAINER_DATABASES = List.of("postgresql", "oracle", "mssql");
    /** Every dependency compose starts a service for. */
    public static final List<String> SERVICES = List.of("postgresql", "oracle", "mssql", "mongodb", "redis", "kafka");

    private static final Map<String, String> TEXTS = new ConcurrentHashMap<>();

    private LocalRun() {
    }

    /** True when at least one dependency has a compose service. */
    public static boolean hasServices(Collection<String> deps) {
        return SERVICES.stream().anyMatch(deps::contains);
    }

    /** An ldap-auth variant is on the build: its {@code local} profile gives dev-user its roles. */
    public static boolean ldap(Collection<String> deps) {
        return deps.contains("ldap-auth") || deps.contains("ldap-auth-rest");
    }

    /** The profiles a developer turns on to run against compose: {@code compose}, and {@code local} with ldap-auth. */
    public static String profiles(Collection<String> deps) {
        return ldap(deps) ? PROFILE + ",local" : PROFILE;
    }

    /** No migration tool on the build, so nothing would make the tables on an empty database. */
    static boolean createsSchema(Collection<String> deps) {
        return !deps.contains("flyway") && !deps.contains("liquibase");
    }

    // ── compose.yaml ────────────────────────────────────────────────────────────────────────

    /**
     * {@code compose.yaml} for a project with these dependencies. {@code apps} adds a fullstack
     * project's backend and frontend, built from their Dockerfiles; null for services only.
     */
    public static String compose(String projectName, Collection<String> deps, ContainerImages images, boolean apps) {
        return compose(projectName, deps, images, apps, apps ? "backend/" + PROFILE_FILE : PROFILE_FILE);
    }

    /** {@link #compose(String, Collection, ContainerImages, boolean)} naming where the profile file is. */
    public static String compose(String projectName, Collection<String> deps, ContainerImages images, boolean apps,
                                 String profilePath) {
        Map<String, Object> view = serviceView(deps, images);
        view.put("name", composeName(projectName));
        view.put("profiles", profiles(deps));
        view.put("profilePath", profilePath);
        if (deps.contains("db2")) {
            view.put("unsupported", List.of("IBM i (the db2 dependency, jt400) has no container image: DB2_DB_URL still names"
                    + "\n# a real server."));
        }
        if (apps) {
            Map<String, Object> app = new HashMap<>();
            app.put("ldap", ldap(deps));
            app.put("profiles", profiles(deps));
            List<Map<String, String>> env = backendEnv(deps);
            app.put("backendEnv", env);
            app.put("hasBackendEnv", !env.isEmpty());
            List<String> dependsOn = SERVICES.stream().filter(deps::contains).map(LocalRun::serviceName).toList();
            app.put("dependsOn", dependsOn);
            app.put("hasDependsOn", !dependsOn.isEmpty());
            view.put("apps", app);
        }
        return CompiledTemplates.render(text("compose.yaml"), view);
    }

    /** The service's name in compose.yaml — also its host name inside the compose network. */
    public static String serviceName(String dep) {
        return switch (dep) {
            case "postgresql" -> "postgres";
            case "mongodb" -> "mongo";
            default -> dep;
        };
    }

    /** What the backend container is told, so that it reaches the services by their names. */
    private static List<Map<String, String>> backendEnv(Collection<String> deps) {
        Map<String, String> env = new LinkedHashMap<>();
        if (deps.contains("postgresql")) env.put("PG_DB_URL", "jdbc:postgresql://postgres:5432/appdb");
        if (deps.contains("oracle")) env.put("ORACLE_DB_URL", "jdbc:oracle:thin:@//oracle:1521/FREEPDB1");
        if (deps.contains("mssql")) {
            env.put("MSSQL_DB_URL", "jdbc:sqlserver://mssql:1433;databaseName=appdb;encrypt=true;trustServerCertificate=true");
        }
        if (deps.contains("mongodb")) env.put("MONGODB_URI", "mongodb://mongo:27017/appdb");
        if (deps.contains("redis")) env.put("REDIS_HOST", "redis");
        if (deps.contains("kafka")) env.put("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092");
        List<Map<String, String>> list = new ArrayList<>();
        env.forEach((k, v) -> list.add(Map.of("name", k, "value", v)));
        return list;
    }

    /** A compose project name: lower case letters, digits, dashes and underscores. */
    static String composeName(String projectName) {
        String name = projectName == null ? "" : projectName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        name = name.replaceAll("^[^a-z0-9]+", "");
        return name.isEmpty() ? "app" : name;
    }

    // ── the compose profile ─────────────────────────────────────────────────────────────────

    /** {@code application-compose.yaml}: the settings the containers need that the defaults do not give. */
    public static String profile(Collection<String> deps, boolean composeAtParent) {
        Map<String, Object> view = new HashMap<>();
        for (String dep : SERVICES) view.put(dep, deps.contains(dep));
        view.put("schemaRegistry", deps.contains("kafka") && deps.contains(SCHEMA_REGISTRY));
        boolean createsSchema = createsSchema(deps);
        view.put("createsSchema", createsSchema);
        view.put("profiles", profiles(deps));
        view.put("composePath", composeAtParent ? "../" + COMPOSE_FILE : COMPOSE_FILE);
        boolean settings = deps.contains("oracle") || deps.contains("mssql") || deps.contains("kafka")
                || (deps.contains("postgresql") && createsSchema);
        view.put("hasSettings", settings);
        return CompiledTemplates.render(text("application-compose.yaml"), view);
    }

    /**
     * A plain project's compose file and profile, when a dependency has a service. Nothing
     * otherwise — an H2-only project needs nothing running.
     */
    public static boolean writeProject(Path root, String projectName, Collection<String> deps, ContainerImages images)
            throws IOException {
        if (!hasServices(deps)) return false;
        Files.writeString(root.resolve(COMPOSE_FILE), compose(projectName, deps, images, false));
        writeProfile(root, deps, false);
        return true;
    }

    /** The compose profile in the application's (sub)project. */
    public static void writeProfile(Path applicationRoot, Collection<String> deps, boolean composeAtParent) throws IOException {
        Path target = applicationRoot.resolve(PROFILE_FILE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, profile(deps, composeAtParent));
    }

    // ── Testcontainers ─────────────────────────────────────────────────────────────────────

    /** The databases among the dependencies a container can stand in for, in migration order. */
    public static List<String> containerDatabases(Collection<String> deps) {
        return CONTAINER_DATABASES.stream().filter(deps::contains).toList();
    }

    /** The Testcontainers module for a database dependency. */
    static String testcontainersModule(String db) {
        return switch (db) {
            case "postgresql" -> "postgresql";
            case "oracle" -> "oracle-free";
            case "mssql" -> "mssqlserver";
            default -> throw new IllegalArgumentException(db);
        };
    }

    /** With the {@code testcontainers} dependency: the module of each database the project has. */
    public static void addTestcontainersModules(MavenBuild build, Collection<String> deps) {
        for (String db : containerDatabases(deps)) {
            String module = testcontainersModule(db);
            build.dependencies().add("testcontainers-" + module,
                    Dependency.withCoordinates("org.testcontainers", module).scope(DependencyScope.TEST_COMPILE));
        }
    }

    /** The sample test of the {@code testcontainers} dependency, or empty without a database it fits. */
    public static Optional<String> containerTests(String packageName, Collection<String> deps, ContainerImages images) {
        List<String> dbs = containerDatabases(deps);
        if (dbs.isEmpty()) return Optional.empty();
        Map<String, Object> view = serviceView(deps, images);
        // The test names plain Docker Hub images: testcontainers.properties carries the proxy.
        view.put("postgresImage", POSTGRES_IMAGE);
        view.put("oracleImage", ORACLE_IMAGE);
        view.put("packageName", packageName);
        view.put("flyway", deps.contains("flyway"));
        view.put("databases", String.join(" and ", dbs.stream().map(LocalRun::label).toList()));
        return Optional.of(CompiledTemplates.render(text("ContainerDatabaseTests.java"), view));
    }

    private static String label(String db) {
        return switch (db) {
            case "postgresql" -> "PostgreSQL";
            case "oracle" -> "Oracle";
            case "mssql" -> "SQL Server";
            default -> db;
        };
    }

    /** {@code src/test/resources/testcontainers.properties}, or empty when images come from Docker Hub itself. */
    public static Optional<String> testcontainersProperties(ContainerImages images) {
        if (images.hubPrefix().isEmpty()) return Optional.empty();
        return Optional.of("# Docker Hub images (the databases, Testcontainers' own) come through this registry.\n"
                + "hub.image.name.prefix=" + images.hubPrefix() + "\n");
    }

    /** Writes {@code testcontainers.properties} under the project's test resources when there is a proxy. */
    public static void writeTestcontainersProperties(Path root, ContainerImages images) throws IOException {
        Optional<String> properties = testcontainersProperties(images);
        if (properties.isEmpty()) return;
        Path target = root.resolve("src/test/resources/testcontainers.properties");
        Files.createDirectories(target.getParent());
        Files.writeString(target, properties.get());
    }

    /** The database the fullstack {@code db-tests} profile runs on: the migration's, when a container fits it. */
    public static Optional<String> dbTestsDatabase(Collection<String> deps) {
        // The migration is written for the first of PostgreSQL, Oracle, SQL Server, DB2: with DB2
        // first there is no container for it.
        for (String db : List.of("postgresql", "oracle", "mssql", "db2")) {
            if (deps.contains(db)) return db.equals("db2") ? Optional.empty() : Optional.of(db);
        }
        return Optional.empty();
    }

    /**
     * The fullstack backend's {@code db-tests} Maven profile: Testcontainers' JDBC driver in place
     * of the test profile's H2 for {@code db}, so {@code mvn test -Pdb-tests} runs the same API
     * tests on that database in Docker — on the schema the Flyway migration makes, when there is
     * one. The other datasources a build may have stay on H2.
     */
    public static void addDbTestsProfile(MavenBuild build, String db, boolean flyway) {
        MavenProfile profile = build.profiles().id(DB_TESTS_PROFILE);
        profile.dependencies().add("testcontainers-jdbc",
                Dependency.withCoordinates("org.testcontainers", "jdbc").scope(DependencyScope.TEST_COMPILE));
        String module = testcontainersModule(db);
        profile.dependencies().add("testcontainers-" + module,
                Dependency.withCoordinates("org.testcontainers", module).scope(DependencyScope.TEST_COMPILE));
        profile.plugins().add("org.apache.maven.plugins", "maven-surefire-plugin", plugin -> plugin
                .configuration(c -> c.configure("systemPropertyVariables", p -> {
                    p.add(db + ".datasource.jdbc-url", tcUrl(db));
                    p.add(db + ".datasource.driver-class-name", "org.testcontainers.jdbc.ContainerDatabaseDriver");
                    p.add(db + ".hibernate.dialect", dialect(db));
                    // With Flyway the migration makes the schema and Hibernate checks it; without,
                    // Hibernate makes it as on H2.
                    p.add(db + ".hibernate.hbm2ddl-auto", flyway ? "validate" : "create-drop");
                    if (flyway) p.add("spring.flyway.enabled", "true");
                })));
    }

    /** Testcontainers' JDBC URL: the driver starts the container the first time it is asked. */
    static String tcUrl(String db) {
        return switch (db) {
            case "postgresql" -> "jdbc:tc:postgresql:" + POSTGRES_IMAGE.substring(POSTGRES_IMAGE.indexOf(':') + 1) + ":///appdb";
            case "oracle" -> "jdbc:tc:oracle:" + ORACLE_TAG + ":///appdb";
            case "mssql" -> "jdbc:tc:sqlserver:" + MSSQL_IMAGE.substring(MSSQL_IMAGE.indexOf(':') + 1) + ":///appdb";
            default -> throw new IllegalArgumentException(db);
        };
    }

    static String dialect(String db) {
        return switch (db) {
            case "postgresql" -> "org.hibernate.dialect.PostgreSQLDialect";
            case "oracle" -> "org.hibernate.dialect.OracleDialect";
            case "mssql" -> "org.hibernate.dialect.SQLServerDialect";
            default -> throw new IllegalArgumentException(db);
        };
    }

    /**
     * SQL Server's image asks for its licence to be accepted: Testcontainers reads the image names
     * from this file. The JDBC URL names Microsoft's registry, whatever proxy compose uses.
     */
    public static Optional<String> licenceAcceptance(String db) {
        return db.equals("mssql") ? Optional.of("mcr.microsoft.com/" + MSSQL_IMAGE + "\n") : Optional.empty();
    }

    // ── Flyway's database modules ───────────────────────────────────────────────────────────

    /** First Boot line on Flyway 10, where PostgreSQL and Oracle support left flyway-core. */
    static final Version FLYWAY_10_SINCE = Version.parse("3.3.0");

    /**
     * The Flyway module a database needs beside {@code flyway-core} on this Boot line: SQL Server
     * has had its own since Flyway 8, PostgreSQL and Oracle since Flyway 10 (Boot 3.3). Without it
     * Flyway stops the application with "Unsupported Database".
     */
    public static List<String> flywayModules(Collection<String> deps, Version boot) {
        List<String> modules = new ArrayList<>();
        if (!deps.contains("flyway")) return modules;
        boolean flyway10 = boot != null && boot.compareTo(FLYWAY_10_SINCE) >= 0;
        if (deps.contains("postgresql") && flyway10) modules.add("flyway-database-postgresql");
        if (deps.contains("oracle") && flyway10) modules.add("flyway-database-oracle");
        if (deps.contains("mssql")) modules.add("flyway-sqlserver");
        return modules;
    }

    // ── shared ──────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> serviceView(Collection<String> deps, ContainerImages images) {
        Map<String, Object> view = new HashMap<>();
        for (String dep : SERVICES) view.put(dep, deps.contains(dep));
        view.put("postgresImage", images.hub(POSTGRES_IMAGE));
        view.put("oracleImage", images.hub(ORACLE_IMAGE));
        view.put("mssqlImage", images.mcr(MSSQL_IMAGE));
        view.put("mongoImage", images.hub(MONGO_IMAGE));
        view.put("redisImage", images.hub(REDIS_IMAGE));
        view.put("kafkaImage", images.hub(KAFKA_IMAGE));
        view.put("schemaRegistry", deps.contains("kafka") && deps.contains(SCHEMA_REGISTRY));
        view.put("schemaRegistryImage", images.hub(SCHEMA_REGISTRY_IMAGE));
        return view;
    }

    static String text(String name) {
        return TEXTS.computeIfAbsent(name, n -> {
            String path = "templates/localrun/" + n + ".mustache";
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            } catch (IOException e) {
                throw new UncheckedIOException("Missing template " + path, e);
            }
        });
    }
}
