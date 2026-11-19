package com.menora.initializr.config;

import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.VersionService;
import com.menora.initializr.db.entity.ColorPaletteEntity;
import com.menora.initializr.db.entity.EntityTemplateFileEntity;
import com.menora.initializr.db.entity.EntityTemplateSetDefaultDepEntity;
import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import com.menora.initializr.db.entity.VersionKind;
import com.menora.initializr.db.repository.ColorPaletteRepository;
import com.menora.initializr.db.repository.EntityTemplateFileRepository;
import com.menora.initializr.db.repository.EntityTemplateSetDefaultDepRepository;
import com.menora.initializr.db.repository.EntityTemplateSetRepository;
import com.menora.initializr.extension.frontend.FrontendMustacheContext;
import com.menora.initializr.extension.frontend.FrontendProjectDescription;
import com.menora.initializr.extension.frontend.FrontendProjectGenerator;
import com.menora.initializr.fullstack.EntityDefinition;
import com.menora.initializr.fullstack.EntityScaffoldContext;
import com.menora.initializr.fullstack.FeatureContext;
import com.menora.initializr.fullstack.PageContext;
import com.menora.initializr.fullstack.TemplateText;
import com.menora.initializr.fullstack.FullstackRenderer;
import com.menora.initializr.fullstack.GeneratedTests;
import com.menora.initializr.fullstack.FullstackPageValidator;
import com.menora.initializr.fullstack.FullstackRequestValidator;
import com.menora.initializr.fullstack.PageDefinition;
import com.menora.initializr.fullstack.ScaffoldOption;
import com.menora.initializr.fullstack.FullstackStarterRequest;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;
import com.menora.initializr.fullstack.schema.SchemaBuilder;
import com.menora.initializr.fullstack.schema.SchemaDialect;
import com.menora.initializr.fullstack.schema.SchemaMigrations;
import com.menora.initializr.extension.fullstack.BackendScaffoldContext;
import io.spring.initializr.generator.version.Version;
import com.menora.initializr.localrun.ContainerImages;
import com.menora.initializr.localrun.LocalRun;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.menora.initializr.config.FullstackModelFile.*;
import static com.menora.initializr.config.FullstackRootFiles.*;

/**
 * Builds a fullstack project from a request, for {@link FullstackStarterController}'s endpoints: the
 * request checked and resolved as generation needs it, the backend through the standard
 * {@link ProjectGenerationInvoker} pipeline, the frontend as a substrate plus the template set's
 * overlay, and the root files. The callers own the temp directories and clear the per-request
 * contexts.
 */
@Component
public class FullstackProjectAssembler {

    private static final Logger log = LoggerFactory.getLogger(FullstackProjectAssembler.class);

    private final ProjectGenerationInvoker<ProjectRequest> invoker;
    private final ProjectRequestFactory requestFactory;
    private final ProjectOptionsContext optionsContext;
    private final EntityDefinitionContext entityContext;
    private final EntityTemplateSetRepository setRepo;
    private final EntityTemplateFileRepository fileRepo;
    private final EntityTemplateSetDefaultDepRepository defaultDepRepo;
    private final ColorPaletteRepository colorPaletteRepo;
    private final FrontendProjectGenerator frontendGenerator;
    private final FrontendProperties frontendProperties;
    private final VersionService versionService;
    private final DependencyConfigService configService;
    private final DepartmentResolver departmentResolver;
    private final ContainerImages containerImages;

    public FullstackProjectAssembler(ProjectGenerationInvoker<ProjectRequest> invoker,
                                     ProjectRequestFactory requestFactory,
                                     ProjectOptionsContext optionsContext,
                                     EntityDefinitionContext entityContext,
                                     EntityTemplateSetRepository setRepo,
                                     EntityTemplateFileRepository fileRepo,
                                     EntityTemplateSetDefaultDepRepository defaultDepRepo,
                                     ColorPaletteRepository colorPaletteRepo,
                                     FrontendProjectGenerator frontendGenerator,
                                     FrontendProperties frontendProperties,
                                     VersionService versionService,
                                     DependencyConfigService configService,
                                     DepartmentResolver departmentResolver,
                                     ContainerImages containerImages) {
        this.invoker = invoker;
        this.requestFactory = requestFactory;
        this.optionsContext = optionsContext;
        this.entityContext = entityContext;
        this.setRepo = setRepo;
        this.fileRepo = fileRepo;
        this.defaultDepRepo = defaultDepRepo;
        this.colorPaletteRepo = colorPaletteRepo;
        this.frontendGenerator = frontendGenerator;
        this.frontendProperties = frontendProperties;
        this.versionService = versionService;
        this.configService = configService;
        this.departmentResolver = departmentResolver;
        this.containerImages = containerImages;
    }

    private static final String DEFAULT_BACKEND_SET = "spring-jpa-crud";

    private static final String DEFAULT_FRONTEND_SET = "react-tailwind-crud";

    /** Standalone design-system dep reused by template sets tagged {@code MENORA_DIGITAL}. */
    private static final String DESIGN_MENORA_DIGITAL_DEP = "design-menora-digital";

    /** The model's schema as its Flyway baseline is built, and what its dependencies say about it. */
    record BuiltSchema(com.menora.initializr.fullstack.schema.Schema schema, SchemaDialect projectDialect,
                       boolean flyway, SchemaDialect dialect) {}

