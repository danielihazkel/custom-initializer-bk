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

    public DataSeeder(DependencyGroupRepository groupRepo,
                      DependencyEntryRepository entryRepo,
                      FileContributionRepository fileContribRepo,
                      BuildCustomizationRepository buildCustomRepo,
                      DependencySubOptionRepository subOptionRepo) {
        this.groupRepo = groupRepo;
        this.entryRepo = entryRepo;
        this.fileContribRepo = fileContribRepo;
        this.buildCustomRepo = buildCustomRepo;
        this.subOptionRepo = subOptionRepo;
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

        DependencyGroupEntity web = group("Web", 1);
        entry(web, "web", "Spring Web", "Build web applications with Spring MVC",
                null, null, null, null, null, 0);
        entry(web, "webflux", "Spring Reactive Web", "Build reactive web applications with Spring WebFlux",
                null, null, null, null, null, 1);

        DependencyGroupEntity data = group("Data", 2);
        entry(data, "data-jpa", "Spring Data JPA", "Persist data with JPA and Hibernate",
                null, null, null, null, null, 0);
        entry(data, "postgresql", "PostgreSQL Driver", null,
                "org.postgresql", "postgresql", null, "runtime", null, 1);

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

        // data-jpa
        fc("data-jpa", FileContributionEntity.FileType.YAML_MERGE,
                readClasspath("static-configs/jpa/application-jpa.yml"),
                "src/main/resources/application.yaml",
                FileContributionEntity.SubstitutionType.NONE, null, null, 0);
        fc("data-jpa", FileContributionEntity.FileType.TEMPLATE,
                readClasspath("templates/jpa-config.mustache"),
                "src/main/java/{{packagePath}}/config/JpaConfig.java",
                FileContributionEntity.SubstitutionType.PACKAGE, null, null, 1);

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
    }

    private void seedSubOptions() {
        subOption("kafka", "consumer-example", "Consumer Example",
                "Add a KafkaConsumerExample.java class", 0);
        subOption("kafka", "producer-example", "Producer Example",
                "Add a KafkaProducerExample.java class", 1);
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

    private void subOption(String depId, String optionId, String label, String desc, int order) {
        DependencySubOptionEntity e = new DependencySubOptionEntity();
        e.setDependencyId(depId);
        e.setOptionId(optionId);
        e.setLabel(label);
        e.setDescription(desc);
        e.setSortOrder(order);
        subOptionRepo.save(e);
    }

    private String readClasspath(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IOException("Classpath resource not found: " + path);
        }
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
