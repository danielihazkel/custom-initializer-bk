package com.menora.initializr.db;

import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.DependencyCompatibilityEntity;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.BuildCustomizationRepository;
import com.menora.initializr.db.repository.ColorPaletteRepository;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import com.menora.initializr.db.repository.DependencyEntryRepository;
import com.menora.initializr.db.repository.DependencyGroupRepository;
import com.menora.initializr.db.repository.DependencySubOptionRepository;
import com.menora.initializr.db.repository.EntityTemplateFileRepository;
import com.menora.initializr.db.repository.EntityTemplateSetDefaultDepRepository;
import com.menora.initializr.db.repository.EntityTemplateSetRepository;
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
    @Autowired private EntityTemplateSetRepository templateSetRepo;
    @Autowired private EntityTemplateFileRepository templateFileRepo;
    @Autowired private EntityTemplateSetDefaultDepRepository defaultDepRepo;

    // ── Dependency catalog (backend) ───────────────────────────────────────────

    @Test
    void seedsBackendDependencyGroupsInOrder() {
        List<String> backendGroupNames = groupRepo
                .findAllByProjectKindOrderBySortOrderAsc(ProjectKind.BACKEND)
                .stream().map(g -> g.getName()).toList();

        assertThat(backendGroupNames).containsExactly(
                "Menora Standards", "Web", "Data", "Messaging", "Security",
                "Observability", "Communication", "Utilities");
    }

    @Test
    void seedsAllBackendDependencyEntries() {
        assertThat(entryRepo.findAllByProjectKind(ProjectKind.BACKEND)).hasSize(29);
    }

    @Test
    void ldapAuthEntryIsNotAStarterAndHasCompatibilityRange() {
        DependencyEntryEntity ldap = entryRepo.findByDepId("ldap-auth").orElseThrow();
        assertThat(ldap.isStarter()).isFalse();
        assertThat(ldap.getCompatibilityRange()).isEqualTo("[3.2.0,4.0.0)");
    }

    @Test
    void rqueueEntryHasMavenCoordinatesAndCompatibilityRange() {
        DependencyEntryEntity rqueue = entryRepo.findByDepId("rqueue").orElseThrow();
        assertThat(rqueue.getMavenGroupId()).isEqualTo("com.sonus");
        assertThat(rqueue.getMavenArtifactId()).isEqualTo("sonus-rqueue");
        assertThat(rqueue.getVersion()).isEqualTo("3.0.0-RELEASE");
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

    @Test
    @org.springframework.transaction.annotation.Transactional
    void loggingEntryLivesInMenoraStandardsGroup() {
        DependencyEntryEntity logging = entryRepo.findByDepId("logging").orElseThrow();
        assertThat(logging.getGroup().getName()).isEqualTo("Menora Standards");
    }

    @Test
    void loggingIsNotAStarter() {
        // Base JSON logging (log4j2 + JSON layout) now ships via __common__; the `logging`
        // dep is the opt-in Kafka log-shipping appender and adds only kafka-clients, so it
        // must not be a starter that pulls its own pom <dependency> marker.
        assertThat(entryRepo.findByDepId("logging").orElseThrow().isStarter()).isFalse();
    }

    @Test
    void baseJsonLoggingContributedByCommon() {
        // Menora JSON logging is a default: logFormat.json, detailedLogFormat.json, and
        // log4j2-spring.xml (all TEMPLATE) live under __common__, not the `logging` dep.
        List<FileContributionEntity> common = fileContribRepo
                .findByDependencyIdInAndProjectKindOrderBySortOrderAsc(
                        java.util.Set.of(DependencyConfigService.COMMON_ID), ProjectKind.BACKEND);
        assertThat(common).extracting(FileContributionEntity::getTargetPath).contains(
                "src/main/resources/logFormat.json",
                "src/main/resources/detailedLogFormat.json",
                "src/main/resources/log4j2-spring.xml");

        // The `logging` dep itself no longer contributes any files.
        assertThat(fileContribRepo.findByDependencyIdInAndProjectKindOrderBySortOrderAsc(
                java.util.Set.of("logging"), ProjectKind.BACKEND)).isEmpty();
    }

    @Test
    void loggingBuildCustomizationsSeeded() {
        // log4j-layout-template-json is a __common__ default (JSON layout ships by default);
        // the `logging` dep adds only kafka-clients, unconditionally (no sub-option gate).
        List<BuildCustomizationEntity> common = buildCustomRepo
                .findByDependencyIdInAndProjectKindOrderBySortOrderAsc(
                        java.util.Set.of(DependencyConfigService.COMMON_ID), ProjectKind.BACKEND);
        assertThat(common).anySatisfy(c -> {
            assertThat(c.getMavenArtifactId()).isEqualTo("log4j-layout-template-json");
            assertThat(c.getSubOptionId()).isNull();
        });

        List<BuildCustomizationEntity> logging = buildCustomRepo
                .findByDependencyIdInAndProjectKindOrderBySortOrderAsc(
                        java.util.Set.of("logging"), ProjectKind.BACKEND);
        assertThat(logging).singleElement().satisfies(c -> {
            assertThat(c.getMavenArtifactId()).isEqualTo("kafka-clients");
            assertThat(c.getSubOptionId()).isNull();
        });
    }

    // ── File contributions ──────────────────────────────────────────────────────

    @Test
    void seedsCommonBackendFileContributions() {
        // application-base, .editorconfig, entrypoint.sh, settings.xml, VERSION,
        // Dockerfile-java17, Dockerfile-java21, Jenkinsfile, k8s/values.yaml,
        // + Menora JSON logging defaults: logFormat.json, detailedLogFormat.json, log4j2-spring.xml,
        // + DELETE application.properties, + DELETE mvnw / mvnw.cmd /
        // .mvn/wrapper/maven-wrapper.properties (strip the framework's Maven wrapper).
        // (countByDependencyId would also count the FRONTEND __common__ rows, so scope to BACKEND.)
        assertThat(fileContribRepo.findByDependencyIdInAndProjectKindOrderBySortOrderAsc(
                java.util.Set.of(DependencyConfigService.COMMON_ID), ProjectKind.BACKEND)).hasSize(16);
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
    void ldapAuthFileContributionsSeeded() {
        // application-ldap-auth YAML + LdapConfigurationProperties, LdapConfiguration, Base64Utils,
        // LdapService, PermissionService, PermissionAspect, Constants, RequiresPermission,
        // UnauthorizedException + sample-controller
        assertThat(fileContribRepo.countByDependencyId("ldap-auth")).isEqualTo(11);
    }

    @Test
    void ldapAuthRestFileContributionsSeeded() {
        // application-ldap-auth-rest YAML + REST LdapService, PermissionService, PermissionAspect,
        // Constants, RequiresPermission, UnauthorizedException + sample-controller (no direct-LDAP
        // config/properties/Base64Utils — resolved over REST instead).
        assertThat(fileContribRepo.countByDependencyId("ldap-auth-rest")).isEqualTo(8);
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
        // menora-release repo, menora-snapshot repo, exclude logging, add log4j2, add lombok,
        // add log4j-layout-template-json (JSON logging is now a default)
        List<BuildCustomizationEntity> common = buildCustomRepo
                .findByDependencyIdInAndProjectKindOrderBySortOrderAsc(
                        java.util.Set.of(DependencyConfigService.COMMON_ID), ProjectKind.BACKEND);
        assertThat(common).hasSize(6);

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
        assertThat(backendRules).isEqualTo(13);
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
        assertThat(colorPaletteRepo.findAll()).hasSize(10);
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

    @Test
    void seedsAllFrontendDependencyEntries() {
        assertThat(entryRepo.findAllByProjectKind(ProjectKind.FRONTEND)).hasSize(29);
    }

    @Test
    void seedsFrontendFileContributions() {
        long feFileContribs = fileContribRepo.findAll().stream()
                .filter(f -> f.getProjectKind() == ProjectKind.FRONTEND)
                .count();
        assertThat(feFileContribs).isEqualTo(98);

        // The FSD layer barrels are FRONTEND __common__ rows.
        assertThat(fileContribRepo.findAll()).anySatisfy(f -> {
            assertThat(f.getProjectKind()).isEqualTo(ProjectKind.FRONTEND);
            assertThat(f.getDependencyId()).isEqualTo(DependencyConfigService.COMMON_ID);
            assertThat(f.getTargetPath()).isEqualTo("src/app/index.ts");
        });
    }

    @Test
    void seedsFrontendBuildCustomizationsByType() {
        List<BuildCustomizationEntity> fe = buildCustomRepo.findAll().stream()
                .filter(b -> b.getProjectKind() == ProjectKind.FRONTEND)
                .toList();

        assertThat(fe).filteredOn(b -> b.getCustomizationType()
                == BuildCustomizationEntity.CustomizationType.ADD_NPM_DEPENDENCY).hasSize(70);
        assertThat(fe).filteredOn(b -> b.getCustomizationType()
                == BuildCustomizationEntity.CustomizationType.ADD_VITE_PLUGIN).hasSize(1);
        assertThat(fe).filteredOn(b -> b.getCustomizationType()
                == BuildCustomizationEntity.CustomizationType.ADD_NPM_SCRIPT).hasSize(6);
    }

    @Test
    void zustandNpmDependencySeeded() {
        BuildCustomizationEntity zustand = buildCustomRepo.findAll().stream()
                .filter(b -> b.getProjectKind() == ProjectKind.FRONTEND)
                .filter(b -> b.getCustomizationType()
                        == BuildCustomizationEntity.CustomizationType.ADD_NPM_DEPENDENCY)
                .filter(b -> "state-zustand".equals(b.getDependencyId())
                        && "zustand".equals(b.getMavenArtifactId()))
                .findFirst().orElseThrow();
        assertThat(zustand.getVersion()).isEqualTo("^4.5.5");
        assertThat(zustand.getScope()).isNull();           // prod dep — blank scope coerced to null
    }

    @Test
    void viteReactPluginSeeded() {
        BuildCustomizationEntity plugin = buildCustomRepo.findAll().stream()
                .filter(b -> b.getCustomizationType()
                        == BuildCustomizationEntity.CustomizationType.ADD_VITE_PLUGIN)
                .findFirst().orElseThrow();
        assertThat(plugin.getMavenGroupId()).isEqualTo("@vitejs/plugin-react");  // import path
        assertThat(plugin.getMavenArtifactId()).isEqualTo("react");              // import binding
        assertThat(plugin.getVersion()).isEqualTo("react()");                    // plugin call
    }

    @Test
    void lintFixNpmScriptSeeded() {
        BuildCustomizationEntity script = buildCustomRepo.findAll().stream()
                .filter(b -> b.getCustomizationType()
                        == BuildCustomizationEntity.CustomizationType.ADD_NPM_SCRIPT)
                .filter(b -> "lint:fix".equals(b.getMavenArtifactId()))
                .findFirst().orElseThrow();
        assertThat(script.getVersion()).isEqualTo("eslint . --fix");
    }

    @Test
    void seedsFrontendSubOptions() {
        long feSubOptions = subOptionRepo.findAll().stream()
                .filter(o -> o.getProjectKind() == ProjectKind.FRONTEND)
                .count();
        assertThat(feSubOptions).isEqualTo(28);

        assertThat(subOptionRepo.findAll()).anySatisfy(o -> {
            assertThat(o.getDependencyId()).isEqualTo("design-shadcn");
            assertThat(o.getOptionId()).isEqualTo("comp-dialog");
        });
    }

    @Test
    void seedsFrontendCompatibilityRules() {
        List<DependencyCompatibilityEntity> fe = compatibilityRepo.findAll().stream()
                .filter(c -> c.getProjectKind() == ProjectKind.FRONTEND)
                .toList();
        assertThat(fe).hasSize(18);

        assertThat(fe).anySatisfy(c -> {
            assertThat(c.getSourceDepId()).isEqualTo("design-shadcn");
            assertThat(c.getTargetDepId()).isEqualTo("style-tailwind");
            assertThat(c.getRelationType())
                    .isEqualTo(DependencyCompatibilityEntity.RelationType.REQUIRES);
        });
    }

    @Test
    void frontendDesignSystemsHaveReactVersionRanges() {
        assertThat(entryRepo.findByDepId("design-mui").orElseThrow().getCompatibilityRange())
                .isEqualTo("[18.0.0,19.0.0)");
        assertThat(entryRepo.findByDepId("design-chakra").orElseThrow().getCompatibilityRange())
                .isEqualTo("[18.0.0,19.0.0)");
        assertThat(entryRepo.findByDepId("design-mantine").orElseThrow().getCompatibilityRange())
                .isEqualTo("[18.0.0,19.0.0)");
    }

    @Test
    void menoraDigitalDesignSystemIsPlainCssAndReactVersionAgnostic() {
        // Plain CSS tokens + component ports: no React-version range, one npm dep (the Assistant
        // font), the 15 content rows and a CONFLICTS pair with each of the other four design systems.
        DependencyEntryEntity entry = entryRepo.findByDepId("design-menora-digital").orElseThrow();
        assertThat(entry.getCompatibilityRange()).isNull();
        assertThat(entry.getProjectKind()).isEqualTo(ProjectKind.FRONTEND);

        assertThat(buildCustomRepo.findAll())
                .filteredOn(b -> "design-menora-digital".equals(b.getDependencyId()))
                .singleElement()
                .satisfies(b -> assertThat(b.getMavenArtifactId()).isEqualTo("@fontsource/assistant"));

        List<String> targets = fileContribRepo.findAll().stream()
                .filter(f -> "design-menora-digital".equals(f.getDependencyId()))
                .map(FileContributionEntity::getTargetPath)
                .toList();
        assertThat(targets).hasSize(15)
                .contains("src/shared/ui/menora/tokens.css", "src/shared/ui/menora/components.css",
                        "src/shared/ui/menora/Hero.tsx", "src/shared/ui/menora/index.ts",
                        "src/shared/ui/menora/useMenoraTheme.ts", "src/shared/ui/menora/ThemeToggle.tsx",
                        "src/index.css", "src/pages/home/ui/HomePage.tsx");

        assertThat(compatibilityRepo.findAll())
                .filteredOn(c -> "design-menora-digital".equals(c.getTargetDepId()))
                .extracting(DependencyCompatibilityEntity::getSourceDepId)
                .containsExactlyInAnyOrder("design-shadcn", "design-mui", "design-chakra", "design-mantine");
    }

    @Test
    void menoraDigitalFullstackSetBorrowsTheTailwindCrudFiles() {
        // The variant set authors only the theme/shell/dashboard/main/package files and borrows the
        // rest from react-tailwind-crud via sourceSet — so it must carry the same file paths.
        EntityTemplateSetEntity set = templateSetRepo.findBySetKey("react-menora-digital-crud").orElseThrow();
        assertThat(set.getKind()).isEqualTo(EntityTemplateSetEntity.Kind.FRONTEND_REACT);
        assertThat(set.getDesignSystem()).isEqualTo(EntityTemplateSetEntity.DesignSystem.MENORA_DIGITAL);

        Long tailwindId = templateSetRepo.findBySetKey("react-tailwind-crud").orElseThrow().getId();
        List<String> tailwindPaths = templateFileRepo.findBySetIdOrderBySortOrderAsc(tailwindId).stream()
                .map(f -> f.getPathTemplate()).toList();
        List<String> menoraPaths = templateFileRepo.findBySetIdOrderBySortOrderAsc(set.getId()).stream()
                .map(f -> f.getPathTemplate()).toList();
        assertThat(menoraPaths).containsExactlyInAnyOrderElementsOf(tailwindPaths);

        // Borrowed content is copied verbatim at seed time; authored content differs.
        assertThat(templateFileRepo.findBySetIdOrderBySortOrderAsc(set.getId()))
                .filteredOn(f -> f.getPathTemplate().equals("src/shared/ui/Table.tsx"))
                .singleElement()
                .satisfies(f -> assertThat(f.getContent()).contains("onSortChange"));
        assertThat(templateFileRepo.findBySetIdOrderBySortOrderAsc(set.getId()))
                .filteredOn(f -> f.getPathTemplate().equals("src/index.css"))
                .singleElement()
                .satisfies(f -> assertThat(f.getContent()).contains("--color-primary:      #ffc700"));
    }

    // ── Fullstack template-set default deps ─────────────────────────────────────

    @Test
    void backendFullstackSetsDefaultToLdapAuth() {
        for (String setKey : List.of("spring-jpa-crud", "spring-jpa-crud-lombok")) {
            Long setId = templateSetRepo.findBySetKey(setKey).orElseThrow().getId();
            List<String> deps = defaultDepRepo.findBySetIdOrderBySortOrderAsc(setId).stream()
                    .map(d -> d.getDepId()).toList();
            assertThat(deps)
                    .as("default deps for %s", setKey)
                    .containsExactly("data-jpa", "web", "h2", "validation", "actuator", "ldap-auth");
        }
    }
}
