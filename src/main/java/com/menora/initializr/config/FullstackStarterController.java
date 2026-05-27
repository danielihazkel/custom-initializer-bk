package com.menora.initializr.config;

import com.menora.initializr.db.entity.EntityTemplateFileEntity;
import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import com.menora.initializr.db.repository.EntityTemplateFileRepository;
import com.menora.initializr.db.repository.EntityTemplateSetRepository;
import com.menora.initializr.fullstack.EntityDefinition;
import com.menora.initializr.fullstack.EntityScaffoldContext;
import com.menora.initializr.fullstack.FullstackRenderer;
import com.menora.initializr.fullstack.FullstackRequestValidator;
import com.menora.initializr.fullstack.FullstackStarterRequest;
import io.spring.initializr.metadata.InitializrMetadata;
import io.spring.initializr.metadata.InitializrMetadataProvider;
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

    public FullstackStarterController(ProjectGenerationInvoker<ProjectRequest> invoker,
                                      InitializrMetadataProvider metadataProvider,
                                      ProjectOptionsContext optionsContext,
                                      EntityDefinitionContext entityContext,
                                      EntityTemplateSetRepository setRepo,
                                      EntityTemplateFileRepository fileRepo) {
        this.invoker = invoker;
        this.metadataProvider = metadataProvider;
        this.optionsContext = optionsContext;
        this.entityContext = entityContext;
        this.setRepo = setRepo;
        this.fileRepo = fileRepo;
    }

    @PostMapping("/starter-fullstack.zip")
    public ResponseEntity<byte[]> generate(@RequestBody FullstackStarterRequest body) throws IOException {
        List<EntityDefinition> entities = FullstackRequestValidator.validateAndConvert(body);
        String backendSetKey = orDefault(body.backendTemplateSet(), DEFAULT_BACKEND_SET);
        String frontendSetKey = orDefault(body.frontendTemplateSet(), DEFAULT_FRONTEND_SET);

        WebProjectRequest request = toWebRequest(body);
        ensureRequiredDeps(request);
        optionsContext.populate(body.opts());
        entityContext.populate(entities, backendSetKey, frontendSetKey);

        Path tempDir = Files.createTempDirectory("fullstack-");
        Path backendDir = null;
        try {
            // Backend — runs through the standard pipeline. The EntityScaffoldContributor
            // (registered in spring.factories) picks up the populated context.
            backendDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            copyDirectory(backendDir, tempDir.resolve("backend"));

            // Frontend — rendered inline outside the Initializr pipeline.
            renderFrontend(frontendSetKey, request, entities, tempDir.resolve("frontend"));

            // Root files
            Files.writeString(tempDir.resolve("README.md"), buildReadme(request.getArtifactId()));
            Files.writeString(tempDir.resolve(".gitignore"), ROOT_GITIGNORE);

            byte[] zipBytes = zipDirectory(tempDir, request.getArtifactId());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + request.getArtifactId() + ".zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zipBytes);
        } finally {
            if (backendDir != null) FileSystemUtils.deleteRecursively(backendDir);
            FileSystemUtils.deleteRecursively(tempDir);
            entityContext.clear();
            optionsContext.clear();
        }
    }

    @PostMapping("/starter-fullstack.preview")
    public ProjectPreviewController.PreviewResponse preview(@RequestBody FullstackStarterRequest body) throws IOException {
        List<EntityDefinition> entities = FullstackRequestValidator.validateAndConvert(body);
        String backendSetKey = orDefault(body.backendTemplateSet(), DEFAULT_BACKEND_SET);
        String frontendSetKey = orDefault(body.frontendTemplateSet(), DEFAULT_FRONTEND_SET);

        WebProjectRequest request = toWebRequest(body);
        ensureRequiredDeps(request);
        optionsContext.populate(body.opts());
        entityContext.populate(entities, backendSetKey, frontendSetKey);

        Path tempDir = Files.createTempDirectory("fullstack-preview-");
        Path backendDir = null;
        try {
            backendDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            copyDirectory(backendDir, tempDir.resolve("backend"));
            renderFrontend(frontendSetKey, request, entities, tempDir.resolve("frontend"));
            Files.writeString(tempDir.resolve("README.md"), buildReadme(request.getArtifactId()));
            Files.writeString(tempDir.resolve(".gitignore"), ROOT_GITIGNORE);

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
            if (backendDir != null) FileSystemUtils.deleteRecursively(backendDir);
            FileSystemUtils.deleteRecursively(tempDir);
            entityContext.clear();
            optionsContext.clear();
        }
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

    /** v1 requires JPA + an embedded DB so the generated controllers actually persist. */
    private void ensureRequiredDeps(WebProjectRequest request) {
        Set<String> deps = new LinkedHashSet<>(
                request.getDependencies() == null ? List.of() : request.getDependencies());
        boolean changed = deps.add("data-jpa") | deps.add("web");
        // Embedded DB: prefer h2 if no DB driver is selected. This list mirrors
        // the seeded DB drivers in DataSeeder.
        boolean hasDb = deps.contains("h2") || deps.contains("postgresql")
                || deps.contains("mysql") || deps.contains("oracle")
                || deps.contains("db2") || deps.contains("mssql");
        if (!hasDb) {
            deps.add("h2");
            changed = true;
        }
        if (changed) {
            request.setDependencies(new ArrayList<>(deps));
        }
    }

    private void renderFrontend(String setKey, WebProjectRequest request,
                                List<EntityDefinition> entities, Path targetDir) throws IOException {
        EntityTemplateSetEntity set = setRepo.findBySetKey(setKey).orElse(null);
        if (set == null) {
            log.warn("frontendTemplateSet '{}' not found — frontend will be empty", setKey);
            Files.createDirectories(targetDir);
            return;
        }
        if (set.getKind() != EntityTemplateSetEntity.Kind.FRONTEND_REACT) {
            throw new WizardArgumentException("frontendTemplateSet '" + setKey
                    + "' is not a FRONTEND_REACT set (kind=" + set.getKind() + ")");
        }
        List<EntityTemplateFileEntity> files = fileRepo.findBySetIdOrderBySortOrderAsc(set.getId());
        Map<String, Object> projectCtx = EntityScaffoldContext.buildProjectContext(
                request.getArtifactId(),
                request.getGroupId(),
                request.getVersion(),
                request.getPackageName(),
                request.getJavaVersion(),
                request.getPackaging(),
                entities);
        Files.createDirectories(targetDir);
        log.info("Rendering frontend CRUD scaffolding: set='{}', {} files, {} entities",
                setKey, files.size(), entities.size());
        FullstackRenderer.render(files, projectCtx, entities, targetDir);
    }

    private String buildReadme(String artifactId) {
        return "# " + artifactId + "\n\n"
                + "Fullstack scaffold generated by the Menora Initializr.\n\n"
                + "## Layout\n\n"
                + "- `backend/` — Spring Boot app. Run with `cd backend && ./mvnw spring-boot:run` (port 8080).\n"
                + "- `frontend/` — React + Vite app. Run with `cd frontend && npm install && npm run dev` (port 5173).\n\n"
                + "Open http://localhost:5173 to use the UI. The frontend talks directly to the backend at :8080.\n";
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
