package com.menora.initializr.extension.dynamic;

import com.menora.initializr.config.EntityDefinitionContext;
import com.menora.initializr.config.EventsSpecContext;
import com.menora.initializr.config.OpenApiSpecContext;
import com.menora.initializr.config.BackendModel;
import com.menora.initializr.config.CompiledTemplates;
import com.menora.initializr.config.GeneratedProjectFiles;
import com.menora.initializr.config.DepartmentResolver;
import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.config.SoapSpecContext;
import com.menora.initializr.config.SqlScriptsContext;
import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.events.EventsCodeGenerator;
import com.menora.initializr.events.EventsWizardOptions;
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
import com.menora.initializr.sql.SqlProjectFacts;
import io.spring.initializr.generator.buildsystem.BillOfMaterials;
import io.spring.initializr.generator.buildsystem.Dependency;
import io.spring.initializr.generator.spring.scm.git.GitIgnoreCustomizer;
import io.spring.initializr.generator.version.Version;
import io.spring.initializr.generator.version.VersionProperty;
import io.spring.initializr.generator.version.VersionReference;
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

    /**
     * The file-contribution rows for this generation. Both contributors below need the same
     * list; holding it in one bean of the per-request child context keeps it to a single query.
     */
    record SelectedFileContributions(List<FileContributionEntity> rows) {}

    @Bean
    SelectedFileContributions selectedFileContributions(ProjectDescription description,
                                                        DependencyConfigService configService) {
        Set<String> depIds = selectedDepIds(description);
        log.info("generation: selectedDepIds={}", depIds);
        return new SelectedFileContributions(configService.getFileContributions(depIds));
    }

    @Bean
    @Order(0)
    ProjectContributor dynamicFileContributor(
            ProjectDescription description,
            SelectedFileContributions selectedFileContributions,
            ProjectOptionsContext optionsContext,
            EntityDefinitionContext entityContext,
            SqlScriptsContext sqlContext,
            DepartmentResolver departmentResolver) {
        return projectRoot -> {
            Set<String> depIds = selectedDepIds(description);
            List<FileContributionEntity> contributions = selectedFileContributions.rows();

            Map<String, Object> baseContext =
                    buildBaseContext(description, depIds, optionsContext, entityContext, sqlContext);
            departmentResolver.putVars(baseContext, optionsContext.department());

            for (FileContributionEntity fc : contributions) {
                if (isGatedOut(fc, description, optionsContext)) continue;

                Path target = GeneratedProjectFiles.resolveInside(projectRoot,
                        resolveTargetPath(fc.getTargetPath(), description));

                switch (fc.getFileType()) {
                    case YAML_MERGE -> mergeYaml(render(fc, baseContext), target);
                    case TEMPLATE -> writeTemplate(fc, baseContext, target);
                    case STATIC_COPY -> writeStatic(fc.getContent(), target);
                    // Deliberately not handled here: dynamicDeleteContributor runs at
                    // LOWEST_PRECEDENCE, after the framework has written what these target.
                    case DELETE -> { }
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
            SelectedFileContributions selectedFileContributions,
            ProjectOptionsContext optionsContext) {
        return projectRoot -> {
            for (FileContributionEntity fc : selectedFileContributions.rows()) {
                if (fc.getFileType() != FileContributionEntity.FileType.DELETE) continue;
                // Same gating and path resolution as the write pass — a DELETE row can be
                // gated on a sub-option or Java version, and can use {{packagePath}}.
                if (isGatedOut(fc, description, optionsContext)) continue;
                Files.deleteIfExists(GeneratedProjectFiles.resolveInside(projectRoot,
                        resolveTargetPath(fc.getTargetPath(), description)));
            }
        };
    }

    /**
     * Whether a file contribution is excluded for this generation. Shared by the write pass
     * and the LOWEST_PRECEDENCE delete pass so the two can't drift apart.
     */
    private static boolean isGatedOut(FileContributionEntity fc,
                                      ProjectDescription description,
                                      ProjectOptionsContext optionsContext) {
        if (fc.getSubOptionId() != null
                && !optionsContext.hasOption(fc.getDependencyId(), fc.getSubOptionId())) {
            log.debug("skip fc id={} dep={} target={}: sub-option '{}' not selected",
                    fc.getId(), fc.getDependencyId(), fc.getTargetPath(), fc.getSubOptionId());
            return true;
        }
        if (fc.getJavaVersion() != null
                && !fc.getJavaVersion().equals(description.getLanguage().jvmVersion())) {
            log.debug("skip fc id={} dep={} target={}: javaVersion='{}' != project '{}'",
                    fc.getId(), fc.getDependencyId(), fc.getTargetPath(),
                    fc.getJavaVersion(), description.getLanguage().jvmVersion());
            return true;
        }
        return false;
    }

    @Bean
    BuildCustomizer<MavenBuild> dynamicBuildCustomizer(
            ProjectDescription description,
            DependencyConfigService configService,
            ProjectOptionsContext optionsContext) {
        return build -> {
            build.settings().finalName("${project.artifactId}");

            Set<String> depIds = selectedDepIds(description);
            List<BuildCustomizationEntity> customizations = configService.getBuildCustomizations(depIds);

            for (BuildCustomizationEntity bc : customizations) {
                // Skip if gated on a sub-option that wasn't selected
                if (bc.getSubOptionId() != null
                        && !optionsContext.hasOption(bc.getDependencyId(), bc.getSubOptionId())) {
                    log.debug("skip bc id={} dep={} artifact={}: sub-option '{}' not selected",
                            bc.getId(), bc.getDependencyId(), bc.getMavenArtifactId(), bc.getSubOptionId());
                    continue;
                }
                // Skip a row pinned to another Spring Boot line (the spring-cloud BOM and
                // springdoc carry one row per line).
                if (!appliesTo(bc.getCompatibilityRange(), description.getPlatformVersion())) {
                    log.debug("skip bc id={} dep={} artifact={}: outside Boot range '{}'",
                            bc.getId(), bc.getDependencyId(), bc.getMavenArtifactId(), bc.getCompatibilityRange());
                    continue;
                }
                switch (bc.getCustomizationType()) {
                    case ADD_DEPENDENCY -> {
                        Dependency.Builder<?> dep = Dependency.withCoordinates(
                                bc.getMavenGroupId(), bc.getMavenArtifactId());
                        // Artifacts not managed by the Spring Boot BOM (e.g.
                        // mapstruct-processor, lombok-mapstruct-binding) carry an
                        // explicit version — without it the generated pom is invalid.
                        if (bc.getVersion() != null && !bc.getVersion().isBlank()) {
                            dep.version(VersionReference.ofValue(bc.getVersion()));
                        }
                        // A dependency the build already holds under these coordinates (the
                        // catalog entry itself, under its dep id) is replaced, not added again:
                        // that is how a ranged row gives it another version on another Boot line.
                        String id = build.dependencies().ids()
                                .filter(existing -> {
                                    Dependency held = build.dependencies().get(existing);
                                    return bc.getMavenGroupId().equals(held.getGroupId())
                                            && bc.getMavenArtifactId().equals(held.getArtifactId());
                                })
                                .findFirst().orElse(bc.getMavenArtifactId());
                        build.dependencies().add(id, dep.build());
                    }
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
                    case ADD_BOM -> {
                        // The version lives in a pom property (<spring-cloud.version>), the way
                        // a hand-written pom imports a BOM, so a team can bump it in one place.
                        String property = bomVersionProperty(bc.getMavenArtifactId());
                        build.properties().version(VersionProperty.of(property), bc.getVersion());
                        build.boms().add(bc.getMavenArtifactId(),
                                BillOfMaterials.withCoordinates(bc.getMavenGroupId(), bc.getMavenArtifactId())
                                        .version(VersionReference.ofProperty(property)));
                    }
                    default -> { /* FRONTEND types — applied by PackageJsonBuilder / ViteConfigBuilder */ }
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
     * Whether a row with this Boot version range applies to the project's Boot version. Blank
     * range: always. A range that does not parse applies too — the row was valid before ranges
     * existed, and dropping a dependency silently is worse than keeping it.
     */
    static boolean appliesTo(String range, io.spring.initializr.generator.version.Version platformVersion) {
        if (range == null || range.isBlank() || platformVersion == null) return true;
        try {
            return io.spring.initializr.generator.version.VersionParser.DEFAULT.parseRange(range.trim())
                    .match(platformVersion);
        } catch (RuntimeException e) {
            log.warn("Build customization has an unreadable Boot range '{}' — applying it to every version", range);
            return true;
        }
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
            Set<String> deps = selectedDepIds(description);
            Version boot = description.getPlatformVersion();
            SqlProjectFacts facts = new SqlProjectFacts(deps.contains("validation"), deps.contains("flyway"),
                    boot != null && boot.compareTo(Version.parse("3.4.0")) >= 0);
            // One schema history: the first script is the baseline, a second database's is not.
            boolean baselineWritten = false;
            for (var entry : sqlContext.all().entrySet()) {
                SqlDialect dialect = SqlDialect.forDepId(entry.getKey());
                if (dialect == null || entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                SqlDepOptions opts = sqlContext.optionsFor(entry.getKey());
                List<GeneratedJavaFile> files = generator.generate(
                        entry.getValue(), dialect, description.getPackageName(), opts, facts);
                String packagePath = description.getPackageName().replace('.', '/');
                for (GeneratedJavaFile f : files) {
                    if (SqlEntityGenerator.FLYWAY_BASELINE_PATH.equals(f.relativePath())) {
                        if (baselineWritten) continue;
                        baselineWritten = true;
                    }
                    Path target = GeneratedProjectFiles.resolveInside(projectRoot,
                            f.relativePath().replace("{{packagePath}}", packagePath));
                    GeneratedProjectFiles.createParentDirectories(target);
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
            // Gate generated @Valid on the validation starter — otherwise jakarta.validation
            // wouldn't resolve and the generated controller fails to compile.
            boolean hasValidation = selectedDepIds(description).contains("validation");
            for (var entry : specContext.all().entrySet()) {
                String spec = entry.getValue();
                if (spec == null || spec.isBlank()) continue;
                OpenApiWizardOptions opts = specContext.optionsFor(entry.getKey());
                List<GeneratedOpenApiFile> files = generator.generate(
                        spec, description.getPackageName(), opts, hasValidation,
                        // springdoc on the build: the security schemes go into the API documentation.
                        selectedDepIds(description).contains("openapi"));
                if (opts != null && opts.copySpec()) {
                    // The contract the code was written from, kept beside it: JSON when it is JSON.
                    String extension = spec.stripLeading().startsWith("{") ? "json" : "yaml";
                    Path copy = GeneratedProjectFiles.resolveInside(projectRoot,
                            "src/main/resources/openapi/" + entry.getKey() + "." + extension);
                    GeneratedProjectFiles.createParentDirectories(copy);
                    Files.writeString(copy, spec);
                }
                String packagePath = description.getPackageName().replace('.', '/');
                for (GeneratedOpenApiFile f : files) {
                    Path target = GeneratedProjectFiles.resolveInside(projectRoot,
                            f.relativePath().replace("{{packagePath}}", packagePath));
                    if (isYaml(target)) {
                        mergeYaml(f.content(), target);
                    } else {
                        GeneratedProjectFiles.createParentDirectories(target);
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
     * Last of all: {@code menora-model.json}, what this project was generated from — the request
     * as it was <em>resolved</em> (the starters a wizard added, the datasource roles defaulted), so
     * that generating it again gives the same project, and a later change of it can be delivered
     * as a patch ({@code POST /starter.patch}). Then one line ending for every text file, so the
     * project is the same bytes whatever machine generated it.
     *
     * <p>It is written here, from the project description and the per-request contexts, and not
     * by the endpoints: {@code /starter.zip} is the framework's own, and what it generates has to
     * stay byte for byte what {@code /starter-wizard.zip} generates without a wizard.
     * A project that is part of a larger one (a fullstack backend, a module) writes none.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    ProjectContributor backendModelContributor(
            ProjectDescription description,
            SelectedFileContributions selectedFileContributions,
            DependencyConfigService configService,
            ProjectOptionsContext optionsContext,
            SqlScriptsContext sqlContext,
            OpenApiSpecContext specContext,
            SoapSpecContext soapContext,
            EventsSpecContext eventsContext) {
        return projectRoot -> {
            if (optionsContext.isEmbedded()) return;
            Set<String> depIds = selectedDepIds(description);
            com.fasterxml.jackson.databind.node.ObjectNode request = BackendModel.newRequest();
            request.put("groupId", description.getGroupId());
            request.put("artifactId", description.getArtifactId());
            request.put("name", description.getName());
            if (description.getDescription() != null) request.put("description", description.getDescription());
            request.put("packageName", description.getPackageName());
            if (description.getPlatformVersion() != null) request.put("bootVersion", description.getPlatformVersion().toString());
            request.put("javaVersion", description.getLanguage().jvmVersion());
            request.put("packaging", description.getPackaging().id());
            if (description.getVersion() != null) request.put("version", description.getVersion());
            request.set("dependencies", BackendModel.array(depIds));
            com.fasterxml.jackson.databind.node.ObjectNode opts = BackendModel.newRequest();
            new java.util.TreeMap<>(optionsContext.all()).forEach((dep, ids) -> {
                if (depIds.contains(dep) && !ids.isEmpty()) opts.set(dep, BackendModel.array(new java.util.TreeSet<>(ids)));
            });
            if (!opts.isEmpty()) request.set("opts", opts);
            if (optionsContext.department() != null) request.put("department", optionsContext.department());
            putWizard(request, "sqlByDep", "sqlOptions", sqlContext.all(), sqlContext::optionsFor);
            putWizard(request, "specByDep", "openApiOptions", specContext.all(), specContext::optionsFor);
            putWizard(request, "wsdlByDep", "soapOptions", soapContext.all(), soapContext::optionsFor);
            putWizard(request, "asyncApiByDep", "eventOptions", eventsContext.all(), eventsContext::optionsFor);

            String catalogHash = BackendModel.catalogHash(selectedFileContributions.rows(),
                    configService.getBuildCustomizations(depIds));
            Files.writeString(projectRoot.resolve(BackendModel.FILE),
                    BackendModel.json(BackendModel.KIND_BACKEND, request, catalogHash));
            Path gitAttributes = projectRoot.resolve(".gitattributes");
            Files.writeString(gitAttributes, BackendModel.gitAttributes(
                    Files.isRegularFile(gitAttributes) ? Files.readString(gitAttributes) : null));
            GeneratedProjectFiles.normalizeLineEndings(projectRoot);
        };
    }

    /** A wizard's documents and — for those that have one — its options, as a request carries them. */
    private static void putWizard(com.fasterxml.jackson.databind.node.ObjectNode request, String textsKey, String optionsKey,
                                  Map<String, String> texts, java.util.function.Function<String, Object> optionsFor) {
        com.fasterxml.jackson.databind.node.ObjectNode byDep = BackendModel.texts(texts);
        if (byDep == null) return;
        request.set(textsKey, byDep);
        com.fasterxml.jackson.databind.node.ObjectNode options = BackendModel.newRequest();
        byDep.fieldNames().forEachRemaining(dep -> {
            Object value = optionsFor.apply(dep);
            if (value != null) options.set(dep, BackendModel.wire(value));
        });
        if (!options.isEmpty()) request.set(optionsKey, options);
    }

    /** HELP.md says what the model file is for. */
    @Bean
    io.spring.initializr.generator.spring.documentation.HelpDocumentCustomizer modelHelpCustomizer(
            ProjectOptionsContext optionsContext) {
        return document -> {
            if (optionsContext.isEmbedded()) return;
            document.addSection(writer -> {
                writer.println("# Reopening and updating this project");
                writer.println();
                writer.println("`" + BackendModel.FILE + "` at the project's root is what this project was generated from: its");
                writer.println("dependencies and options, and whatever a wizard was given (SQL, OpenAPI, WSDL, AsyncAPI).");
                writer.println("Keep it in version control.");
                writer.println();
                writer.println("* **Reopen** it in the generator (Backend tab, *Import model*) to carry on from where the");
                writer.println("  project was generated.");
                writer.println("* **Update** instead of generating again: change the model there and choose *Download update*.");
                writer.println("  The generator compares what the old model generates with what the new one does and answers");
                writer.println("  with a patch for `git apply --3way`, which merges with the edits made since.");
                writer.println();
            });
        };
    }

    /**
     * Writes the Kafka producers and listeners, their payload types and the topic properties from
     * the AsyncAPI document given to the events wizard. Skips silently when none was supplied.
     */
    @Bean
    @Order(100)
    ProjectContributor eventsCodeContributor(
            ProjectDescription description,
            EventsSpecContext eventsContext,
            EventsCodeGenerator generator) {
        return projectRoot -> {
            if (eventsContext.isEmpty()) return;
            EventsCodeGenerator.ProjectFacts facts =
                    new EventsCodeGenerator.ProjectFacts(selectedDepIds(description).contains("validation"));
            String packagePath = description.getPackageName().replace('.', '/');
            for (var entry : eventsContext.all().entrySet()) {
                String document = entry.getValue();
                if (document == null || document.isBlank()) continue;
                EventsWizardOptions opts = eventsContext.optionsFor(entry.getKey());
                for (EventsCodeGenerator.GeneratedEventFile f
                        : generator.generate(document, description.getPackageName(), opts, facts)) {
                    Path target = GeneratedProjectFiles.resolveInside(projectRoot,
                            f.relativePath().replace("{{packagePath}}", packagePath));
                    if (isYaml(target)) {
                        mergeYaml(f.content(), target);
                    } else {
                        GeneratedProjectFiles.createParentDirectories(target);
                        Files.writeString(target, f.content());
                    }
                }
            }
        };
    }

    /**
     * The generated producers and listeners turn payloads into JSON with the application's
     * {@code ObjectMapper}. A project with Kafka and no web starter has neither Jackson nor that
     * bean: the JSON starter brings both.
     */
    @Bean
    BuildCustomizer<MavenBuild> eventsBuildCustomizer(ProjectDescription description, EventsSpecContext eventsContext,
                                                      EventsCodeGenerator generator) {
        return build -> {
            if (eventsContext.isEmpty()) return;
            build.dependencies().add("events-json", Dependency
                    .withCoordinates("org.springframework.boot", "spring-boot-starter-json")
                    .scope(DependencyScope.COMPILE));
            // Avro payloads: their .avsc files become classes when the project is built.
            List<String> avroImports = null;
            for (var entry : eventsContext.all().entrySet()) {
                List<String> imports = generator.avroImports(entry.getValue(), description.getPackageName(),
                        eventsContext.optionsFor(entry.getKey()));
                if (imports != null) {
                    if (avroImports == null) avroImports = new java.util.ArrayList<>();
                    avroImports.addAll(imports);
                }
            }
            if (avroImports == null) return;
            addAvro(build, avroImports);
        };
    }

    /**
     * Avro and the avro-maven-plugin's {@code schema} goal over {@code src/main/avro}. The files
     * other files refer to are read first ({@code imports}): the plugin reads every file with one
     * parser, and in no order of its own.
     */
    private static void addAvro(MavenBuild build, List<String> imports) {
        VersionProperty avroVersion = VersionProperty.of("avro.version");
        build.properties().version(avroVersion, EventsCodeGenerator.AVRO_VERSION);
        build.dependencies().add("events-avro", Dependency.withCoordinates("org.apache.avro", "avro")
                .version(VersionReference.ofProperty(avroVersion))
                .scope(DependencyScope.COMPILE));
        build.plugins().add("org.apache.avro", "avro-maven-plugin", plugin -> {
            plugin.version("${avro.version}");
            plugin.execution("avro-schemas", execution -> {
                execution.phase("generate-sources");
                execution.goal("schema");
                execution.configuration(config -> {
                    config.add("sourceDirectory", "${project.basedir}/src/main/avro");
                    config.add("outputDirectory", "${project.build.directory}/generated-sources/avro");
                    config.add("stringType", "String");
                    config.add("enableDecimalLogicalType", "true");
                    if (!imports.isEmpty()) {
                        config.add("imports", list -> {
                            for (String file : imports) list.add("import", "${project.basedir}/src/main/avro/" + file);
                        });
                    }
                });
            });
        });
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
                    Path target = GeneratedProjectFiles.resolveInside(projectRoot,
                            f.relativePath().replace("{{packagePath}}", packagePath));
                    if (isYaml(target)) {
                        mergeYaml(f.content(), target);
                    } else {
                        GeneratedProjectFiles.createParentDirectories(target);
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

    /**
     * Git-ignores the project's {@code .env} when a file contribution writes one (the local
     * Vault connection's {@code VAULT_TOKEN} file). Keyed off the selected rows rather than
     * hard-coded, so removing the {@code .env} row in admin removes the ignore entry with it.
     */
    @Bean
    GitIgnoreCustomizer dynamicGitIgnoreCustomizer(
            ProjectDescription description,
            SelectedFileContributions selectedFileContributions,
            ProjectOptionsContext optionsContext) {
        return gitIgnore -> {
            boolean writesEnvFile = selectedFileContributions.rows().stream()
                    .filter(fc -> fc.getFileType() != FileContributionEntity.FileType.DELETE)
                    .filter(fc -> ENV_FILE.equals(fc.getTargetPath()))
                    .anyMatch(fc -> !isGatedOut(fc, description, optionsContext));
            if (writesEnvFile) {
                gitIgnore.addSectionIfAbsent("Local secrets").add(ENV_FILE);
            }
        };
    }

    private static final String ENV_FILE = ".env";

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** {@code spring-cloud-dependencies} → {@code spring-cloud.version}; {@code foo-bom} → {@code foo-bom.version}. */
    static String bomVersionProperty(String artifactId) {
        String base = artifactId.endsWith("-dependencies")
                ? artifactId.substring(0, artifactId.length() - "-dependencies".length())
                : artifactId;
        return base + ".version";
    }

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
        String content = render(fc, ctx);
        GeneratedProjectFiles.createParentDirectories(target);
        Files.writeString(target, content);
    }

    /**
     * Builds the Mustache context exposed to every TEMPLATE file contribution.
     * Contains project fields, resolved {@code packagePath}, {@code javaVersion},
     * {@code packaging}, plus boolean flags for each selected dependency
     * ({@code hasKafka}, {@code hasSpringBootStarter}, …) and sub-option
     * ({@code optKafkaConsumerExample}, …).
     *
     * <p>When entities are auto-generated (fullstack or SQL wizard), it also exposes the
     * actual entity/repository packages the generated classes land in, so a per-driver
     * datasource config class scans where the entities really are rather than its legacy
     * {@code <packageName>.<driver>} convention: {@code hasScaffoldedEntities},
     * {@code dsEntityPackage}, {@code dsRepositoryPackage}.
     */
    private Map<String, Object> buildBaseContext(ProjectDescription description,
                                                 Set<String> depIds,
                                                 ProjectOptionsContext optionsContext,
                                                 EntityDefinitionContext entityContext,
                                                 SqlScriptsContext sqlContext) {
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

        // Where the datasource config class should scan when entities are scaffolded. The
        // fullstack generator files entities under <domainPackage>.entity / .repository; the
        // SQL wizard under <packageName>.<subPackage> / .repository (see SqlEntityGenerator).
        String dsEntityPackage = null;
        String dsRepositoryPackage = null;
        // Packages the fullstack scaffold adds beside those two, each holding an entity and its
        // repository (attachments): a hand-written config scans only what it names.
        List<String> dsExtraPackages = List.of();
        boolean hasScaffold = false;
        if (!entityContext.isEmpty()) {                       // fullstack
            dsExtraPackages = com.menora.initializr.fullstack.EntityScaffoldContext.supportPackages(description.getPackageName(), entityContext.all(),
                    optionsContext.hasOption("scaffold", com.menora.initializr.fullstack.ScaffoldOption.SAVED_VIEWS.key()),
                    optionsContext.hasOption("scaffold", com.menora.initializr.fullstack.ScaffoldOption.HISTORY.key()),
                    optionsContext.hasOption("scaffold", com.menora.initializr.fullstack.ScaffoldOption.EVENTS.key()));
            String domain = entityContext.getDomainPackage();
            if (domain == null || domain.isBlank()) {
                domain = description.getPackageName();
            }
            dsEntityPackage = domain + ".entity";
            dsRepositoryPackage = domain + ".repository";
            hasScaffold = true;
        } else if (!sqlContext.isEmpty()) {                   // SQL wizard
            String subPkg = "entity";
            for (Map.Entry<String, String> e : sqlContext.all().entrySet()) {
                if (e.getValue() == null || e.getValue().isBlank()) {
                    continue;
                }
                SqlDepOptions opts = sqlContext.optionsFor(e.getKey());
                if (opts != null && opts.subPackage() != null && !opts.subPackage().isBlank()) {
                    subPkg = opts.subPackage();
                }
                break;                                        // first non-blank SQL dep wins
            }
            dsEntityPackage = description.getPackageName() + "." + subPkg;
            dsRepositoryPackage = description.getPackageName() + ".repository";
            hasScaffold = true;
        }
        ctx.put("hasScaffoldedEntities", hasScaffold);
        ctx.put("dsEntityPackage", dsEntityPackage);
        ctx.put("dsRepositoryPackage", dsRepositoryPackage);
        ctx.put("dsExtraPackages", dsExtraPackages);
        ctx.put("dsHasExtraPackages", !dsExtraPackages.isEmpty());
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
        GeneratedProjectFiles.createParentDirectories(target);
        Files.writeString(target, content);
    }

    /** The row's content, rendered through Mustache when its substitution type is MUSTACHE.
     *  Honoured by TEMPLATE and YAML_MERGE rows alike. */
    private static String render(FileContributionEntity fc, Map<String, Object> ctx) {
        return fc.getSubstitutionType() == FileContributionEntity.SubstitutionType.MUSTACHE
                ? CompiledTemplates.render(fc.getContent(), ctx)
                : fc.getContent();
    }

    private void mergeYaml(String newContent, Path targetYamlPath) throws IOException {
        Yaml yaml = new Yaml();
        // SnakeYAML returns null for empty or comments-only input, which the validator lets
        // through (it only rejects null/empty strings). Coalesce before merging so a blank
        // YAML_MERGE row is a no-op instead of an NPE or a file containing the literal "null".
        Map<String, Object> incoming = orEmpty(yaml.load(newContent));
        Map<String, Object> merged = Files.exists(targetYamlPath)
                ? deepMerge(orEmpty(yaml.load(Files.readString(targetYamlPath))), incoming)
                : incoming;
        if (merged.isEmpty()) return;
        GeneratedProjectFiles.createParentDirectories(targetYamlPath);
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        Files.writeString(targetYamlPath, new Yaml(opts).dump(merged));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> orEmpty(Object loaded) {
        return loaded instanceof Map ? (Map<String, Object>) loaded : new LinkedHashMap<>();
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
