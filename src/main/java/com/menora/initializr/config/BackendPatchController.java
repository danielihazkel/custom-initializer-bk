package com.menora.initializr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.events.AsyncApiReader;
import com.menora.initializr.fullstack.patch.ProjectUpdateBuilder;
import com.menora.initializr.openapi.OpenApiCodeGenerator;
import com.menora.initializr.soap.SoapCodeGenerator;
import com.menora.initializr.sql.SqlEntityGenerator;
import io.spring.initializr.web.project.WebProjectRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code POST /starter.patch} — updates a plain backend instead of generating it again.
 *
 * <p>Body {@code {base, target, baseCatalogHash?}}: the model the project was generated from and
 * the model it should have now. Each is a {@code menora-model.json} as it is, or just its
 * {@code request} — a {@code /starter-wizard.zip} body, with {@code modules} for a multi-module
 * project. Both are generated, and the difference comes back as {@code <artifact>-update.zip}:
 * a patch for {@code git apply --3way}, each added and changed file whole, the removed paths and
 * notes (see {@link ProjectUpdateBuilder} — the machinery the fullstack update uses).
 *
 * <p>What it is not: both sides are generated with today's catalog, so the patch is the change the
 * <em>model</em> makes. It does not bring in what the catalog's files have changed since the
 * project was generated; {@code generator.catalogHash} of the model file says whether they have.
 */
@RestController
public class BackendPatchController {

    private final WizardStarterController wizard;
    private final MultiModuleController multiModule;
    private final ProjectOptionsContext optionsContext;
    private final ObjectMapper mapper;
    private final com.menora.initializr.observability.SnapshotStore snapshotStore;

    public BackendPatchController(WizardStarterController wizard, MultiModuleController multiModule,
                                  ProjectOptionsContext optionsContext, ObjectMapper mapper,
                                  com.menora.initializr.observability.SnapshotStore snapshotStore) {
        this.wizard = wizard;
        this.multiModule = multiModule;
        this.optionsContext = optionsContext;
        this.mapper = mapper;
        this.snapshotStore = snapshotStore;
    }

    /**
     * {@code baseSnapshot}: the {@code generator.snapshot} of the project's model file, when the
     * caller has it apart from {@code base} (else it is read from {@code base} itself).
     */
    public record BackendPatchRequest(JsonNode base, JsonNode target, String baseCatalogHash, String baseSnapshot) {
        public BackendPatchRequest(JsonNode base, JsonNode target, String baseCatalogHash) {
            this(base, target, baseCatalogHash, null);
        }
    }

    /**
     * The files an update replaces instead of patching: the model file, and every {@code HELP.md} —
     * the generated {@code .gitignore} leaves it out of the project's repository, so a patch to it
     * has no file in the index to apply to and fails {@code git apply --3way}.
     */
    static Set<String> deliveredWhole(Map<String, byte[]> base, Map<String, byte[]> target) {
        Set<String> paths = new java.util.TreeSet<>();
        paths.add(BackendModel.FILE);
        for (Map<String, byte[]> tree : List.of(base, target)) {
            for (String path : tree.keySet()) {
                if (path.equals("HELP.md") || path.endsWith("/HELP.md")) paths.add(path);
            }
        }
        return paths;
    }

