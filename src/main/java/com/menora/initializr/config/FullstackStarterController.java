package com.menora.initializr.config;

import com.menora.initializr.fullstack.FullstackStarterRequest;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.WebProjectRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import com.menora.initializr.fullstack.patch.ProjectUpdateBuilder;
import com.menora.initializr.fullstack.schema.SchemaBuilder;
import com.menora.initializr.fullstack.schema.SchemaDialect;
import com.menora.initializr.fullstack.schema.SchemaInstaller;
import com.menora.initializr.fullstack.schema.SchemaMigrations;
import com.menora.initializr.fullstack.schema.SchemaSql;
import io.spring.initializr.generator.version.Version;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.menora.initializr.config.FullstackModelFile.*;
import static com.menora.initializr.config.FullstackRootFiles.*;

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

    /** Builds the project; this class is the HTTP around it. */
    private final FullstackProjectAssembler assembler;
    private final ProjectOptionsContext optionsContext;
    private final EntityDefinitionContext entityContext;
    private final com.menora.initializr.observability.SnapshotStore snapshotStore;
    private final SchemaInstaller schemaInstaller;
    /** The header naming the caller (the SSO gateway's), for the create-tables log line. */
    private final String userHeader;

    public FullstackStarterController(FullstackProjectAssembler assembler,
                                      ProjectOptionsContext optionsContext,
                                      EntityDefinitionContext entityContext,
                                      com.menora.initializr.observability.SnapshotStore snapshotStore,
                                      SchemaInstaller schemaInstaller,
                                      @Value("${menora.audit.user-header:userinfo}") String userHeader) {
        this.assembler = assembler;
        this.snapshotStore = snapshotStore;
        this.schemaInstaller = schemaInstaller;
        this.userHeader = userHeader;
        this.optionsContext = optionsContext;
        this.entityContext = entityContext;
    }

    @PostMapping("/starter-fullstack.zip")
    public ResponseEntity<byte[]> generate(@RequestBody FullstackStarterRequest body) throws IOException {
        Path tempDir = Files.createTempDirectory("fullstack-");
        try {
            WebProjectRequest request = assembler.buildArtifacts(body, tempDir);
            byte[] zipBytes = GeneratedProjectFiles.zipDirectory(tempDir, request.getArtifactId());
            // The id the model file carries too: a client keeps it with the model it sent, and
            // hands it back when it later asks for an update to this project.
            String templates = templatesHashOf(Files.readAllBytes(tempDir.resolve(MODEL_FILE)));
            return ResponseEntity.ok()
                    .header("X-Templates-Hash", templates == null ? "" : templates)
                    .header("X-Schema-Version", String.valueOf(com.menora.initializr.fullstack.schema.SchemaBuilder.CURRENT_VERSION))
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
            assembler.buildArtifacts(body, tempDir);

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
     * The model's tables as a script for one database.
     *
     * @param dialect        the database it is written for ({@code POSTGRESQL}, …)
     * @param label          that database as people name it
     * @param sql            the script: exactly the {@code V1__init.sql} a project with Flyway on that
     *                       database gets
     * @param tables         the tables it creates, as it names them
     * @param projectDialect the database the model's own dependencies select
     * @param flyway         the model has the {@code flyway} dependency
     */
    public record DdlResponse(String dialect, String label, String sql, List<String> tables, String projectDialect,
                              boolean flyway) {}

    /**
     * {@code POST /starter-fullstack.ddl}: the model's schema as a script, for {@code dialect} (a
     * name such as {@code POSTGRESQL} or a dependency id such as {@code mssql}) or else for the
     * database the model's dependencies select. Whether the model has Flyway does not matter.
     * A model the generator would refuse is the same 400.
     */
    @PostMapping("/starter-fullstack.ddl")
    public DdlResponse ddl(@RequestBody FullstackStarterRequest body,
                           @RequestParam(name = "dialect", required = false) String dialect) {
        try {
            FullstackProjectAssembler.BuiltSchema built = assembler.schemaOf(body,
                    dialect == null || dialect.isBlank() ? null : FullstackProjectAssembler.dialectOf(dialect));
            SchemaDialect target = built.dialect();
            return new DdlResponse(target.name(), SchemaSql.label(target), SchemaSql.baseline(built.schema(), target),
                    built.schema().tables().stream().map(com.menora.initializr.fullstack.schema.Schema.Table::ref).toList(),
                    built.projectDialect().name(), built.flyway());
        } finally {
            entityContext.clear();
            optionsContext.clear();
        }
    }

    /**
     * @param request the model, as {@code /starter-fullstack.zip} takes it
     * @param source  the schema source to create the tables on — one an admin allowed it for
     * @param schema  the schema to create them in, as the source lists it
     * @param check   plan only: says what would run, creates nothing
     */
    public record CreateTablesRequest(FullstackStarterRequest request, String source, String schema, Boolean check) {}

    /**
     * @param created the tables made; empty for a check
     * @param transactional a failure rolls back (PostgreSQL, SQL Server); elsewhere the tables a
     *                      failed run made are dropped again
     */
    public record CreateTablesResponse(String dialect, String label, String schema, String sql, List<String> tables,
                                       int statements, List<String> created, boolean checked, boolean transactional,
                                       String projectDialect, boolean flyway) {}

    /**
     * {@code POST /starter-fullstack.create-tables}: the model's tables created on a schema source,
     * in the source's database dialect and the schema picked (see {@link SchemaInstaller}). A
     * table already there is a 409 listing them, with nothing run; a source that does not allow it
     * is a 403. A model problem is the generator's 400, its path under {@code request.}.
     */
    @PostMapping("/starter-fullstack.create-tables")
    public CreateTablesResponse createTables(@RequestBody CreateTablesRequest body, HttpServletRequest http) {
        if (body == null || body.request() == null) {
            throw new WizardArgumentException("Send the model as 'request', and the source and schema to create its tables in",
                    "request");
        }
        boolean check = Boolean.TRUE.equals(body.check());
        String who = http.getHeader(userHeader) == null ? "anonymous" : http.getHeader(userHeader).replaceAll("[\\r\\n\\t]", " ");
        try {
            FullstackProjectAssembler.BuiltSchema built;
            try {
                // Written for the source's database: a name that is a keyword there is quoted its way.
                built = assembler.schemaOf(body.request(), schemaInstaller.dialectOf(body.source()));
            } catch (WizardArgumentException e) {
                throw e.at("request");
            }
            SchemaInstaller.Result r;
            try {
                r = schemaInstaller.install(body.source(), body.schema(), built.schema(), check);
            } catch (com.menora.initializr.liveschema.LiveSchemaException e) {
                log.warn("Create tables ({}) on source {} schema {} refused or failed, by {}: {}", check ? "check" : "run",
                        body.source(), body.schema(), who, e.getMessage());
                throw e;
            }
            log.info("Create tables ({}) on source {} schema {}: {} tables, {} created, by {}", check ? "check" : "run",
                    body.source(), r.schema(), r.tables().size(), r.created().size(), who);
            return new CreateTablesResponse(r.dialect().name(), SchemaSql.label(r.dialect()), r.schema(), r.sql(),
                    r.tables(), r.statements(), r.created(), check, r.transactional(), built.projectDialect().name(),
                    built.flyway());
        } finally {
            entityContext.clear();
            optionsContext.clear();
        }
    }

    @ExceptionHandler(com.menora.initializr.liveschema.LiveSchemaException.class)
    public ResponseEntity<Map<String, Object>> handleLiveSchema(com.menora.initializr.liveschema.LiveSchemaException ex) {
        entityContext.clear();
        optionsContext.clear();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.error());
        body.put("detail", ex.getMessage());
        if (ex.existing() != null) body.put("existing", ex.existing());
        return ResponseEntity.status(ex.status()).body(body);
    }

    /**
     * The two models of an update: what the project was generated from and what it should be
     * now. {@code baseTemplatesHash} is the {@code generator.templatesHash} of the project's
     * {@code menora-model.json}, when the client has it — the notes then say whether the
     * templates are still the ones the project was generated with.
     */
    public record FullstackPatchRequest(FullstackStarterRequest base, FullstackStarterRequest target,
                                        String baseTemplatesHash, String baseSnapshot, Integer baseSchemaVersion) {
        public FullstackPatchRequest(FullstackStarterRequest base, FullstackStarterRequest target, String baseTemplatesHash) {
            this(base, target, baseTemplatesHash, null, null);
        }
    }

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
            // The base side is generated even when its files were kept: the migration needs its schema.
            FullstackProjectAssembler.Side base = assembler.generateSide("base", body.base(), baseDir);
            FullstackProjectAssembler.Side changed = assembler.generateSide("target", body.target(), targetDir);
            WebProjectRequest request = changed.request();
            Map<String, byte[]> baseTree = GeneratedProjectFiles.readTree(baseDir);
            // What the project really is, when its download was kept: then the patch also carries
            // what the generator writes differently since (GeneratedSnapshots).
            java.util.Optional<Map<String, byte[]>> kept = snapshotStore.find(body.baseSnapshot());
            if (kept.isPresent()) {
                Map<String, byte[]> stored = new java.util.TreeMap<>(kept.get());
                // The project's model file names those files: the update then replaces it with one
                // naming its result, and the next update starts from there.
                stored.put(MODEL_FILE, GeneratedSnapshots.withSnapshot(
                        new String(baseTree.get(MODEL_FILE), StandardCharsets.UTF_8), body.baseSnapshot())
                        .getBytes(StandardCharsets.UTF_8));
                baseTree = stored;
            }
            Map<String, byte[]> target = GeneratedProjectFiles.readTree(targetDir);
            // With Flyway, the first migration has run and stays as it was; what the model
            // changed is a migration of its own.
            java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
            // The generator's own tables as the project has them: the version its model file names.
            SchemaMigrations.carryBaselineOver(baseTree, target, base.schema(), changed.schema(),
                    now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
                    now.toLocalDate().toString(), body.baseSchemaVersion());
            // What the project is once the update is applied — the next update starts from it.
            GeneratedSnapshots.stamp(target);
            ProjectUpdateBuilder.ProjectUpdate update = ProjectUpdateBuilder.build(
                    baseTree, target,
                    new ProjectUpdateBuilder.Info(request.getArtifactId(), body.baseTemplatesHash(),
                            templatesHashOf(target.get(MODEL_FILE))).fromSnapshot(kept.isPresent()),
                    // Never edited by hand and rewritten whole by every generation: replaced, not patched.
                    Set.of(MODEL_FILE));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + request.getArtifactId() + "-update.zip\"")
                    .header(UPDATE_BASE_HEADER, kept.isPresent() ? "snapshot" : "regenerated")
                    // Once applied, the project's own tables are this release's.
                    .header("X-Schema-Version", String.valueOf(com.menora.initializr.fullstack.schema.SchemaBuilder.CURRENT_VERSION))
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

    /**
     * Says what an update compared the new model with: {@code snapshot} — the files the project
     * was generated with, kept since its download — or {@code regenerated}, the old model generated
     * again now (a project from before snapshots, or one whose snapshot has expired).
     */
    public static final String UPDATE_BASE_HEADER = "X-Update-Base";

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
}
