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
import com.menora.initializr.fullstack.ScaffoldOption;
import com.menora.initializr.sql.SqlNames;
import io.spring.initializr.generator.project.ProjectDescription;
import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import io.spring.initializr.generator.project.contributor.ProjectContributor;
import io.spring.initializr.generator.version.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
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
            // Per-endpoint @RequiresPermission. The security.* classes only exist when an
            // ldap-auth variant (direct bind or REST) is on the build, so the flag requires one —
            // otherwise the imports wouldn't resolve.
            // With an ldap-auth variant the backend also answers /api/me/roles (page roles in the UI).
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
            // CurrentUser and GET /api/me: for a workflow, or for a shell that shows who is signed in.
            projectCtx.put("usesCurrentUser", Boolean.TRUE.equals(projectCtx.get("hasWorkflows")) || entityContext.shellUser());
            // @EnableJpaAuditing ships when any entity is audited, not only with the project option.
            EntityScaffoldContext.putAudit(projectCtx, entities);
            // Demo rows on first start (config/DemoDataLoader, a CommandLineRunner). Backend-only;
            // the seeding order/expressions come from EntityScaffoldContext (seedEntities/seedExpr).
            // Boot 3.4 deprecated @MockBean in favour of Spring Framework 6.2's @MockitoBean and
            // Boot 4 removes it — pick the annotation the generated slice test uses by platform version.
            projectCtx.put("useMockitoBean", usesMockitoBean(description.getPlatformVersion()));
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
        };
    }

    /** First Boot line whose test starter ships {@code @MockitoBean} (Spring Framework 6.2). */
    static final Version MOCKITO_BEAN_SINCE = Version.parse("3.4.0");

    /** {@code @MockitoBean} for Boot ≥ 3.4, the deprecated {@code @MockBean} before that. */
    static boolean usesMockitoBean(Version platformVersion) {
        return platformVersion != null && platformVersion.compareTo(MOCKITO_BEAN_SINCE) >= 0;
    }
}