    /**
     * From here on the request's names are rendered for {@code dialect}: one that is a keyword
     * there is quoted ({@link com.menora.initializr.fullstack.SchemaNames#forDatabase}). A column
     * named like one every table of that database already has cannot be had at all: a 400.
     */
    private static void namesFor(SchemaDialect dialect, List<EntityDefinition> entities) {
        com.menora.initializr.sql.SqlDialect database = com.menora.initializr.sql.SqlDialect.forDepId(dialect.dependencyId());
        for (int i = 0; i < entities.size(); i++) {
            EntityDefinition e = entities.get(i);
            if (e.isView()) continue;
            for (int j = 0; j < e.fields().size(); j++) {
                String column = com.menora.initializr.fullstack.SchemaNames.bareColumnOf(e.fields().get(j));
                if (com.menora.initializr.sql.SqlKeywords.systemColumn(column, database)) {
                    throw new WizardArgumentException("Field '" + e.fields().get(j).name() + "' of " + e.name()
                            + " would be the column " + column + ", which every table on "
                            + com.menora.initializr.fullstack.schema.SchemaSql.label(dialect)
                            + " already has — give the field another name or a column of its own",
                            "entities[" + i + "].fields[" + j + "].name");
                }
            }
        }
        com.menora.initializr.fullstack.SchemaNames.forDatabase(database);
    }

    /**
     * The schema {@link SchemaMigrations#writeBaseline} would write for this model, without
     * rendering it: the request resolved as generation resolves it, the backend context from the
     * same builder ({@link BackendScaffoldContext}). The caller clears the contexts.
     */
    BuiltSchema schemaOf(FullstackStarterRequest body) {
        return schemaOf(body, null);
    }

    /** {@link #schemaOf(FullstackStarterRequest)} written for {@code wanted} — its keywords quoted its way — when it is not the project's own database. */
    BuiltSchema schemaOf(FullstackStarterRequest body, SchemaDialect wanted) {
        Resolved resolved = resolve(body);
        WebProjectRequest request = resolved.request();
        List<String> deps = request.getDependencies() == null ? List.of() : request.getDependencies();
        SchemaDialect dialect = wanted != null ? wanted : SchemaDialect.of(deps);
        if (wanted != null) namesFor(wanted, resolved.entities());
        Map<String, Object> ctx = BackendScaffoldContext.build(new BackendScaffoldContext.Inputs(
                request.getArtifactId(), request.getGroupId(), request.getVersion(), request.getPackageName(),
                resolved.domainPackage(), request.getJavaVersion(), request.getPackaging(),
                Version.safeParse(request.getBootVersion()), deps, resolved.shellUser()),
                resolved.entities(), optionsContext, departmentResolver);
        return new BuiltSchema(SchemaBuilder.build(ctx, resolved.entities()), SchemaDialect.of(deps),
                deps.contains(SchemaMigrations.FLYWAY), dialect);
    }

    /** {@code POSTGRESQL} or {@code postgresql}: a dialect's name or its dependency id. */
    static SchemaDialect dialectOf(String raw) {
        String value = raw.trim();
        for (SchemaDialect d : SchemaDialect.values()) {
            if (d.name().equalsIgnoreCase(value) || d.dependencyId().equalsIgnoreCase(value)) return d;
        }
        throw new WizardArgumentException("dialect '" + value + "' is not one of "
                + java.util.Arrays.stream(SchemaDialect.values()).map(Enum::name).toList(), "dialect");
    }

    /** One side of an update: the request it resolved to, and the schema it wrote a migration for (or null). */
    record Side(WebProjectRequest request, SchemaMigrations.Generated schema) {
    }

    /**
     * Generates one side of an update. The per-request contexts are cleared after each side —
     * the second generation must not see the first one's entities or options.
     */
    Side generateSide(String side, FullstackStarterRequest model, Path dir) throws IOException {
        try {
            WebProjectRequest request = buildArtifacts(model, dir);
            return new Side(request, entityContext.generatedSchema());
        } catch (WizardArgumentException problem) {
            throw problem.at(side);
        } finally {
            entityContext.clear();
            optionsContext.clear();
        }
    }

    /** The request's {@code opts.scaffold} names, for the page validator: a list page may only show
     *  the audit columns when the audit opt is on. */
    private static Set<String> scaffoldOptsOf(FullstackStarterRequest body) {
        List<String> scaffold = body.opts() == null ? null : body.opts().get("scaffold");
        return scaffold == null ? Set.of() : new LinkedHashSet<>(scaffold);
    }