    @PostMapping("/starter.patch")
    public ResponseEntity<byte[]> patch(@RequestBody BackendPatchRequest body) throws IOException {
        if (body == null || requestOf(body.base()) == null || requestOf(body.target()) == null) {
            throw new WizardArgumentException("An update needs both models: 'base' (what the project was"
                    + " generated from) and 'target' (what it should be now)");
        }
        Path baseDir = Files.createTempDirectory("backend-update-base-");
        Path targetDir = Files.createTempDirectory("backend-update-target-");
        try {
            // What the project really is, when its download was kept (GeneratedSnapshots): the
            // patch then also carries what the generator writes differently since. Otherwise the
            // old model, generated again now.
            String baseId = GeneratedSnapshots.isId(body.baseSnapshot()) ? body.baseSnapshot()
                    : GeneratedSnapshots.snapshotOf(body.base());
            java.util.Optional<Map<String, byte[]>> kept = snapshotStore.find(baseId);
            // Generated either way: a model that no longer generates is the same 400 as before,
            // and its model file stands in for the one the snapshot leaves out.
            generateSide("base", requestOf(body.base()), baseDir);
            WebProjectRequest request = generateSide("target", requestOf(body.target()), targetDir);
            Map<String, byte[]> base = GeneratedProjectFiles.readTree(baseDir);
            if (kept.isPresent()) {
                Map<String, byte[]> stored = new java.util.TreeMap<>(kept.get());
                // The project's model file names those files: the update then replaces it with one
                // naming its result, and the next update starts from there.
                stored.put(BackendModel.FILE, GeneratedSnapshots.withSnapshot(
                        new String(base.get(BackendModel.FILE), java.nio.charset.StandardCharsets.UTF_8), baseId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                base = stored;
            }
            Map<String, byte[]> target = GeneratedProjectFiles.readTree(targetDir);
            // What the project was generated with: the hash the caller names, else the one its
            // model file carries. It is compared with the hash of the *same* model generated now —
            // the hash covers the rows of the model's own dependencies, so the new model's (which
            // may have others) says nothing about whether the catalog moved.
            String generatedWith = body.baseCatalogHash() != null && !body.baseCatalogHash().isBlank() ? body.baseCatalogHash()
                    : text(body.base().path(BackendModel.GENERATOR_KEY).path("catalogHash"));
            ProjectUpdateBuilder.ProjectUpdate update = ProjectUpdateBuilder.build(
                    base, target,
                    new ProjectUpdateBuilder.Info(request.getArtifactId(), generatedWith,
                            BackendModel.catalogHashOf(base.get(BackendModel.FILE)),
                            "the folder holding `pom.xml`", "catalog", kept.isPresent()),
                    // Never edited by hand and rewritten whole by every generation: replaced, not patched.
                    deliveredWhole(base, target));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + request.getArtifactId() + "-update.zip\"")
                    .header(FullstackStarterController.UPDATE_BASE_HEADER, kept.isPresent() ? "snapshot" : "regenerated")
                    .header("X-Update-Added", String.valueOf(update.added().size()))
                    .header("X-Update-Changed", String.valueOf(update.changed().size()))
                    .header("X-Update-Deleted", String.valueOf(update.deleted().size()))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(update.zip());
        } finally {
            GeneratedProjectFiles.deleteLater(baseDir);
            GeneratedProjectFiles.deleteLater(targetDir);
            optionsContext.clear();
        }
    }

    /** A model file's {@code request}, or the node itself when it is a bare request; null when neither. */
    private static JsonNode requestOf(JsonNode side) {
        if (side == null || !side.isObject()) return null;
        JsonNode request = side.get("request");
        return request != null && request.isObject() ? request : side;
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    /**
     * Generates one side into {@code dir}: a multi-module project when the request names
     * {@code modules}, else what {@code /starter-wizard.zip} generates. A problem with the model
     * is the endpoint's 400, saying which side it is on.
     */
    private WebProjectRequest generateSide(String side, JsonNode request, Path dir) throws IOException {
        try {
            WizardStarterController.WizardStarterRequest body =
                    mapper.treeToValue(request, WizardStarterController.WizardStarterRequest.class);
            List<String> modules = BackendModel.modulesOf(request);
            if (!modules.isEmpty()) {
                optionsContext.populate(body.opts());
                optionsContext.setDepartment(body.department());
                try {
                    return multiModule.assembleModel(dir, new ProjectRequestFactory.Base(body.groupId(), body.artifactId(),
                                    body.name(), body.description(), body.packageName(), body.type(), body.language(),
                                    body.bootVersion(), body.packaging(), body.javaVersion(), body.version(),
                                    body.configurationFileFormat()),
                            new LinkedHashSet<>(modules),
                            body.dependencies() == null ? Set.of() : new LinkedHashSet<>(body.dependencies()));
                } finally {
                    optionsContext.clear();
                }
            }
            WizardStarterController.GeneratedProject project = wizard.generateProject(body);
            try {
                GeneratedProjectFiles.copyDirectory(project.directory(), dir);
                return project.request();
            } finally {
                wizard.release(project);
            }
        } catch (WizardArgumentException problem) {
            throw new SideProblem(side, List.of(problem.getMessage()), problem);
        } catch (OpenApiCodeGenerator.OpenApiParseException problem) {
            throw new SideProblem(side, problem.messages(), problem);
        } catch (SoapCodeGenerator.SoapParseException problem) {
            throw new SideProblem(side, problem.messages(), problem);
        } catch (AsyncApiReader.AsyncApiParseException problem) {
            throw new SideProblem(side, problem.messages(), problem);
        } catch (SqlEntityGenerator.SqlParseException problem) {
            throw new SideProblem(side, List.of(problem.getMessage()), problem);
        } catch (com.fasterxml.jackson.core.JsonProcessingException problem) {
            throw new SideProblem(side, List.of("The model is not a generation request: " + problem.getOriginalMessage()), problem);
        }
    }

    /** One of the two models cannot be generated. */
    private static final class SideProblem extends RuntimeException {
        private final String side;
        private final List<String> messages;

        SideProblem(String side, List<String> messages, Throwable cause) {
            super(String.join("; ", messages), cause);
            this.side = side;
            this.messages = List.copyOf(messages);
        }
    }

    @ExceptionHandler(SideProblem.class)
    public ResponseEntity<Map<String, Object>> handleSideProblem(SideProblem ex) {
        optionsContext.clear();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid model");
        body.put("side", ex.side);
        body.put("detail", ex.getMessage());
        body.put("messages", ex.messages);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(WizardArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidArgument(WizardArgumentException ex) {
        optionsContext.clear();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid request");
        body.put("detail", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
