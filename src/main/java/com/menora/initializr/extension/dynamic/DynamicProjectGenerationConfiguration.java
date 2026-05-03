package com.menora.initializr.extension.dynamic;

import com.menora.initializr.ai.AiDtos.GeneratedAiFile;
import com.menora.initializr.ai.SafePath;
import com.menora.initializr.config.AiFilesContext;
import com.menora.initializr.config.OpenApiSpecContext;
import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.config.SoapSpecContext;
import com.menora.initializr.config.SqlScriptsContext;
import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.openapi.GeneratedOpenApiFile;
import com.menora.initializr.openapi.OpenApiCodeGenerator;
import com.menora.initializr.openapi.OpenApiWizardOptions;
import com.menora.initializr.soap.GeneratedSoapFile;
import com.menora.initializr.soap.SoapCodeGenerator;
import com.menora.initializr.soap.SoapWizardOptions;
import com.menora.initializr.sql.GeneratedJavaFile;
import com.menora.initializr.sql.SqlDepOptions;
import com.menora.initializr.sql.SqlDialect;
import com.menora.initializr.sql.SqlEntityGenerator;
import com.samskivert.mustache.Mustache;
import io.spring.initializr.generator.buildsystem.Dependency;
import io.spring.initializr.generator.buildsystem.DependencyScope;
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
import java.util.HashMap;
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

    // escapeHTML=false: we render Java, Dockerfile, YAML — never HTML.
    private static final Mustache.Compiler MUSTACHE = Mustache.compiler().escapeHTML(false);

    @Bean
    @Order(0)
    ProjectContributor dynamicFileContributor(
            ProjectDescription description,
            DependencyConfigService configService,
            ProjectOptionsContext optionsContext) {
        return projectRoot -> {
            Set<String> depIds = selectedDepIds(description);
            log.info("generation: selectedDepIds={}", depIds);
            List<FileContributionEntity> contributions = configService.getFileContributions(depIds);

            Map<String, Object> baseContext = buildBaseContext(description, depIds, optionsContext);

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
                    case TEMPLATE -> writeTemplate(fc, baseContext, target);
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
            build.settings().finalName("${project.artifactId}");

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

    /**
     * Writes JPA entity (and optional repository) classes parsed from SQL the
     * user pasted into the wizard. Runs per-request; skips silently when no
     * SQL was supplied.
     */
    @Bean
    ProjectContributor sqlEntityContributor(
            ProjectDescription description,
            SqlScriptsContext sqlContext,
            SqlEntityGenerator generator) {
        return projectRoot -> {
            if (sqlContext.isEmpty()) return;
            for (var entry : sqlContext.all().entrySet()) {
                SqlDialect dialect = SqlDialect.forDepId(entry.getKey());
                if (dialect == null || entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                SqlDepOptions opts = sqlContext.optionsFor(entry.getKey());
                List<GeneratedJavaFile> files = generator.generate(
                        entry.getValue(), dialect, description.getPackageName(), opts);
                String packagePath = description.getPackageName().replace('.', '/');
                for (GeneratedJavaFile f : files) {
                    Path target = projectRoot.resolve(
                            f.relativePath().replace("{{packagePath}}", packagePath));
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, f.content());
                }
            }
        };
    }

    /**
     * Writes controller + DTO record source files from the OpenAPI spec pasted
     * into the wizard. Symmetric counterpart to {@link #sqlEntityContributor}.
     * Skips silently when no spec was supplied.
     */
    @Bean
    @Order(100)
    ProjectContributor openApiCodeContributor(
            ProjectDescription description,
            OpenApiSpecContext specContext,
            OpenApiCodeGenerator generator) {
        return projectRoot -> {
            if (specContext.isEmpty()) return;
            for (var entry : specContext.all().entrySet()) {
                String spec = entry.getValue();
                if (spec == null || spec.isBlank()) continue;
                OpenApiWizardOptions opts = specContext.optionsFor(entry.getKey());
                List<GeneratedOpenApiFile> files = generator.generate(
                        spec, description.getPackageName(), opts);
                String packagePath = description.getPackageName().replace('.', '/');
                for (GeneratedOpenApiFile f : files) {
                    Path target = projectRoot.resolve(
                            f.relativePath().replace("{{packagePath}}", packagePath));
                    if (isYaml(target)) {
                        mergeYaml(f.content(), target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, f.content());
                    }
                }
            }
        };
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    /**
     * Writes Spring-WS endpoint and/or client source files plus the WSDL itself
     * from the WSDL pasted into the SOAP wizard. Symmetric counterpart to
     * {@link #openApiCodeContributor}. Skips silently when no WSDL was supplied.
     */
    @Bean
    @Order(100)
    ProjectContributor soapCodeContributor(
            ProjectDescription description,
            SoapSpecContext soapContext,
            SoapCodeGenerator generator) {
        return projectRoot -> {
            if (soapContext.isEmpty()) return;
            for (var entry : soapContext.all().entrySet()) {
                String wsdl = entry.getValue();
                if (wsdl == null || wsdl.isBlank()) continue;
                SoapWizardOptions opts = soapContext.optionsFor(entry.getKey());
                List<GeneratedSoapFile> files = generator.generate(
                        wsdl, description.getPackageName(), opts);
                String packagePath = description.getPackageName().replace('.', '/');
                for (GeneratedSoapFile f : files) {
                    Path target = projectRoot.resolve(
                            f.relativePath().replace("{{packagePath}}", packagePath));
                    if (isYaml(target)) {
                        mergeYaml(f.content(), target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, f.content());
                    }
                }
            }
        };
    }

    /**
     * Configures the JAX-WS Maven plugin (wsimport goal) for every WSDL the
     * SOAP wizard dropped into {@code src/main/resources/wsdl/}. Generated
     * JAXB classes land under {@code <projectPackage>.<payloadSubPackage>}
     * during the {@code generate-sources} phase.
     */
    @Bean
    BuildCustomizer<MavenBuild> soapBuildCustomizer(
            ProjectDescription description,
            SoapSpecContext soapContext,
            SoapCodeGenerator generator) {
        return build -> {
            if (soapContext.isEmpty()) return;

            List<String> allWsdlFiles = new java.util.ArrayList<>();
            String payloadPackage = null;
            for (var entry : soapContext.all().entrySet()) {
                String wsdl = entry.getValue();
                if (wsdl == null || wsdl.isBlank()) continue;
                SoapWizardOptions opts = soapContext.optionsFor(entry.getKey());
                if (payloadPackage == null) {
                    payloadPackage = SoapCodeGenerator.payloadFullPackage(description.getPackageName(), opts);
                }
                for (String name : generator.resolveWsdlFileNames(wsdl)) {
                    allWsdlFiles.add(name + ".wsdl");
                }
            }
            if (allWsdlFiles.isEmpty()) return;

            final String pkg = payloadPackage;
            build.plugins().add("com.sun.xml.ws", "jaxws-maven-plugin", plugin -> {
                plugin.version("4.0.2");
                plugin.execution("wsimport-generate", execution -> {
                    execution.phase("generate-sources");
                    execution.goal("wsimport");
                    execution.configuration(config -> {
                        config.add("packageName", pkg);
                        config.add("wsdlDirectory", "${project.basedir}/src/main/resources/wsdl");
                        config.add("sourceDestDir", "${project.build.directory}/generated-sources/jaxws");
                        config.add("wsdlFiles", wsdlFiles -> {
                            for (String f : allWsdlFiles) {
                                wsdlFiles.add("wsdlFile", f);
                            }
                        });
                    });
                });
            });
        };
    }

    /**
     * Writes the AI-generated files the user kept in the AI assistant panel.
     * Path safety is re-validated here (defense in depth) — the
     * {@link com.menora.initializr.ai.AiFileGenerationService} already filters
     * the AI's output, but a hand-crafted request body could send files
     * straight to {@code /starter-wizard.zip} without going through the AI.
     *
     * <p>Order 150 sits after the OpenAPI/SOAP contributors (100) and before
     * the LOWEST_PRECEDENCE delete contributor, so AI files don't clobber
     * anything from earlier in the pipeline and can themselves be cleaned up
     * by a DELETE entry if a future feature wants that.
     */
    @Bean
    @Order(150)
    ProjectContributor aiFileContributor(AiFilesContext aiFilesContext) {
        return projectRoot -> {
            if (aiFilesContext.isEmpty()) return;
            for (GeneratedAiFile f : aiFilesContext.all()) {
                String safe;
                try {
                    safe = SafePath.validate(f.path());
                } catch (IllegalArgumentException ex) {
                    // Defense in depth — AiFileGenerationService already filters,
                    // so an unsafe path here means a hand-crafted request body.
                    // Log and skip rather than 500 the whole generation.
                    log.warn("ai file skipped: '{}' rejected ({})", f.path(), ex.getMessage());
                    continue;
                }
                Path target = projectRoot.resolve(safe);
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.content());
                log.debug("ai file written: {}", safe);
            }
        };
    }

    /**
     * Adds Lombok to the generated pom when the SQL wizard was used. Scope
     * {@code ANNOTATION_PROCESSOR} → the Maven writer emits the dep as
     * {@code <optional>true</optional>} and the parent BOM manages the version.
     */
    @Bean
    BuildCustomizer<MavenBuild> sqlLombokCustomizer(SqlScriptsContext sqlContext) {
        return build -> {
            if (sqlContext.isEmpty()) return;
            if (build.dependencies().has("lombok")) return;
            build.dependencies().add("lombok",
                    Dependency.withCoordinates("org.projectlombok", "lombok")
                            .scope(DependencyScope.ANNOTATION_PROCESSOR)
                            .build());
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

    private void writeTemplate(FileContributionEntity fc, Map<String, Object> ctx, Path target)
            throws IOException {
        String content = fc.getSubstitutionType() == FileContributionEntity.SubstitutionType.MUSTACHE
                ? MUSTACHE.compile(fc.getContent()).execute(ctx)
                : fc.getContent();
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    /**
     * Builds the Mustache context exposed to every TEMPLATE file contribution.
     * Contains project fields, resolved {@code packagePath}, {@code javaVersion},
     * {@code packaging}, plus boolean flags for each selected dependency
     * ({@code hasKafka}, {@code hasSpringBootStarter}, …) and sub-option
     * ({@code optKafkaConsumerExample}, …).
     */
    private Map<String, Object> buildBaseContext(ProjectDescription description,
                                                 Set<String> depIds,
                                                 ProjectOptionsContext optionsContext) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("artifactId", description.getArtifactId());
        ctx.put("groupId", description.getGroupId());
        ctx.put("version", description.getVersion());
        ctx.put("packageName", description.getPackageName());
        ctx.put("packagePath", description.getPackageName().replace('.', '/'));
        ctx.put("javaVersion", description.getLanguage().jvmVersion());
        ctx.put("packaging", description.getPackaging() != null ? description.getPackaging().id() : null);

        for (String depId : depIds) {
            ctx.put("has" + toPascalCase(depId), Boolean.TRUE);
            for (String optId : optionsContext.selectedOptions(depId)) {
                ctx.put("opt" + toPascalCase(depId) + toPascalCase(optId), Boolean.TRUE);
            }
        }
        return ctx;
    }

    /** {@code "kafka"} → {@code "Kafka"}; {@code "mail-sampler"} → {@code "MailSampler"}. */
    private static String toPascalCase(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        boolean upper = true;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '-' || c == '_' || c == '.') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
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
