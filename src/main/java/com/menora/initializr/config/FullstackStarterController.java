package com.menora.initializr.config;

import com.menora.initializr.db.entity.EntityTemplateFileEntity;
import com.menora.initializr.db.entity.EntityTemplateSetDefaultDepEntity;
import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import com.menora.initializr.db.repository.EntityTemplateFileRepository;
import com.menora.initializr.db.repository.EntityTemplateSetDefaultDepRepository;
import com.menora.initializr.db.repository.EntityTemplateSetRepository;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    private final ProjectGenerationInvoker<ProjectRequest> invoker;
    private final InitializrMetadataProvider metadataProvider;
    private final ProjectOptionsContext optionsContext;
    private final EntityDefinitionContext entityContext;
    private final EntityTemplateSetRepository setRepo;
    private final EntityTemplateFileRepository fileRepo;
    private final EntityTemplateSetDefaultDepRepository defaultDepRepo;

    public FullstackStarterController(ProjectGenerationInvoker<ProjectRequest> invoker,
                                      InitializrMetadataProvider metadataProvider,
                                      ProjectOptionsContext optionsContext,
                                      EntityDefinitionContext entityContext,
                                      EntityTemplateSetRepository setRepo,
                                      EntityTemplateFileRepository fileRepo,
                                      EntityTemplateSetDefaultDepRepository defaultDepRepo) {
        this.invoker = invoker;
        this.metadataProvider = metadataProvider;
        this.optionsContext = optionsContext;
        this.entityContext = entityContext;
        this.setRepo = setRepo;
        this.fileRepo = fileRepo;
        this.defaultDepRepo = defaultDepRepo;
    }

    @PostMapping("/starter-fullstack.zip")
    public ResponseEntity<byte[]> generate(@RequestBody FullstackStarterRequest body) throws IOException {
        Path tempDir = Files.createTempDirectory("fullstack-");
        try {
            WebProjectRequest request = buildArtifacts(body, tempDir);
            byte[] zipBytes = zipDirectory(tempDir, request.getArtifactId());
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
                            files.add(new ProjectPreviewController.PreviewFile(rel, readSafely(p)));
                        });
            }
            List<String> paths = files.stream()
                    .map(ProjectPreviewController.PreviewFile::path).sorted().toList();
            return new ProjectPreviewController.PreviewResponse(files, buildChildren("", paths));
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
        entityContext.populate(entities, backendSetKey, frontendSetKey, domainPackage);

        // Backend — runs through the standard pipeline. The EntityScaffoldContributor
        // (registered in spring.factories) picks up the populated context.
        Path backendDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        try {
            copyDirectory(backendDir, tempDir.resolve("backend"));
        } finally {
            FileSystemUtils.deleteRecursively(backendDir);
        }

        // Frontend — rendered inline outside the Initializr pipeline.
        renderFrontend(frontendSet, request, entities, domainPackage, tempDir.resolve("frontend"));

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

    private void renderFrontend(EntityTemplateSetEntity set, WebProjectRequest request,
                                List<EntityDefinition> entities, String domainPackage, Path targetDir) throws IOException {
        List<EntityTemplateFileEntity> files = fileRepo.findBySetIdOrderBySortOrderAsc(set.getId());
        Map<String, Object> projectCtx = EntityScaffoldContext.buildProjectContext(
                request.getArtifactId(),
                request.getGroupId(),
                request.getVersion(),
                request.getPackageName(),
                domainPackage,
                request.getJavaVersion(),
                request.getPackaging(),
                entities);
        Files.createDirectories(targetDir);
        log.info("Rendering frontend CRUD scaffolding: set='{}', {} files, {} entities",
                set.getSetKey(), files.size(), entities.size());
        FullstackRenderer.render(files, projectCtx, entities, targetDir);
    }

    private String buildReadme(String artifactId) {
        return "# " + artifactId + "\n\n"
                + "Fullstack scaffold generated by the Menora Initializr.\n\n"
                + "## Layout\n\n"
                + "- `backend/` — Spring Boot app. Run with `cd backend && ./mvnw spring-boot:run` (port 8080).\n"
                + "- `frontend/` — React + Vite app. Run with `cd frontend && npm install && npm run dev` (port 5173).\n\n"
                + "Open http://localhost:5173 to use the UI.\n\n"
                + "## How the frontend reaches the backend\n\n"
                + "In dev the frontend calls same-origin `/api/...` paths; the Vite dev server\n"
                + "proxies `/api` to `http://localhost:8080` (see `frontend/vite.config.ts`), so no\n"
                + "CORS setup is needed when you run `npm run dev`.\n\n"
                + "For a production build (`npm run build`), `vite preview` and static hosting do\n"
                + "**not** run that proxy. Either serve the API at the same origin behind a reverse\n"
                + "proxy / gateway, or set a base URL via the `BASE` constant in\n"
                + "`frontend/src/api/client.ts` to point at the backend directly.\n";
    }

    private static final String ROOT_GITIGNORE =
            "# Maven\nbackend/target/\n\n# Node\nfrontend/node_modules/\nfrontend/dist/\n\n# IDE\n.idea/\n.vscode/\n*.iml\n";

    private static String orDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(s -> {
                Path t = target.resolve(source.relativize(s).toString());
                try {
                    if (Files.isDirectory(s)) {
                        Files.createDirectories(t);
                    } else {
                        if (t.getParent() != null) Files.createDirectories(t.getParent());
                        Files.copy(s, t, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private String readSafely(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[binary file]";
        }
    }

    private List<ProjectPreviewController.TreeNode> buildChildren(String prefix, List<String> paths) {
        Map<String, List<String>> subdirs = new LinkedHashMap<>();
        List<String> directFiles = new ArrayList<>();
        for (String path : paths) {
            String relative = prefix.isEmpty() ? path : path.substring(prefix.length() + 1);
            int slash = relative.indexOf('/');
            if (slash == -1) {
                directFiles.add(path);
            } else {
                String childDir = relative.substring(0, slash);
                String childPrefix = prefix.isEmpty() ? childDir : prefix + "/" + childDir;
                subdirs.computeIfAbsent(childPrefix, k -> new ArrayList<>()).add(path);
            }
        }
        List<ProjectPreviewController.TreeNode> result = new ArrayList<>();
        for (var e : subdirs.entrySet()) {
            String dirPath = e.getKey();
            String dirName = dirPath.contains("/") ? dirPath.substring(dirPath.lastIndexOf('/') + 1) : dirPath;
            result.add(new ProjectPreviewController.TreeNode(dirName, dirPath, "directory",
                    buildChildren(dirPath, e.getValue())));
        }
        for (String file : directFiles) {
            String name = file.contains("/") ? file.substring(file.lastIndexOf('/') + 1) : file;
            result.add(new ProjectPreviewController.TreeNode(name, file, "file", List.of()));
        }
        return result;
    }

    private byte[] zipDirectory(Path dir, String rootDirName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> {
                            String entry = rootDirName + "/" + dir.relativize(p).toString().replace('\\', '/');
                            try {
                                zos.putNextEntry(new ZipEntry(entry));
                                Files.copy(p, zos);
                                zos.closeEntry();
                            } catch (IOException ex) {
                                throw new UncheckedIOException(ex);
                            }
                        });
            }
        }
        return baos.toByteArray();
    }

}
