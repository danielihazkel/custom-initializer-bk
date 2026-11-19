package com.menora.initializr.extension.localrun;

import com.menora.initializr.config.EntityDefinitionContext;
import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.localrun.ContainerImages;
import com.menora.initializr.localrun.LocalRun;
import io.spring.initializr.generator.buildsystem.Dependency;
import io.spring.initializr.generator.buildsystem.maven.MavenBuild;
import io.spring.initializr.generator.project.ProjectDescription;
import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import io.spring.initializr.generator.project.contributor.ProjectContributor;
import io.spring.initializr.generator.spring.build.BuildCustomizer;
import io.spring.initializr.generator.spring.documentation.HelpDocumentCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Running a generated backend on a developer's machine ({@link LocalRun}): {@code compose.yaml}
 * and the {@code compose} profile, Testcontainers for the {@code testcontainers} dependency, and
 * the Flyway module each database needs.
 *
 * <p>A plain or wizard project writes its own compose file. A fullstack backend writes only the
 * profile (the compose file is at the fullstack project's root, written with the frontend), and a
 * module of a multi-module project writes neither: {@code MultiModuleController} writes them for
 * the whole project.
 */
@ProjectGenerationConfiguration
public class LocalRunGenerationConfiguration {

    /** Before the model contributor, whose line-ending pass runs last. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 20)
    ProjectContributor localRunContributor(ProjectDescription description, ProjectOptionsContext optionsContext,
                                           EntityDefinitionContext entityContext, ContainerImages images,
                                           com.menora.initializr.config.EventsSpecContext eventsContext,
                                           com.menora.initializr.events.EventsCodeGenerator events) {
        return projectRoot -> {
            Set<String> deps = new java.util.LinkedHashSet<>(description.getRequestedDependencies().keySet());
            // The events wizard's Avro channels through Confluent's schema registry: its container too.
            if (!eventsContext.isEmpty() && eventsContext.all().entrySet().stream().anyMatch(e -> events.usesRegistry(
                    e.getValue(), description.getPackageName(), eventsContext.optionsFor(e.getKey())))) {
                deps.add(LocalRun.SCHEMA_REGISTRY);
            }
            if (!optionsContext.isEmbedded()) {
                LocalRun.writeProject(projectRoot, description.getArtifactId(), deps, images);
            } else if (!entityContext.isEmpty()) {
                // A fullstack backend: its project's compose file is one directory up.
                LocalRun.writeProfile(projectRoot, deps, true);
            }
        };
    }

    /** The {@code testcontainers} dependency's sample test: the application on its databases in Docker. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 20)
    ProjectContributor containerTestsContributor(ProjectDescription description, ProjectOptionsContext optionsContext,
                                                 EntityDefinitionContext entityContext, ContainerImages images) {
        return projectRoot -> {
            Set<String> deps = description.getRequestedDependencies().keySet();
            if (!deps.contains(LocalRun.TESTCONTAINERS)) return;
            // A module but the entry point has no application to start.
            if (optionsContext.isEmbedded() && entityContext.isEmpty()) return;
            Optional<String> test = LocalRun.containerTests(description.getPackageName(), deps, images);
            if (test.isEmpty()) return;
            Path target = projectRoot.resolve("src/test/java/" + description.getPackageName().replace('.', '/')
                    + "/ContainerDatabaseTests.java");
            Files.createDirectories(target.getParent());
            Files.writeString(target, test.get());
            LocalRun.writeTestcontainersProperties(projectRoot, images);
        };
    }

    /** The Testcontainers module of each database the project has, beside the dependency's junit-jupiter. */
    @Bean
    BuildCustomizer<MavenBuild> testcontainersModulesCustomizer(ProjectDescription description) {
        return build -> {
            Set<String> deps = description.getRequestedDependencies().keySet();
            if (deps.contains(LocalRun.TESTCONTAINERS)) LocalRun.addTestcontainersModules(build, deps);
        };
    }

    /** Flyway's module for each database on the build — without it Flyway does not start on that database. */
    @Bean
    BuildCustomizer<MavenBuild> flywayDatabaseModulesCustomizer(ProjectDescription description) {
        return build -> {
            for (String module : LocalRun.flywayModules(description.getRequestedDependencies().keySet(),
                    description.getPlatformVersion())) {
                build.dependencies().add(module, Dependency.withCoordinates("org.flywaydb", module));
            }
        };
    }

    /** HELP.md says how to run the project on its compose services. */
    @Bean
    HelpDocumentCustomizer localRunHelpCustomizer(ProjectDescription description, ProjectOptionsContext optionsContext) {
        return document -> {
            Set<String> deps = description.getRequestedDependencies().keySet();
            if (optionsContext.isEmbedded() || !LocalRun.hasServices(deps)) return;
            document.addSection(writer -> {
                writer.println("# Running it locally");
                writer.println();
                writer.println("`" + LocalRun.COMPOSE_FILE + "` starts what this project's dependencies need, in Docker:");
                writer.println();
                writer.println("    docker compose up -d");
                writer.println("    mvn spring-boot:run -Dspring-boot.run.profiles=" + LocalRun.profiles(deps));
                writer.println();
                writer.println("The `" + LocalRun.PROFILE + "` profile (`" + LocalRun.PROFILE_FILE
                        + "`) points the application at those containers;");
                writer.println("`docker compose down` stops them and throws their data away.");
                if (deps.contains("db2")) {
                    writer.println("IBM i (the `db2` dependency) has no container image: it still needs a real server.");
                }
                writer.println();
            });
        };
    }
}
