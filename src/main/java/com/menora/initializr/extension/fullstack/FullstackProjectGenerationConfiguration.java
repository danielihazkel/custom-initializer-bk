package com.menora.initializr.extension.fullstack;

import com.menora.initializr.config.DepartmentResolver;
import com.menora.initializr.config.EntityDefinitionContext;
import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.db.entity.EntityTemplateFileEntity;
import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import com.menora.initializr.db.repository.EntityTemplateFileRepository;
import com.menora.initializr.db.repository.EntityTemplateSetRepository;
import com.menora.initializr.fullstack.EntityDefinition;
import com.menora.initializr.fullstack.EntityScaffoldContext;
import com.menora.initializr.fullstack.FullstackRenderer;
import com.menora.initializr.fullstack.GeneratedTests;
import com.menora.initializr.fullstack.ScaffoldOption;
import com.menora.initializr.fullstack.schema.SchemaMigrations;
import com.menora.initializr.localrun.ContainerImages;
import com.menora.initializr.localrun.LocalRun;
import com.menora.initializr.sql.SqlNames;
import io.spring.initializr.generator.buildsystem.Dependency;
import io.spring.initializr.generator.buildsystem.DependencyScope;
import io.spring.initializr.generator.buildsystem.maven.MavenBuild;
import io.spring.initializr.generator.spring.build.BuildCustomizer;
import io.spring.initializr.generator.project.ProjectDescription;
import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import io.spring.initializr.generator.project.contributor.ProjectContributor;
import io.spring.initializr.generator.version.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Spring Initializr per-request project-generation config that runs entity scaffolding
 * for the backend half of the fullstack flow. Sibling to
 * {@code DynamicProjectGenerationConfiguration}. Short-circuits when no entities are
 * populated, so the existing {@code /starter.zip} flow is unaffected.
 *
 * <p>Wired via {@code META-INF/spring.factories}.
 */
