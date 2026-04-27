package com.menora.initializr.agent;

import com.menora.initializr.agent.dto.MenoraInitManifest;
import com.menora.initializr.agent.dto.ScaffoldFile;
import com.menora.initializr.agent.dto.ScaffoldRequest;
import com.menora.initializr.agent.dto.ScaffoldResponse;
import com.menora.initializr.config.OpenApiSpecContext;
import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.config.SoapSpecContext;
import com.menora.initializr.config.SqlScriptsContext;
import com.menora.initializr.config.WizardStarterController.OpenApiOptionsDto;
import com.menora.initializr.config.WizardStarterController.SoapOptionsDto;
import com.menora.initializr.config.WizardStarterController.SqlDepOptionsDto;
import com.menora.initializr.openapi.OpenApiCodeGenerator;
import com.menora.initializr.openapi.OpenApiWizardOptions;
import com.menora.initializr.soap.SoapCodeGenerator;
import com.menora.initializr.soap.SoapWizardOptions;
import com.menora.initializr.sql.SqlDepOptions;
import com.menora.initializr.sql.SqlDialect;
import com.menora.initializr.sql.SqlEntityGenerator;
import com.menora.initializr.sql.SqlTableOptions;
import io.spring.initializr.metadata.InitializrMetadata;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectGenerationResult;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.springframework.http.HttpStatus;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Agent-friendly twin of {@code /starter-wizard.zip}. Returns the generated
 * project as a JSON envelope (file paths + UTF-8/base64 contents + per-file
 * checksums) instead of a binary ZIP, and ships a {@code .menora-init.json}
 * manifest at the project root.
 *
 * <p>Same pipeline beans as {@code WizardStarterController} — accepts the same
 * request shape (extended with {@code mode} and {@code modules} fields),
 * funnels through {@link ProjectGenerationInvoker}, then walks the resulting
 * directory.
 */
@RestController
public class AgentScaffoldController {

