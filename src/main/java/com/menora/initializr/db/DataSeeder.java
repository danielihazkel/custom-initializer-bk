package com.menora.initializr.db;

import com.menora.initializr.db.entity.*;
import com.menora.initializr.db.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Seeds the database from existing classpath resources on first startup.
 * Runs only when all tables are empty.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final DependencyGroupRepository groupRepo;
    private final DependencyEntryRepository entryRepo;
    private final FileContributionRepository fileContribRepo;
    private final BuildCustomizationRepository buildCustomRepo;
    private final DependencySubOptionRepository subOptionRepo;
    private final DependencyCompatibilityRepository compatibilityRepo;
    private final StarterTemplateRepository templateRepo;
    private final StarterTemplateDepRepository templateDepRepo;
    private final ModuleTemplateRepository moduleRepo;
    private final ModuleDependencyMappingRepository moduleMappingRepo;
    private final ColorPaletteRepository colorPaletteRepo;

    public DataSeeder(DependencyGroupRepository groupRepo,
                      DependencyEntryRepository entryRepo,
                      FileContributionRepository fileContribRepo,
                      BuildCustomizationRepository buildCustomRepo,
                      DependencySubOptionRepository subOptionRepo,
                      DependencyCompatibilityRepository compatibilityRepo,
                      StarterTemplateRepository templateRepo,
                      StarterTemplateDepRepository templateDepRepo,
                      ModuleTemplateRepository moduleRepo,
                      ModuleDependencyMappingRepository moduleMappingRepo,
                      ColorPaletteRepository colorPaletteRepo) {
        this.groupRepo = groupRepo;
        this.entryRepo = entryRepo;
        this.fileContribRepo = fileContribRepo;
        this.buildCustomRepo = buildCustomRepo;
        this.subOptionRepo = subOptionRepo;
        this.compatibilityRepo = compatibilityRepo;
        this.templateRepo = templateRepo;
        this.templateDepRepo = templateDepRepo;
        this.moduleRepo = moduleRepo;
        this.moduleMappingRepo = moduleMappingRepo;
        this.colorPaletteRepo = colorPaletteRepo;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        // Run table-scoped seeds before the main guard so new tables added in
        // later releases (e.g. color_palette) get populated on existing installations
        // without forcing a full re-seed.
        seedColorPalettes();

        if (groupRepo.count() > 0) {
            log.info("Database already seeded — skipping DataSeeder");
            normalizeLegacyBlankStrings();
            return;
        }
        log.info("Seeding database from classpath resources...");
        seedDependencyCatalog();
        seedCommonFileContributions();
        seedDependencyFileContributions();
        seedBuildCustomizations();
        seedSubOptions();
        seedCompatibilityRules();
        seedStarterTemplates();
        seedModuleTemplates();
        seedFrontendCatalog();
        log.info("Database seeding complete");
    }

    /**
     * Legacy rows inserted through the admin UI before the entity setters were
     * taught to coerce blank strings to null. Re-saving each row runs the now-coercing
     * setters and flushes NULLs to columns that used to hold "".
     */
    private void normalizeLegacyBlankStrings() {
        int fcFixed = 0;
        for (FileContributionEntity f : fileContribRepo.findAll()) {
            boolean bad = "".equals(f.getSubOptionId()) || "".equals(f.getJavaVersion());
            if (bad) {
                f.setSubOptionId(f.getSubOptionId());
                f.setJavaVersion(f.getJavaVersion());
                fileContribRepo.save(f);
                fcFixed++;
            }
        }
        int entryFixed = 0;
        for (DependencyEntryEntity e : entryRepo.findAll()) {
            boolean bad = "".equals(e.getMavenGroupId()) || "".equals(e.getMavenArtifactId())
                    || "".equals(e.getVersion()) || "".equals(e.getScope())
                    || "".equals(e.getRepository()) || "".equals(e.getCompatibilityRange())
                    || "".equals(e.getDescription());
            if (bad) {
                e.setMavenGroupId(e.getMavenGroupId());
                e.setMavenArtifactId(e.getMavenArtifactId());
                e.setVersion(e.getVersion());
                e.setScope(e.getScope());
                e.setRepository(e.getRepository());
                e.setCompatibilityRange(e.getCompatibilityRange());
                e.setDescription(e.getDescription());
                entryRepo.save(e);
                entryFixed++;
            }
        }
        if (fcFixed + entryFixed > 0) {
            log.info("Normalized legacy blank strings: {} file contributions, {} dependency entries",
                    fcFixed, entryFixed);
        }
    }

    private DependencyGroupEntity group(String name, int order) {
        DependencyGroupEntity g = new DependencyGroupEntity();
        g.setName(name);
        g.setSortOrder(order);
        return groupRepo.save(g);
    }

    private void entry(DependencyGroupEntity group, String depId, String name, String desc,
                       String mvnGroup, String mvnArtifact, String version, String scope,
                       String repo, int order) {
        DependencyEntryEntity e = new DependencyEntryEntity();
        e.setGroup(group);
        e.setDepId(depId);
        e.setName(name);
        e.setDescription(desc);
        e.setMavenGroupId(mvnGroup);
        e.setMavenArtifactId(mvnArtifact);
        e.setVersion(version);
        e.setScope(scope);
        e.setRepository(repo);
        e.setSortOrder(order);
        entryRepo.save(e);
    }

    private void seedDependencyCatalog() {
        DependencyGroupEntity menora = group("Menora Standards", 0);
        entry(menora, "rqueue", "Sonus Rqueue", "Sonus Rqueue messaging library",
                "com.sonus", "sonus-rqueue", "1.0.0", null, "menora-release", 0);
        entryRepo.findAll().stream()
                .filter(e -> "rqueue".equals(e.getDepId()))
                .findFirst()
                .ifPresent(e -> { e.setCompatibilityRange("[3.2.0,4.0.0)"); entryRepo.save(e); });

        DependencyGroupEntity web = group("Web", 1);
        entry(web, "web", "Spring Web", "Build web applications with Spring MVC",
                null, null, null, null, null, 0);
        entry(web, "webflux", "Spring Reactive Web", "Build reactive web applications with Spring WebFlux",
                null, null, null, null, null, 1);
        entry(web, "web-services", "Spring Web Services",
                "Contract-first SOAP services and clients with Spring-WS",
                "org.springframework.boot", "spring-boot-starter-web-services", null, null, null, 2);
        entry(web, "validation", "Bean Validation",
                "Hibernate Validator + @RestControllerAdvice for ConstraintViolation handling",
                "org.springframework.boot", "spring-boot-starter-validation", null, null, null, 3);
        entry(web, "openapi", "OpenAPI / Swagger UI",
                "Springdoc OpenAPI — auto-generated API docs + Swagger UI at /swagger-ui.html",
                "org.springdoc", "springdoc-openapi-starter-webmvc-ui", "2.6.0", null, null, 4);
        setRange("openapi", "[3.2.0,4.0.0)");

        DependencyGroupEntity data = group("Data", 2);
        entry(data, "data-jpa", "Spring Data JPA", "Persist data with JPA and Hibernate",
                null, null, null, null, null, 0);
        entry(data, "postgresql", "PostgreSQL Driver", "PostgreSQL JDBC driver",
                "org.postgresql", "postgresql", null, "runtime", null, 1);
        entry(data, "mssql", "Microsoft SQL Server Driver", "Microsoft SQL Server JDBC driver",
                "com.microsoft.sqlserver", "mssql-jdbc", null, "runtime", null, 2);
        entry(data, "db2", "IBM DB2 Driver", "IBM DB2 JDBC driver",
                "com.ibm.db2", "jcc", null, "runtime", null, 3);
        entry(data, "oracle", "Oracle Database Driver", "Oracle JDBC driver",
                "com.oracle.database.jdbc", "ojdbc11", null, "runtime", null, 4);
        entry(data, "h2", "H2 Database",
                "Embedded relational database — ideal for development and testing",
                "com.h2database", "h2", null, "runtime", null, 5);
        entry(data, "mongodb", "MongoDB", "Spring Data MongoDB — document database driver",
                null, null, null, null, null, 6);
        entry(data, "flyway", "Flyway Migration",
                "Database schema migrations with Flyway — drop SQL files under db/migration/",
                "org.flywaydb", "flyway-core", null, null, null, 7);
        entry(data, "liquibase", "Liquibase Migration",
                "Database schema migrations with Liquibase — XML changelogs under db/changelog/",
                "org.liquibase", "liquibase-core", null, null, null, 8);
        entry(data, "redis", "Spring Data Redis",
                "Spring Data Redis — Lettuce client + RedisTemplate for cache and ops",
                "org.springframework.boot", "spring-boot-starter-data-redis", null, null, null, 9);

        DependencyGroupEntity messaging = group("Messaging", 3);
        entry(messaging, "kafka", "Spring for Apache Kafka", "Kafka messaging support",
                null, null, null, null, null, 0);

        DependencyGroupEntity security = group("Security", 4);
        entry(security, "security", "Spring Security", "Authentication and authorization framework",
                null, null, null, null, null, 0);

        DependencyGroupEntity observability = group("Observability", 5);
        entry(observability, "actuator", "Spring Boot Actuator", "Production-ready features",
                null, null, null, null, null, 0);
        entry(observability, "prometheus", "Micrometer Prometheus", null,
                "io.micrometer", "micrometer-registry-prometheus", null, "runtime", null, 1);

        DependencyGroupEntity logging = group("Logging", 6);
        entry(logging, "logging", "Menora Logging Standards",
                "Corporate logging configuration and standards",
                "org.springframework.boot", "spring-boot-starter-log4j2", null, null, null, 0);

        DependencyGroupEntity communication = group("Communication", 7);
        entry(communication, "mail-sampler", "Mail Sampler",
                "Outlook mail integration for internal organization",
                null, null, null, null, null, 0);

        DependencyGroupEntity utilities = group("Utilities", 8);
        entry(utilities, "file-handler-utils", "File Handler Utils",
                "Drop-in helper classes for reading/writing files — no pom dependency added",
                null, null, null, null, null, 0);
        entryRepo.findAll().stream()
                .filter(e -> "file-handler-utils".equals(e.getDepId()))
                .findFirst()
                .ifPresent(e -> { e.setStarter(false); entryRepo.save(e); });
        entry(utilities, "mapstruct", "MapStruct",
                "Compile-time DTO/entity mapping via annotation processor",
                "org.mapstruct", "mapstruct", "1.6.3", null, null, 1);
        entry(utilities, "cache", "Caching (Caffeine)",
                "Spring Cache abstraction backed by an in-memory Caffeine cache manager",
                "org.springframework.boot", "spring-boot-starter-cache", null, null, null, 2);
        entry(utilities, "resilience4j", "Resilience4j",
                "Circuit breaker, retry, and time-limiter — fault tolerance toolkit",
                "io.github.resilience4j", "resilience4j-spring-boot3", "2.2.0", null, null, 3);
        setRange("resilience4j", "[3.2.0,4.0.0)");
        entry(utilities, "quartz", "Quartz Scheduler",
                "Spring Boot Quartz starter — schedule background jobs with JobDetail/Trigger",
                "org.springframework.boot", "spring-boot-starter-quartz", null, null, null, 4);
    }

    private void setRange(String depId, String range) {
        entryRepo.findAll().stream()
                .filter(e -> depId.equals(e.getDepId()))
                .findFirst()
                .ifPresent(e -> { e.setCompatibilityRange(range); entryRepo.save(e); });
    }

    // ── Common file contributions (every project) ────────────────────────────

    private void seedCommonFileContributions() throws IOException {
        int order = 0;

        // Base application.yaml — written first (sortOrder=-1) so every project gets
        // spring.application.name before dependency-specific YAML_MERGE entries run.
        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/application-base.mustache"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, -1);

        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/common/log4j2-spring.xml"),
                "src/main/resources/log4j2-spring.xml",
                FileContributionEntity.SubstitutionType.NONE, null, null, order++);

        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/common/.editorconfig"),
                ".editorconfig",
                FileContributionEntity.SubstitutionType.NONE, null, null, order++);

        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/common/entrypoint.sh"),
                "entrypoint.sh",
                FileContributionEntity.SubstitutionType.NONE, null, null, order++);

        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/common/settings.xml"),
                "settings.xml",
                FileContributionEntity.SubstitutionType.NONE, null, null, order++);

        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/VERSION.mustache"),
                "VERSION",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, order++);

        // Dockerfile — version-specific
        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/Dockerfile-java17.mustache"),
                "Dockerfile",
                FileContributionEntity.SubstitutionType.MUSTACHE, "17", null, order++);

        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/Dockerfile-java21.mustache"),
                "Dockerfile",
                FileContributionEntity.SubstitutionType.MUSTACHE, "21", null, order++);

        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/Jenkinsfile.mustache"),
                "k8s/Jenkinsfile",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, order++);

        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/k8s-values.mustache"),
                "k8s/values.yaml",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, order++);

        // Delete application.properties (lowest precedence — large sort order)
        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.DELETE,
                null, "src/main/resources/application.properties",
                FileContributionEntity.SubstitutionType.NONE, null, null, 9999);
    }

    // ── Per-dependency file contributions ────────────────────────────────────

    private void seedDependencyFileContributions() throws IOException {
        // kafka
        fc("kafka", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/kafka/application-kafka.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("kafka", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/kafka-config.mustache"),
                "src/main/java/{{packagePath}}/config/KafkaConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, 1);
        fc("kafka", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/kafka-consumer-example.mustache"),
                "src/main/java/{{packagePath}}/config/KafkaConsumerExample.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "consumer-example", 2);
        fc("kafka", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/kafka-producer-example.mustache"),
                "src/main/java/{{packagePath}}/config/KafkaProducerExample.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "producer-example", 3);

        // security
        fc("security", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/security/application-security.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("security", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/security-config.mustache"),
                "src/main/java/{{packagePath}}/config/SecurityConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, 1);

        // data-jpa (shared JPA properties only — datasource config comes from each driver)
        fc("data-jpa", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/jpa/application-jpa.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);

        // ── Per-driver datasource configurations ────────────────────────────

        // PostgreSQL
        fc("postgresql", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/postgresql/application-postgresql.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("postgresql", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/postgresql-config-primary.mustache"),
                "src/main/java/{{packagePath}}/config/PostgresqlConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "pg-primary", 1);
        fc("postgresql", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/postgresql-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/PostgresqlConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "pg-secondary", 2);

        // MSSQL
        fc("mssql", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/mssql/application-mssql.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("mssql", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mssql-config-primary.mustache"),
                "src/main/java/{{packagePath}}/config/MssqlConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "mssql-primary", 1);
        fc("mssql", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mssql-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/MssqlConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "mssql-secondary", 2);

        // DB2
        fc("db2", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/db2/application-db2.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("db2", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/db2-config-primary.mustache"),
                "src/main/java/{{packagePath}}/config/Db2Config.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "db2-primary", 1);
        fc("db2", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/db2-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/Db2Config.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "db2-secondary", 2);

        // Oracle
        fc("oracle", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/oracle/application-oracle.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("oracle", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/oracle-config-primary.mustache"),
                "src/main/java/{{packagePath}}/config/OracleConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "oracle-primary", 1);
        fc("oracle", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/oracle-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/OracleConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "oracle-secondary", 2);

        // H2
        fc("h2", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/h2/application-h2.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("h2", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/h2-config-primary.mustache"),
                "src/main/java/{{packagePath}}/config/H2Config.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "h2-primary", 1);
        fc("h2", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/h2-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/H2Config.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "h2-secondary", 2);

        // MongoDB
        fc("mongodb", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/mongodb/application-mongodb.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("mongodb", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mongodb-config-primary.mustache"),
                "src/main/java/{{packagePath}}/config/MongoConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "mongodb-primary", 1);
        fc("mongodb", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mongodb-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/MongoConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "mongodb-secondary", 2);

        // actuator / observability
        fc("actuator", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/observability/application-observability.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);

        // rqueue
        fc("rqueue", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/rqueue/application-rqueue.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("rqueue", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/rqueue-config.mustache"),
                "src/main/java/{{packagePath}}/config/RqueueConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, 1);

        // logging
        fc("logging", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/logging/application-logging.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);

        // mail-sampler
        fc("mail-sampler", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/mail-sampler/application-mail.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("mail-sampler", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mail-config.mustache"),
                "src/main/java/{{packagePath}}/config/MailConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, 1);
        fc("mail-sampler", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mail-service.mustache"),
                "src/main/java/{{packagePath}}/service/MailService.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "send-mail", 2);
        fc("mail-sampler", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mail-inbox-reader.mustache"),
                "src/main/java/{{packagePath}}/service/InboxReaderService.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "inbox-reader", 3);

        // file-handler-utils — file-only option (no pom dependency)
        fc("file-handler-utils", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/file-handler-sync.mustache"),
                "src/main/java/{{packagePath}}/util/SyncFileHandler.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "sync-handler", 0);
        fc("file-handler-utils", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/file-handler-async.mustache"),
                "src/main/java/{{packagePath}}/util/AsyncFileHandler.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "async-handler", 1);

        // mapstruct
        fc("mapstruct", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mapstruct-config.mustache"),
                "src/main/java/{{packagePath}}/config/MapstructConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, 0);
        fc("mapstruct", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mapstruct-example-dto.mustache"),
                "src/main/java/{{packagePath}}/dto/ExampleDto.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "example-mapper", 1);
        fc("mapstruct", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mapstruct-example-mapper.mustache"),
                "src/main/java/{{packagePath}}/mapper/ExampleMapper.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "example-mapper", 2);

        // validation
        fc("validation", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/validation/application-validation.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("validation", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/validation-exception-handler.mustache"),
                "src/main/java/{{packagePath}}/web/ValidationExceptionHandler.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, 1);
        fc("validation", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/validation-dto-sample.mustache"),
                "src/main/java/{{packagePath}}/dto/SampleRequest.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "dto-sample", 2);

        // openapi
        fc("openapi", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/openapi/application-openapi.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("openapi", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/openapi-config.mustache"),
                "src/main/java/{{packagePath}}/config/OpenApiConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, 1);

        // flyway
        fc("flyway", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/flyway/application-flyway.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("flyway", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/flyway/V1__init.sql"),
                "src/main/resources/db/migration/V1__init.sql",
                FileContributionEntity.SubstitutionType.NONE, null, null, 1);

        // liquibase
        fc("liquibase", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/liquibase/application-liquibase.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("liquibase", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/liquibase/db.changelog-master.xml"),
                "src/main/resources/db/changelog/db.changelog-master.xml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 1);
        fc("liquibase", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/liquibase/db.changelog-1.0.xml"),
                "src/main/resources/db/changelog/db.changelog-1.0.xml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 2);

        // redis
        fc("redis", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/redis/application-redis.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("redis", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/redis-config.mustache"),
                "src/main/java/{{packagePath}}/config/RedisConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, 1);

        // cache (Caffeine)
        fc("cache", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/cache/application-cache.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("cache", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/cache-config.mustache"),
                "src/main/java/{{packagePath}}/config/CacheConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, 1);

        // resilience4j
        fc("resilience4j", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/resilience4j/application-resilience4j.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("resilience4j", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/resilience4j-example-service.mustache"),
                "src/main/java/{{packagePath}}/service/ResilientService.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "circuit-breaker-example", 1);

        // quartz
        fc("quartz", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/quartz/application-quartz.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("quartz", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/quartz-config.mustache"),
                "src/main/java/{{packagePath}}/config/QuartzConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, null, 1);
        fc("quartz", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/quartz-job-example.mustache"),
                "src/main/java/{{packagePath}}/job/HelloJob.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "job-example", 2);
        fc("quartz", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/quartz-job-config.mustache"),
                "src/main/java/{{packagePath}}/job/HelloJobConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "job-example", 3);

        // security — JWT + method security sub-options
        fc("security", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/security-jwt-filter.mustache"),
                "src/main/java/{{packagePath}}/config/JwtAuthFilter.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "jwt-example", 2);
        fc("security", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/security-jwt-service.mustache"),
                "src/main/java/{{packagePath}}/service/JwtService.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "jwt-example", 3);
        fc("security", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/security-method-config.mustache"),
                "src/main/java/{{packagePath}}/config/MethodSecurityConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "method-security", 4);

        // data-jpa — auditing + sample entity sub-options
        fc("data-jpa", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/jpa-auditing-config.mustache"),
                "src/main/java/{{packagePath}}/config/JpaAuditingConfig.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "auditing-example", 1);
        fc("data-jpa", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/jpa-sample-entity.mustache"),
                "src/main/java/{{packagePath}}/domain/User.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "sample-entity", 2);
        fc("data-jpa", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/jpa-sample-repo.mustache"),
                "src/main/java/{{packagePath}}/repository/UserRepository.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "sample-entity", 3);

        // web — REST example + global exception handler
        fc("web", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/web-hello-controller.mustache"),
                "src/main/java/{{packagePath}}/web/HelloController.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "rest-example", 0);
        fc("web", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/web-exception-handler.mustache"),
                "src/main/java/{{packagePath}}/web/GlobalExceptionHandler.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "global-exception-handler", 1);

        // webflux — functional router/handler sample
        fc("webflux", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/webflux-router.mustache"),
                "src/main/java/{{packagePath}}/web/HelloRouter.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "handler-function", 0);
        fc("webflux", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/webflux-handler.mustache"),
                "src/main/java/{{packagePath}}/web/HelloHandler.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "handler-function", 1);

        // web-services — example @Endpoint + XSD
        fc("web-services", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/web-services/ping.xsd"),
                "src/main/resources/xsd/ping.xsd",
                FileContributionEntity.SubstitutionType.NONE, null, "example-endpoint", 0);
        fc("web-services", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/ws-ping-endpoint.mustache"),
                "src/main/java/{{packagePath}}/endpoint/PingEndpoint.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "example-endpoint", 1);

        // actuator — health-groups + info-contributor sub-options
        fc("actuator", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/actuator/health-groups.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, "health-groups", 1);
        fc("actuator", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/actuator-info-contributor.mustache"),
                "src/main/java/{{packagePath}}/config/BuildInfoContributor.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "info-contributor", 2);

        // prometheus — sample grafana dashboard
        fc("prometheus", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/prometheus/grafana-dashboard.json"),
                "k8s/grafana-dashboard.json",
                FileContributionEntity.SubstitutionType.NONE, null, "grafana-dashboard", 0);

        // kafka — streams example (requires spring-kafka-streams on classpath)
        fc("kafka", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/kafka-streams-example.mustache"),
                "src/main/java/{{packagePath}}/config/KafkaStreamsExample.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "streams-example", 4);

        // mongodb — sample document + repository
        fc("mongodb", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mongo-sample-doc.mustache"),
                "src/main/java/{{packagePath}}/domain/SampleDoc.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "sample-document", 3);
        fc("mongodb", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mongo-sample-repo.mustache"),
                "src/main/java/{{packagePath}}/repository/SampleDocRepository.java",
                FileContributionEntity.SubstitutionType.MUSTACHE, null, "sample-document", 4);
    }

    // ── Build customizations ──────────────────────────────────────────────────

    private void seedBuildCustomizations() {
        // Common: Artifactory repos
        repo(DependencyConfigService.COMMON_ID, "menora-release", "Menora Artifactory Releases",
                "https://repo.menora.co.il/artifactory/libs-release", false, 0);
        repo(DependencyConfigService.COMMON_ID, "menora-snapshot", "Menora Artifactory Snapshots",
                "https://repo.menora.co.il/artifactory/libs-snapshot", true, 1);

        // Common: log4j2 — exclude logging starter, add log4j2 starter
        excludeDep(DependencyConfigService.COMMON_ID,
                "org.springframework.boot", "spring-boot-starter",
                "org.springframework.boot", "spring-boot-starter-logging", 2);
        addDep(DependencyConfigService.COMMON_ID,
                "org.springframework.boot", "spring-boot-starter-log4j2", null, null, 3);

        // Common: Lombok
        addDep(DependencyConfigService.COMMON_ID,
                "org.projectlombok", "lombok", null, null, 4);

        // Mail Sampler: spring-boot-starter-mail
        addDep("mail-sampler",
                "org.springframework.boot", "spring-boot-starter-mail", null, null, 0);

        // MapStruct: annotation processor + Lombok binding (mapstruct runtime is on the entry)
        addDep("mapstruct",
                "org.mapstruct", "mapstruct-processor", "1.6.3", "provided", 0);
        addDep("mapstruct",
                "org.projectlombok", "lombok-mapstruct-binding", "0.2.0", "provided", 1);

        // Cache: Caffeine alongside spring-boot-starter-cache (entry provides the starter)
        addDep("cache",
                "com.github.ben-manes.caffeine", "caffeine", null, null, 0);
    }

    private void seedSubOptions() {
        subOption("kafka", "consumer-example", "Consumer Example",
                "Add a KafkaConsumerExample.java class", 0);
        subOption("kafka", "producer-example", "Producer Example",
                "Add a KafkaProducerExample.java class", 1);

        subOption("mail-sampler", "send-mail", "Send Mail Example",
                "Add a MailService.java with simple and HTML email sending", 0);
        subOption("mail-sampler", "inbox-reader", "Inbox Reader Example",
                "Add an InboxReaderService.java that reads emails via IMAP", 1);

        // Database driver primary/secondary sub-options (managed automatically by the UI)
        subOption("postgresql", "pg-primary", "Primary DataSource",
                "Mark this database as the primary datasource (@Primary)", 0);
        subOption("postgresql", "pg-secondary", "Secondary DataSource",
                "Use this database as a secondary datasource", 1);

        subOption("mssql", "mssql-primary", "Primary DataSource",
                "Mark this database as the primary datasource (@Primary)", 0);
        subOption("mssql", "mssql-secondary", "Secondary DataSource",
                "Use this database as a secondary datasource", 1);

        subOption("db2", "db2-primary", "Primary DataSource",
                "Mark this database as the primary datasource (@Primary)", 0);
        subOption("db2", "db2-secondary", "Secondary DataSource",
                "Use this database as a secondary datasource", 1);

        subOption("oracle", "oracle-primary", "Primary DataSource",
                "Mark this database as the primary datasource (@Primary)", 0);
        subOption("oracle", "oracle-secondary", "Secondary DataSource",
                "Use this database as a secondary datasource", 1);

        subOption("mongodb", "mongodb-primary", "Primary DataSource",
                "Mark this MongoDB connection as the primary datasource (@Primary)", 0);
        subOption("mongodb", "mongodb-secondary", "Secondary DataSource",
                "Use this MongoDB connection as a secondary datasource", 1);

        subOption("h2", "h2-primary", "Primary DataSource",
                "Mark this database as the primary datasource (@Primary)", 0);
        subOption("h2", "h2-secondary", "Secondary DataSource",
                "Use this database as a secondary datasource", 1);

        subOption("file-handler-utils", "sync-handler", "Synchronous Handler",
                "Add SyncFileHandler.java — blocking read/write helpers", 0);
        subOption("file-handler-utils", "async-handler", "Asynchronous Handler",
                "Add AsyncFileHandler.java — CompletableFuture-based read/write helpers", 1);

        subOption("mapstruct", "example-mapper", "Example Mapper",
                "Add ExampleDto.java + ExampleMapper.java demonstrating a MapStruct mapping", 0);

        // ── New sub-options on existing deps ────────────────────────────────

        subOption("security", "jwt-example", "JWT Example",
                "Add JwtAuthFilter.java + JwtService.java — Bearer-token authentication scaffold", 0);
        subOption("security", "method-security", "Method Security",
                "Add MethodSecurityConfig.java — enables @PreAuthorize / @PostAuthorize / @Secured", 1);

        subOption("data-jpa", "auditing-example", "JPA Auditing",
                "Add JpaAuditingConfig.java with AuditorAware<String> — wires @CreatedBy/@LastModifiedBy", 0);
        subOption("data-jpa", "sample-entity", "Sample Entity & Repository",
                "Add User.java entity + UserRepository.java with audit fields", 1);

        subOption("web", "rest-example", "REST Controller Example",
                "Add HelloController.java exposing GET /api/hello", 0);
        subOption("web", "global-exception-handler", "Global Exception Handler",
                "Add GlobalExceptionHandler.java — @RestControllerAdvice with consistent error envelope", 1);

        subOption("webflux", "handler-function", "Functional Routes Example",
                "Add HelloRouter.java + HelloHandler.java — RouterFunction-based endpoints", 0);

        subOption("web-services", "example-endpoint", "Example @Endpoint",
                "Add PingEndpoint.java + ping.xsd — Spring-WS contract-first sample", 0);

        subOption("actuator", "health-groups", "Health Probes (readiness/liveness)",
                "Merge management.endpoint.health.group.readiness/liveness blocks for Kubernetes probes", 0);
        subOption("actuator", "info-contributor", "Build Info Contributor",
                "Add BuildInfoContributor.java — exposes app name/group/version under /actuator/info", 1);

        subOption("prometheus", "grafana-dashboard", "Grafana Dashboard Starter",
                "Add k8s/grafana-dashboard.json — basic JVM + HTTP latency panels", 0);

        subOption("kafka", "streams-example", "Streams Example",
                "Add KafkaStreamsExample.java — sample KStream topology (requires spring-kafka-streams)", 2);

        subOption("mongodb", "sample-document", "Sample Document & Repository",
                "Add SampleDoc.java + SampleDocRepository.java — MongoRepository example", 2);

        subOption("validation", "dto-sample", "Sample DTO with Constraints",
                "Add SampleRequest.java with @NotBlank/@Email — demonstrates bean validation", 0);

        subOption("resilience4j", "circuit-breaker-example", "Circuit Breaker Example",
                "Add ResilientService.java — @CircuitBreaker + @Retry with fallback method", 0);

        subOption("quartz", "job-example", "Job Example",
                "Add HelloJob.java + HelloJobConfig.java — registers a 1-minute repeating Quartz job", 0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void fc(String depId, FileContributionEntity.FileType fileType, String content,
                    String targetPath, FileContributionEntity.SubstitutionType subType,
                    String javaVersion, String subOptionId, int order) {
        FileContributionEntity e = new FileContributionEntity();
        e.setDependencyId(depId);
        e.setFileType(fileType);
        e.setContent(content);
        e.setTargetPath(targetPath);
        e.setSubstitutionType(subType);
        e.setJavaVersion(javaVersion);
        e.setSubOptionId(subOptionId);
        e.setSortOrder(order);
        fileContribRepo.save(e);
    }

    private void addDep(String depId, String groupId, String artifactId,
                        String version, String scope, int order) {
        BuildCustomizationEntity e = new BuildCustomizationEntity();
        e.setDependencyId(depId);
        e.setCustomizationType(BuildCustomizationEntity.CustomizationType.ADD_DEPENDENCY);
        e.setMavenGroupId(groupId);
        e.setMavenArtifactId(artifactId);
        e.setVersion(version);
        e.setSortOrder(order);
        buildCustomRepo.save(e);
    }

    private void excludeDep(String depId, String fromGroup, String fromArtifact,
                             String excludeGroup, String excludeArtifact, int order) {
        BuildCustomizationEntity e = new BuildCustomizationEntity();
        e.setDependencyId(depId);
        e.setCustomizationType(BuildCustomizationEntity.CustomizationType.EXCLUDE_DEPENDENCY);
        e.setExcludeFromGroupId(fromGroup);
        e.setExcludeFromArtifactId(fromArtifact);
        e.setMavenGroupId(excludeGroup);
        e.setMavenArtifactId(excludeArtifact);
        e.setSortOrder(order);
        buildCustomRepo.save(e);
    }

    private void repo(String depId, String repoId, String repoName, String repoUrl,
                      boolean snapshotsEnabled, int order) {
        BuildCustomizationEntity e = new BuildCustomizationEntity();
        e.setDependencyId(depId);
        e.setCustomizationType(BuildCustomizationEntity.CustomizationType.ADD_REPOSITORY);
        e.setRepoId(repoId);
        e.setRepoName(repoName);
        e.setRepoUrl(repoUrl);
        e.setSnapshotsEnabled(snapshotsEnabled);
        e.setSortOrder(order);
        buildCustomRepo.save(e);
    }

    private void seedCompatibilityRules() {
        compatibility("web", "webflux", DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Spring MVC and WebFlux use incompatible server models — choose one", 0);
        compatibility("data-jpa", "postgresql", DependencyCompatibilityEntity.RelationType.RECOMMENDS,
                "JPA requires a database driver — add the PostgreSQL Driver", 1);
        compatibility("mssql", "data-jpa", DependencyCompatibilityEntity.RelationType.RECOMMENDS,
                "MSSQL driver works best with Spring Data JPA", 6);
        compatibility("db2", "data-jpa", DependencyCompatibilityEntity.RelationType.RECOMMENDS,
                "DB2 driver works best with Spring Data JPA", 7);
        compatibility("oracle", "data-jpa", DependencyCompatibilityEntity.RelationType.RECOMMENDS,
                "Oracle driver works best with Spring Data JPA", 8);
        compatibility("actuator", "prometheus", DependencyCompatibilityEntity.RelationType.RECOMMENDS,
                "Add Micrometer Prometheus to export Actuator metrics to Prometheus", 2);
        compatibility("rqueue", "data-jpa", DependencyCompatibilityEntity.RelationType.REQUIRES,
                "Sonus Rqueue requires a JPA datasource to persist job state", 3);
        compatibility("security", "web", DependencyCompatibilityEntity.RelationType.REQUIRES,
                "Spring Security requires a web layer — add Spring Web", 4);
        compatibility("mail-sampler", "web", DependencyCompatibilityEntity.RelationType.RECOMMENDS,
                "Mail Sampler works best with a web layer for REST endpoints", 5);
        compatibility("data-jpa", "h2", DependencyCompatibilityEntity.RelationType.RECOMMENDS,
                "JPA requires a database driver — add H2 for development/testing", 9);
        compatibility("data-jpa", "mapstruct", DependencyCompatibilityEntity.RelationType.RECOMMENDS,
                "Use MapStruct to convert between JPA entities and DTOs", 10);
    }

    private void compatibility(String source, String target,
                                DependencyCompatibilityEntity.RelationType type,
                                String desc, int order) {
        DependencyCompatibilityEntity e = new DependencyCompatibilityEntity();
        e.setSourceDepId(source);
        e.setTargetDepId(target);
        e.setRelationType(type);
        e.setDescription(desc);
        e.setSortOrder(order);
        compatibilityRepo.save(e);
    }

    private void subOption(String depId, String optionId, String label, String desc, int order) {
        DependencySubOptionEntity e = new DependencySubOptionEntity();
        e.setDependencyId(depId);
        e.setOptionId(optionId);
        e.setLabel(label);
        e.setDescription(desc);
        e.setSortOrder(order);
        subOptionRepo.save(e);
    }

    // ── Starter templates ───────────────────────────────────────────────────

    private void seedStarterTemplates() {
        StarterTemplateEntity restApi = starterTemplate(
                "rest-api", "REST API Service",
                "Spring Web + JPA + PostgreSQL + Actuator",
                "api", "#4CAF50", null, null, null, 0);
        templateDep(restApi, "web", null);
        templateDep(restApi, "data-jpa", null);
        templateDep(restApi, "postgresql", "pg-primary");
        templateDep(restApi, "actuator", null);
        templateDep(restApi, "logging", null);

        StarterTemplateEntity eventDriven = starterTemplate(
                "event-driven", "Event-Driven Service",
                "Kafka + JPA + Consumer/Producer examples",
                "bolt", "#FF9800", null, null, null, 1);
        templateDep(eventDriven, "kafka", "consumer-example,producer-example");
        templateDep(eventDriven, "data-jpa", null);
        templateDep(eventDriven, "postgresql", "pg-primary");
        templateDep(eventDriven, "actuator", null);
        templateDep(eventDriven, "logging", null);

        StarterTemplateEntity microservice = starterTemplate(
                "microservice", "Microservice (Full Stack)",
                "Web + Kafka + JPA + Security + Observability",
                "cloud", "#2196F3", null, null, null, 2);
        templateDep(microservice, "web", null);
        templateDep(microservice, "kafka", null);
        templateDep(microservice, "data-jpa", null);
        templateDep(microservice, "postgresql", "pg-primary");
        templateDep(microservice, "security", null);
        templateDep(microservice, "actuator", null);
        templateDep(microservice, "prometheus", null);
        templateDep(microservice, "logging", null);
    }

    private StarterTemplateEntity starterTemplate(String templateId, String name, String description,
                                                   String icon, String color,
                                                   String bootVersion, String javaVersion, String packaging,
                                                   int sortOrder) {
        StarterTemplateEntity e = new StarterTemplateEntity();
        e.setTemplateId(templateId);
        e.setName(name);
        e.setDescription(description);
        e.setIcon(icon);
        e.setColor(color);
        e.setBootVersion(bootVersion);
        e.setJavaVersion(javaVersion);
        e.setPackaging(packaging);
        e.setSortOrder(sortOrder);
        return templateRepo.save(e);
    }

    private void templateDep(StarterTemplateEntity template, String depId, String subOptions) {
        StarterTemplateDepEntity e = new StarterTemplateDepEntity();
        e.setTemplate(template);
        e.setDepId(depId);
        e.setSubOptions(subOptions);
        templateDepRepo.save(e);
    }

    // ── Module templates ──────────────────────────────────────────────────

    private void seedModuleTemplates() {
        // API module — gets the main class and web-facing dependencies
        moduleTemplate("api", "API Module",
                "REST controllers, web layer, and application entry point",
                "-api", "jar", true, 0);

        // Core module — shared business logic, no web or DB
        moduleTemplate("core", "Core Module",
                "Shared domain models, services, and utilities",
                "-core", "jar", false, 1);

        // Persistence module — JPA entities and repositories
        moduleTemplate("persistence", "Persistence Module",
                "JPA entities, repositories, and database configuration",
                "-persistence", "jar", false, 2);

        // Module-to-dependency mappings
        moduleDepMapping("api", "web", 0);
        moduleDepMapping("api", "security", 1);
        moduleDepMapping("api", "actuator", 2);

        moduleDepMapping("persistence", "data-jpa", 0);
        moduleDepMapping("persistence", "postgresql", 1);

        moduleDepMapping("core", "logging", 0);
    }

    private void moduleTemplate(String moduleId, String label, String description,
                                 String suffix, String packaging, boolean hasMainClass, int sortOrder) {
        ModuleTemplateEntity e = new ModuleTemplateEntity();
        e.setModuleId(moduleId);
        e.setLabel(label);
        e.setDescription(description);
        e.setSuffix(suffix);
        e.setPackaging(packaging);
        e.setHasMainClass(hasMainClass);
        e.setSortOrder(sortOrder);
        moduleRepo.save(e);
    }

    private void moduleDepMapping(String moduleId, String depId, int sortOrder) {
        ModuleDependencyMappingEntity e = new ModuleDependencyMappingEntity();
        e.setModuleId(moduleId);
        e.setDependencyId(depId);
        e.setSortOrder(sortOrder);
        moduleMappingRepo.save(e);
    }

    private String readClasspath(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IOException("Classpath resource not found: " + path);
        }
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    // ── Frontend (React + TS + Vite + FSD) catalog ──────────────────────────────
    //
    // Mirrors the backend seed but produces FRONTEND-kind rows. The
    // FrontendProjectGenerator queries them through DependencyConfigService
    // filtered by ProjectKind.FRONTEND.

    private void seedColorPalettes() {
        // Independent of the main "all tables empty" guard — seed only if
        // color_palette is empty so admins can wipe & re-seed without resetting
        // everything else.
        if (colorPaletteRepo.count() > 0) return;
        colorPalette("menora-default", "Menora Default",
                "Default Menora blue/violet palette", "#1976d2", "#9c27b0", null, "#d32f2f", true, 0);
        colorPalette("forest", "Forest",
                "Earthy greens and warm accents", "#2e7d32", "#8d6e63", "#ff8f00", "#c62828", false, 1);
        colorPalette("slate", "Slate",
                "Neutral cool grays for understated UIs", "#475569", "#0ea5e9", null, "#dc2626", false, 2);
    }

    private void colorPalette(String paletteId, String name, String description,
                              String primary, String secondary, String accent, String error,
                              boolean isDefault, int sortOrder) {
        ColorPaletteEntity p = new ColorPaletteEntity();
        p.setPaletteId(paletteId);
        p.setName(name);
        p.setDescription(description);
        p.setPrimary(primary);
        p.setSecondary(secondary);
        p.setAccent(accent);
        p.setError(error);
        p.setDefault(isDefault);
        p.setSortOrder(sortOrder);
        colorPaletteRepo.save(p);
    }

    private void seedFrontendCatalog() throws IOException {
        // Groups
        DependencyGroupEntity routing  = feGroup("Routing", 0);
        DependencyGroupEntity state    = feGroup("State Management", 1);
        DependencyGroupEntity data     = feGroup("Data Fetching", 2);
        DependencyGroupEntity styling  = feGroup("Styling", 3);
        DependencyGroupEntity design   = feGroup("Design System", 4);
        DependencyGroupEntity forms    = feGroup("Forms & Validation", 5);
        DependencyGroupEntity anim     = feGroup("Animation", 6);
        DependencyGroupEntity testing  = feGroup("Testing", 7);
        DependencyGroupEntity quality  = feGroup("Quality (default-on)", 8);
        DependencyGroupEntity extras   = feGroup("Extras", 9);

        // Entries
        feEntry(routing,  "router-react-router", "React Router",
                "Declarative routing for React (react-router-dom v6)", 0);
        feEntry(routing,  "router-tanstack",     "TanStack Router",
                "Type-safe, file-based routing with first-class data loading", 1);

        feEntry(state,    "state-zustand",       "Zustand",
                "Small, fast, scalable state-management with a tiny API", 0);
        feEntry(state,    "state-redux-toolkit", "Redux Toolkit",
                "Standard, opinionated Redux with @reduxjs/toolkit + react-redux", 1);
        feEntry(state,    "state-jotai",         "Jotai",
                "Primitive, atomic state management for React", 2);

        feEntry(data,     "data-tanstack-query", "TanStack Query",
                "Powerful asynchronous state management for server data", 0);
        feEntry(data,     "data-swr",            "SWR",
                "React Hooks for data fetching from Vercel", 1);

        feEntry(styling,  "style-tailwind",      "Tailwind CSS",
                "Utility-first CSS framework with PostCSS + Autoprefixer", 0);
        feEntry(styling,  "style-styled",        "styled-components",
                "Visual primitives for CSS-in-JS", 1);

        feEntry(design,   "design-none",         "None / Plain CSS",
                "No component library — bring your own UI", 0);
        feEntry(design,   "design-shadcn",       "shadcn/ui",
                "Tailwind + Radix copy-paste components (requires Tailwind)", 1);
        feEntry(design,   "design-mui",          "Material UI (MUI)",
                "Comprehensive React component library implementing Material Design", 2);
        feEntry(design,   "design-chakra",       "Chakra UI",
                "Modular, accessible component library powered by Emotion", 3);
        feEntry(design,   "design-mantine",      "Mantine",
                "Full-featured components plus a rich React hooks library", 4);

        feEntry(forms,    "form-react-hook-form","React Hook Form",
                "Performant, flexible and extensible forms with easy validation", 0);
        feEntry(forms,    "form-zod",            "Zod",
                "TypeScript-first schema validation with static type inference", 1);

        feEntry(anim,     "anim-framer-motion",  "Framer Motion",
                "Production-ready animation library for React", 0);

        feEntry(testing,  "test-vitest-rtl",     "Vitest + React Testing Library",
                "Fast unit test runner + RTL with jsdom and jest-dom matchers", 0);
        feEntry(testing,  "test-playwright",     "Playwright",
                "End-to-end testing for modern web apps", 1);
        feEntry(testing,  "test-msw",            "MSW (Mock Service Worker)",
                "API mocking library for browsers and Node, network-level interception", 2);

        feEntry(quality,  "quality-eslint",      "ESLint (flat config)",
                "Linting for TS + React, included by default in every project", 0);
        feEntry(quality,  "quality-prettier",    "Prettier",
                "Opinionated code formatter, included by default in every project", 1);
        feEntry(quality,  "quality-husky",       "Husky + lint-staged",
                "Git hook runner with pre-commit lint/format checks", 2);

        feEntry(extras,   "i18n-react-i18next",  "react-i18next",
                "Internationalization framework based on i18next", 0);
        feEntry(extras,   "storybook",           "Storybook",
                "Workshop for building UI components and pages in isolation", 1);
        feEntry(extras,   "auth-msal",           "Microsoft Auth (MSAL React)",
                "@azure/msal-react — Microsoft Identity Platform integration", 2);
        feEntry(extras,   "chart-recharts",      "Recharts",
                "Composable charting library built on React + D3", 3);

        // ── Common file contributions ────────────────────────────────────────
        int o = 0;
        // FSD layer barrels (one row each — keeps admin edits granular)
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export {};\n",
                "src/app/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export { HomePage } from './home';\n",
                "src/pages/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export {};\n",
                "src/widgets/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export {};\n",
                "src/features/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export {};\n",
                "src/entities/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "export {};\n",
                "src/shared/index.ts", FileContributionEntity.SubstitutionType.NONE, o++);

        // Layer READMEs
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# app\n\nApp-level providers, router, and global wiring. Imports from every layer below.\n",
                "src/app/README.md", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# pages\n\nRoute-level components. May import from widgets/features/entities/shared.\n",
                "src/pages/README.md", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# widgets\n\nComposite UI blocks. May import from features/entities/shared.\n",
                "src/widgets/README.md", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# features\n\nBusiness-level interactions. May import from entities/shared.\n",
                "src/features/README.md", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# entities\n\nDomain models and their UI. May import from shared only.\n",
                "src/entities/README.md", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                "# shared\n\nReusable infrastructure: ui-kit, lib helpers, api, config. Imports nothing from above.\n",
                "src/shared/README.md", FileContributionEntity.SubstitutionType.NONE, o++);

        // Entry files (templated so they pick up project metadata + selected deps)
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-index-html.mustache"),
                "index.html", FileContributionEntity.SubstitutionType.MUSTACHE, o++);
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-main-tsx.mustache"),
                "src/main.tsx", FileContributionEntity.SubstitutionType.MUSTACHE, o++);
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-app-tsx.mustache"),
                "src/app/App.tsx", FileContributionEntity.SubstitutionType.MUSTACHE, o++);
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-pages-home-index.mustache"),
                "src/pages/home/index.ts", FileContributionEntity.SubstitutionType.MUSTACHE, o++);
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-pages-home-ui.mustache"),
                "src/pages/home/ui/HomePage.tsx", FileContributionEntity.SubstitutionType.MUSTACHE, o++);
        feFc("__common__", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/frontend/fe-readme.mustache"),
                "README.md", FileContributionEntity.SubstitutionType.MUSTACHE, o++);

        // Common static configs
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/tsconfig.json"),
                "tsconfig.json", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/tsconfig.node.json"),
                "tsconfig.node.json", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/.gitignore"),
                ".gitignore", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/.editorconfig"),
                ".editorconfig", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/.dockerignore"),
                ".dockerignore", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/Dockerfile"),
                "Dockerfile", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/nginx.conf"),
                "nginx.conf", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/Jenkinsfile"),
                "Jenkinsfile", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/eslint.config.js"),
                "eslint.config.js", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/.prettierrc.json"),
                ".prettierrc.json", FileContributionEntity.SubstitutionType.NONE, o++);
        feFc("__common__", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/common/husky-pre-commit"),
                ".husky/pre-commit", FileContributionEntity.SubstitutionType.NONE, o++);

        // ── Per-dep file contributions ───────────────────────────────────────
        // Tailwind: config files + base CSS (Vite plugin import handled via ADD_VITE_PLUGIN below).
        feFc("style-tailwind", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/style-tailwind/tailwind.config.js"),
                "tailwind.config.js", FileContributionEntity.SubstitutionType.NONE, 0);
        feFc("style-tailwind", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/style-tailwind/postcss.config.js"),
                "postcss.config.js", FileContributionEntity.SubstitutionType.NONE, 1);
        feFc("style-tailwind", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/style-tailwind/index.css"),
                "src/index.css", FileContributionEntity.SubstitutionType.NONE, 2);

        // Vitest: config + test setup
        feFc("test-vitest-rtl", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/test-vitest-rtl/vitest.config.ts"),
                "vitest.config.ts", FileContributionEntity.SubstitutionType.NONE, 0);
        feFc("test-vitest-rtl", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/test-vitest-rtl/test-setup.ts"),
                "src/test-setup.ts", FileContributionEntity.SubstitutionType.NONE, 1);

        // Design system: theme stubs & shadcn helpers
        feFc("design-shadcn", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/design-shadcn/components.json"),
                "components.json", FileContributionEntity.SubstitutionType.NONE, 0);
        feFc("design-shadcn", FileContributionEntity.FileType.STATIC_COPY,
                readClasspath("static-configs/frontend/design-shadcn/lib-utils.ts"),
                "src/shared/lib/utils.ts", FileContributionEntity.SubstitutionType.NONE, 1);
        feFc("design-mui", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("static-configs/frontend/design-mui/theme.ts"),
                "src/shared/theme/theme.ts", FileContributionEntity.SubstitutionType.MUSTACHE, 0);
        feFc("design-chakra", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("static-configs/frontend/design-chakra/theme.ts"),
                "src/shared/theme/theme.ts", FileContributionEntity.SubstitutionType.MUSTACHE, 0);
        feFc("design-mantine", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("static-configs/frontend/design-mantine/theme.ts"),
                "src/shared/theme/theme.ts", FileContributionEntity.SubstitutionType.MUSTACHE, 0);

        // ── Common npm deps (every FE project) ───────────────────────────────
        // React/React-DOM versions: ranges keyed off reactVersion. For v1 we ship two majors.
        feNpm("__common__", "react",          "^18.3.1", "",    0);
        feNpm("__common__", "react-dom",      "^18.3.1", "",    1);
        feNpm("__common__", "@types/react",         "^18.3.3", "dev", 2);
        feNpm("__common__", "@types/react-dom",     "^18.3.0", "dev", 3);
        feNpm("__common__", "@vitejs/plugin-react", "^4.3.1",  "dev", 4);
        feNpm("__common__", "vite",                 "^5.2.0",  "dev", 5);
        feNpm("__common__", "typescript",           "^5.4.5",  "dev", 6);

        // Quality baseline (always on, per user preference)
        feNpm("__common__", "eslint",                              "^9.10.0", "dev", 10);
        feNpm("__common__", "@eslint/js",                          "^9.10.0", "dev", 11);
        feNpm("__common__", "typescript-eslint",                   "^8.5.0",  "dev", 12);
        feNpm("__common__", "eslint-plugin-react-hooks",           "^5.1.0",  "dev", 13);
        feNpm("__common__", "eslint-plugin-react-refresh",         "^0.4.11", "dev", 14);
        feNpm("__common__", "globals",                             "^15.9.0", "dev", 15);
        feNpm("__common__", "prettier",                            "^3.3.3",  "dev", 16);
        feNpm("__common__", "husky",                               "^9.1.5",  "dev", 17);
        feNpm("__common__", "lint-staged",                         "^15.2.10","dev", 18);

        // Common Vite plugin: @vitejs/plugin-react
        feVitePlugin("__common__", "@vitejs/plugin-react", "react", "react()", 0);

        // ── Per-dep npm deps ─────────────────────────────────────────────────
        feNpm("router-react-router", "react-router-dom",                "^6.26.0", "",    0);
        feNpm("router-tanstack",     "@tanstack/react-router",          "^1.50.0", "",    0);
        feNpm("router-tanstack",     "@tanstack/router-devtools",       "^1.50.0", "dev", 1);

        feNpm("state-zustand",       "zustand",                         "^4.5.5",  "",    0);
        feNpm("state-redux-toolkit", "@reduxjs/toolkit",                "^2.2.7",  "",    0);
        feNpm("state-redux-toolkit", "react-redux",                     "^9.1.2",  "",    1);
        feNpm("state-jotai",         "jotai",                           "^2.9.3",  "",    0);

        feNpm("data-tanstack-query", "@tanstack/react-query",           "^5.51.23","",    0);
        feNpm("data-tanstack-query", "@tanstack/react-query-devtools",  "^5.51.23","dev", 1);
        feNpm("data-swr",            "swr",                             "^2.2.5",  "",    0);

        feNpm("style-tailwind",      "tailwindcss",                     "^3.4.10", "dev", 0);
        feNpm("style-tailwind",      "postcss",                         "^8.4.41", "dev", 1);
        feNpm("style-tailwind",      "autoprefixer",                    "^10.4.20","dev", 2);
        feNpm("style-styled",        "styled-components",               "^6.1.13", "",    0);
        feNpm("style-styled",        "@types/styled-components",        "^5.1.34", "dev", 1);

        // Design system component libraries
        feNpm("design-shadcn",       "clsx",                            "^2.1.1",  "",    0);
        feNpm("design-shadcn",       "tailwind-merge",                  "^2.5.2",  "",    1);
        feNpm("design-shadcn",       "class-variance-authority",        "^0.7.0",  "",    2);
        feNpm("design-shadcn",       "@radix-ui/react-slot",            "^1.1.0",  "",    3);
        feNpm("design-mui",          "@mui/material",                   "^5.16.7", "",    0);
        feNpm("design-mui",          "@emotion/react",                  "^11.13.0","",    1);
        feNpm("design-mui",          "@emotion/styled",                 "^11.13.0","",    2);
        feNpm("design-chakra",       "@chakra-ui/react",                "^2.8.2",  "",    0);
        feNpm("design-chakra",       "@emotion/react",                  "^11.13.0","",    1);
        feNpm("design-chakra",       "@emotion/styled",                 "^11.13.0","",    2);
        feNpm("design-chakra",       "framer-motion",                   "^11.3.31","",    3);
        feNpm("design-mantine",      "@mantine/core",                   "^7.13.0", "",    0);
        feNpm("design-mantine",      "@mantine/hooks",                  "^7.13.0", "",    1);

        feNpm("form-react-hook-form","react-hook-form",                 "^7.53.0", "",    0);
        feNpm("form-zod",            "zod",                             "^3.23.8", "",    0);
        feNpm("form-zod",            "@hookform/resolvers",             "^3.9.0",  "",    1);

        feNpm("anim-framer-motion",  "framer-motion",                   "^11.3.31","",    0);

        feNpm("test-vitest-rtl",     "vitest",                          "^2.0.5",  "dev", 0);
        feNpm("test-vitest-rtl",     "@vitest/ui",                      "^2.0.5",  "dev", 1);
        feNpm("test-vitest-rtl",     "@testing-library/react",          "^16.0.1", "dev", 2);
        feNpm("test-vitest-rtl",     "@testing-library/jest-dom",       "^6.5.0",  "dev", 3);
        feNpm("test-vitest-rtl",     "jsdom",                           "^25.0.0", "dev", 4);
        feNpm("test-playwright",     "@playwright/test",                "^1.46.1", "dev", 0);
        feNpm("test-msw",            "msw",                             "^2.4.2",  "dev", 0);

        feNpm("i18n-react-i18next",  "react-i18next",                   "^15.0.1", "",    0);
        feNpm("i18n-react-i18next",  "i18next",                         "^23.14.0","",    1);

        feNpm("storybook",           "storybook",                       "^8.2.9",  "dev", 0);
        feNpm("storybook",           "@storybook/react-vite",           "^8.2.9",  "dev", 1);
        feNpm("storybook",           "@storybook/react",                "^8.2.9",  "dev", 2);

        feNpm("auth-msal",           "@azure/msal-browser",             "^3.21.0", "",    0);
        feNpm("auth-msal",           "@azure/msal-react",               "^2.0.22", "",    1);

        feNpm("chart-recharts",      "recharts",                        "^2.12.7", "",    0);

        // ── Sub-options (v1: metadata only; file gating can be added per row later) ──
        feSubOption("router-react-router", "lazy-routes",      "Lazy routes",
                "Use React.lazy() + Suspense for code-split routes", 0);
        feSubOption("router-react-router", "error-boundary",   "Error boundary",
                "Wrap routes in an ErrorBoundary for graceful failures", 1);
        feSubOption("router-react-router", "sample-routes",    "Sample routes",
                "Generate a demo router config with /home and /about", 2);

        feSubOption("state-zustand", "devtools",      "Redux DevTools middleware",
                "Wire zustand to the Redux DevTools browser extension", 0);
        feSubOption("state-zustand", "persist",       "Persist middleware",
                "Add zustand/middleware persist for localStorage rehydration", 1);
        feSubOption("state-zustand", "sample-store",  "Sample store",
                "Generate a counter store under src/entities/counter", 2);

        feSubOption("state-redux-toolkit", "sample-store", "Sample slice + store",
                "Generate a counter slice + configured store", 0);

        feSubOption("data-tanstack-query", "devtools",       "Devtools",
                "Include @tanstack/react-query-devtools setup", 0);
        feSubOption("data-tanstack-query", "axios-base",     "Axios base client",
                "Configure an Axios instance under shared/api", 1);
        feSubOption("data-tanstack-query", "sample-query",   "Sample query hook",
                "Generate a useUsers() hook example", 2);

        feSubOption("style-tailwind", "dark-mode",   "Dark mode (class strategy)",
                "Configure Tailwind dark mode via class attribute", 0);

        feSubOption("form-react-hook-form", "rhf-zod-resolver", "Use Zod resolver",
                "Wire @hookform/resolvers to validate with Zod schemas", 0);
        feSubOption("form-react-hook-form", "sample-form",      "Sample form",
                "Generate a demo signup form component", 1);

        feSubOption("test-vitest-rtl", "sample-tests",  "Sample tests",
                "Generate a sample render test for HomePage", 0);
        feSubOption("test-vitest-rtl", "ci-config",     "CI config",
                "Generate a GitHub Actions workflow that runs the test suite", 1);

        // ── Compatibility rules ──────────────────────────────────────────────
        feCompat("router-react-router", "router-tanstack",     DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Only one router can be selected", 0);
        feCompat("state-zustand", "state-redux-toolkit",       DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one state-management library", 0);
        feCompat("state-zustand", "state-jotai",               DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one state-management library", 1);
        feCompat("state-redux-toolkit", "state-jotai",         DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one state-management library", 2);
        feCompat("data-tanstack-query", "data-swr",            DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one data-fetching library", 0);
        feCompat("design-shadcn", "style-tailwind",            DependencyCompatibilityEntity.RelationType.REQUIRES,
                "shadcn/ui is built on Tailwind CSS", 0);
        // Design systems are mutually exclusive (UI enforces single-choice; this is a backend safety net)
        feCompat("design-shadcn",  "design-mui",               DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 0);
        feCompat("design-shadcn",  "design-chakra",            DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 1);
        feCompat("design-shadcn",  "design-mantine",           DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 2);
        feCompat("design-mui",     "design-chakra",            DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 3);
        feCompat("design-mui",     "design-mantine",           DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 4);
        feCompat("design-chakra",  "design-mantine",           DependencyCompatibilityEntity.RelationType.CONFLICTS,
                "Pick one design system", 5);
        feCompat("form-react-hook-form", "form-zod",           DependencyCompatibilityEntity.RelationType.RECOMMENDS,
                "Pair RHF with Zod via @hookform/resolvers for typed validation", 0);

        log.info("Seeded frontend catalog (FRONTEND kind)");
    }

    // ── Frontend seed helpers ──────────────────────────────────────────────────

    private DependencyGroupEntity feGroup(String name, int order) {
        DependencyGroupEntity g = new DependencyGroupEntity();
        g.setName(name);
        g.setSortOrder(order);
        g.setProjectKind(ProjectKind.FRONTEND);
        return groupRepo.save(g);
    }

    private void feEntry(DependencyGroupEntity group, String depId, String name, String desc, int order) {
        DependencyEntryEntity e = new DependencyEntryEntity();
        e.setGroup(group);
        e.setDepId(depId);
        e.setName(name);
        e.setDescription(desc);
        e.setSortOrder(order);
        e.setStarter(true);
        e.setProjectKind(ProjectKind.FRONTEND);
        entryRepo.save(e);
    }

    private void feFc(String depId, FileContributionEntity.FileType fileType, String content,
                       String targetPath, FileContributionEntity.SubstitutionType subType, int order) {
        FileContributionEntity e = new FileContributionEntity();
        e.setDependencyId(depId);
        e.setFileType(fileType);
        e.setContent(content);
        e.setTargetPath(targetPath);
        e.setSubstitutionType(subType);
        e.setSortOrder(order);
        e.setProjectKind(ProjectKind.FRONTEND);
        fileContribRepo.save(e);
    }

    private void feNpm(String depId, String pkg, String version, String scope, int order) {
        BuildCustomizationEntity e = new BuildCustomizationEntity();
        e.setDependencyId(depId);
        e.setCustomizationType(BuildCustomizationEntity.CustomizationType.ADD_NPM_DEPENDENCY);
        e.setMavenArtifactId(pkg);
        e.setVersion(version);
        e.setScope(scope);
        e.setSortOrder(order);
        e.setProjectKind(ProjectKind.FRONTEND);
        buildCustomRepo.save(e);
    }

    private void feVitePlugin(String depId, String importPath, String importBinding,
                               String pluginCall, int order) {
        BuildCustomizationEntity e = new BuildCustomizationEntity();
        e.setDependencyId(depId);
        e.setCustomizationType(BuildCustomizationEntity.CustomizationType.ADD_VITE_PLUGIN);
        e.setMavenGroupId(importPath);
        e.setMavenArtifactId(importBinding);
        e.setVersion(pluginCall);
        e.setSortOrder(order);
        e.setProjectKind(ProjectKind.FRONTEND);
        buildCustomRepo.save(e);
    }

    private void feSubOption(String depId, String optionId, String label, String desc, int order) {
        DependencySubOptionEntity e = new DependencySubOptionEntity();
        e.setDependencyId(depId);
        e.setOptionId(optionId);
        e.setLabel(label);
        e.setDescription(desc);
        e.setSortOrder(order);
        e.setProjectKind(ProjectKind.FRONTEND);
        subOptionRepo.save(e);
    }

    private void feCompat(String source, String target,
                          DependencyCompatibilityEntity.RelationType type,
                          String desc, int order) {
        DependencyCompatibilityEntity e = new DependencyCompatibilityEntity();
        e.setSourceDepId(source);
        e.setTargetDepId(target);
        e.setRelationType(type);
        e.setDescription(desc);
        e.setSortOrder(order);
        e.setProjectKind(ProjectKind.FRONTEND);
        compatibilityRepo.save(e);
    }
}
