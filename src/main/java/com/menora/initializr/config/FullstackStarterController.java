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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import com.menora.initializr.fullstack.patch.ProjectUpdateBuilder;
import com.menora.initializr.fullstack.schema.SchemaMigrations;
import com.menora.initializr.localrun.ContainerImages;
import com.menora.initializr.localrun.LocalRun;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
import java.util.stream.Stream;

/**
 * POST {@code /starter-fullstack.zip} — generates a fullstack scaffold (Spring Boot
 * backend + React frontend) for a list of user-defined entities.
 *
 * <p>Backend is produced via the standard {@link ProjectGenerationInvoker} pipeline;
 * the {@code EntityScaffoldContributor} (registered in {@code spring.factories}) runs
 * inside the per-request child context and renders per-entity Java files. Frontend
 * is rendered inline outside the pipeline — the Initializr framework is shaped for
 * Java projects and doesn't know about Vite.
 *
 * <p>Output is a single ZIP with {@code backend/}, {@code frontend/}, and a root
 * {@code README.md}.
 */
@RestController
public class FullstackStarterController {

    private static final Logger log = LoggerFactory.getLogger(FullstackStarterController.class);

    private static final String DEFAULT_BACKEND_SET = "spring-jpa-crud";
    private static final String DEFAULT_FRONTEND_SET = "react-tailwind-crud";
    /** Standalone design-system dep reused by template sets tagged {@code MENORA_DIGITAL}. */
    private static final String DESIGN_MENORA_DIGITAL_DEP = "design-menora-digital";

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

