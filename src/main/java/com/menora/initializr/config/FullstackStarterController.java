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
import com.menora.initializr.fullstack.FullstackRequestValidator;
import com.menora.initializr.fullstack.FullstackStarterRequest;
import io.spring.initializr.metadata.InitializrMetadata;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.metadata.SingleSelectCapability;
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
    private final InitializrMetadataProvider metadataProvider;
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

    public FullstackStarterController(ProjectGenerationInvoker<ProjectRequest> invoker,
                                      InitializrMetadataProvider metadataProvider,
                                      ProjectOptionsContext optionsContext,
                                      EntityDefinitionContext entityContext,
                                      EntityTemplateSetRepository setRepo,
                                      EntityTemplateFileRepository fileRepo,
                                      EntityTemplateSetDefaultDepRepository defaultDepRepo,
                                      ColorPaletteRepository colorPaletteRepo,
                                      FrontendProjectGenerator frontendGenerator,
                                      FrontendProperties frontendProperties,
                                      VersionService versionService,
                                      DependencyConfigService configService) {
        this.invoker = invoker;
        this.metadataProvider = metadataProvider;
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
    }

    @PostMapping("/starter-fullstack.zip")
    public ResponseEntity<byte[]> generate(@RequestBody FullstackStarterRequest body) throws IOException {
        Path tempDir = Files.createTempDirectory("fullstack-");
        try {
            WebProjectRequest request = buildArtifacts(body, tempDir);
            byte[] zipBytes = GeneratedProjectFiles.zipDirectory(tempDir, request.getArtifactId());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + request.getArtifactId() + ".zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zipBytes);
        } finally {
            FileSystemUtils.deleteRecursively(tempDir);
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
            FileSystemUtils.deleteRecursively(tempDir);
            entityContext.clear();
            optionsContext.clear();
        }
    }

    /**
     * Shared pipeline for {@link #generate} and {@link #preview}: validates the request,
     * resolves and kind-checks both template sets (fail-fast 400 on a bad key), then renders
     * the backend + frontend + root files into {@code tempDir}. Returns the resolved request
     * (for artifactId / zip naming). The caller owns {@code tempDir} cleanup and clearing the
     * thread-local contexts in a {@code finally}.
     */
    private WebProjectRequest buildArtifacts(FullstackStarterRequest body, Path tempDir) throws IOException {
        List<EntityDefinition> entities = FullstackRequestValidator.validateAndConvert(body);
        String backendSetKey = orDefault(body.backendTemplateSet(), DEFAULT_BACKEND_SET);
        String frontendSetKey = orDefault(body.frontendTemplateSet(), DEFAULT_FRONTEND_SET);

        // Resolve both sets up front so a missing/wrong-kind key fails fast with a clear 400,
        // instead of silently producing an empty frontend or an un-scaffolded backend.
        EntityTemplateSetEntity backendSet =
                requireSet(backendSetKey, EntityTemplateSetEntity.Kind.BACKEND_JAVA, "backendTemplateSet");
        EntityTemplateSetEntity frontendSet =
                requireSet(frontendSetKey, EntityTemplateSetEntity.Kind.FRONTEND_REACT, "frontendTemplateSet");

        WebProjectRequest request = toWebRequest(body);
        String domainPackage = resolveDomainPackage(body.domainPackage(), request.getPackageName());
        ensureRequiredDeps(request, backendSet, body.dependencies() != null);
        optionsContext.populate(body.opts());
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
        entityContext.populate(entities, backendSetKey, frontendSetKey, domainPackage);

        // Backend — runs through the standard pipeline. The EntityScaffoldContributor
        // (registered in spring.factories) picks up the populated context.
        Path backendDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        try {
            GeneratedProjectFiles.copyDirectory(backendDir, tempDir.resolve("backend"));
        } finally {
            // cleanTempFiles deletes the tree *and* drops the invoker's map entry for it.
            invoker.cleanTempFiles(backendDir);
        }

        // Frontend — rendered inline outside the Initializr pipeline.
        renderFrontend(frontendSet, request, entities, domainPackage, body.colorPalette(),
                body.dashboardTitle(), body.dashboardOverview(), tempDir.resolve("frontend"));

        // Root files
        Files.writeString(tempDir.resolve("README.md"), buildReadme(request.getArtifactId()));
        Files.writeString(tempDir.resolve(".gitignore"), ROOT_GITIGNORE);
        return request;
    }

    /** Resolves a template set by key and asserts its kind, throwing HTTP 400 otherwise. */
    private EntityTemplateSetEntity requireSet(String setKey, EntityTemplateSetEntity.Kind kind, String fieldName) {
        EntityTemplateSetEntity set = setRepo.findBySetKey(setKey).orElseThrow(() ->
                new WizardArgumentException(fieldName + " '" + setKey + "' not found"));
        if (set.getKind() != kind) {
            throw new WizardArgumentException(fieldName + " '" + setKey
                    + "' is not a " + kind + " set (kind=" + set.getKind() + ")");
        }
        return set;
    }

    /** Rejects an unknown Boot/Java version with a 400. A null/blank value is left for the
     *  framework to default. */
    private static void requireKnownVersion(SingleSelectCapability capability, String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (capability.get(value) == null) {
            throw new WizardArgumentException(fieldName + " '" + value + "' is not a known version");
        }
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
            throw new WizardArgumentException("domainPackage '" + domain + "' is not a valid Java package");
        }
        if (!domain.equals(basePackage) && !domain.startsWith(basePackage + ".")) {
            throw new WizardArgumentException("domainPackage '" + domain
                    + "' must be the base package '" + basePackage + "' or a sub-package of it");
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
        return ResponseEntity.badRequest().body(body);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private WebProjectRequest toWebRequest(FullstackStarterRequest body) {
        InitializrMetadata metadata = metadataProvider.get();
        // Fail fast on an unknown Boot/Java version with a clean 400, rather than letting
        // the framework fail deep in generation with an opaque 500. The UI only ever sends
        // values from the metadata dropdowns; this guards direct API callers (curl/IntelliJ).
        requireKnownVersion(metadata.getBootVersions(), body.bootVersion(), "bootVersion");
        requireKnownVersion(metadata.getJavaVersions(), body.javaVersion(), "javaVersion");
        WebProjectRequest r = new WebProjectRequest();
        r.setType(orDefault(body.type(), "maven-project"));
        r.setLanguage(orDefault(body.language(), "java"));
        r.setBootVersion(body.bootVersion());
        r.setGroupId(orDefault(body.groupId(), "com.menora"));
        r.setArtifactId(orDefault(body.artifactId(), "demo"));
        r.setName(orDefault(body.name(), r.getArtifactId()));
        r.setDescription(orDefault(body.description(), ""));
        r.setPackageName(orDefault(body.packageName(), r.getGroupId() + "." + r.getArtifactId()));
        r.setPackaging(orDefault(body.packaging(), "jar"));
        r.setJavaVersion(orDefault(body.javaVersion(), "21"));
        r.setVersion(orDefault(body.version(), (String) metadata.defaults().get("version")));
        r.setConfigurationFileFormat(orDefault(body.configurationFileFormat(), "properties"));
        if (body.dependencies() != null) {
            r.setDependencies(new ArrayList<>(body.dependencies()));
        }
        return r;
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
                                List<EntityDefinition> entities, String domainPackage,
                                String colorPaletteId, String dashboardTitle, String dashboardOverview,
                                Path targetDir) throws IOException {
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
        // Gate the dev-mode `userinfo` header (read by the backend's @RequiresPermission aspect)
        // on the backend actually including the LDAP authorization dependency.
        boolean hasLdapAuth = request.getDependencies() != null
                && (request.getDependencies().contains("ldap-auth")
                        || request.getDependencies().contains("ldap-auth-rest"));
        projectCtx.put("hasLdapAuth", hasLdapAuth);
        // Opt-in scaffolding flags must be set on the frontend context too — the per-entity FE
        // templates gate audit columns / inverse-collection counts on these. The backend config
        // sets its own copy; the two render paths do not share a context.
        projectCtx.put("optScaffoldTests", optionsContext.hasOption("scaffold", "tests"));
        projectCtx.put("optScaffoldAudit", optionsContext.hasOption("scaffold", "audit"));
        projectCtx.put("optScaffoldSoftDelete", optionsContext.hasOption("scaffold", "softDelete"));
        projectCtx.put("optScaffoldInverse", optionsContext.hasOption("scaffold", "inverseCollections"));
        // CSV export button + bulk-delete selection UI. Mirrors the backend config — the FE per-entity
        // page gates the Export button on optScaffoldCsvExport and the row checkboxes on the per-entity
        // bulkDeleteApplicable (derived in EntityScaffoldContext from optScaffoldBulkDelete).
        projectCtx.put("optScaffoldCsvExport", optionsContext.hasOption("scaffold", "csvExport"));
        projectCtx.put("optScaffoldBulkDelete", optionsContext.hasOption("scaffold", "bulkDelete"));
        // Bulk field-edit selection UI. Like bulkDelete, narrowed per entity in EntityScaffoldContext
        // (bulkUpdateApplicable) to writable, single-PK entities that have ≥1 editable non-PK field.
        projectCtx.put("optScaffoldBulkUpdate", optionsContext.hasOption("scaffold", "bulkUpdate"));
        // Optional dashboard header overrides — blank/absent leaves the template's built-in fallback.
        putIfPresent(projectCtx, "dashboardTitle", dashboardTitle);
        putIfPresent(projectCtx, "dashboardOverview", dashboardOverview);
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

    private String buildReadme(String artifactId) {
        return "# " + artifactId + "\n\n"
                + "Fullstack scaffold generated by the Menora Initializr.\n\n"
                + "## Layout\n\n"
                + "- `backend/` — Spring Boot app. Run with `cd backend && mvn spring-boot:run` (port 8080).\n"
                + "- `frontend/` — React + Vite app. Run with `cd frontend && npm install && npm run dev` (port 5173).\n\n"
                + "Open http://localhost:5173 to use the UI.\n\n"
                + "## How the frontend reaches the backend\n\n"
                + "The app always calls same-origin `/api/...` paths (`VITE_API_BASE_URL` is empty in\n"
                + "both `frontend/.env.development` and `frontend/.env.production`):\n\n"
                + "- **Dev** — the Vite dev server proxies `/api` to `http://localhost:8080`\n"
                + "  (`frontend/vite.config.ts`), so the browser only ever talks to :5173 and no CORS\n"
                + "  setup is needed for `npm run dev`.\n"
                + "- **Production** — the shipped `frontend/nginx/nginx.conf` proxies `/api/` to the\n"
                + "  backend named by the `API_UPSTREAM` container env var (default\n"
                + "  `http://localhost:8080`), e.g. `docker run -e API_UPSTREAM=http://backend:8080 ...`.\n"
                + "  `vite preview` and plain static hosting run no proxy — use nginx or a gateway.\n\n"
                + "To call a backend on another origin instead, set `VITE_API_BASE_URL` in the\n"
                + "matching `.env` file (the API client `frontend/src/shared/api/client.ts` reads it at\n"
                + "build time) and allow that origin on the backend with\n"
                + "`app.cors.allowed-origins=http://localhost:5173` (comma-separated; CORS is off\n"
                + "when the property is empty — see `CorsConfig`).\n";
    }

    private static final String ROOT_GITIGNORE =
            "# Maven\nbackend/target/\n\n# Node\nfrontend/node_modules/\nfrontend/dist/\n\n# IDE\n.idea/\n.vscode/\n*.iml\n";

    private static String orDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /** Puts a trimmed value under {@code key} only when non-blank, so a Mustache
     *  {@code {{#key}}…{{^key}}fallback{{/key}}} resolves to the template's built-in default. */
    private static void putIfPresent(Map<String, Object> ctx, String key, String value) {
        if (value != null && !value.isBlank()) {
            ctx.put(key, value.trim());
        }
    }





}