    /**
     * Shared pipeline for {@link FullstackStarterController#generate} and {@link FullstackStarterController#preview}: validates the request,
     * resolves and kind-checks both template sets (fail-fast 400 on a bad key), then renders
     * the backend + frontend + root files into {@code tempDir}. Returns the resolved request
     * (for artifactId / zip naming). The caller owns {@code tempDir} cleanup and clearing the
     * thread-local contexts in a {@code finally}.
     */
    WebProjectRequest buildArtifacts(FullstackStarterRequest body, Path tempDir) throws IOException {
        Resolved resolved = resolve(body);
        List<EntityDefinition> entities = resolved.entities();
        List<PageDefinition> pages = resolved.pages();
        PageDefinition.Nav nav = resolved.nav();
        EntityTemplateSetEntity frontendSet = resolved.frontendSet();
        EntityTemplateSetEntity backendSet = resolved.backendSet();
        WebProjectRequest request = resolved.request();
        String domainPackage = resolved.domainPackage();
        boolean ldap = resolved.ldap();
        // The audit row: the backend request as resolved.
        com.menora.initializr.observability.AuditDetails.request(request);
        com.menora.initializr.observability.AuditDetails.entityCount(entities.size());
        // The user menu and the bell ask the backend who is signed in.
        entityContext.populate(entities, resolved.backendSetKey(), resolved.frontendSetKey(), domainPackage,
                resolved.shellUser());

        // Backend — runs through the standard pipeline. The EntityScaffoldContributor
        // (registered in spring.factories) picks up the populated context.
        // The fullstack project keeps its own model at its root: its backend writes none.
        optionsContext.setEmbedded(true);
        Path backendDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        try {
            GeneratedProjectFiles.moveDirectory(backendDir, tempDir.resolve("backend"));
        } finally {
            // cleanTempFiles drops the invoker's map entry and deletes what the move left behind.
            invoker.cleanTempFiles(backendDir);
        }

        // Frontend — rendered inline outside the Initializr pipeline.
        renderFrontend(frontendSet, request, entities, pages, nav, domainPackage, body.colorPalette(),
                body.dashboardTitle(), body.dashboardOverview(), resolved.locale(), tempDir.resolve("frontend"));

        // Root files
        Set<String> backendDeps = new LinkedHashSet<>(request.getDependencies() == null ? List.of() : request.getDependencies());
        boolean tested = GeneratedTests.anyTested(entities, optionsContext.hasOption("scaffold", ScaffoldOption.TESTS.key()));
        // The whole application in Docker: its services, its backend and its frontend.
        Files.writeString(tempDir.resolve(LocalRun.COMPOSE_FILE),
                LocalRun.compose(request.getArtifactId(), backendDeps, containerImages, true));
        boolean eventsOpt = optionsContext.hasOption("scaffold", ScaffoldOption.EVENTS.key());
        boolean events = entities.stream().anyMatch(e -> FeatureContext.eventsApply(e, eventsOpt));
        boolean mails = entities.stream().anyMatch(FeatureContext::notifies);
        boolean files = entities.stream().anyMatch(e -> e.fields().stream().anyMatch(f -> f.type().isAttachment()));
        Files.writeString(tempDir.resolve("README.md"), buildReadme(request.getArtifactId(), ldap, tested, backendDeps)
                .replace(REOPEN_HEADING, eventsReadme(events, mails, backendDeps) + attachmentsReadme(files, ldap)
                        + REOPEN_HEADING));
        Files.writeString(tempDir.resolve(".gitignore"), ROOT_GITIGNORE);
        Files.writeString(tempDir.resolve(".gitattributes"), ROOT_GITATTRIBUTES);
        Files.writeString(tempDir.resolve(MODEL_FILE), modelJson(body, templatesHash(backendSet, frontendSet)));
        // One set of bytes whatever machine generated it — see normalizeLineEndings.
        GeneratedProjectFiles.normalizeLineEndings(tempDir);
        // Named last, by everything above (an update names its result again, once its migration is in).
        GeneratedSnapshots.stamp(tempDir);
        return request;
    }

    /**
     * A request checked and resolved as generation needs it — the entities with what their pages
     * imply, both template sets, the backend request with its dependencies — with the options
     * context populated. Shared by the generation and the DDL endpoints, so a model the generator
     * refuses is refused there too, in the same words.
     */
    private record Resolved(List<EntityDefinition> entities, List<PageDefinition> pages, PageDefinition.Nav nav,
                            String backendSetKey, String frontendSetKey, EntityTemplateSetEntity backendSet,
                            EntityTemplateSetEntity frontendSet, String locale, WebProjectRequest request,
                            String domainPackage, boolean ldap, boolean shellUser) {
    }