    public FullstackStarterController(ProjectGenerationInvoker<ProjectRequest> invoker,
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

    @PostMapping("/starter-fullstack.zip")
    public ResponseEntity<byte[]> generate(@RequestBody FullstackStarterRequest body) throws IOException {
        Path tempDir = Files.createTempDirectory("fullstack-");
        try {
            WebProjectRequest request = buildArtifacts(body, tempDir);
            byte[] zipBytes = GeneratedProjectFiles.zipDirectory(tempDir, request.getArtifactId());
            // The id the model file carries too: a client keeps it with the model it sent, and
            // hands it back when it later asks for an update to this project.
            String templates = templatesHashOf(Files.readAllBytes(tempDir.resolve(MODEL_FILE)));
            return ResponseEntity.ok()
                    .header("X-Templates-Hash", templates == null ? "" : templates)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + request.getArtifactId() + ".zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zipBytes);
        } finally {
            GeneratedProjectFiles.deleteLater(tempDir);
            entityContext.clear();
            optionsContext.clear();
        }
    }

    @PostMapping("/starter-fullstack.preview")
    public ProjectPreviewController.PreviewResponse preview(@RequestBody FullstackStarterRequest body) throws IOException {
        Path tempDir = Files.createTempDirectory("fullstack-preview-");
        try {
            buildArtifacts(body, tempDir);

            List<ProjectPreviewController.PreviewFile> files = new ArrayList<>();
            final Path root = tempDir;
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> {
                            String rel = root.relativize(p).toString().replace('\\', '/');
                            files.add(new ProjectPreviewController.PreviewFile(rel, GeneratedProjectFiles.readSafely(p)));
                        });
            }
            List<String> paths = files.stream()
                    .map(ProjectPreviewController.PreviewFile::path).sorted().toList();
            return new ProjectPreviewController.PreviewResponse(files, PreviewTreeBuilder.buildTree(paths));
        } finally {
            GeneratedProjectFiles.deleteLater(tempDir);
            entityContext.clear();
            optionsContext.clear();
        }
    }

    /**
     * The two models of an update: what the project was generated from and what it should be
     * now. {@code baseTemplatesHash} is the {@code generator.templatesHash} of the project's
     * {@code menora-model.json}, when the client has it — the notes then say whether the
     * templates are still the ones the project was generated with.
     */
    public record FullstackPatchRequest(FullstackStarterRequest base, FullstackStarterRequest target,
                                        String baseTemplatesHash) {}

    /**
     * What a model change does to a project that already exists: both models are generated,
     * now, and the difference comes back as a zip — a patch for {@code git apply --3way}, the new
     * and changed files whole, and notes (see {@link ProjectUpdateBuilder}). A problem with one
     * of the models is a 400 like the zip endpoint's, its {@code path} under {@code base.} or
     * {@code target.} and the side in {@code side}.
     */
    @PostMapping("/starter-fullstack.patch")
    public ResponseEntity<byte[]> patch(@RequestBody FullstackPatchRequest body) throws IOException {
        if (body == null || body.base() == null || body.target() == null) {
            throw new WizardArgumentException("An update needs both models: 'base' (what the project was"
                    + " generated from) and 'target' (what it should be now)");
        }
        Path baseDir = Files.createTempDirectory("fullstack-update-base-");
        Path targetDir = Files.createTempDirectory("fullstack-update-target-");
        try {
            Side base = generateSide("base", body.base(), baseDir);
            Side changed = generateSide("target", body.target(), targetDir);
            WebProjectRequest request = changed.request();
            Map<String, byte[]> baseTree = GeneratedProjectFiles.readTree(baseDir);
            Map<String, byte[]> target = GeneratedProjectFiles.readTree(targetDir);
            // With Flyway, the first migration has run and stays as it was; what the model
            // changed is a migration of its own.
            java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
            SchemaMigrations.carryBaselineOver(baseTree, target, base.schema(), changed.schema(),
                    now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
                    now.toLocalDate().toString());
            ProjectUpdateBuilder.ProjectUpdate update = ProjectUpdateBuilder.build(
                    baseTree, target,
                    new ProjectUpdateBuilder.Info(request.getArtifactId(), body.baseTemplatesHash(),
                            templatesHashOf(target.get(MODEL_FILE))),
                    // Never edited by hand and rewritten whole by every generation: replaced, not patched.
                    Set.of(MODEL_FILE));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + request.getArtifactId() + "-update.zip\"")
                    .header("X-Update-Added", String.valueOf(update.added().size()))
                    .header("X-Update-Changed", String.valueOf(update.changed().size()))
                    .header("X-Update-Deleted", String.valueOf(update.deleted().size()))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(update.zip());
        } finally {
            GeneratedProjectFiles.deleteLater(baseDir);
            GeneratedProjectFiles.deleteLater(targetDir);
            entityContext.clear();
            optionsContext.clear();
        }
    }

    /** One side of an update: the request it resolved to, and the schema it wrote a migration for (or null). */
    private record Side(WebProjectRequest request, SchemaMigrations.Generated schema) {
    }

    /**
     * Generates one side of an update. The per-request contexts are cleared after each side —
     * the second generation must not see the first one's entities or options.
     */
    private Side generateSide(String side, FullstackStarterRequest model, Path dir) throws IOException {
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
     * Shared pipeline for {@link #generate} and {@link #preview}: validates the request,
     * resolves and kind-checks both template sets (fail-fast 400 on a bad key), then renders
     * the backend + frontend + root files into {@code tempDir}. Returns the resolved request
     * (for artifactId / zip naming). The caller owns {@code tempDir} cleanup and clearing the
     * thread-local contexts in a {@code finally}.
     */
    private WebProjectRequest buildArtifacts(FullstackStarterRequest body, Path tempDir) throws IOException {
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
        // The audit row: the backend request as resolved (the dependencies added below count too).
        com.menora.initializr.observability.AuditDetails.request(request);
        com.menora.initializr.observability.AuditDetails.entityCount(entities.size());
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
        // The user menu and the bell ask the backend who is signed in.
        entityContext.populate(entities, backendSetKey, frontendSetKey, domainPackage,
                nav != null && (nav.userMenu() || nav.notifications()));

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
                body.dashboardTitle(), body.dashboardOverview(), locale, tempDir.resolve("frontend"));

        // Root files
        Set<String> backendDeps = new LinkedHashSet<>(request.getDependencies() == null ? List.of() : request.getDependencies());
        boolean tested = GeneratedTests.anyTested(entities, optionsContext.hasOption("scaffold", ScaffoldOption.TESTS.key()));
        // The whole application in Docker: its services, its backend and its frontend.
        Files.writeString(tempDir.resolve(LocalRun.COMPOSE_FILE),
                LocalRun.compose(request.getArtifactId(), backendDeps, containerImages, true));
        boolean eventsOpt = optionsContext.hasOption("scaffold", ScaffoldOption.EVENTS.key());
        boolean events = entities.stream().anyMatch(e -> EntityScaffoldContext.eventsApply(e, eventsOpt));
        boolean mails = entities.stream().anyMatch(EntityScaffoldContext::notifies);
        Files.writeString(tempDir.resolve("README.md"), buildReadme(request.getArtifactId(), ldap, tested, backendDeps)
                .replace(REOPEN_HEADING, eventsReadme(events, mails, backendDeps) + REOPEN_HEADING));
        Files.writeString(tempDir.resolve(".gitignore"), ROOT_GITIGNORE);
        Files.writeString(tempDir.resolve(".gitattributes"), ROOT_GITATTRIBUTES);
        Files.writeString(tempDir.resolve(MODEL_FILE), modelJson(body, templatesHash(backendSet, frontendSet)));
        // One set of bytes whatever machine generated it — see normalizeLineEndings.
        GeneratedProjectFiles.normalizeLineEndings(tempDir);
        return request;
    }

    /** The model the project was generated from, at the zip root, for the editor's Import JSON. */
    static final String MODEL_FILE = "menora-model.json";
    /** Marks a model file that holds the request itself (a request sent without the editor's snapshot). */
    static final String REQUEST_MODEL_FORMAT = "menora-fullstack-request/1";
    private static final com.fasterxml.jackson.databind.ObjectMapper MODEL_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * The editor's own snapshot when the request carries a usable one (an object with an
     * {@code entities} array, at most the team-model size), else the request itself — everything
     * but {@code editorModel} — under {@link #REQUEST_MODEL_FORMAT}, which the editor imports too.
     */
    static String modelJson(FullstackStarterRequest body) throws IOException {
        return modelJson(body, null);
    }

    /**
     * {@link #modelJson(FullstackStarterRequest)} with a {@code generator} block naming the
     * templates the project was rendered with ({@link #templatesHash}). An update made later
     * compares it with the templates of the day.
     */
    static String modelJson(FullstackStarterRequest body, String templatesHash) throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode generator = null;
        if (templatesHash != null) {
            generator = MODEL_MAPPER.createObjectNode();
            generator.put("templatesHash", templatesHash);
        }
        com.fasterxml.jackson.databind.JsonNode model = body.editorModel();
        if (model != null && model.isObject() && model.path("entities").isArray()) {
            com.fasterxml.jackson.databind.node.ObjectNode snapshot = model.deepCopy();
            // Last, so the editor's own keys read as they did.
            snapshot.remove(GENERATOR_KEY);
            if (generator != null) snapshot.set(GENERATOR_KEY, generator);
            String json = MODEL_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            if (json.getBytes(StandardCharsets.UTF_8).length <= FullstackModelController.MAX_SNAPSHOT_BYTES) return json + "\n";
        }
        com.fasterxml.jackson.databind.node.ObjectNode request = MODEL_MAPPER.valueToTree(body);
        request.remove("editorModel");
        com.fasterxml.jackson.databind.node.ObjectNode wrapper = MODEL_MAPPER.createObjectNode();
        wrapper.put("format", REQUEST_MODEL_FORMAT);
        wrapper.set("request", request);
        if (generator != null) wrapper.set(GENERATOR_KEY, generator);
        return MODEL_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper) + "\n";
    }

    /** The model file's block about the generation itself — never part of the model. */
    static final String GENERATOR_KEY = "generator";

    /** The {@code generator.templatesHash} of a model file, or null when it has none. */
    static String templatesHashOf(byte[] modelFile) {
        if (modelFile == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode hash = MODEL_MAPPER.readTree(modelFile).path(GENERATOR_KEY).path("templatesHash");
            return hash.isTextual() ? hash.asText() : null;
        } catch (IOException e) {
            return null;
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

    @ExceptionHandler(WizardArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidArgument(WizardArgumentException ex) {
        entityContext.clear();
        optionsContext.clear();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid request");
        body.put("detail", ex.getMessage());
        // Where in the request the problem is (entities[1].fields[3], pages[2].widgets[0], …), so a
        // client can put the message on what caused it. Absent for a problem with the whole request.
        if (ex.path() != null) {
            body.put("path", ex.path());
            // An update names which of its two models the problem is in.
            for (String side : List.of("base", "target")) {
                if (ex.path().equals(side) || ex.path().startsWith(side + ".")) body.put("side", side);
            }
        }
        return ResponseEntity.badRequest().body(body);
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
        EntityScaffoldContext.putCsvImport(projectCtx, entities);
        // Bulk field-edit selection UI. Like bulkDelete, narrowed per entity in EntityScaffoldContext
        // (bulkUpdateApplicable) to writable, single-PK entities that have ≥1 editable non-PK field.
        // The record's version rides along in the DTO; the page turns a stale 409 into the
        // conflict dialog. Mirrors the backend config.
        EntityScaffoldContext.putOptimisticLocking(projectCtx, entities);
        EntityScaffoldContext.putUniqueKeys(projectCtx, entities);
        EntityScaffoldContext.putRules(projectCtx, entities);
        EntityScaffoldContext.putXlsxExport(projectCtx, entities);
        EntityScaffoldContext.putSavedViews(projectCtx, entities);
        // Who changed a row (audit) and the change history: the strings, the shared history panel.
        EntityScaffoldContext.putAudit(projectCtx, entities);
        EntityScaffoldContext.putHistory(projectCtx, entities);
        // Entity events: the live stream the lists and the bell listen to.
        EntityScaffoldContext.putEvents(projectCtx, entities,
                request.getDependencies() == null ? List.of() : request.getDependencies());
        // A change is recorded under the caller's name: the dev user travels with the calls.
        if (Boolean.TRUE.equals(projectCtx.get("usesRequestUser"))) projectCtx.put("sendsDevUser", true);
        // A view belongs to the user who saved it: the dev user travels with the calls.
        if (Boolean.TRUE.equals(projectCtx.get("hasSavedViews"))) projectCtx.put("sendsDevUser", true);
        // Table cells edited in place (frontend only — saved through the existing PUT).
        EntityScaffoldContext.putInlineEdit(projectCtx, entities);
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
        EntityScaffoldContext.putPageContext(projectCtx, pages, nav);
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

    private String buildReadme(String artifactId, boolean ldap, boolean tests, Set<String> deps) {
        // With ldap-auth every endpoint asks the caller's roles of the directory, which a laptop
        // cannot reach: the local profile gives the dev user its roles instead.
        String run = ldap ? "mvn spring-boot:run -Dspring-boot.run.profiles=local" : "mvn spring-boot:run";
        return "# " + artifactId + "\n\n"
                + "Fullstack scaffold generated by the Menora Initializr.\n\n"
                + "## Layout\n\n"
                + "- `backend/` — Spring Boot app. Run with `cd backend && " + run + "` (port 8080).\n"
                + "- `frontend/` — React + Vite app. Run with `cd frontend && npm install && npm run dev` (port 5173).\n\n"
                + "Open http://localhost:5173 to use the UI.\n\n"
                + composeReadme(deps)
                + "## How the frontend reaches the backend\n\n"
                + "The app always calls same-origin `/api/...` paths (`VITE_API_BASE_URL` is empty in\n"
                + "both `frontend/.env.development` and `frontend/.env.production`):\n\n"
                + "- **Dev** — the Vite dev server proxies `/api` to `http://localhost:8080`\n"
                + "  (`frontend/vite.config.ts`), so the browser only ever talks to :5173 and no CORS\n"
                + "  setup is needed for `npm run dev`.\n"
                + "- **Production** — the shipped `frontend/nginx/nginx.conf` proxies `/api/` to the\n"
                + "  backend named by the `API_UPSTREAM` container env var (default\n"
                + "  `http://localhost:8080`), e.g. `docker run -e API_UPSTREAM=http://backend:3000 ...`:\n"
                + "  both images listen on the port in their `PORT` env var, 3000 unless set.\n"
                + "  `vite preview` and plain static hosting run no proxy — use nginx or a gateway.\n\n"
                + "To call a backend on another origin instead, set `VITE_API_BASE_URL` in the\n"
                + "matching `.env` file (the API client `frontend/src/shared/api/client.ts` reads it at\n"
                + "build time) and allow that origin on the backend with\n"
                + "`app.cors.allowed-origins=http://localhost:5173` (comma-separated; CORS is off\n"
                + "when the property is empty — see `CorsConfig`).\n\n"
                + (ldap ? ACCESS_README : "")
                + (tests ? TESTS_README.stripTrailing() + "\n" + dbTestsReadme(deps) + "\n" : "")
                + REOPEN_HEADING
                + "`menora-model.json` is the model this project was generated from. To change the\n"
                + "entities or pages and generate again, open the Fullstack tab of the Menora\n"
                + "Initializr and choose **Start from → Import JSON** with that file.\n\n"
                + "Once this code has been edited, generating again would overwrite the edits. Instead,\n"
                + "after importing the file and changing the model, choose **Download update**: it\n"
                + "compares what the imported model generates with what the changed one does and hands\n"
                + "back a patch for `git apply --3way` (with notes, `UPDATE.md`), which merges the\n"
                + "change into the code as it is now. Commit the project as generated before editing\n"
                + "it — that commit is what the merge works from.\n";
    }

    private static final String REOPEN_HEADING = "## Reopen in the generator\n\n";

    /** The README's section on the events option and the transition e-mails (empty without either). */
    static String eventsReadme(boolean events, boolean mails, Set<String> deps) {
        if (!events && !mails) return "";
        StringBuilder s = new StringBuilder("## Events and e-mails\n\n");
        s.append("Each change ");
        if (events) {
            s.append("to a row of an entity with the `events` option");
            if (mails) s.append(", and each workflow move that e-mails somebody,");
        } else {
            s.append("by a workflow move that e-mails somebody");
        }
        s.append(" is written to the\n`app_outbox` table in the same transaction, and sent from there every few seconds\n")
                .append("(`backend/.../events/OutboxRelay`), tried again until it gets through: a message is\n")
                .append("never lost, and a consumer may see one twice (each carries its event's `id`).\n\n");
        if (events) {
            if (deps.contains("kafka")) {
                s.append("- **Kafka** — a topic per entity, `<artifactId>.<entity>`, keyed by the row's id; set\n")
                        .append("  `app.events.kafka.topic-prefix`, or `app.events.kafka.enabled: false` to stop.\n");
            }
            s.append("- **Webhooks** — none until listed under `app.events.webhooks` (name, url, secret,\n")
                    .append("  events); each call is a JSON POST signed `X-Event-Signature: sha256=<hmac>`.\n")
                    .append("- **The browser** — `GET /api/events/stream` tells open pages that a row changed, and\n")
                    .append("  their lists, charts and bell read it again.\n");
        }
        if (mails) {
            s.append("- **E-mail** — off until `app.events.mail.enabled: true` and `spring.mail.host` are set.\n")
                    .append("  A user's address comes from `app.events.mail.users`, ")
                    .append(deps.contains("ldap-auth-rest") ? "the LDAP REST service\n  (`app.events.mail.lookup-path`), " : "")
                    .append("or `<user>@<app.events.mail.domain>`;\n")
                    .append("  a role's from `app.events.mail.roles`.\n");
        }
        return s.append("\nEvery setting is described in `backend/.../events/EventsProperties.java`.\n\n").toString();
    }

    /** The README's section on who may call the API — only with an ldap-auth dependency. */
    private static final String ACCESS_README = "## Who may do what\n\n"
            + "Every API call is checked against the caller's roles: the `userinfo` header (set by the\n"
            + "SSO gateway) names the user, and their LDAP groups give them `USER` or `ADMIN` — an admin\n"
            + "is a user too. Each entity says who may read it and who may change it (`access` in\n"
            + "`menora-model.json`; read for users and write for admins unless it says otherwise), and\n"
            + "its controller carries the matching `@RequiresPermission`. No header is a 401, the wrong\n"
            + "role a 403.\n\n"
            + "On a developer's machine the directory cannot be asked. The `local` profile\n"
            + "(`backend/src/main/resources/application-local.yaml`) gives `dev-user` — the name the\n"
            + "frontend's dev server signs its calls with — the `ADMIN` role instead; change it there to\n"
            + "see the app as a plain user. Never activate that profile on a server.\n\n";

    /** The README's section on running everything in Docker ({@code compose.yaml}). */
    private static String composeReadme(Set<String> deps) {
        List<String> services = LocalRun.SERVICES.stream().filter(deps::contains).toList();
        String profiles = LocalRun.profiles(deps);
        return "## Run it all in Docker\n\n"
                + "`docker compose up --build` starts "
                + (services.isEmpty() ? "" : String.join(", ", services.stream().map(FullstackStarterController::serviceLabel).toList()) + ", ")
                + "the backend and the frontend, each built from its\n"
                + "Dockerfile; then open http://localhost:3000 (the API is on :8080). The backend runs on the\n"
                + "`" + profiles + "` profile" + (profiles.contains(",") ? "s" : "") + " (`backend/" + LocalRun.PROFILE_FILE
                + "`\npoints it at the containers)"
                + (LocalRun.ldap(deps) ? ", and the frontend's nginx signs calls as `dev-user`, who is an\nadmin there" : "")
                + ".\n\n"
                + (services.isEmpty() ? ""
                        : "To work on the code instead, start the services alone (`docker compose up -d "
                        + String.join(" ", services.stream().map(LocalRun::serviceName).toList())
                        + "`)\nand run the backend with `-Dspring-boot.run.profiles=" + profiles + "`.\n\n")
                + (deps.contains("db2") ? "IBM i (the `db2` dependency) has no container image: it still needs a real server.\n\n" : "")
                + "`docker compose down` stops it all and throws the data away. The passwords in\n"
                + "`compose.yaml` are for this machine only.\n\n";
    }

    private static String serviceLabel(String dep) {
        return switch (dep) {
            case "postgresql" -> "PostgreSQL";
            case "oracle" -> "Oracle";
            case "mssql" -> "SQL Server";
            case "mongodb" -> "MongoDB";
            case "redis" -> "Redis";
            case "kafka" -> "Kafka";
            default -> dep;
        };
    }

    /** The Tests section's line on the {@code db-tests} profile, when a container fits the database. */
    private static String dbTestsReadme(Set<String> deps) {
        return LocalRun.dbTestsDatabase(deps).map(db -> "- `cd backend && mvn test -P" + LocalRun.DB_TESTS_PROFILE
                + "` — the same API tests on the application's own database\n  (" + serviceLabel(db)
                + ") in Docker, through Testcontainers"
                + (deps.contains("flyway") ? ", on the schema the Flyway migration makes" : "")
                + ". Needs Docker running.\n").orElse("");
    }

    /** The README's section on the project's own tests — only with the tests option. */
    private static final String TESTS_README = "## Tests\n\n"
            + "- `cd backend && mvn test` — an API test per entity (`*ApiTest`): its real endpoints, service\n"
            + "  and database, called through MockMvc. They run on the `test` profile\n"
            + "  (`backend/src/test/resources/application-test.yaml`): an in-memory H2 database whatever\n"
            + "  the application's own, so nothing has to be running. Rows are made through the API by\n"
            + "  `ApiTestSupport.body` — change a value there when a field needs another one.\n"
            + "- `cd frontend && npm test` — Vitest: each entity's form checks (`validate.test.ts`) and\n"
            + "  its list page against a mocked API (`*Page.test.tsx`).\n\n";

    private static final String ROOT_GITIGNORE =
            "# Maven\nbackend/target/\n\n# Local secrets (VAULT_TOKEN for the vault-local profile)\nbackend/.env\n\n# Node\nfrontend/node_modules/\nfrontend/dist/\n\n# IDE\n.idea/\n.vscode/\n*.iml\n";

    /**
     * One set of line endings in the repository and in every checkout, so a project reads the
     * same on Windows and Linux and an update's patch applies on both. Batch files keep CRLF.
     */
    private static final String ROOT_GITATTRIBUTES =
            "# LF in the repository and in the working tree, on every machine.\n"
                    + "* text=auto eol=lf\n"
                    + "*.cmd text eol=crlf\n"
                    + "*.bat text eol=crlf\n";

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
            EntityScaffoldContext.putText(ctx, key, value.trim());
        }
    }





}
