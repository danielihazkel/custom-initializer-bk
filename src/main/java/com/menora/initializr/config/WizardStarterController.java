package com.menora.initializr.config;

import com.menora.initializr.ai.AiDtos.GeneratedAiFile;
import com.menora.initializr.openapi.OpenApiCodeGenerator;
import com.menora.initializr.openapi.OpenApiWizardOptions;
import com.menora.initializr.soap.SoapCodeGenerator;
import com.menora.initializr.soap.SoapWizardOptions;
import com.menora.initializr.sql.SqlDepOptions;
import com.menora.initializr.sql.SqlDialect;
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
 * Unified POST sibling of {@code /starter.zip} that accepts a single JSON body
 * carrying both SQL scripts and OpenAPI specs. Replaces the earlier
 * {@code /starter-sql.*} and {@code /starter-openapi.*} endpoints — the wizards
 * are now composable in one request.
 *
 * <p>Either {@code sqlByDep} or {@code specByDep} (or both, or neither) may be
 * present. When both are absent the call is equivalent to the standard
 * {@code GET /starter.zip} flow.
 *
 * <p>Binds all the same base parameters as {@link WebProjectRequest}, populates
 * {@link ProjectOptionsContext}, {@link SqlScriptsContext} and
 * {@link OpenApiSpecContext}, and delegates to {@link ProjectGenerationInvoker}.
 */
@RestController
public class WizardStarterController {

    private final ProjectGenerationInvoker<ProjectRequest> invoker;
    private final InitializrMetadataProvider metadataProvider;
    private final ProjectOptionsContext optionsContext;
    private final SqlScriptsContext sqlContext;
    private final OpenApiSpecContext specContext;
    private final SoapSpecContext soapContext;
    private final AiFilesContext aiFilesContext;
    private final OpenApiCodeGenerator openApiGenerator;
    private final SoapCodeGenerator soapGenerator;
    private final SqlEntityGenerator sqlGenerator;

    public WizardStarterController(ProjectGenerationInvoker<ProjectRequest> invoker,
                                   InitializrMetadataProvider metadataProvider,
                                   ProjectOptionsContext optionsContext,
                                   SqlScriptsContext sqlContext,
                                   OpenApiSpecContext specContext,
                                   SoapSpecContext soapContext,
                                   AiFilesContext aiFilesContext,
                                   OpenApiCodeGenerator openApiGenerator,
                                   SoapCodeGenerator soapGenerator,
                                   SqlEntityGenerator sqlGenerator) {
        this.invoker = invoker;
        this.metadataProvider = metadataProvider;
        this.optionsContext = optionsContext;
        this.sqlContext = sqlContext;
        this.specContext = specContext;
        this.soapContext = soapContext;
        this.aiFilesContext = aiFilesContext;
        this.openApiGenerator = openApiGenerator;
        this.soapGenerator = soapGenerator;
        this.sqlGenerator = sqlGenerator;
    }