    private Resolved resolve(FullstackStarterRequest body) {
        com.menora.initializr.fullstack.SchemaNames.clearDatabase();
        List<EntityDefinition> validated = FullstackRequestValidator.validateAndConvert(body);
        List<PageDefinition> pages = FullstackPageValidator.validateAndConvert(body.pages(), validated, scaffoldOptsOf(body));
        // An import page switches on its entity's csvImport option, for both render paths.
        List<EntityDefinition> entities = FullstackPageValidator.applyPageImplications(validated, pages);
        PageDefinition.Nav nav;
        try {
            nav = FullstackPageValidator.validateNav(body.nav(), pages, entities);
        } catch (WizardArgumentException e) {
            throw e.at("nav");
        }
        String backendSetKey = orDefault(body.backendTemplateSet(), DEFAULT_BACKEND_SET);
        String frontendSetKey = orDefault(body.frontendTemplateSet(), DEFAULT_FRONTEND_SET);

        // Resolve both sets up front so a missing/wrong-kind key fails fast with a clear 400,
        // instead of silently producing an empty frontend or an un-scaffolded backend.
        EntityTemplateSetEntity backendSet =
                requireSet(backendSetKey, EntityTemplateSetEntity.Kind.BACKEND_JAVA, "backendTemplateSet");
        EntityTemplateSetEntity frontendSet =
                requireSet(frontendSetKey, EntityTemplateSetEntity.Kind.FRONTEND_REACT, "frontendTemplateSet");
        String locale = resolveLocale(body.locale());

        WebProjectRequest request = toWebRequest(body);
        String domainPackage = resolveDomainPackage(body.domainPackage(), request.getPackageName());
        ensureRequiredDeps(request, backendSet, body.dependencies() != null);
        // Page roles are enforced against the generated backend's LDAP groups (/api/me/roles), so
        // they need an ldap-auth variant among the resolved deps (the set defaults include one).
        boolean ldap = request.getDependencies() != null
                && (request.getDependencies().contains("ldap-auth") || request.getDependencies().contains("ldap-auth-rest"));
        pages.stream().filter(p -> !p.roles().isEmpty()).findFirst().ifPresent(p -> {
            if (!ldap) {
                throw new WizardArgumentException("Page '" + p.id()
                        + "' has roles, which need the ldap-auth or ldap-auth-rest dependency",
                        "pages[" + pages.indexOf(p) + "].roles");
            }
        });
        // An entity's own access levels are checked by the same classes: without them the setting
        // would be accepted and do nothing.
        for (EntityDefinition e : entities) {
            if (ldap || e.access().isDefault()) continue;
            throw new WizardArgumentException("Entity '" + e.name()
                    + "' sets access levels, which need the ldap-auth or ldap-auth-rest dependency",
                    "entities[" + entities.indexOf(e) + "].access");
        }
        // So are a workflow's role-limited transitions: the generated service asks the user's groups.
        for (EntityDefinition e : entities) {
            if (e.workflow() == null || ldap) continue;
            e.workflow().transitions().stream().filter(t -> !t.roles().isEmpty()).findFirst().ifPresent(t -> {
                throw new WizardArgumentException("Entity '" + e.name() + "' workflow: transition '" + t.name()
                        + "' has roles, which need the ldap-auth or ldap-auth-rest dependency",
                        "entities[" + entities.indexOf(e) + "].workflow");
            });
        }
        optionsContext.populate(body.opts());
        optionsContext.setDepartment(body.department());
        // A move that e-mails the row's creator reads its created_by, which the audit option keeps.
        boolean auditOpt = optionsContext.hasOption("scaffold", ScaffoldOption.AUDIT.key());
        for (EntityDefinition e : entities) {
            if (e.workflow() == null) continue;
            Boolean auditOverride = e.opts().get("audit");
            if (auditOverride != null ? auditOverride : auditOpt) continue;
            e.workflow().transitions().stream().filter(t -> t.mailTo().contains("creator")).findFirst().ifPresent(t -> {
                throw new WizardArgumentException("Entity '" + e.name() + "' workflow: transition '" + t.name()
                        + "' notifies the creator, which needs the audit option on " + e.name(),
                        "entities[" + entities.indexOf(e) + "].workflow");
            });
        }
        // The `openapi` scaffold opt enriches generated controllers with springdoc @Tag/@Operation
        // annotations, which need the springdoc starter on the classpath. Force-add it here (before
        // generation) so the dep lands in the pom — analogous to how the `tests` opt relies on
        // spring-boot-starter-test always being present.
        if (optionsContext.hasOption("scaffold", "openapi")) {
            Set<String> deps = new LinkedHashSet<>(
                    request.getDependencies() == null ? List.of() : request.getDependencies());
            deps.add("openapi");
            request.setDependencies(new ArrayList<>(deps));
        }
        // The fullstack request never carries a datasource role sub-option, so default any
        // selected database dep to its primary datasource — otherwise its config class (gated
        // on <dep>-primary/<dep>-secondary) is skipped and the backend has no DataSource bean.
        Set<String> depIds = new LinkedHashSet<>(
                request.getDependencies() == null ? List.of() : request.getDependencies());
        optionsContext.defaultDatasourceRoles(depIds, configService.getAllSubOptions());
        checkSampleClassClashes(entities, depIds);
        namesFor(SchemaDialect.of(depIds), entities);
        checkCountedTables(entities, optionsContext.hasOption("scaffold", ScaffoldOption.INVERSE_COLLECTIONS.key()));
        return new Resolved(entities, pages, nav, backendSetKey, frontendSetKey, backendSet, frontendSet, locale,
                request, domainPackage, ldap, nav != null && (nav.userMenu() || nav.notifications()));
    }

