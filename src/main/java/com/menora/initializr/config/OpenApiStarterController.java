package com.menora.initializr.config;

import com.menora.initializr.openapi.OpenApiCodeGenerator;
import com.menora.initializr.openapi.OpenApiWizardOptions;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.spring.initializr.metadata.InitializrMetadata;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * POST sibling of {@code /starter.zip} that accepts a JSON body carrying an
 * OpenAPI 3.x spec per dependency. Mirrors {@link SqlStarterController} — the
 * wizard is a symmetrical feature.
 *
 * <p>Parse errors from swagger-parser are surfaced as HTTP 400 via the
 * {@link #handleParseException(OpenApiCodeGenerator.OpenApiParseException)}
 * handler.
 */
@RestController
public class OpenApiStarterController {

    private final ProjectGenerationInvoker<ProjectRequest> invoker;
    private final InitializrMetadataProvider metadataProvider;
    private final ProjectOptionsContext optionsContext;
    private final OpenApiSpecContext specContext;
    private final OpenApiCodeGenerator generator;

    public OpenApiStarterController(ProjectGenerationInvoker<ProjectRequest> invoker,
                                    InitializrMetadataProvider metadataProvider,
                                    ProjectOptionsContext optionsContext,
                                    OpenApiSpecContext specContext,
                                    OpenApiCodeGenerator generator) {
        this.invoker = invoker;
        this.metadataProvider = metadataProvider;
        this.optionsContext = optionsContext;
        this.specContext = specContext;
        this.generator = generator;
    }

    @PostMapping("/starter-openapi.zip")
    public ResponseEntity<byte[]> generate(@RequestBody OpenApiStarterRequest body) throws IOException {
        validateSpecs(body);
        WebProjectRequest request = toWebRequest(body);
        populateContexts(body);
        Path projectDir = null;
        try {
            projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            byte[] zip = zipDirectory(projectDir, request.getArtifactId());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + request.getArtifactId() + ".zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zip);
        } finally {
            if (projectDir != null) FileSystemUtils.deleteRecursively(projectDir);
            specContext.clear();
            optionsContext.clear();
        }
    }

    @PostMapping("/starter-openapi.preview")
    public ProjectPreviewController.PreviewResponse preview(@RequestBody OpenApiStarterRequest body) throws IOException {
        validateSpecs(body);
        WebProjectRequest request = toWebRequest(body);
        populateContexts(body);
        Path projectDir = null;
        try {
            projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            List<ProjectPreviewController.PreviewFile> files = new ArrayList<>();
            final Path root = projectDir;
            try (Stream<Path> walk = Files.walk(projectDir)) {
                walk.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> {
                            String rel = root.relativize(p).toString().replace('\\', '/');
                            files.add(new ProjectPreviewController.PreviewFile(rel, readSafely(p)));
                        });
            }
            return new ProjectPreviewController.PreviewResponse(files,
                    buildChildren("", files.stream()
                            .map(ProjectPreviewController.PreviewFile::path).sorted().toList()));
        } finally {
            if (projectDir != null) FileSystemUtils.deleteRecursively(projectDir);
            specContext.clear();
            optionsContext.clear();
        }
    }

    /** Server-side path detection for the wizard's live preview. */
    @PostMapping("/starter-openapi.paths")
    public List<String> detectPaths(@RequestBody DetectPathsRequest body) {
        return generator.detectPaths(body.spec());
    }

    @ExceptionHandler(OpenApiCodeGenerator.OpenApiParseException.class)
    public ResponseEntity<Map<String, Object>> handleParseException(OpenApiCodeGenerator.OpenApiParseException ex) {
        specContext.clear();
        optionsContext.clear();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid OpenAPI spec");
        body.put("messages", ex.messages());
        return ResponseEntity.badRequest().body(body);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Runs the spec through the generator once up-front so parse errors surface
     * as HTTP 400 instead of leaking out of a {@code ProjectContributor} (which
     * would otherwise become a 500 with a partially-generated project on disk).
     */
    private void validateSpecs(OpenApiStarterRequest body) {
        if (body.specByDep() == null) return;
        for (var e : body.specByDep().entrySet()) {
            String spec = e.getValue();
            if (spec == null || spec.isBlank()) continue;
            generator.detectPaths(spec); // does not throw
            // Trigger a real parse
            generator.generate(spec, "com.menora.demo",
                    body.openApiOptions() == null ? null : toOptions(body.openApiOptions().get(e.getKey())));
        }
    }

    private WebProjectRequest toWebRequest(OpenApiStarterRequest body) {
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

    private void populateContexts(OpenApiStarterRequest body) {
        optionsContext.populate(body.opts());

        Map<String, OpenApiWizardOptions> opts = new LinkedHashMap<>();
        if (body.openApiOptions() != null) {
            for (var e : body.openApiOptions().entrySet()) {
                opts.put(e.getKey(), toOptions(e.getValue()));
            }
        }
        specContext.populate(body.specByDep() == null ? Map.of() : body.specByDep(), opts);
    }

    private static OpenApiWizardOptions toOptions(OpenApiOptionsDto dto) {
        if (dto == null) return new OpenApiWizardOptions(null, null, null, null, null);
        return new OpenApiWizardOptions(
                dto.apiSubPackage(),
                dto.dtoSubPackage(),
                dto.clientSubPackage(),
                OpenApiWizardOptions.GenerationMode.parse(dto.mode()),
                dto.baseUrlProperty());
    }

    private static String orDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
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

    // ── Request records ───────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpenApiStarterRequest(
            String groupId, String artifactId, String name, String description,
            String packageName, String type, String language, String bootVersion,
            String packaging, String javaVersion, String version,
            String configurationFileFormat,
            List<String> dependencies,
            Map<String, List<String>> opts,
            Map<String, String> specByDep,
            Map<String, OpenApiOptionsDto> openApiOptions) {
    }

    public record OpenApiOptionsDto(
            String apiSubPackage,
            String dtoSubPackage,
            String clientSubPackage,
            String mode,
            String baseUrlProperty) {}

    public record DetectPathsRequest(String spec) {}
}
