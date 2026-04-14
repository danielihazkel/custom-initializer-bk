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

    public DataSeeder(DependencyGroupRepository groupRepo,
                      DependencyEntryRepository entryRepo,
                      FileContributionRepository fileContribRepo,
                      BuildCustomizationRepository buildCustomRepo,
                      DependencySubOptionRepository subOptionRepo,
                      DependencyCompatibilityRepository compatibilityRepo,
                      StarterTemplateRepository templateRepo,
                      StarterTemplateDepRepository templateDepRepo,
                      ModuleTemplateRepository moduleRepo,
                      ModuleDependencyMappingRepository moduleMappingRepo) {
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
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (groupRepo.count() > 0) {
            log.info("Database already seeded — skipping DataSeeder");
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
        log.info("Database seeding complete");
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
    }

    // ── Common file contributions (every project) ────────────────────────────

    private void seedCommonFileContributions() throws IOException {
        int order = 0;

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
                FileContributionEntity.SubstitutionType.PROJECT, null, null, order++);

        // Dockerfile — version-specific
        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/Dockerfile-java17.mustache"),
                "Dockerfile",
                FileContributionEntity.SubstitutionType.PROJECT, "17", null, order++);

        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/Dockerfile-java21.mustache"),
                "Dockerfile",
                FileContributionEntity.SubstitutionType.PROJECT, "21", null, order++);

        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/Jenkinsfile.mustache"),
                "k8s/Jenkinsfile",
                FileContributionEntity.SubstitutionType.PROJECT, null, null, order++);

        fc(DependencyConfigService.COMMON_ID, FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/k8s-values.mustache"),
                "k8s/values.yaml",
                FileContributionEntity.SubstitutionType.PROJECT, null, null, order++);

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
                FileContributionEntity.SubstitutionType.PACKAGE, null, null, 1);
        fc("kafka", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/kafka-consumer-example.mustache"),
                "src/main/java/{{packagePath}}/config/KafkaConsumerExample.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "consumer-example", 2);
        fc("kafka", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/kafka-producer-example.mustache"),
                "src/main/java/{{packagePath}}/config/KafkaProducerExample.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "producer-example", 3);

        // security
        fc("security", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/security/application-security.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("security", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/security-config.mustache"),
                "src/main/java/{{packagePath}}/config/SecurityConfig.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, null, 1);

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
                FileContributionEntity.SubstitutionType.PACKAGE, null, "pg-primary", 1);
        fc("postgresql", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/postgresql-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/PostgresqlConfig.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "pg-secondary", 2);

        // MSSQL
        fc("mssql", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/mssql/application-mssql.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("mssql", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mssql-config-primary.mustache"),
                "src/main/java/{{packagePath}}/config/MssqlConfig.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "mssql-primary", 1);
        fc("mssql", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mssql-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/MssqlConfig.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "mssql-secondary", 2);

        // DB2
        fc("db2", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/db2/application-db2.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("db2", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/db2-config-primary.mustache"),
                "src/main/java/{{packagePath}}/config/Db2Config.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "db2-primary", 1);
        fc("db2", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/db2-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/Db2Config.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "db2-secondary", 2);

        // Oracle
        fc("oracle", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/oracle/application-oracle.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("oracle", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/oracle-config-primary.mustache"),
                "src/main/java/{{packagePath}}/config/OracleConfig.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "oracle-primary", 1);
        fc("oracle", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/oracle-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/OracleConfig.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "oracle-secondary", 2);

        // H2
        fc("h2", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/h2/application-h2.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("h2", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/h2-config-primary.mustache"),
                "src/main/java/{{packagePath}}/config/H2Config.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "h2-primary", 1);
        fc("h2", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/h2-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/H2Config.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "h2-secondary", 2);

        // MongoDB
        fc("mongodb", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/mongodb/application-mongodb.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("mongodb", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mongodb-config-primary.mustache"),
                "src/main/java/{{packagePath}}/config/MongoConfig.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "mongodb-primary", 1);
        fc("mongodb", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mongodb-config-secondary.mustache"),
                "src/main/java/{{packagePath}}/config/MongoConfig.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "mongodb-secondary", 2);

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
                FileContributionEntity.SubstitutionType.PACKAGE, null, null, 1);

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
                FileContributionEntity.SubstitutionType.PACKAGE, null, null, 1);
        fc("mail-sampler", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mail-service.mustache"),
                "src/main/java/{{packagePath}}/service/MailService.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "send-mail", 2);
        fc("mail-sampler", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/mail-inbox-reader.mustache"),
                "src/main/java/{{packagePath}}/service/InboxReaderService.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "inbox-reader", 3);

        // file-handler-utils — file-only option (no pom dependency)
        fc("file-handler-utils", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/file-handler-sync.mustache"),
                "src/main/java/{{packagePath}}/util/SyncFileHandler.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "sync-handler", 0);
        fc("file-handler-utils", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/file-handler-async.mustache"),
                "src/main/java/{{packagePath}}/util/AsyncFileHandler.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, "async-handler", 1);
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
}