    /**
     * A parent counts its child rows in SQL written into its mapping (a {@code @Formula}), where a
     * quoted name of another table cannot be written: Hibernate reads it as the parent's own. So a
     * child whose table or link column has to be quoted — a keyword on the project's database, an
     * exact-case name — cannot be listed under a parent. A 400 saying which name to change.
     */
    private static void checkCountedTables(List<EntityDefinition> entities, boolean inverseCollections) {
        for (int i = 0; i < entities.size(); i++) {
            EntityDefinition child = entities.get(i);
            if (child.isView()) continue;
            for (com.menora.initializr.fullstack.RelationDefinition link : child.relations()) {
                EntityDefinition parent = entities.stream()
                        .filter(e -> e.name().equalsIgnoreCase(link.targetEntity())).findFirst().orElse(null);
                if (parent == null) continue;
                boolean counted = inverseCollections || parent.children().stream()
                        .anyMatch(list -> list.targetEntity().equalsIgnoreCase(child.name()));
                if (!counted) continue;
                String table = com.menora.initializr.fullstack.SchemaNames.tableOf(child);
                String column = com.menora.initializr.fullstack.SchemaNames.joinColumnOf(link);
                if (!com.menora.initializr.fullstack.SchemaNames.formulaCanName(
                        com.menora.initializr.fullstack.SchemaNames.tableOf(child))
                        || (com.menora.initializr.fullstack.SchemaNames.schemaOf(child) != null
                        && !com.menora.initializr.fullstack.SchemaNames.formulaCanName(
                        com.menora.initializr.fullstack.SchemaNames.schemaOf(child)))) {
                    throw new WizardArgumentException(child.name() + "'s table is written " + table + " (a keyword, or a name"
                            + " kept in exact case), which the SQL that counts a " + parent.name() + "'s " + child.name()
                            + " rows cannot name — give " + child.name() + " a tableName that needs no quotes",
                            "entities[" + i + "].tableName");
                }
                if (!com.menora.initializr.fullstack.SchemaNames.formulaCanName(column)) {
                    throw new WizardArgumentException(child.name() + "'s link '" + link.fieldName() + "' is the column " + column
                            + " (a keyword, or a name kept in exact case), which the SQL that counts a " + parent.name() + "'s "
                            + child.name() + " rows cannot name — give the link a joinColumn that needs no quotes",
                            "entities[" + i + "].relations[" + child.relations().indexOf(link) + "].joinColumn");
                }
            }
        }
    }

    /** The classes an entity {@code X} is generated as: {@code X}, {@code XRepository}, {@code XDto}, … */
    private static final List<String> ENTITY_CLASS_SUFFIXES =
            List.of("", "Repository", "Dto", "Service", "Controller", "Rules", "Id");

    /**
     * An entity's classes must not be ones a selected dependency writes too — data-jpa's sample
     * {@code User} and {@code UserRepository}, a sample {@code MailService}: the two would be one
     * file, or two beans of one name, and the project would not start. A 400 naming the entity
     * and the dependency, before anything is generated. (The names every project has are refused
     * earlier, for every request: {@code fullstack/ReservedNames}.)
     */
    private void checkSampleClassClashes(List<EntityDefinition> entities, Set<String> depIds) {
        Map<String, com.menora.initializr.db.entity.FileContributionEntity> written = new LinkedHashMap<>();
        for (com.menora.initializr.db.entity.FileContributionEntity fc : configService.getFileContributions(depIds)) {
            String path = fc.getTargetPath();
            if (path == null || !path.endsWith(".java")
                    || fc.getFileType() == com.menora.initializr.db.entity.FileContributionEntity.FileType.DELETE) {
                continue;
            }
            // A sample behind a sub-option is written only when that option was picked.
            if (fc.getSubOptionId() != null && !optionsContext.hasOption(fc.getDependencyId(), fc.getSubOptionId())) continue;
            String file = path.substring(path.lastIndexOf('/') + 1);
            written.putIfAbsent(file.substring(0, file.length() - ".java".length()).toLowerCase(java.util.Locale.ROOT), fc);
        }
        for (int i = 0; i < entities.size(); i++) {
            String name = entities.get(i).name();
            for (String suffix : ENTITY_CLASS_SUFFIXES) {
                com.menora.initializr.db.entity.FileContributionEntity fc =
                        written.get((name + suffix).toLowerCase(java.util.Locale.ROOT));
                if (fc == null) continue;
                String from = "__common__".equals(fc.getDependencyId()) ? "every project"
                        : "the '" + fc.getDependencyId() + "' dependency"
                        + (fc.getSubOptionId() == null ? "" : " (its '" + fc.getSubOptionId() + "' option)");
                throw new WizardArgumentException("Entity '" + name + "' would be generated as " + name + suffix
                        + ", a class " + from + " already writes — rename the entity"
                        + (fc.getSubOptionId() == null ? "" : " or leave that option out"),
                        "entities[" + i + "].name");
            }
        }
    }