@ProjectGenerationConfiguration
public class FullstackProjectGenerationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FullstackProjectGenerationConfiguration.class);

    @Bean
    @Order(50)
    ProjectContributor entityScaffoldContributor(
            ProjectDescription description,
            EntityDefinitionContext entityContext,
            EntityTemplateSetRepository setRepo,
            EntityTemplateFileRepository fileRepo,
            ProjectOptionsContext optionsContext,
            DepartmentResolver departmentResolver) {
        return projectRoot -> {
            if (entityContext.isEmpty()) {
                return;
            }
            String setKey = entityContext.getBackendSetKey();
            if (setKey == null || setKey.isBlank()) {
                log.warn("Entity context populated but no backendTemplateSet selected — skipping CRUD scaffolding");
                return;
            }
            EntityTemplateSetEntity set = setRepo.findBySetKey(setKey).orElse(null);
            if (set == null) {
                log.warn("backendTemplateSet '{}' not found — skipping CRUD scaffolding", setKey);
                return;
            }
            if (set.getKind() != EntityTemplateSetEntity.Kind.BACKEND_JAVA) {
                log.warn("Template set '{}' is not BACKEND_JAVA (kind={}) — skipping", setKey, set.getKind());
                return;
            }
            List<EntityTemplateFileEntity> files = fileRepo.findBySetIdOrderBySortOrderAsc(set.getId());
            if (files.isEmpty()) {
                log.warn("backendTemplateSet '{}' has no files", setKey);
                return;
            }
            List<EntityDefinition> entities = entityContext.all();
            String domainPackage = entityContext.getDomainPackage() != null
                    ? entityContext.getDomainPackage() : description.getPackageName();
            Map<String, Object> projectCtx = EntityScaffoldContext.buildProjectContext(
                    description.getArtifactId(),
                    description.getGroupId(),
                    description.getVersion(),
                    description.getPackageName(),
                    domainPackage,
                    description.getLanguage().jvmVersion(),
                    description.getPackaging() != null ? description.getPackaging().id() : null,
                    entities);
            // Gate generated Bean Validation (@Valid, @NotNull, @Size, the 400 handler) on the
            // validation starter actually being selected — otherwise the imports won't resolve.
            projectCtx.put("hasValidation",
                    description.getRequestedDependencies().containsKey("validation"));
            // Opt-in scaffolding extras (via opts: { "scaffold": ["tests", ...] }). The matching
            // gatedBy flag on a template file toggles whether it is rendered. Uses spring-boot-
            // starter-test, which the Initializr framework adds to every generated project.
            departmentResolver.putVars(projectCtx, optionsContext.department());
            // Every opt the backend templates read, from the one registry (ScaffoldOption).
            ScaffoldOption.putFlags(projectCtx, optionsContext, ScaffoldOption.Side.BACKEND);
            // Springdoc annotations (@Tag/@Operation). The `openapi` dep is force-added in
            // FullstackStarterController when this opt is set, so the swagger imports resolve.
            // With an ldap-auth variant (direct bind or REST) on the build every generated endpoint
            // carries @RequiresPermission at its entity's access level — the security.* classes only
            // exist then — and the backend answers /api/me/roles (page roles in the UI).
            projectCtx.put("hasLdapAuth", description.getRequestedDependencies().containsKey("ldap-auth")
                    || description.getRequestedDependencies().containsKey("ldap-auth-rest"));
            projectCtx.put(ScaffoldOption.SECURED.flag(),
                    optionsContext.hasOption("scaffold", ScaffoldOption.SECURED.key())
                            && (description.getRequestedDependencies().containsKey("ldap-auth")
                                    || description.getRequestedDependencies().containsKey("ldap-auth-rest")));
            // CSV export endpoint + per-entity bulk-delete endpoint. Neither needs an extra
            // dependency (plain Spring Web + Spring Data). bulkDelete is further narrowed per entity
            // to writable, single-PK entities in EntityScaffoldContext (bulkDeleteApplicable).
            // Bulk field-edit endpoint. Narrowed per entity in EntityScaffoldContext (bulkUpdateApplicable)
            // to writable, single-PK entities with ≥1 editable non-PK field. No extra dependency.
            // @Version + a 409 for a stale update. Narrowed per entity (lockingApplicable);
            // hasOptimisticLocking gates the shared stale-update handler.
            EntityScaffoldContext.putOptimisticLocking(projectCtx, entities);
            EntityScaffoldContext.putXlsxExport(projectCtx, entities);
            EntityScaffoldContext.putSavedViews(projectCtx, entities);
            // CurrentUser and GET /api/me: for a workflow, or for a shell that shows who is signed in.
            projectCtx.put("usesCurrentUser", Boolean.TRUE.equals(projectCtx.get("hasWorkflows")) || entityContext.shellUser());
            // @EnableJpaAuditing ships when any entity is audited, not only with the project option.
            EntityScaffoldContext.putAudit(projectCtx, entities);
            // The change log (history option) and who makes a change (audit columns and the log).
            EntityScaffoldContext.putHistory(projectCtx, entities);
            // Demo rows on first start (config/DemoDataLoader, a CommandLineRunner). Backend-only;
            // the seeding order/expressions come from EntityScaffoldContext (seedEntities/seedExpr).
            // Boot 3.4 deprecated @MockBean in favour of Spring Framework 6.2's @MockitoBean and
            // Boot 4 removes it — pick the annotation the generated slice test uses by platform version.
            projectCtx.put("useMockitoBean", usesMockitoBean(description.getPlatformVersion()));
            // The tests option: an API test per entity on an in-memory H2 (the `test` profile).
            GeneratedTests.putBackend(projectCtx, entities, description.getRequestedDependencies().keySet());
            log.info("Rendering backend CRUD scaffolding: set='{}', {} files, {} entities",
                    setKey, files.size(), entities.size());
            FullstackRenderer.render(files, projectCtx, entities, projectRoot);
            // A quoted table/column name (exact case from the DDL) only survives Boot's naming
            // strategy with this one; written from Java, not a template row, so upgraded DBs get it.
            if (EntityScaffoldContext.rendersQuotedName(entities)) {
                Path target = projectRoot.resolve(SqlNames.NAMING_STRATEGY_PATH.replace(
                        "{{packagePath}}", description.getPackageName().replace('.', '/')));
                Files.createDirectories(target.getParent());
                Files.writeString(target, SqlNames.namingStrategySource(description.getPackageName()));
            }
            // With Flyway on the build the schema is the migration's to create, not Hibernate's:
            // the entities' tables as V1, in place of the dependency's placeholder (this
            // contributor runs after the catalog's file contributor).
            if (description.getRequestedDependencies().containsKey(SchemaMigrations.FLYWAY)) {
                entityContext.setGeneratedSchema(SchemaMigrations.writeBaseline(projectRoot, projectCtx, entities,
                        description.getRequestedDependencies().keySet()));
            }
        };
    }

    /**
     * The generated tests run on an in-memory H2 whatever the application's database, so a build
     * without the {@code h2} dependency gets it for its tests only.
     */
    @Bean
    BuildCustomizer<MavenBuild> testDatabaseCustomizer(EntityDefinitionContext entityContext, ProjectOptionsContext optionsContext,
                                                       ProjectDescription description) {
        return build -> {
            if (entityContext.isEmpty() || description.getRequestedDependencies().containsKey("h2")) return;
            if (!GeneratedTests.anyTested(entityContext.all(), optionsContext.hasOption("scaffold", ScaffoldOption.TESTS.key()))) return;
            build.dependencies().add("h2-test", Dependency.withCoordinates("com.h2database", "h2")
                    .scope(DependencyScope.TEST_RUNTIME));
        };
    }

    /**
     * With the generated tests and a database a container fits: the {@code db-tests} Maven profile,
     * which runs the same API tests on that database in Docker ({@code mvn test -Pdb-tests}).
     */
    @Bean
    BuildCustomizer<MavenBuild> dbTestsProfileCustomizer(EntityDefinitionContext entityContext, ProjectOptionsContext optionsContext,
                                                         ProjectDescription description) {
        return build -> {
            if (!dbTestsApply(entityContext, optionsContext)) return;
            var deps = description.getRequestedDependencies().keySet();
            LocalRun.dbTestsDatabase(deps).ifPresent(db ->
                    LocalRun.addDbTestsProfile(build, db, deps.contains(SchemaMigrations.FLYWAY)));
        };
    }

    /** What the {@code db-tests} profile reads from the test resources: the image proxy, SQL Server's licence. */
    @Bean
    ProjectContributor dbTestsResourcesContributor(EntityDefinitionContext entityContext, ProjectOptionsContext optionsContext,
                                                   ProjectDescription description, ContainerImages images) {
        return projectRoot -> {
            if (!dbTestsApply(entityContext, optionsContext)) return;
            var db = LocalRun.dbTestsDatabase(description.getRequestedDependencies().keySet());
            if (db.isEmpty()) return;
            LocalRun.writeTestcontainersProperties(projectRoot, images);
            var licence = LocalRun.licenceAcceptance(db.get());
            if (licence.isPresent()) {
                Path target = projectRoot.resolve("src/test/resources/container-license-acceptance.txt");
                Files.createDirectories(target.getParent());
                Files.writeString(target, licence.get());
            }
        };
    }

    private static boolean dbTestsApply(EntityDefinitionContext entityContext, ProjectOptionsContext optionsContext) {
        return !entityContext.isEmpty()
                && GeneratedTests.anyTested(entityContext.all(), optionsContext.hasOption("scaffold", ScaffoldOption.TESTS.key()));
    }

    /**
     * With the generated tests, the framework's own context-load test ({@code *ApplicationTests})
     * starts on the {@code test} profile too: without it, it needs the application's real database
     * running — which on anything but H2 failed {@code mvn package} on a developer's machine.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 10)
    ProjectContributor testProfileContributor(EntityDefinitionContext entityContext, ProjectOptionsContext optionsContext,
                                              ProjectDescription description) {
        return projectRoot -> {
            if (entityContext.isEmpty()
                    || !GeneratedTests.anyTested(entityContext.all(), optionsContext.hasOption("scaffold", ScaffoldOption.TESTS.key()))) {
                return;
            }
            Path tests = projectRoot.resolve("src/test/java/" + description.getPackageName().replace('.', '/'));
            if (!Files.isDirectory(tests)) return;
            try (var files = Files.list(tests)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith("ApplicationTests.java")).toList()) {
                    String source = Files.readString(file);
                    if (source.contains("@ActiveProfiles") || !source.contains("@SpringBootTest")) continue;
                    source = source.replace("import org.springframework.boot.test.context.SpringBootTest;",
                                    "import org.springframework.boot.test.context.SpringBootTest;\nimport org.springframework.test.context.ActiveProfiles;")
                            .replace("@SpringBootTest", "@SpringBootTest\n@ActiveProfiles(\"test\")");
                    Files.writeString(file, source);
                }
            }
        };
    }

    /** First Boot line whose test starter ships {@code @MockitoBean} (Spring Framework 6.2). */
    static final Version MOCKITO_BEAN_SINCE = Version.parse("3.4.0");

    /** {@code @MockitoBean} for Boot ≥ 3.4, the deprecated {@code @MockBean} before that. */
    static boolean usesMockitoBean(Version platformVersion) {
        return platformVersion != null && platformVersion.compareTo(MOCKITO_BEAN_SINCE) >= 0;
    }
}
