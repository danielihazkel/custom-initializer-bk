package com.menora.initializr.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.menora.initializr.agent.dto.MenoraInitManifest;
import com.menora.initializr.agent.dto.ScaffoldFile;
import com.menora.initializr.config.WizardStarterController.WizardStarterRequest;
import com.menora.initializr.extension.frontend.FrontendCompatibilityResolver;
import com.menora.initializr.extension.frontend.FrontendProjectDescription;
import com.menora.initializr.extension.frontend.FrontendProjectGenerator;
import com.menora.initializr.extension.frontend.FrontendVersionRangeFilter;
import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencyGroupEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.samskivert.mustache.Mustache;
import io.spring.initializr.metadata.InitializrMetadata;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectGenerationResult;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates a backend + frontend pair from a single JSON request.
 *
 * <p>{@code POST /starter-paired.zip} returns a single monorepo ZIP with
 * {@code backend/} and {@code frontend/} directories plus root-level
 * {@code README.md}, {@code docker-compose.yml}, and {@code .menora-init.json}
 * files. {@code POST /agent/scaffold/paired} is the JSON-envelope twin used by
 * AI agents — same pipeline, returns file trees + the paired manifest.
 *
 * <p>The two halves are auto-wired: the FE side gets {@code VITE_API_BASE_URL}
 * pointing at the backend ({@code http://localhost:8080} if not specified) and
 * a Vite {@code /api} proxy; the BE side picks up a {@code CorsConfig.java}
 * (via the {@code paired-cors} sub-option on the {@code web} dep) so the
 * browser can call the backend during development.
 */
@RestController
public class PairedStarterController {

    private static final Mustache.Compiler MUSTACHE = Mustache.compiler().escapeHTML(false);
    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final ProjectGenerationInvoker<ProjectRequest> invoker;
    private final InitializrMetadataProvider metadataProvider;
    private final ProjectOptionsContext optionsContext;
    private final SqlScriptsContext sqlContext;
    private final OpenApiSpecContext specContext;
    private final SoapSpecContext soapContext;
    private final FrontendProjectGenerator frontendGenerator;
    private final FrontendVersionRangeFilter frontendVersionFilter;
    private final FrontendCompatibilityResolver frontendCompatibilityResolver;
    private final DependencyConfigService configService;
    private final FrontendProperties frontendProperties;

    public PairedStarterController(ProjectGenerationInvoker<ProjectRequest> invoker,
                                   InitializrMetadataProvider metadataProvider,
                                   ProjectOptionsContext optionsContext,
                                   SqlScriptsContext sqlContext,
                                   OpenApiSpecContext specContext,
                                   SoapSpecContext soapContext,
                                   FrontendProjectGenerator frontendGenerator,
                                   FrontendVersionRangeFilter frontendVersionFilter,
                                   FrontendCompatibilityResolver frontendCompatibilityResolver,
                                   DependencyConfigService configService,
                                   FrontendProperties frontendProperties) {
        this.invoker = invoker;
        this.metadataProvider = metadataProvider;
        this.optionsContext = optionsContext;
        this.sqlContext = sqlContext;
        this.specContext = specContext;
        this.soapContext = soapContext;
        this.frontendGenerator = frontendGenerator;
        this.frontendVersionFilter = frontendVersionFilter;
        this.frontendCompatibilityResolver = frontendCompatibilityResolver;
        this.configService = configService;
        this.frontendProperties = frontendProperties;
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    @PostMapping("/starter-paired.zip")
    public ResponseEntity<byte[]> generate(@RequestBody PairedStarterRequest body) throws IOException {
        PairedResult result = build(body);
        try {
            byte[] zip = zipPaired(result);
            String filename = (result.backendArtifactId == null || result.backendArtifactId.isBlank()
                    ? "paired" : result.backendArtifactId) + ".zip";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zip);
        } finally {
            if (result.backendDir != null) FileSystemUtils.deleteRecursively(result.backendDir);
            clearAllContexts();
        }
    }

    @PostMapping("/agent/scaffold/paired")
    public ResponseEntity<PairedScaffoldResponse> agentScaffold(@RequestBody PairedStarterRequest body) throws IOException {
        PairedResult result = build(body);
        try {
            List<ScaffoldFile> files = scaffoldFiles(result);
            MenoraInitManifest manifest = buildPairedManifest(result, files);
            return ResponseEntity.ok(new PairedScaffoldResponse(manifest, files));
        } finally {
            if (result.backendDir != null) FileSystemUtils.deleteRecursively(result.backendDir);
            clearAllContexts();
        }
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    /**
     * Runs BE then FE generation in sequence and returns both file trees plus
     * the resolved metadata needed to assemble root files / manifests.
     */
    private PairedResult build(PairedStarterRequest body) throws IOException {
        PairedResult result = new PairedResult();

        if (body.backend() != null) {
            // Auto-add the paired-cors sub-option when web is present so the BE
            // pipeline drops a CorsConfig.java into the generated project.
            WizardStarterRequest backendReq = withPairedCors(body.backend());
            WebProjectRequest request = toWebRequest(backendReq);
            populateBackendContexts(backendReq);
            try {
                ProjectGenerationResult gen = invoker.invokeProjectStructureGeneration(request);
                result.backendDir = gen.getRootDirectory();
                result.backendRequest = backendReq;
                result.backendDescription = gen.getProjectDescription();
                result.backendArtifactId = request.getArtifactId();
            } finally {
                // Sql/spec/soap state stays around until the FE half (which doesn't
                // touch them) so we keep them populated, but tests rely on cleanup
                // after the whole request — done in the controller's finally block.
            }
        }

        if (body.frontend() != null) {
            FrontendProjectDescription fe = toFrontendDescription(body.frontend(), body.backend());
            optionsContext.populate(body.frontend().opts() == null ? Map.of() : body.frontend().opts());
            try {
                result.frontendFiles = frontendGenerator.generateFileMap(fe);
                result.frontendDescription = fe;
                result.frontendProjectName = fe.getProjectName();
            } finally {
                optionsContext.clear();
            }
        }

        result.apiBaseUrl = resolveApiBaseUrl(body);
        return result;
    }

    private WizardStarterRequest withPairedCors(WizardStarterRequest req) {
        // No frontend half → no need to add the CORS config.
        if (req == null || req.dependencies() == null || !req.dependencies().contains("web")) return req;

        Map<String, List<String>> opts = req.opts() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(req.opts());
        List<String> webOpts = new ArrayList<>(opts.getOrDefault("web", List.of()));
        if (!webOpts.contains("paired-cors")) webOpts.add("paired-cors");
        opts.put("web", webOpts);

        return new WizardStarterRequest(
                req.groupId(), req.artifactId(), req.name(), req.description(),
                req.packageName(), req.type(), req.language(), req.bootVersion(),
                req.packaging(), req.javaVersion(), req.version(),
                req.configurationFileFormat(),
                req.dependencies(),
                opts,
                req.sqlByDep(), req.sqlOptions(),
                req.specByDep(), req.openApiOptions(),
                req.wsdlByDep(), req.soapOptions());
    }

    private FrontendProjectDescription toFrontendDescription(PairedFrontendRequest in, WizardStarterRequest backend) {
        FrontendProjectDescription d = new FrontendProjectDescription();
        d.setProjectName(orDefault(in.projectName(), "demo-ui"));
        d.setDescription(orDefault(in.description(), ""));
        d.setScope(orDefault(in.scope(), ""));
        d.setAppTitle(orDefault(in.appTitle(),
                frontendProperties.getDefaults().getAppTitle()));
        d.setReactVersion(orDefault(in.reactVersion(), frontendProperties.defaultReactVersion()));
        d.setNodeVersion(orDefault(in.nodeVersion(), frontendProperties.defaultNodeVersion()));
        d.setPackageManager(orDefault(in.packageManager(), frontendProperties.defaultPackageManager()));
        d.setBasePath(orDefault(in.basePath(), "/"));
        d.setColorPaletteId(orDefault(in.colorPalette(), ""));
        d.setTypescriptVersion(frontendProperties.getPinned().getTypescript());
        d.setViteVersion(frontendProperties.getPinned().getVite());

        // Auto-default the apiBaseUrl to localhost:8080 (or whatever the BE half is
        // wired for). The user can still override explicitly per request.
        String apiBaseUrl = in.apiBaseUrl();
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) apiBaseUrl = DEFAULT_API_BASE_URL;
        d.setApiBaseUrl(apiBaseUrl);
        d.setBackendArtifactId(orDefault(in.backendArtifactId(),
                backend == null ? "" : orDefault(backend.artifactId(), "")));

        if (in.dependencies() != null) {
            for (String dep : in.dependencies()) {
                String trimmed = dep == null ? null : dep.trim();
                if (trimmed != null && !trimmed.isEmpty()) d.getDependencies().add(trimmed);
            }
        }

        // Mirror /frontend/starter.zip: drop incompat deps then apply REQUIRES/CONFLICTS.
        Map<String, DependencyEntryEntity> entriesById = frontendEntriesById();
        Set<String> filtered = frontendVersionFilter.filterCompatibleDepIds(
                d.getDependencies(), entriesById, d.getReactVersion());
        Set<String> resolved = frontendCompatibilityResolver.resolve(filtered, entriesById.keySet());
        d.getDependencies().clear();
        d.getDependencies().addAll(resolved);
        return d;
    }

    private Map<String, DependencyEntryEntity> frontendEntriesById() {
        Map<String, DependencyEntryEntity> out = new LinkedHashMap<>();
        for (DependencyGroupEntity g : configService.getAllGroupsWithEntries(ProjectKind.FRONTEND)) {
            for (DependencyEntryEntity e : g.getEntries()) {
                out.put(e.getDepId(), e);
            }
        }
        return out;
    }

    private String resolveApiBaseUrl(PairedStarterRequest body) {
        if (body.frontend() != null
                && body.frontend().apiBaseUrl() != null
                && !body.frontend().apiBaseUrl().isBlank()) {
            return body.frontend().apiBaseUrl();
        }
        return DEFAULT_API_BASE_URL;
    }

    // ── Zip assembly ──────────────────────────────────────────────────────────

    private byte[] zipPaired(PairedResult result) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Backend half — preserve directory structure under `backend/`.
            if (result.backendDir != null) {
                final Path root = result.backendDir;
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile).sorted().forEach(p -> {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        try {
                            zos.putNextEntry(new ZipEntry("backend/" + rel));
                            Files.copy(p, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
            }

            // Frontend half — generated as a path→content map.
            if (result.frontendFiles != null) {
                for (Map.Entry<String, String> e : result.frontendFiles.entrySet()) {
                    zos.putNextEntry(new ZipEntry("frontend/" + e.getKey()));
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }

            // Root files: README, docker-compose, and the paired manifest.
            Map<String, Object> rootCtx = rootTemplateContext(result);
            writeZipEntry(zos, "README.md", renderClasspath("templates/paired/README.md.mustache", rootCtx));
            writeZipEntry(zos, "docker-compose.yml", renderClasspath("templates/paired/docker-compose.yml.mustache", rootCtx));

            List<ScaffoldFile> files = scaffoldFiles(result);  // re-walk for sha entries
            MenoraInitManifest manifest = buildPairedManifest(result, files);
            writeZipEntry(zos, ".menora-init.json", serializeManifest(manifest));
        }
        return baos.toByteArray();
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private Map<String, Object> rootTemplateContext(PairedResult result) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("backendArtifactId", result.backendArtifactId == null ? "backend" : result.backendArtifactId);
        ctx.put("backendBootVersion", result.backendDescription != null
                && result.backendDescription.getPlatformVersion() != null
                ? result.backendDescription.getPlatformVersion().toString() : "");
        ctx.put("backendJavaVersion", result.backendDescription != null
                && result.backendDescription.getLanguage() != null
                ? result.backendDescription.getLanguage().jvmVersion() : "");
        ctx.put("frontendProjectName", result.frontendProjectName == null ? "frontend" : result.frontendProjectName);
        ctx.put("frontendReactVersion", result.frontendDescription == null ? ""
                : result.frontendDescription.getReactVersion());
        String pm = result.frontendDescription == null ? "" : result.frontendDescription.getPackageManager();
        ctx.put("frontendPackageManager", pm);
        ctx.put("frontendIsPnpm", "pnpm".equalsIgnoreCase(pm));
        ctx.put("frontendIsNpm", "npm".equalsIgnoreCase(pm));
        ctx.put("apiBaseUrl", result.apiBaseUrl);
        return ctx;
    }

    // ── JSON envelope ─────────────────────────────────────────────────────────

    private List<ScaffoldFile> scaffoldFiles(PairedResult result) throws IOException {
        List<ScaffoldFile> out = new ArrayList<>();
        if (result.backendDir != null) {
            final Path root = result.backendDir;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).sorted().forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    out.add(scaffoldFileFromPath(p, "backend/" + rel));
                });
            }
        }
        if (result.frontendFiles != null) {
            for (Map.Entry<String, String> e : result.frontendFiles.entrySet()) {
                byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
                out.add(new ScaffoldFile("frontend/" + e.getKey(), ScaffoldFile.UTF8,
                        e.getValue(), sha256Hex(bytes)));
            }
        }
        return out;
    }

    private static ScaffoldFile scaffoldFileFromPath(Path p, String rel) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            String sha = sha256Hex(bytes);
            // Reuse the same text-detection threshold as AgentScaffoldController: NUL-byte sniff.
            int probe = Math.min(bytes.length, 4096);
            for (int i = 0; i < probe; i++) {
                if (bytes[i] == 0) {
                    return new ScaffoldFile(rel, ScaffoldFile.BASE64,
                            Base64.getEncoder().encodeToString(bytes), sha);
                }
            }
            return new ScaffoldFile(rel, ScaffoldFile.UTF8,
                    new String(bytes, StandardCharsets.UTF_8), sha);
        } catch (IOException e) {
            return new ScaffoldFile(rel, ScaffoldFile.UTF8,
                    "[unreadable: " + e.getMessage() + "]", "ERROR");
        }
    }

    private MenoraInitManifest buildPairedManifest(PairedResult result, List<ScaffoldFile> files) {
        MenoraInitManifest.Inputs backendInputs = result.backendRequest == null ? null : new MenoraInitManifest.Inputs(
                "paired",
                result.backendRequest.groupId(),
                result.backendRequest.artifactId(),
                result.backendRequest.name(),
                result.backendRequest.packageName(),
                result.backendRequest.bootVersion(),
                result.backendRequest.javaVersion(),
                result.backendRequest.packaging(),
                result.backendRequest.language(),
                result.backendRequest.type(),
                result.backendRequest.version(),
                result.backendRequest.dependencies() == null ? List.of() : List.copyOf(result.backendRequest.dependencies()),
                List.of(),
                result.backendRequest.opts() == null ? Map.of() : copyOpts(result.backendRequest.opts()),
                null);

        MenoraInitManifest.FrontendInputs frontendInputs = result.frontendDescription == null ? null
                : new MenoraInitManifest.FrontendInputs(
                        result.frontendDescription.getProjectName(),
                        result.frontendDescription.getDescription(),
                        result.frontendDescription.getScope(),
                        result.frontendDescription.getAppTitle(),
                        result.frontendDescription.getReactVersion(),
                        result.frontendDescription.getNodeVersion(),
                        result.frontendDescription.getPackageManager(),
                        result.frontendDescription.getBasePath(),
                        result.frontendDescription.getColorPaletteId(),
                        result.frontendDescription.getApiBaseUrl(),
                        result.frontendDescription.getBackendArtifactId(),
                        List.copyOf(result.frontendDescription.getDependencies()),
                        Map.of());

        MenoraInitManifest.PairedInputs paired = new MenoraInitManifest.PairedInputs(
                backendInputs, frontendInputs, result.apiBaseUrl);

        List<MenoraInitManifest.FileEntry> entries = new ArrayList<>(files.size());
        for (ScaffoldFile f : files) {
            entries.add(new MenoraInitManifest.FileEntry(f.path(), f.sha256()));
        }

        return new MenoraInitManifest(
                1,
                new MenoraInitManifest.Generator("menora-initializr", "dev", Instant.now().toString()),
                paired,
                entries);
    }

    // ── BE pipeline duplication (minimal) ─────────────────────────────────────

    private WebProjectRequest toWebRequest(WizardStarterRequest body) {
        InitializrMetadata metadata = metadataProvider.get();
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
        if (body.dependencies() != null) r.setDependencies(new ArrayList<>(body.dependencies()));
        return r;
    }

    private void populateBackendContexts(WizardStarterRequest body) {
        optionsContext.populate(body.opts() == null ? Map.of() : body.opts());
        // Wizard contexts — paired requests don't typically carry SQL/OpenAPI/SOAP
        // payloads, but populate empty so the existing contributors stay null-safe.
        sqlContext.populate(body.sqlByDep() == null ? Map.of() : body.sqlByDep(), Map.of());
        specContext.populate(body.specByDep() == null ? Map.of() : body.specByDep(), Map.of());
        soapContext.populate(body.wsdlByDep() == null ? Map.of() : body.wsdlByDep(), Map.of());
    }

    private void clearAllContexts() {
        sqlContext.clear();
        specContext.clear();
        soapContext.clear();
        optionsContext.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String renderClasspath(String resourcePath, Map<String, Object> ctx) throws IOException {
        try (var in = new ClassPathResource(resourcePath).getInputStream()) {
            String tpl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return MUSTACHE.compile(tpl).execute(ctx);
        }
    }

    private static String serializeManifest(MenoraInitManifest manifest) {
        try {
            return MAPPER.writeValueAsString(manifest);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException("Failed to serialize paired manifest", e));
        }
    }

    private static String orDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static Map<String, List<String>> copyOpts(Map<String, List<String>> src) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            copy.put(e.getKey(), e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
        }
        return copy;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(bytes);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            return "ERROR";
        }
    }

    /** Transient package-private DTO carrying the build outputs across the pipeline. */
    private static class PairedResult {
        Path backendDir;
        WizardStarterRequest backendRequest;
        io.spring.initializr.generator.project.ProjectDescription backendDescription;
        String backendArtifactId;
        Map<String, String> frontendFiles;
        FrontendProjectDescription frontendDescription;
        String frontendProjectName;
        String apiBaseUrl;
    }

    // ── Request / response records ────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PairedStarterRequest(
            WizardStarterRequest backend,
            PairedFrontendRequest frontend,
            String layout) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PairedFrontendRequest(
            String projectName,
            String description,
            String scope,
            String appTitle,
            String reactVersion,
            String nodeVersion,
            String packageManager,
            String basePath,
            String colorPalette,
            String apiBaseUrl,
            String backendArtifactId,
            List<String> dependencies,
            Map<String, List<String>> opts) {
    }

    public record PairedScaffoldResponse(MenoraInitManifest manifest, List<ScaffoldFile> files) {
    }
}