    /**
     * One id for the two template sets a project is rendered with: the SHA-256 of every file's
     * path, gate and content (line endings aside, so the same templates hash alike on every
     * machine). It changes when a template is edited — in the seed or in admin — and tells an
     * update whether the project was generated with the templates of the day. The dependency
     * catalog's files are not part of it.
     */
    String templatesHash(EntityTemplateSetEntity backendSet, EntityTemplateSetEntity frontendSet) {
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            for (EntityTemplateSetEntity set : List.of(backendSet, frontendSet)) {
                sha.update(set.getSetKey().getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
                List<EntityTemplateFileEntity> files = new ArrayList<>(fileRepo.findBySetIdOrderBySortOrderAsc(set.getId()));
                files.sort(java.util.Comparator.comparing(EntityTemplateFileEntity::getPathTemplate));
                for (EntityTemplateFileEntity file : files) {
                    for (String part : new String[] {file.getPathTemplate(), file.getGatedBy(), file.getContent()}) {
                        if (part != null) sha.update(part.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
                        sha.update((byte) 0);
                    }
                }
            }
            return java.util.HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Resolves a template set by key and asserts its kind, throwing HTTP 400 otherwise. */
    private EntityTemplateSetEntity requireSet(String setKey, EntityTemplateSetEntity.Kind kind, String fieldName) {
        EntityTemplateSetEntity set = setRepo.findBySetKey(setKey).orElseThrow(() ->
                new WizardArgumentException(fieldName + " '" + setKey + "' not found", fieldName));
        if (set.getKind() != kind) {
            throw new WizardArgumentException(fieldName + " '" + setKey
                    + "' is not a " + kind + " set (kind=" + set.getKind() + ")", fieldName);
        }
        return set;
    }

    /**
     * Resolves the package the generated CRUD classes are scaffolded under. Defaults to the
     * project's base {@code packageName}; a caller-supplied value must be a syntactically valid
     * Java package and live at or below the base package, so Spring's default component/entity
     * scanning (rooted at the {@code @SpringBootApplication} package) still finds the beans.
     */
    private static String resolveDomainPackage(String requested, String basePackage) {
        if (requested == null || requested.isBlank()) {
            return basePackage;
        }
        String domain = requested.trim();
        if (!isValidPackageName(domain)) {
            throw new WizardArgumentException("domainPackage '" + domain + "' is not a valid Java package", "domainPackage");
        }
        if (!domain.equals(basePackage) && !domain.startsWith(basePackage + ".")) {
            throw new WizardArgumentException("domainPackage '" + domain
                    + "' must be the base package '" + basePackage + "' or a sub-package of it", "domainPackage");
        }
        return domain;
    }

    /** Dot-separated Java identifiers, each a valid identifier and not empty. */
    private static boolean isValidPackageName(String pkg) {
        if (pkg == null || pkg.isBlank()) return false;
        for (String segment : pkg.split("\\.", -1)) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) return false;
            for (int i = 1; i < segment.length(); i++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(i))) return false;
            }
        }
        return true;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** The framework's request for the backend half: an unknown Boot or Java version is a clean
     *  400 from the shared builder, which also takes what the caller left out from the metadata. */
    private WebProjectRequest toWebRequest(FullstackStarterRequest body) {
        return requestFactory.create(new ProjectRequestFactory.Base(body.groupId(), body.artifactId(), body.name(),
                body.description(), body.packageName(), body.type(), body.language(), body.bootVersion(),
                body.packaging(), body.javaVersion(), body.version(), body.configurationFileFormat()),
                body.dependencies());
    }

    /** If the caller did not specify a {@code dependencies} field at all, fall back
     *  to the admin-configured default deps for the chosen backend set so API
     *  consumers who haven't read {@code /metadata/entity-template-sets} still get
     *  a working project. When a list is supplied (even empty), it is respected
     *  exactly — nothing is force-added. The UI always sends the user's final
     *  selection here, so this is the user-respecting path. */
    private void ensureRequiredDeps(WebProjectRequest request, EntityTemplateSetEntity backendSet,
                                    boolean callerSpecifiedDeps) {
        if (callerSpecifiedDeps) {
            // Respect explicit intent — even an empty list means "I want nothing extra".
            return;
        }
        Set<String> deps = new LinkedHashSet<>(
                request.getDependencies() == null ? List.of() : request.getDependencies());
        for (EntityTemplateSetDefaultDepEntity dd :
                defaultDepRepo.findBySetIdOrderBySortOrderAsc(backendSet.getId())) {
            deps.add(dd.getDepId());
        }
        request.setDependencies(new ArrayList<>(deps));
    }

