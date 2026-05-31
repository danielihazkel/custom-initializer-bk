package com.menora.initializr.db;

import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.DependencyCompatibilityEntity;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.BuildCustomizationRepository;
import com.menora.initializr.db.repository.ColorPaletteRepository;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import com.menora.initializr.db.repository.DependencyEntryRepository;
import com.menora.initializr.db.repository.DependencyGroupRepository;
import com.menora.initializr.db.repository.DependencySubOptionRepository;
import com.menora.initializr.db.repository.FileContributionRepository;
import com.menora.initializr.db.repository.ModuleDependencyMappingRepository;
import com.menora.initializr.db.repository.ModuleTemplateRepository;
import com.menora.initializr.db.repository.StarterTemplateRepository;
import com.menora.initializr.db.repository.VersionDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization test for {@link DataSeeder}.
 *
 * <p>Asserts the <em>observable result</em> of seeding — table contents and counts —
 * independent of <em>how</em> the seed data is expressed in code. This is the regression
 * oracle that lets the seeder be refactored (e.g. moving the catalog into JSON manifests)
 * with confidence: the data must come out identical, so these assertions must stay green
 * with zero changes across that refactor.
 *
 * <p>The test relies on {@code DataSeeder} having already run at application startup
 * (see {@code src/test/resources/application.properties}, in-memory H2).
 */
@SpringBootTest
class DataSeederTest {

    @Autowired private DependencyGroupRepository groupRepo;
    @Autowired private DependencyEntryRepository entryRepo;
    @Autowired private FileContributionRepository fileContribRepo;
    @Autowired private BuildCustomizationRepository buildCustomRepo;
    @Autowired private DependencySubOptionRepository subOptionRepo;
    @Autowired private DependencyCompatibilityRepository compatibilityRepo;
    @Autowired private StarterTemplateRepository templateRepo;
    @Autowired private ModuleTemplateRepository moduleRepo;
    @Autowired private ModuleDependencyMappingRepository moduleMappingRepo;
    @Autowired private ColorPaletteRepository colorPaletteRepo;
    @Autowired private VersionDefinitionRepository versionRepo;

    // ── Dependency catalog (backend) ───────────────────────────────────────────

    @Test
    void seedsBackendDependencyGroupsInOrder() {
        List<String> backendGroupNames = groupRepo
                .findAllByProjectKindOrderBySortOrderAsc(ProjectKind.BACKEND)
                .stream().map(g -> g.getName()).toList();

        assertThat(backendGroupNames).containsExactly(
                "Menora Standards", "Web", "Data", "Messaging", "Security",
                "Observability", "Logging", "Communication", "Utilities");
    }

    @Test
    void seedsAllBackendDependencyEntries() {
        assertThat(entryRepo.findAllByProjectKind(ProjectKind.BACKEND)).hasSize(27);
    }

    @Test
    void rqueueEntryHasMavenCoordinatesAndCompatibilityRange() {
        DependencyEntryEntity rqueue = entryRepo.findByDepId("rqueue").orElseThrow();
        assertThat(rqueue.getMavenGroupId()).isEqualTo("com.sonus");
        assertThat(rqueue.getMavenArtifactId()).isEqualTo("sonus-rqueue");
        assertThat(rqueue.getVersion()).isEqualTo("1.0.0");
        assertThat(rqueue.getRepository()).isEqualTo("menora-release");
        assertThat(rqueue.getCompatibilityRange()).isEqualTo("[3.2.0,4.0.0)");
    }

    @Test
    void boot4SensitiveEntriesHaveCompatibilityRanges() {
        assertThat(entryRepo.findByDepId("openapi").orElseThrow().getCompatibilityRange())
                .isEqualTo("[3.2.0,4.0.0)");
        assertThat(entryRepo.findByDepId("resilience4j").orElseThrow().getCompatibilityRange())
                .isEqualTo("[3.2.0,4.0.0)");
    }

    @Test
    void fileHandlerUtilsIsNotAStarter() {
        assertThat(entryRepo.findByDepId("file-handler-utils").orElseThrow().isStarter()).isFalse();
    }

    // ── File contributions ──────────────────────────────────────────────────────

    @Test
    void seedsCommonBackendFileContributions() {
        // application-base, log4j2, .editorconfig, entrypoint.sh, settings.xml, VERSION,
        // Dockerfile-java17, Dockerfile-java21, Jenkinsfile, k8s/values.yaml,
        // + DELETE application.properties. (countByDependencyId would also count the
        // FRONTEND __common__ rows, so scope to BACKEND.)
        assertThat(fileContribRepo.findByDependencyIdInAndProjectKindOrderBySortOrderAsc(
                java.util.Set.of(DependencyConfigService.COMMON_ID), ProjectKind.BACKEND)).hasSize(11);
    }

