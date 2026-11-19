package com.menora.initializr.extension.fullstack;

import com.menora.initializr.config.DepartmentResolver;
import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.fullstack.EntityDefinition;
import com.menora.initializr.fullstack.EntityScaffoldContext;
import com.menora.initializr.fullstack.FeatureContext;
import com.menora.initializr.fullstack.GeneratedTests;
import com.menora.initializr.fullstack.ScaffoldOption;
import io.spring.initializr.generator.version.Version;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The project-level context the backend CRUD templates are rendered with — and which
 * {@code SchemaBuilder} reads its flags from. One method, so the generation and the
 * {@code /starter-fullstack.ddl} endpoint (which builds the schema without rendering) cannot
 * disagree about which tables a model has.
 */
public final class BackendScaffoldContext {

    private BackendScaffoldContext() {}

    /** What the context is built from: the backend request as resolved. */
    public record Inputs(String artifactId, String groupId, String version, String packageName, String domainPackage,
                         String jvmVersion, String packaging, Version platformVersion,
                         Collection<String> dependencyIds, boolean shellUser) {
    }

    public static Map<String, Object> build(Inputs in, List<EntityDefinition> entities,
                                            ProjectOptionsContext optionsContext, DepartmentResolver departmentResolver) {
        Collection<String> deps = in.dependencyIds();
        Map<String, Object> projectCtx = EntityScaffoldContext.buildProjectContext(
                in.artifactId(), in.groupId(), in.version(), in.packageName(), in.domainPackage(),
                in.jvmVersion(), in.packaging(), entities);
        // Gate generated Bean Validation (@Valid, @NotNull, @Size, the 400 handler) on the
        // validation starter actually being selected — otherwise the imports won't resolve.
        projectCtx.put("hasValidation", deps.contains("validation"));
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
        boolean ldap = deps.contains("ldap-auth") || deps.contains("ldap-auth-rest");
        projectCtx.put("hasLdapAuth", ldap);
        projectCtx.put(ScaffoldOption.SECURED.flag(),
                optionsContext.hasOption("scaffold", ScaffoldOption.SECURED.key()) && ldap);
        // CSV export endpoint + per-entity bulk-delete endpoint. Neither needs an extra
        // dependency (plain Spring Web + Spring Data). bulkDelete is further narrowed per entity
        // to writable, single-PK entities in EntityScaffoldContext (bulkDeleteApplicable).
        // Bulk field-edit endpoint. Narrowed per entity in EntityScaffoldContext (bulkUpdateApplicable)
        // to writable, single-PK entities with ≥1 editable non-PK field. No extra dependency.
        // @Version + a 409 for a stale update. Narrowed per entity (lockingApplicable);
        // hasOptimisticLocking gates the shared stale-update handler.
        FeatureContext.putOptimisticLocking(projectCtx, entities);
        FeatureContext.putUniqueKeys(projectCtx, entities);
        // A soft-deleted row that still holds a unique value: the 409 that says so.
        FeatureContext.putDeletedHolders(projectCtx, entities);
        FeatureContext.putRules(projectCtx, entities);
        FeatureContext.putXlsxExport(projectCtx, entities);
        FeatureContext.putSavedViews(projectCtx, entities);
        // CurrentUser and GET /api/me: for a workflow, or for a shell that shows who is signed in.
        projectCtx.put("usesCurrentUser", Boolean.TRUE.equals(projectCtx.get("hasWorkflows")) || in.shellUser()
                || Boolean.TRUE.equals(projectCtx.get("hasRowAccess")));
        // @EnableJpaAuditing ships when any entity is audited, not only with the project option.
        FeatureContext.putAudit(projectCtx, entities);
        // The change log (history option) and who makes a change (audit columns and the log).
        FeatureContext.putHistory(projectCtx, entities);
        // Entity events (Kafka, webhooks, the browser) and transition e-mails, through one outbox.
        FeatureContext.putEvents(projectCtx, entities, deps);
        // Which columns hold attachment ids: who may download a file, and which uploads no row holds.
        FeatureContext.putAttachments(projectCtx, entities);
        // Demo rows on first start (config/DemoDataLoader, a CommandLineRunner). Backend-only;
        // the seeding order/expressions come from EntityScaffoldContext (seedEntities/seedExpr).
        // Boot 3.4 deprecated @MockBean in favour of Spring Framework 6.2's @MockitoBean and
        // Boot 4 removes it — pick the annotation the generated slice test uses by platform version.
        projectCtx.put("useMockitoBean", FullstackProjectGenerationConfiguration.usesMockitoBean(in.platformVersion()));
        // The tests option: an API test per entity on an in-memory H2 (the `test` profile).
        GeneratedTests.putBackend(projectCtx, entities, deps);
        return projectCtx;
    }
}