    /**
     * Renders the frontend in two layers, mirroring how the backend reuses the standard
     * Initializr pipeline and only adds entity scaffolding on top:
     *
     * <ol>
     *   <li><b>Substrate</b> — the standalone {@link FrontendProjectGenerator} lays down the
     *       FSD skeleton, tooling configs (tsconfig/eslint/prettier/husky/Dockerfile/nginx),
     *       layer barrels + READMEs, and the dev {@code .env}/Vite proxy wiring.</li>
     *   <li><b>Overlay</b> — the template set contributes only the per-entity CRUD files and
     *       the fullstack-owned shared UI + Tailwind-v4 theming, rendered last so it overwrites
     *       the substrate where paths collide (e.g. {@code App.tsx}, {@code src/pages/index.ts}).</li>
     * </ol>
     */
    private void renderFrontend(EntityTemplateSetEntity set, WebProjectRequest request,
                                List<EntityDefinition> entities, List<PageDefinition> pages, PageDefinition.Nav nav,
                                String domainPackage, String colorPaletteId, String dashboardTitle, String dashboardOverview,
                                String locale, Path targetDir) throws IOException {
        // 1. Substrate — reuse the standalone frontend generator.
        FrontendProjectDescription desc = buildFrontendDescription(request, colorPaletteId);
        if (set.getDesignSystem() == EntityTemplateSetEntity.DesignSystem.MENORA_DIGITAL) {
            // The Menora Digital set builds on the standalone design-system dep: selecting it here
            // lays down the tokens/component ports under src/shared/ui/menora/, the brand logo in
            // public/, and the @fontsource/assistant npm dep — the overlay then re-skins the
            // Tailwind theme/shell on top. (Its substrate index.css/App.tsx/HomePage are overwritten
            // or deleted by the overlay exactly like the default set's.)
            desc.getDependencies().add(DESIGN_MENORA_DIGITAL_DEP);
        }
        frontendGenerator.renderInto(targetDir, desc);
        // The standalone landing page is replaced by the per-entity pages below.
        FileSystemUtils.deleteRecursively(targetDir.resolve("src/pages/home"));

        // 2. Overlay — per-entity CRUD files + fullstack-owned shared UI / theming.
        List<EntityTemplateFileEntity> files = fileRepo.findBySetIdOrderBySortOrderAsc(set.getId());
        ColorPaletteEntity palette = resolvePalette(colorPaletteId);
        Map<String, Object> projectCtx = EntityScaffoldContext.buildProjectContext(
                request.getArtifactId(),
                request.getGroupId(),
                request.getVersion(),
                request.getPackageName(),
                domainPackage,
                request.getJavaVersion(),
                request.getPackaging(),
                entities);
        // Overlay the frontend view-model (dep flags, versions, palette with HSL forms, backend
        // pairing) onto the entity-scaffold context so per-entity templates see both shapes. This
        // replaces the plain palette from EntityScaffoldContext with the HSL-bearing one.
        projectCtx.putAll(FrontendMustacheContext.build(desc, desc.getDependencies(), optionsContext, palette));
        departmentResolver.putVars(projectCtx, desc.getDepartment());
        // Gate the dev-mode `userinfo` header (read by the backend's @RequiresPermission aspect)
        // on the backend actually including the LDAP authorization dependency.
        boolean hasLdapAuth = request.getDependencies() != null
                && (request.getDependencies().contains("ldap-auth")
                        || request.getDependencies().contains("ldap-auth-rest"));
        projectCtx.put("hasLdapAuth", hasLdapAuth);
        // The API client sends the dev user's `userinfo` header when something reads it: the ldap
        // permission check, or a workflow (who claims a row, whose inbox).
        projectCtx.put("sendsDevUser", hasLdapAuth || Boolean.TRUE.equals(projectCtx.get("hasWorkflows"))
                || (nav != null && nav.userMenu()));
        // Opt-in scaffolding flags must be set on the frontend context too — the per-entity FE
        // templates gate audit columns / inverse-collection counts on these. The backend config
        // sets its own copy; the two render paths do not share a context.
        ScaffoldOption.putFlags(projectCtx, optionsContext, ScaffoldOption.Side.FRONTEND);
        // CSV export button + bulk-delete selection UI. Mirrors the backend config — the FE per-entity
        // page gates the Export button on optScaffoldCsvExport and the row checkboxes on the per-entity
        // bulkDeleteApplicable (derived in EntityScaffoldContext from optScaffoldBulkDelete).
        FeatureContext.putCsvImport(projectCtx, entities);
        // Bulk field-edit selection UI. Like bulkDelete, narrowed per entity in EntityScaffoldContext
        // (bulkUpdateApplicable) to writable, single-PK entities that have ≥1 editable non-PK field.
        // The record's version rides along in the DTO; the page turns a stale 409 into the
        // conflict dialog. Mirrors the backend config.
        FeatureContext.putOptimisticLocking(projectCtx, entities);
        FeatureContext.putUniqueKeys(projectCtx, entities);
        // A soft-deleted row that still holds a unique value: the 409 that says so.
        FeatureContext.putDeletedHolders(projectCtx, entities);
        FeatureContext.putRules(projectCtx, entities);
        FeatureContext.putXlsxExport(projectCtx, entities);
        FeatureContext.putSavedViews(projectCtx, entities);
        // Who changed a row (audit) and the change history: the strings, the shared history panel.
        FeatureContext.putAudit(projectCtx, entities);
        FeatureContext.putHistory(projectCtx, entities);
        // Entity events: the live stream the lists and the bell listen to.
        FeatureContext.putEvents(projectCtx, entities,
                request.getDependencies() == null ? List.of() : request.getDependencies());
        // A change is recorded under the caller's name: the dev user travels with the calls.
        if (Boolean.TRUE.equals(projectCtx.get("usesRequestUser"))) projectCtx.put("sendsDevUser", true);
        // A view belongs to the user who saved it: the dev user travels with the calls.
        if (Boolean.TRUE.equals(projectCtx.get("hasSavedViews"))) projectCtx.put("sendsDevUser", true);
        // Table cells edited in place (frontend only — saved through the existing PUT).
        FeatureContext.putInlineEdit(projectCtx, entities);
        // Optional dashboard header overrides — blank/absent leaves the template's built-in fallback.
        putIfPresent(projectCtx, "dashboardTitle", dashboardTitle);
        putIfPresent(projectCtx, "dashboardOverview", dashboardOverview);
        // Chrome-string language. The overlay's src/shared/i18n/strings.ts ships both tables and
        // picks one on `isHebrew`; `locale` is also the BCP-47 tag the generated app hands to Intl.
        // Frontend-only — the backend's ProblemDetail messages stay English.
        projectCtx.put("locale", locale);
        projectCtx.put("isHebrew", "he".equals(locale));
        // Page layout (dashboards / list pages / tabs / master-detail / record / report). No pages
        // → hasPages=false, the classic shell. Runs last: a report screen resolves its Export
        // button from optScaffoldCsvExport, which is only in the context by this point.
        PageContext.putPageContext(projectCtx, pages, nav);
        // The tests option: Vitest, a test of each entity's validator and list page, and of the
        // shared CSV reader and route parsing where the app has them.
        GeneratedTests.putFrontend(projectCtx, entities);
        log.info("Rendering frontend: substrate via FrontendProjectGenerator + {} overlay files, "
                        + "{} entities (set='{}', palette='{}')",
                files.size(), entities.size(), set.getSetKey(), palette.getPaletteId());
        FullstackRenderer.render(files, projectCtx, entities, targetDir);
        // 3. The overlay overwrites the substrate's package.json (both shipped sets do, to pin the
        // Tailwind v4 stack) — fold the substrate's eslint/prettier/husky packages and scripts back
        // in so the eslint.config.js / .husky/pre-commit it wrote actually work.
        frontendGenerator.mergeSubstrateTooling(targetDir, desc.getDependencies());
    }