    /** Extensions whose contents are guaranteed to be valid UTF-8 text. */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "kt", "kts", "groovy", "scala",
            "xml", "yaml", "yml", "properties", "json", "txt", "md",
            "gitignore", "gitattributes", "editorconfig",
            "dockerfile", "sh", "bat", "cmd", "ps1",
            "html", "css", "js", "ts", "tsx",
            "wsdl", "xsd", "sql"
    );

    /** Filenames (no extension) we know are text. */
    private static final Set<String> TEXT_FILENAMES = Set.of(
            "Dockerfile", "Jenkinsfile", "Makefile", "VERSION",
            ".gitignore", ".gitattributes", ".editorconfig",
            "mvnw", "mvnw.cmd", "gradlew", "gradlew.bat"
    );

    private final ProjectGenerationInvoker<ProjectRequest> invoker;
    private final InitializrMetadataProvider metadataProvider;
    private final ProjectOptionsContext optionsContext;
    private final SqlScriptsContext sqlContext;
    private final OpenApiSpecContext specContext;
    private final SoapSpecContext soapContext;
    private final OpenApiCodeGenerator openApiGenerator;
    private final SoapCodeGenerator soapGenerator;
    private final SqlEntityGenerator sqlGenerator;
    private final ManifestWriter manifestWriter;

    public AgentScaffoldController(ProjectGenerationInvoker<ProjectRequest> invoker,
                                   InitializrMetadataProvider metadataProvider,
                                   ProjectOptionsContext optionsContext,
                                   SqlScriptsContext sqlContext,
                                   OpenApiSpecContext specContext,
                                   SoapSpecContext soapContext,
                                   OpenApiCodeGenerator openApiGenerator,
                                   SoapCodeGenerator soapGenerator,
                                   SqlEntityGenerator sqlGenerator,
                                   ManifestWriter manifestWriter) {
        this.invoker = invoker;
        this.metadataProvider = metadataProvider;
        this.optionsContext = optionsContext;
        this.sqlContext = sqlContext;
        this.specContext = specContext;
        this.soapContext = soapContext;
        this.openApiGenerator = openApiGenerator;
        this.soapGenerator = soapGenerator;
        this.sqlGenerator = sqlGenerator;
        this.manifestWriter = manifestWriter;
    }

    @PostMapping("/agent/scaffold")
    public ResponseEntity<?> scaffold(@RequestBody ScaffoldRequest body) throws IOException {
        String mode = body.resolvedMode();
        if ("multimodule".equals(mode)) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                    "error", "multimodule mode is not yet implemented in /agent/scaffold",
                    "hint", "use GET /starter-multimodule.zip for now; JSON-mode multimodule is on the roadmap"));
        }

        validateWizardInputs(body);

        WebProjectRequest request = toWebRequest(body);
        populateContexts(body);
        Path projectDir = null;
        try {
            ProjectGenerationResult result = invoker.invokeProjectStructureGeneration(request);
            projectDir = result.getRootDirectory();

            MenoraInitManifest manifest = manifestWriter.writeAndReturn(
                    projectDir, body, result.getProjectDescription());

            List<ScaffoldFile> files = readFiles(projectDir);
            return ResponseEntity.ok(new ScaffoldResponse(manifest, files));
        } finally {
            if (projectDir != null) FileSystemUtils.deleteRecursively(projectDir);
            clearAllContexts();
        }
    }

    @ExceptionHandler(OpenApiCodeGenerator.OpenApiParseException.class)
    public ResponseEntity<Map<String, Object>> handleParseException(
            OpenApiCodeGenerator.OpenApiParseException ex) {
        clearAllContexts();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid OpenAPI spec");
        body.put("messages", ex.messages());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(SoapCodeGenerator.SoapParseException.class)
    public ResponseEntity<Map<String, Object>> handleSoapParseException(
            SoapCodeGenerator.SoapParseException ex) {
        clearAllContexts();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid WSDL");
        body.put("messages", ex.messages());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(SqlEntityGenerator.SqlParseException.class)
    public ResponseEntity<Map<String, Object>> handleSqlParseException(
            SqlEntityGenerator.SqlParseException ex) {
        clearAllContexts();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid SQL");
        if (ex.depId() != null) body.put("dep", ex.depId());
        body.put("detail", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void validateWizardInputs(ScaffoldRequest body) {
        if (body.specByDep() != null) {
            for (var e : body.specByDep().entrySet()) {
                String spec = e.getValue();
                if (spec == null || spec.isBlank()) continue;
                openApiGenerator.detectPaths(spec);
                openApiGenerator.generate(spec, "com.menora.demo",
                        body.openApiOptions() == null ? null : toOpenApiOptions(body.openApiOptions().get(e.getKey())));
            }
        }
        if (body.wsdlByDep() != null) {
            for (var e : body.wsdlByDep().entrySet()) {
                String wsdl = e.getValue();
                if (wsdl == null || wsdl.isBlank()) continue;
                soapGenerator.generate(wsdl, "com.menora.demo",
                        body.soapOptions() == null ? null : toSoapOptions(body.soapOptions().get(e.getKey())));
            }
        }
        if (body.sqlByDep() != null) {
            for (var e : body.sqlByDep().entrySet()) {
                String depId = e.getKey();
                String sql = e.getValue();
                if (sql == null || sql.isBlank()) continue;
                SqlDialect dialect = SqlDialect.forDepId(depId);
                if (dialect == null) continue;
                try {
                    sqlGenerator.detectTableNames(sql, dialect);
                } catch (SqlEntityGenerator.SqlParseException ex) {
                    throw new SqlEntityGenerator.SqlParseException(depId, ex.getMessage(), ex.getCause());
                }
            }
        }
    }

    private WebProjectRequest toWebRequest(ScaffoldRequest body) {
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

    private void populateContexts(ScaffoldRequest body) {
        optionsContext.populate(body.opts());

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

        Map<String, OpenApiWizardOptions> openApiOpts = new LinkedHashMap<>();
        if (body.openApiOptions() != null) {
            for (var e : body.openApiOptions().entrySet()) {
                openApiOpts.put(e.getKey(), toOpenApiOptions(e.getValue()));
            }
        }
        specContext.populate(body.specByDep() == null ? Map.of() : body.specByDep(), openApiOpts);

        Map<String, SoapWizardOptions> soapOpts = new LinkedHashMap<>();
        if (body.soapOptions() != null) {
            for (var e : body.soapOptions().entrySet()) {
                soapOpts.put(e.getKey(), toSoapOptions(e.getValue()));
            }
        }
        soapContext.populate(body.wsdlByDep() == null ? Map.of() : body.wsdlByDep(), soapOpts);
    }

    private void clearAllContexts() {
        sqlContext.clear();
        specContext.clear();
        soapContext.clear();
        optionsContext.clear();
    }

    private List<ScaffoldFile> readFiles(Path projectDir) throws IOException {
        List<ScaffoldFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectDir)) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        String rel = projectDir.relativize(p).toString().replace('\\', '/');
                        out.add(toScaffoldFile(p, rel));
                    });
        }
        return out;
    }

    private static ScaffoldFile toScaffoldFile(Path p, String rel) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            String sha = sha256Hex(bytes);
            if (isText(rel, bytes)) {
                return new ScaffoldFile(rel, ScaffoldFile.UTF8,
                        new String(bytes, StandardCharsets.UTF_8), sha);
            }
            return new ScaffoldFile(rel, ScaffoldFile.BASE64,
                    Base64.getEncoder().encodeToString(bytes), sha);
        } catch (IOException e) {
            return new ScaffoldFile(rel, ScaffoldFile.UTF8, "[unreadable: " + e.getMessage() + "]", "ERROR");
        }
    }

    private static boolean isText(String relativePath, byte[] bytes) {
        String name = relativePath.contains("/")
                ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
                : relativePath;
        if (TEXT_FILENAMES.contains(name)) return true;
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            if (TEXT_EXTENSIONS.contains(ext)) return true;
        }
        // Fallback: treat as text if no NUL bytes appear in the first 4 KB.
        int probe = Math.min(bytes.length, 4096);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) return false;
        }
        return true;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(bytes);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            return "ERROR";
        }
    }

    private static OpenApiWizardOptions toOpenApiOptions(OpenApiOptionsDto dto) {
        if (dto == null) return new OpenApiWizardOptions(null, null, null, null, null);
        return new OpenApiWizardOptions(
                dto.apiSubPackage(), dto.dtoSubPackage(), dto.clientSubPackage(),
                OpenApiWizardOptions.GenerationMode.parse(dto.mode()),
                dto.baseUrlProperty());
    }

    private static SoapWizardOptions toSoapOptions(SoapOptionsDto dto) {
        if (dto == null) return new SoapWizardOptions(null, null, null, null, null, null);
        return new SoapWizardOptions(
                dto.endpointSubPackage(), dto.clientSubPackage(), dto.payloadSubPackage(),
                SoapWizardOptions.GenerationMode.parse(dto.mode()),
                dto.baseUrlProperty(), dto.contextPath());
    }

    private static String orDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