    @PostMapping("/starter-wizard.zip")
    public ResponseEntity<byte[]> generate(@RequestBody WizardStarterRequest body) throws IOException {
        validateSpecs(body);
        validateWsdls(body);
        validateSql(body);
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
            clearAllContexts();
        }
    }

    @PostMapping("/starter-wizard.preview")
    public ProjectPreviewController.PreviewResponse preview(@RequestBody WizardStarterRequest body) throws IOException {
        validateSpecs(body);
        validateWsdls(body);
        validateSql(body);
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
            clearAllContexts();
        }
    }

    /** Server-side path detection for the OpenAPI wizard's live preview. */
    @PostMapping("/starter-wizard.detect-paths")
    public List<String> detectPaths(@RequestBody DetectPathsRequest body) {
        return openApiGenerator.detectPaths(body.spec());
    }

    /** Server-side service detection for the SOAP wizard's live preview. */
    @PostMapping("/starter-wizard.detect-services")
    public List<String> detectServices(@RequestBody DetectServicesRequest body) {
        return soapGenerator.detectServices(body.wsdl());
    }

    @ExceptionHandler(OpenApiCodeGenerator.OpenApiParseException.class)
    public ResponseEntity<Map<String, Object>> handleParseException(OpenApiCodeGenerator.OpenApiParseException ex) {
        clearAllContexts();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid OpenAPI spec");
        body.put("messages", ex.messages());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(SoapCodeGenerator.SoapParseException.class)
    public ResponseEntity<Map<String, Object>> handleSoapParseException(SoapCodeGenerator.SoapParseException ex) {
        clearAllContexts();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid WSDL");
        body.put("messages", ex.messages());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(SqlEntityGenerator.SqlParseException.class)
    public ResponseEntity<Map<String, Object>> handleSqlParseException(SqlEntityGenerator.SqlParseException ex) {
        clearAllContexts();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid SQL");
        if (ex.depId() != null) body.put("dep", ex.depId());
        body.put("detail", ex.getMessage());
        if (ex.statementIndex() != null) body.put("statementIndex", ex.statementIndex());
        if (ex.statementSnippet() != null) body.put("snippet", ex.statementSnippet());
        return ResponseEntity.badRequest().body(body);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Runs every submitted spec through the generator once up-front so parse
     * errors surface as HTTP 400 instead of leaking out of a
     * {@code ProjectContributor} (which would otherwise become a 500 with a
     * partially-generated project on disk).
     */
    private void validateSpecs(WizardStarterRequest body) {
        if (body.specByDep() == null) return;
        for (var e : body.specByDep().entrySet()) {
            String spec = e.getValue();
            if (spec == null || spec.isBlank()) continue;
            openApiGenerator.detectPaths(spec); // does not throw
            openApiGenerator.generate(spec, "com.menora.demo",
                    body.openApiOptions() == null ? null : toOpenApiOptions(body.openApiOptions().get(e.getKey())));
        }
    }

    /**
     * Same up-front parse for the SOAP wizard — a malformed WSDL becomes a
     * clean 400 instead of a partial project + 500.
     */
    private void validateWsdls(WizardStarterRequest body) {
        if (body.wsdlByDep() == null) return;
        for (var e : body.wsdlByDep().entrySet()) {
            String wsdl = e.getValue();
            if (wsdl == null || wsdl.isBlank()) continue;
            soapGenerator.generate(wsdl, "com.menora.demo",
                    body.soapOptions() == null ? null : toSoapOptions(body.soapOptions().get(e.getKey())));
        }
    }

    /**
     * Up-front parse of every submitted SQL script so {@code SqlParseException}
     * surfaces as HTTP 400 here — instead of inside the {@code sqlEntityContributor}
     * {@link io.spring.initializr.generator.project.contributor.ProjectContributor}
     * where it would bubble out as a 500 with a partial project on disk.
     */
    private void validateSql(WizardStarterRequest body) {
        if (body.sqlByDep() == null) return;
        for (var e : body.sqlByDep().entrySet()) {
            String depId = e.getKey();
            String sql = e.getValue();
            if (sql == null || sql.isBlank()) continue;
            SqlDialect dialect = SqlDialect.forDepId(depId);
            if (dialect == null) continue;
            try {
                sqlGenerator.detectTableNames(sql, dialect);
            } catch (SqlEntityGenerator.SqlParseException ex) {
                throw new SqlEntityGenerator.SqlParseException(depId, ex.statementIndex(),
                        ex.statementSnippet(), ex.getMessage(), ex.getCause());
            }
        }
    }

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
        // The servlet filter (InitializrWebConfiguration) normally injects this
        // default, but it only acts on URL params — not JSON bodies.
        r.setConfigurationFileFormat(orDefault(body.configurationFileFormat(), "properties"));
        if (body.dependencies() != null) {
            r.setDependencies(new ArrayList<>(body.dependencies()));
        }
        return r;
    }

    private void populateContexts(WizardStarterRequest body) {
        // The servlet filter that populates ProjectOptionsContext from opts-*
        // URL params isn't in our chain here (JSON body, not query string).
        optionsContext.populate(body.opts());

        // SQL side — always populate (empty map is a no-op for the contributor).
        Map<String, SqlDepOptions> sqlOpts = new LinkedHashMap<>();
        if (body.sqlOptions() != null) {
            for (var e : body.sqlOptions().entrySet()) {
                SqlDepOptionsDto dto = e.getValue();
                if (dto == null) continue;
                List<SqlTableOptions> tables = dto.tables() == null ? List.of()
                        : dto.tables().stream()
                                .map(t -> new SqlTableOptions(t.name(),
                                        t.generateRepository() == null || t.generateRepository()))
                                .toList();
                sqlOpts.put(e.getKey(), new SqlDepOptions(dto.subPackage(), tables));
            }
        }
        sqlContext.populate(body.sqlByDep() == null ? Map.of() : body.sqlByDep(), sqlOpts);

        // OpenAPI side — same treatment.
        Map<String, OpenApiWizardOptions> openApiOpts = new LinkedHashMap<>();
        if (body.openApiOptions() != null) {
            for (var e : body.openApiOptions().entrySet()) {
                openApiOpts.put(e.getKey(), toOpenApiOptions(e.getValue()));
            }
        }
        specContext.populate(body.specByDep() == null ? Map.of() : body.specByDep(), openApiOpts);

        // SOAP side — same treatment.
        Map<String, SoapWizardOptions> soapOpts = new LinkedHashMap<>();
        if (body.soapOptions() != null) {
            for (var e : body.soapOptions().entrySet()) {
                soapOpts.put(e.getKey(), toSoapOptions(e.getValue()));
            }
        }
        soapContext.populate(body.wsdlByDep() == null ? Map.of() : body.wsdlByDep(), soapOpts);

        // AI files — kept files the user reviewed in the AI panel.
        List<GeneratedAiFile> aiFiles = new ArrayList<>();
        if (body.aiFiles() != null) {
            for (AiFileDto dto : body.aiFiles()) {
                if (dto != null && dto.path() != null && dto.content() != null) {
                    aiFiles.add(new GeneratedAiFile(dto.path(), dto.content()));
                }
            }
        }
        aiFilesContext.populate(aiFiles);
    }

    private void clearAllContexts() {
        sqlContext.clear();
        specContext.clear();
        soapContext.clear();
        aiFilesContext.clear();
        optionsContext.clear();
    }

    private static OpenApiWizardOptions toOpenApiOptions(OpenApiOptionsDto dto) {
        if (dto == null) return new OpenApiWizardOptions(null, null, null, null, null);
        return new OpenApiWizardOptions(
                dto.apiSubPackage(),
                dto.dtoSubPackage(),
                dto.clientSubPackage(),
                OpenApiWizardOptions.GenerationMode.parse(dto.mode()),
                dto.baseUrlProperty());
    }

    private static SoapWizardOptions toSoapOptions(SoapOptionsDto dto) {
        if (dto == null) return new SoapWizardOptions(null, null, null, null, null, null);
        return new SoapWizardOptions(
                dto.endpointSubPackage(),
                dto.clientSubPackage(),
                dto.payloadSubPackage(),
                SoapWizardOptions.GenerationMode.parse(dto.mode()),
                dto.baseUrlProperty(),
                dto.contextPath());
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
    public record WizardStarterRequest(
            String groupId, String artifactId, String name, String description,
            String packageName, String type, String language, String bootVersion,
            String packaging, String javaVersion, String version,
            String configurationFileFormat,
            List<String> dependencies,
            Map<String, List<String>> opts,
            Map<String, String> sqlByDep,
            Map<String, SqlDepOptionsDto> sqlOptions,
            Map<String, String> specByDep,
            Map<String, OpenApiOptionsDto> openApiOptions,
            Map<String, String> wsdlByDep,
            Map<String, SoapOptionsDto> soapOptions,
            List<AiFileDto> aiFiles) {
    }

    public record AiFileDto(String path, String content) {}

    public record SqlDepOptionsDto(String subPackage, List<SqlTableOptionsDto> tables) {}

    public record SqlTableOptionsDto(String name, Boolean generateRepository) {}

    public record OpenApiOptionsDto(
            String apiSubPackage,
            String dtoSubPackage,
            String clientSubPackage,
            String mode,
            String baseUrlProperty) {}

    public record SoapOptionsDto(
            String endpointSubPackage,
            String clientSubPackage,
            String payloadSubPackage,
            String mode,
            String baseUrlProperty,
            String contextPath) {}

    public record DetectPathsRequest(String spec) {}

    public record DetectServicesRequest(String wsdl) {}
}
