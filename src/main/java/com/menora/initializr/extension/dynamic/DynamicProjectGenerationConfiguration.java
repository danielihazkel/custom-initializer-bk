package com.menora.initializr.extension.dynamic;

import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import io.spring.initializr.generator.buildsystem.Dependency;
import io.spring.initializr.generator.buildsystem.MavenRepository;
import io.spring.initializr.generator.buildsystem.maven.MavenBuild;
import io.spring.initializr.generator.project.ProjectDescription;
import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import io.spring.initializr.generator.spring.build.BuildCustomizer;
import io.spring.initializr.generator.project.contributor.ProjectContributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single generic ProjectGenerationConfiguration that replaces all per-dependency
 * configuration classes. Reads file contributions and build customizations from
 * the database at generation time.
 */
@ProjectGenerationConfiguration
public class DynamicProjectGenerationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DynamicProjectGenerationConfiguration.class);

    @Bean
    ProjectContributor dynamicFileContributor(
            ProjectDescription description,
            DependencyConfigService configService,
            ProjectOptionsContext optionsContext) {
        return projectRoot -> {
            Set<String> depIds = selectedDepIds(description);
            log.info("generation: selectedDepIds={}", depIds);
            List<FileContributionEntity> contributions = configService.getFileContributions(depIds);

            for (FileContributionEntity fc : contributions) {
                // Skip if gated on a sub-option that wasn't selected
                if (fc.getSubOptionId() != null
                        && !optionsContext.hasOption(fc.getDependencyId(), fc.getSubOptionId())) {
                    log.debug("skip fc id={} dep={} target={}: sub-option '{}' not selected",
                            fc.getId(), fc.getDependencyId(), fc.getTargetPath(), fc.getSubOptionId());
                    continue;
                }
                // Skip if gated on a Java version that doesn't match
                if (fc.getJavaVersion() != null
                        && !fc.getJavaVersion().equals(description.getLanguage().jvmVersion())) {
                    log.debug("skip fc id={} dep={} target={}: javaVersion='{}' != project '{}'",
                            fc.getId(), fc.getDependencyId(), fc.getTargetPath(),
                            fc.getJavaVersion(), description.getLanguage().jvmVersion());
                    continue;
                }

                String targetPath = resolveTargetPath(fc.getTargetPath(), description);
                Path target = projectRoot.resolve(targetPath);

                switch (fc.getFileType()) {
                    case YAML_MERGE -> mergeYaml(fc.getContent(), target);
                    case TEMPLATE -> writeTemplate(fc, description, target);
                    case STATIC_COPY -> writeStatic(fc.getContent(), target);
                    case DELETE -> Files.deleteIfExists(target);
                }
            }
        };
    }

    /**
     * Runs at LOWEST_PRECEDENCE so the DELETE contributors run after all writes.
     * The main contributor above handles ordering via sortOrder already, but
     * the DELETE of application.properties must happen after the framework writes it.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    ProjectContributor dynamicDeleteContributor(
            ProjectDescription description,
            DependencyConfigService configService) {
        return projectRoot -> {
            Set<String> depIds = selectedDepIds(description);
            List<FileContributionEntity> contributions = configService.getFileContributions(depIds);

            for (FileContributionEntity fc : contributions) {
                if (fc.getFileType() == FileContributionEntity.FileType.DELETE) {
                    Files.deleteIfExists(projectRoot.resolve(fc.getTargetPath()));
                }
            }
        };
    }

    @Bean
    BuildCustomizer<MavenBuild> dynamicBuildCustomizer(
            ProjectDescription description,
            DependencyConfigService configService) {
        return build -> {
            Set<String> depIds = selectedDepIds(description);
            List<BuildCustomizationEntity> customizations = configService.getBuildCustomizations(depIds);

            for (BuildCustomizationEntity bc : customizations) {
                switch (bc.getCustomizationType()) {
                    case ADD_DEPENDENCY -> build.dependencies().add(
                            bc.getMavenArtifactId(),
                            Dependency.withCoordinates(bc.getMavenGroupId(), bc.getMavenArtifactId())
                                    .build());
                    case EXCLUDE_DEPENDENCY -> build.dependencies().add(
                            bc.getExcludeFromArtifactId(),
                            Dependency.withCoordinates(bc.getExcludeFromGroupId(), bc.getExcludeFromArtifactId())
                                    .exclusions(new Dependency.Exclusion(bc.getMavenGroupId(), bc.getMavenArtifactId()))
                                    .build());
                    case ADD_REPOSITORY -> {
                        MavenRepository.Builder repoBuilder = MavenRepository
                                .withIdAndUrl(bc.getRepoId(), bc.getRepoUrl())
                                .name(bc.getRepoName());
                        if (bc.isSnapshotsEnabled()) {
                            repoBuilder.snapshotsEnabled(true);
                        }
                        build.repositories().add(bc.getRepoId(), repoBuilder.build());
                    }
                }
            }

            // Remove file-only deps — the framework adds every requested dep to the
            // build by default, but file-only entries (starter=false) must not produce
            // a pom.xml <dependency> entry.
            Set<String> fileOnlyIds = configService.getFileOnlyDepIds(depIds);
            for (String id : fileOnlyIds) {
                boolean removed = build.dependencies().remove(id);
                log.debug("file-only dep '{}': removed from build={}", id, removed);
            }

        };
    }

    /**
     * Removes the bare {@code spring-boot-starter} that Spring Initializr's
     * {@code DefaultStarterBuildCustomizer} adds under the internal key {@code "root_starter"}
     * when it finds no metadata-known starters in the build.
     *
     * <p>This runs as a {@link ProjectContributor} (not a {@link BuildCustomizer}) so that it
     * executes <em>after</em> all {@code BuildCustomizer} beans — including the framework's own
     * {@code DefaultStarterBuildCustomizer} — have finished modifying the build. At that point we
     * can see both entries in {@link MavenBuild#dependencies()} and remove the duplicate.
     *
     * <p>Our {@code EXCLUDE_DEPENDENCY} build customization for {@code __common__} explicitly adds
     * {@code spring-boot-starter} (with the logging exclusion) under the key {@code "spring-boot-starter"}.
     * Keeping both keys produces two identical {@code <dependency>} blocks in the pom.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ProjectContributor deduplicateRootStarterContributor(MavenBuild build) {
        return projectRoot -> {
            if (build.dependencies().has("spring-boot-starter") && build.dependencies().has("root_starter")) {
                build.dependencies().remove("root_starter");
                log.debug("removed duplicate 'root_starter' — spring-boot-starter already present with exclusion");
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Set<String> selectedDepIds(ProjectDescription description) {
        return description.getRequestedDependencies().keySet();
    }

    /**
     * Resolves {{packagePath}} placeholder in target paths using the project's package name.
     */
    private String resolveTargetPath(String targetPath, ProjectDescription description) {
        if (targetPath.contains("{{packagePath}}")) {
            String packagePath = description.getPackageName().replace('.', '/');
            targetPath = targetPath.replace("{{packagePath}}", packagePath);
        }
        return targetPath;
    }

    private void writeTemplate(FileContributionEntity fc, ProjectDescription description, Path target)
            throws IOException {
        String content = fc.getContent();
        if (fc.getSubstitutionType() == FileContributionEntity.SubstitutionType.PROJECT) {
            content = content
                    .replace("{{artifactId}}", description.getArtifactId())
                    .replace("{{groupId}}", description.getGroupId())
                    .replace("{{version}}", description.getVersion());
        } else if (fc.getSubstitutionType() == FileContributionEntity.SubstitutionType.PACKAGE) {
            content = content.replace("{{packageName}}", description.getPackageName());
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private void writeStatic(String content, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private void mergeYaml(String newContent, Path targetYamlPath) throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> merged;
        if (Files.exists(targetYamlPath)) {
            Map<String, Object> existing = yaml.load(Files.readString(targetYamlPath));
            Map<String, Object> incoming = yaml.load(newContent);
            merged = deepMerge(existing, incoming);
        } else {
            merged = yaml.load(newContent);
        }
        Files.createDirectories(targetYamlPath.getParent());
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        Files.writeString(targetYamlPath, new Yaml(opts).dump(merged));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            Object baseVal = result.get(entry.getKey());
            Object overrideVal = entry.getValue();
            if (baseVal instanceof Map && overrideVal instanceof Map) {
                result.put(entry.getKey(), deepMerge((Map<String, Object>) baseVal, (Map<String, Object>) overrideVal));
            } else {
                result.put(entry.getKey(), overrideVal);
            }
        }
        return result;
    }
}