    @Test
    void kafkaFileContributionsSeeded() {
        // application-kafka YAML, KafkaConfig, consumer-example, producer-example, streams-example
        assertThat(fileContribRepo.countByDependencyId("kafka")).isEqualTo(5);
    }

    @Test
    void securityFileContributionsSeeded() {
        // application-security YAML, SecurityConfig, jwt-filter, jwt-service, method-config
        assertThat(fileContribRepo.countByDependencyId("security")).isEqualTo(5);
    }

    @Test
    void applicationPropertiesIsDeletedAtLowestPrecedence() {
        FileContributionEntity delete = fileContribRepo
                .findByDependencyIdInAndProjectKindOrderBySortOrderAsc(
                        java.util.Set.of(DependencyConfigService.COMMON_ID), ProjectKind.BACKEND)
                .stream()
                .filter(f -> f.getFileType() == FileContributionEntity.FileType.DELETE)
                .findFirst().orElseThrow();
        assertThat(delete.getTargetPath()).isEqualTo("src/main/resources/application.properties");
        assertThat(delete.getSortOrder()).isEqualTo(9999);
    }

    // ── Build customizations ──────────────────────────────────────────────────

    @Test
    void commonBuildCustomizationsSeeded() {
        // menora-release repo, menora-snapshot repo, exclude logging, add log4j2, add lombok
        List<BuildCustomizationEntity> common = buildCustomRepo
                .findByDependencyIdInAndProjectKindOrderBySortOrderAsc(
                        java.util.Set.of(DependencyConfigService.COMMON_ID), ProjectKind.BACKEND);
        assertThat(common).hasSize(5);

        assertThat(common).anySatisfy(c -> {
            assertThat(c.getCustomizationType())
                    .isEqualTo(BuildCustomizationEntity.CustomizationType.ADD_REPOSITORY);
            assertThat(c.getRepoUrl()).isEqualTo("https://repo.menora.co.il/artifactory/libs-release");
        });
        assertThat(common).anySatisfy(c ->
                assertThat(c.getMavenArtifactId()).isEqualTo("spring-boot-starter-log4j2"));
        assertThat(common).anySatisfy(c ->
                assertThat(c.getMavenArtifactId()).isEqualTo("lombok"));
    }

    // ── Sub-options ──────────────────────────────────────────────────────────

    @Test
    void kafkaSubOptionsSeeded() {
        List<String> optionIds = subOptionRepo.findAll().stream()
                .filter(o -> "kafka".equals(o.getDependencyId()))
                .map(o -> o.getOptionId()).toList();
        assertThat(optionIds).contains("consumer-example", "producer-example", "streams-example");
    }

    // ── Compatibility rules ─────────────────────────────────────────────────

    @Test
    void seedsAllBackendCompatibilityRules() {
        long backendRules = compatibilityRepo.findAll().stream()
                .filter(c -> c.getProjectKind() == ProjectKind.BACKEND)
                .count();
        assertThat(backendRules).isEqualTo(11);
    }

    @Test
    void securityRequiresWebAndWebConflictsWebflux() {
        List<DependencyCompatibilityEntity> all = compatibilityRepo.findAll();
        assertThat(all).anySatisfy(c -> {
            assertThat(c.getSourceDepId()).isEqualTo("security");
            assertThat(c.getTargetDepId()).isEqualTo("web");
            assertThat(c.getRelationType())
                    .isEqualTo(DependencyCompatibilityEntity.RelationType.REQUIRES);
        });
        assertThat(all).anySatisfy(c -> {
            assertThat(c.getSourceDepId()).isEqualTo("web");
            assertThat(c.getTargetDepId()).isEqualTo("webflux");
            assertThat(c.getRelationType())
                    .isEqualTo(DependencyCompatibilityEntity.RelationType.CONFLICTS);
        });
    }

    // ── Templates, modules, palettes, versions ──────────────────────────────

    @Test
    void seedsStarterTemplates() {
        // 3 backend (rest-api, event-driven, microservice) + 3 frontend (fe-*)
        assertThat(templateRepo.findAll()).hasSize(6);
    }

    @Test
    void seedsModuleTemplatesAndMappings() {
        assertThat(moduleRepo.findAll()).hasSize(3);       // api, core, persistence
        assertThat(moduleMappingRepo.findAll()).hasSize(6); // api:3, persistence:2, core:1
    }

    @Test
    void seedsColorPalettes() {
        assertThat(colorPaletteRepo.findAll()).hasSize(9);
    }

    @Test
    void seedsVersionDefinitions() {
        // java 2, boot 1, react 2, node 3, package-manager 2
        assertThat(versionRepo.findAll()).hasSize(10);
    }

    // ── Frontend catalog ──────────────────────────────────────────────────────

    @Test
    void seedsFrontendDependencyGroups() {
        assertThat(groupRepo.findAllByProjectKindOrderBySortOrderAsc(ProjectKind.FRONTEND)).hasSize(11);
    }
}
