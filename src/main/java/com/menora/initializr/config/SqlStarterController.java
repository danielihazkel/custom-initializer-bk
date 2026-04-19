package com.menora.initializr.config;

import com.menora.initializr.sql.SqlDepOptions;
import com.menora.initializr.sql.SqlEntityGenerator;
import com.menora.initializr.sql.SqlTableOptions;
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
 * POST sibling of {@code /starter.zip} that accepts a JSON body carrying
 * {@code CREATE TABLE} scripts. The body cannot fit in a URL for realistic
 * schemas, hence the new endpoint — the GET flow is left untouched.
 *
 * <p>Binds all the same base parameters as {@link WebProjectRequest}, populates
 * {@link ProjectOptionsContext} and {@link SqlScriptsContext}, and delegates
 * to {@link ProjectGenerationInvoker}.
 */
@RestController
public class SqlStarterController {

    private final ProjectGenerationInvoker<ProjectRequest> invoker;
    private final InitializrMetadataProvider metadataProvider;
    private final ProjectOptionsContext optionsContext;
    private final SqlScriptsContext sqlContext;
    private final SqlEntityGenerator generator;

    public SqlStarterController(ProjectGenerationInvoker<ProjectRequest> invoker,
                                InitializrMetadataProvider metadataProvider,
                                ProjectOptionsContext optionsContext,
                                SqlScriptsContext sqlContext,
                                SqlEntityGenerator generator) {
        this.invoker = invoker;
        this.metadataProvider = metadataProvider;
        this.optionsContext = optionsContext;
        this.sqlContext = sqlContext;
        this.generator = generator;
    }

    @PostMapping("/starter-sql.zip")
    public ResponseEntity<byte[]> generate(@RequestBody SqlStarterRequest body) throws IOException {
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
            sqlContext.clear();
            optionsContext.clear();
        }
    }

    @PostMapping("/starter-sql.preview")
    public ProjectPreviewController.PreviewResponse preview(@RequestBody SqlStarterRequest body) throws IOException {
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
                    buildChildren("", files.stream().map(ProjectPreviewController.PreviewFile::path).sorted().toList()));
        } finally {
            if (projectDir != null) FileSystemUtils.deleteRecursively(projectDir);
            sqlContext.clear();
            optionsContext.clear();
        }
    }

    /** Server-side parse for the wizard's live table-name preview. */
    @PostMapping("/starter-sql.tables")
    public List<String> detectTables(@RequestBody DetectTablesRequest body) {
        return generator.detectTableNames(body.sql());
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private WebProjectRequest toWebRequest(SqlStarterRequest body) {
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
        // The servlet filter (InitializrWebConfiguration) normally injects this
        // default, but it only acts on URL params — not JSON bodies.
        r.setConfigurationFileFormat(orDefault(body.configurationFileFormat(), "properties"));
        if (body.dependencies() != null) {
            r.setDependencies(new ArrayList<>(body.dependencies()));
        }
        return r;
    }

    private void populateContexts(SqlStarterRequest body) {
        // The servlet filter that normally populates ProjectOptionsContext from
        // opts-* URL params isn't in our chain here (JSON body, not query string).
        optionsContext.populate(body.opts());

        Map<String, SqlDepOptions> opts = new LinkedHashMap<>();
        if (body.sqlOptions() != null) {
            for (var e : body.sqlOptions().entrySet()) {
                SqlDepOptionsDto dto = e.getValue();
                List<SqlTableOptions> tables = dto.tables() == null ? List.of()
                        : dto.tables().stream()
                                .map(t -> new SqlTableOptions(t.name(), t.generateRepository() == null || t.generateRepository()))
                                .toList();
                opts.put(e.getKey(), new SqlDepOptions(dto.subPackage(), tables));
            }
        }
        sqlContext.populate(body.sqlByDep() == null ? Map.of() : body.sqlByDep(), opts);
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

    // Tree builder — same recursion as ProjectPreviewController
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
    public record SqlStarterRequest(
            String groupId, String artifactId, String name, String description,
            String packageName, String type, String language, String bootVersion,
            String packaging, String javaVersion, String version,
            String configurationFileFormat,
            List<String> dependencies,
            Map<String, List<String>> opts,
            Map<String, String> sqlByDep,
            Map<String, SqlDepOptionsDto> sqlOptions) {
    }

    public record SqlDepOptionsDto(String subPackage, List<SqlTableOptionsDto> tables) {}

    public record SqlTableOptionsDto(String name, Boolean generateRepository) {}

    public record DetectTablesRequest(String sql) {}
}
