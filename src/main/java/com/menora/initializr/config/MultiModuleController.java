package com.menora.initializr.config;

import com.menora.initializr.db.entity.ModuleDependencyMappingEntity;
import com.menora.initializr.db.entity.ModuleTemplateEntity;
import com.menora.initializr.db.repository.ModuleDependencyMappingRepository;
import com.menora.initializr.db.repository.ModuleTemplateRepository;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GET /starter-multimodule.zip — generates a multi-module Maven project.
 *
 * Accepts the same base parameters as /starter.zip, plus:
 *   modules=api,core,persistence   — which module templates to include
 *
 * For each selected module, it invokes the standard project generation pipeline
 * with the module's mapped dependencies, then assembles everything under a
 * parent POM with &lt;modules&gt; entries.
 *
 * <p>The zip and the preview assemble the same tree ({@link #assemble}); a parameter a caller
 * leaves out takes the metadata's default through {@link ProjectRequestFactory}, like every
 * other generation endpoint.
 */
@RestController
public class MultiModuleController {

    private static final Logger log = LoggerFactory.getLogger(MultiModuleController.class);

    private final ProjectGenerationInvoker<ProjectRequest> invoker;
    private final ProjectRequestFactory requestFactory;
    private final ModuleTemplateRepository moduleRepo;
    private final ModuleDependencyMappingRepository moduleMappingRepo;
    private final ProjectOptionsContext optionsContext;
    private final com.menora.initializr.db.DependencyConfigService configService;

    public MultiModuleController(ProjectGenerationInvoker<ProjectRequest> invoker,
                                 ProjectRequestFactory requestFactory,
                                 ModuleTemplateRepository moduleRepo,
                                 ModuleDependencyMappingRepository moduleMappingRepo,
                                 ProjectOptionsContext optionsContext,
                                 com.menora.initializr.db.DependencyConfigService configService) {
        this.invoker = invoker;
        this.requestFactory = requestFactory;
        this.moduleRepo = moduleRepo;
        this.moduleMappingRepo = moduleMappingRepo;
        this.optionsContext = optionsContext;
        this.configService = configService;
    }

    /**
     * The multi-module project of a model ({@code menora-model.json}, {@code request.modules})
     * into {@code dir}: what {@code /starter-multimodule.zip} assembles, for the update patch.
     * The caller has populated the per-request options (sub-options, department) and clears them.
     */
    WebProjectRequest assembleModel(Path dir, ProjectRequestFactory.Base base, Set<String> moduleIds,
                                    Set<String> dependencies) throws IOException {
        if (moduleIds.isEmpty()) {
            throw new WizardArgumentException("modules is required: name at least one module template", "modules");
        }
        WebProjectRequest root = requestFactory.create(base, null);
        assemble(dir, root, moduleIds, dependencies);
        return root;
    }

    @GetMapping("/starter-multimodule.zip")
    public ResponseEntity<byte[]> generateMultiModule(
            @RequestParam String modules,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String artifactId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String packageName,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String bootVersion,
            @RequestParam(required = false) String packaging,
            @RequestParam(required = false) String javaVersion,
            @RequestParam(defaultValue = "") String dependencies,
            @RequestParam(required = false) String configurationFileFormat
    ) throws IOException {
        Set<String> moduleIds = idsOf(modules);
        if (moduleIds.isEmpty()) {
            throw new WizardArgumentException("modules is required: name at least one module template", "modules");
        }
        WebProjectRequest root = requestFactory.create(new ProjectRequestFactory.Base(groupId, artifactId, name,
                description, packageName, type, language, bootVersion, packaging, javaVersion, null,
                configurationFileFormat), null);
        Path tempDir = Files.createTempDirectory("multimodule-");
        try {
            assemble(tempDir, root, moduleIds, idsOf(dependencies));
            byte[] zipBytes = GeneratedProjectFiles.zipDirectory(tempDir, root.getArtifactId());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + root.getArtifactId() + ".zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zipBytes);
        } finally {
            GeneratedProjectFiles.deleteLater(tempDir);
        }
    }

    @GetMapping("/starter-multimodule.preview")
    public MultiModulePreviewResponse previewMultiModule(
            @RequestParam String modules,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String artifactId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String packageName,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String bootVersion,
            @RequestParam(required = false) String packaging,
            @RequestParam(required = false) String javaVersion,
            @RequestParam(defaultValue = "") String dependencies,
            @RequestParam(required = false) String configurationFileFormat
    ) throws IOException {
        Set<String> moduleIds = idsOf(modules);
        if (moduleIds.isEmpty()) {
            // The editor previews while the user is still ticking modules: nothing yet is not an error.
            return new MultiModulePreviewResponse(List.of(), List.of());
        }
        WebProjectRequest root = requestFactory.create(new ProjectRequestFactory.Base(groupId, artifactId, name,
                description, packageName, type, language, bootVersion, packaging, javaVersion, null,
                configurationFileFormat), null);
        Path tempDir = Files.createTempDirectory("multimodule-preview-");
        try {
            assemble(tempDir, root, moduleIds, idsOf(dependencies));
            List<ProjectPreviewController.PreviewFile> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> {
                            String rel = tempDir.relativize(p).toString().replace('\\', '/');
                            files.add(new ProjectPreviewController.PreviewFile(rel, GeneratedProjectFiles.readSafely(p)));
                        });
            }
            return new MultiModulePreviewResponse(files,
                    PreviewTreeBuilder.buildTree(files.stream().map(ProjectPreviewController.PreviewFile::path).sorted().toList()));
        } finally {
            GeneratedProjectFiles.deleteLater(tempDir);
        }
    }

    /** A request the endpoint cannot serve — no known module, an unknown version — is a 400 that says why. */
    @ExceptionHandler(WizardArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidArgument(WizardArgumentException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Invalid request");
        body.put("detail", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    // ── Assembly ─────────────────────────────────────────────────────────────

    /**
     * Generates each selected module as its own project under {@code dir} (named
     * {@code <artifact><suffix>}, with the module template's packaging and mapped dependencies
     * plus {@code extraDeps}) and writes the parent POM that lists them.
     */
    private void assemble(Path dir, WebProjectRequest root, Set<String> moduleIds, Set<String> extraDeps) throws IOException {
        List<ModuleTemplateEntity> moduleTemplates = moduleRepo.findByModuleIdInOrderBySortOrderAsc(moduleIds);
        if (moduleTemplates.isEmpty()) {
            throw new WizardArgumentException("modules: none of " + moduleIds + " is a module template", "modules");
        }
        // moduleId -> [depId, ...]
        Map<String, List<String>> depsByModule = moduleMappingRepo.findAllByOrderBySortOrderAsc().stream()
                .filter(m -> moduleIds.contains(m.getModuleId()))
                .collect(Collectors.groupingBy(
                        ModuleDependencyMappingEntity::getModuleId,
                        Collectors.mapping(ModuleDependencyMappingEntity::getDependencyId, Collectors.toList())));

        List<String> moduleNames = new ArrayList<>();
        Set<String> everyDependency = new LinkedHashSet<>(extraDeps);
        // The project keeps one model, at its root: a module writes none.
        optionsContext.setEmbedded(true);
        try {
        for (ModuleTemplateEntity module : moduleTemplates) {
            String moduleArtifactId = root.getArtifactId() + module.getSuffix();
            moduleNames.add(moduleArtifactId);

            // The module's own dependencies, then the ones asked for on every module.
            Set<String> moduleDeps = new LinkedHashSet<>(depsByModule.getOrDefault(module.getModuleId(), List.of()));
            moduleDeps.addAll(extraDeps);
            everyDependency.addAll(moduleDeps);

            WebProjectRequest request = requestFactory.create(new ProjectRequestFactory.Base(
                    root.getGroupId(), moduleArtifactId, moduleArtifactId, root.getDescription(), root.getPackageName(),
                    root.getType(), root.getLanguage(), root.getBootVersion(), module.getPackaging(),
                    root.getJavaVersion(), root.getVersion(), root.getConfigurationFileFormat()),
                    moduleDeps.isEmpty() ? null : moduleDeps);

            Path moduleDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
            try {
                // A module that is not the entry point has no @SpringBootApplication (nor its test).
                if (!module.isHasMainClass()) {
                    deleteMainClass(moduleDir);
                    deleteTestClass(moduleDir);
                }
                GeneratedProjectFiles.moveDirectory(moduleDir, dir.resolve(moduleArtifactId));
            } finally {
                // cleanTempFiles drops the invoker's map entry and deletes what the move left behind.
                invoker.cleanTempFiles(moduleDir);
            }
        }
        } finally {
            optionsContext.setEmbedded(false);
        }
        Files.writeString(dir.resolve("pom.xml"), parentPom(root, moduleNames), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(BackendModel.FILE), modelOf(root, moduleTemplates, extraDeps, everyDependency),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(".gitattributes"), BackendModel.gitAttributes(null), StandardCharsets.UTF_8);
        // One set of bytes whatever machine generated it.
        GeneratedProjectFiles.normalizeLineEndings(dir);
    }

    /**
     * {@code menora-model.json} of a multi-module project: the root's coordinates, the module
     * templates it was assembled from (those that exist, in their order) and what every module
     * was given beside its own dependencies.
     */
    private String modelOf(WebProjectRequest root, List<ModuleTemplateEntity> modules, Set<String> extraDeps,
                           Set<String> everyDependency) {
        com.fasterxml.jackson.databind.node.ObjectNode request = BackendModel.newRequest();
        request.put("groupId", root.getGroupId());
        request.put("artifactId", root.getArtifactId());
        if (root.getName() != null) request.put("name", root.getName());
        if (root.getDescription() != null) request.put("description", root.getDescription());
        request.put("packageName", root.getPackageName());
        request.put("bootVersion", root.getBootVersion());
        request.put("javaVersion", root.getJavaVersion());
        if (root.getVersion() != null) request.put("version", root.getVersion());
        request.set("dependencies", BackendModel.array(extraDeps));
        request.set("modules", BackendModel.array(modules.stream().map(ModuleTemplateEntity::getModuleId).toList()));
        com.fasterxml.jackson.databind.node.ObjectNode opts = BackendModel.newRequest();
        new java.util.TreeMap<>(optionsContext.all()).forEach((dep, ids) -> {
            if (everyDependency.contains(dep) && !ids.isEmpty()) opts.set(dep, BackendModel.array(new java.util.TreeSet<>(ids)));
        });
        if (!opts.isEmpty()) request.set("opts", opts);
        if (optionsContext.department() != null) request.put("department", optionsContext.department());
        String catalogHash = BackendModel.catalogHash(configService.getFileContributions(everyDependency),
                configService.getBuildCustomizations(everyDependency));
        return BackendModel.json(BackendModel.KIND_MULTI_MODULE, request, catalogHash);
    }

    private static String parentPom(WebProjectRequest root, List<String> moduleNames) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("bootVersion", root.getBootVersion());
        view.put("groupId", root.getGroupId());
        view.put("artifactId", root.getArtifactId());
        view.put("nameXml", escapeXml(root.getName()));
        view.put("descriptionXml", escapeXml(root.getDescription()));
        view.put("javaVersion", root.getJavaVersion());
        view.put("modules", moduleNames);
        return WizardTemplates.render("multimodule/parent-pom.xml", view);
    }

    private static Set<String> idsOf(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) return new LinkedHashSet<>();
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void deleteMainClass(Path projectDir) throws IOException {
        // Find and delete the main Application class (contains @SpringBootApplication)
        try (Stream<Path> walk = Files.walk(projectDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("Application.java"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            if (content.contains("@SpringBootApplication")) {
                                Files.delete(p);
                            }
                        } catch (IOException ex) {
                            // Leaves a stray @SpringBootApplication in a module meant to have
                            // none — the generated project won't build, so make it visible.
                            log.warn("could not remove main class {}: {}", p, ex.toString());
                        }
                    });
        }
    }

    private void deleteTestClass(Path projectDir) throws IOException {
        // Delete test classes that reference the main Application class
        try (Stream<Path> walk = Files.walk(projectDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("ApplicationTests.java"))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ex) {
                            log.warn("could not remove test class {}: {}", p, ex.toString());
                        }
                    });
        }
    }

    public record MultiModulePreviewResponse(
            List<ProjectPreviewController.PreviewFile> files,
            List<ProjectPreviewController.TreeNode> tree) {}
}