    /**
     * Builds the {@link FrontendProjectDescription} that drives substrate generation. The paired
     * backend is known from the same request, so dev {@code .env}/Vite-proxy wiring is enabled by
     * default. No frontend deps are defaulted here — the fullstack styling/tooling stack ships via the
     * overlay and the {@code __common__} substrate, not via selectable frontend dependencies. The one
     * exception is added by {@link #renderFrontend}: a set tagged {@code MENORA_DIGITAL} pulls in the
     * standalone {@code design-menora-digital} dep so its tokens/components/logo are reused.
     */
    private FrontendProjectDescription buildFrontendDescription(WebProjectRequest request, String colorPaletteId) {
        FrontendProjectDescription desc = new FrontendProjectDescription();
        desc.setProjectName(request.getArtifactId() + "-frontend");
        desc.setAppTitle(request.getArtifactId());
        desc.setRtl(optionsContext.hasOption("scaffold", "rtl"));
        desc.setColorPaletteId(colorPaletteId);
        desc.setDepartment(optionsContext.department());
        desc.setApiBaseUrl("http://localhost:8080");
        desc.setBackendArtifactId(request.getArtifactId());
        String react = versionService.defaultId(VersionKind.REACT);
        desc.setReactVersion(react);
        desc.setNodeVersion(versionService.defaultId(VersionKind.NODE));
        desc.setPackageManager(versionService.defaultId(VersionKind.PACKAGE_MANAGER));
        desc.setTypescriptVersion(frontendProperties.getPinned().getTypescript());
        desc.setViteVersion(frontendProperties.getPinned().getVite());
        versionService.reactSemver(react).ifPresent(desc::setReactPackageVersion);
        versionService.reactTypesSemver(react).ifPresent(desc::setReactTypesVersion);
        return desc;
    }

    /**
     * Resolves the palette whose colors theme the generated frontend. Order:
     * explicit id (if present in DB) → the {@code isDefault} palette (Menora) →
     * a hardcoded sentinel so generation never crashes on an unseeded DB.
     * Mirrors {@code FrontendProjectGenerator.resolvePalette}.
     */
    private ColorPaletteEntity resolvePalette(String paletteId) {
        if (paletteId != null && !paletteId.isBlank()) {
            var hit = colorPaletteRepo.findByPaletteId(paletteId);
            if (hit.isPresent()) return hit.get();
            log.warn("Requested colorPalette '{}' not found — falling back to default", paletteId);
        }
        var def = colorPaletteRepo.findFirstByIsDefaultTrueOrderBySortOrderAsc();
        if (def.isPresent()) return def.get();
        log.warn("No default color palette in DB — using hardcoded sentinel");
        ColorPaletteEntity sentinel = new ColorPaletteEntity();
        sentinel.setPaletteId("sentinel");
        sentinel.setName("Sentinel");
        sentinel.setPrimary("#0a2b6b");
        sentinel.setSecondary("#2d3344");
        sentinel.setAccent("#ffc700");
        sentinel.setError("#d32f2f");
        return sentinel;
    }

    private static String orDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /** Supported chrome-string languages of the generated frontend (see {@code i18n-strings.ts.mustache}). */
    private static final Set<String> SUPPORTED_LOCALES = Set.copyOf(com.menora.initializr.fullstack.FullstackSchema.LOCALES);

    /** Blank → {@code en}; anything outside {@link #SUPPORTED_LOCALES} is a 400, never a silent fallback. */
    private static String resolveLocale(String raw) {
        String locale = orDefault(raw, "en").trim().toLowerCase(java.util.Locale.ROOT);
        if (!SUPPORTED_LOCALES.contains(locale)) {
            throw new WizardArgumentException("locale '" + raw + "' is not supported (expected one of "
                    + String.join(", ", SUPPORTED_LOCALES.stream().sorted().toList()) + ")", "locale");
        }
        return locale;
    }

    /** Puts a trimmed value under {@code key} only when non-blank, so a Mustache
     *  {@code {{#key}}…{{^key}}fallback{{/key}}} resolves to the template's built-in default. */
    private static void putIfPresent(Map<String, Object> ctx, String key, String value) {
        if (value != null && !value.isBlank()) {
            // Raw under the key (the section test), escaped for where the templates splice it.
            TemplateText.putText(ctx, key, value.trim());
        }
    }
}
