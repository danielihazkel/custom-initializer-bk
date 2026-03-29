package com.menora.initializr.config;

import com.menora.initializr.db.entity.ModuleDependencyMappingEntity;
import com.menora.initializr.db.entity.ModuleTemplateEntity;
import com.menora.initializr.db.repository.ModuleDependencyMappingRepository;
import com.menora.initializr.db.repository.ModuleTemplateRepository;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GET /starter-multimodule.zip — generates a multi-module Maven project.
 *
 * Accepts the same base parameters as /starter.zip, plus:
 *   modules=api,core,persistence   — which module templates to include
 *
 * For each selected module, it invokes the standard project generation pipeline
 * with the module's mapped dependencies, then assembles everything under a
 * parent POM with &lt;modules&gt; entries.
 */
@RestController
public class MultiModuleController {

    private final ProjectGenerationInvoker<ProjectRequest> invoker;
    private final InitializrMetadataProvider metadataProvider;
    private final ModuleTemplateRepository moduleRepo;
    private final ModuleDependencyMappingRepository moduleMappingRepo;

    public MultiModuleController(ProjectGenerationInvoker<ProjectRequest> invoker,
                                 InitializrMetadataProvider metadataProvider,
                                 ModuleTemplateRepository moduleRepo,
                                 ModuleDependencyMappingRepository moduleMappingRepo) {
        this.invoker = invoker;
        this.metadataProvider = metadataProvider;
        this.moduleRepo = moduleRepo;
        this.moduleMappingRepo = moduleMappingRepo;
    }

    @GetMapping("/starter-multimodule.zip")
    public ResponseEntity<byte[]> generateMultiModule(
            @RequestParam String modules,
            @RequestParam(defaultValue = "com.menora") String groupId,
            @RequestParam(defaultValue = "demo") String artifactId,
            @RequestParam(defaultValue = "demo") String name,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "com.menora.demo") String packageName,
            @RequestParam(defaultValue = "maven-project") String type,
            @RequestParam(defaultValue = "java") String language,
            @RequestParam(defaultValue = "3.2.1") String bootVersion,
            @RequestParam(defaultValue = "jar") String packaging,
            @RequestParam(defaultValue = "21") String javaVersion,
            @RequestParam(defaultValue = "") String dependencies
    ) throws IOException {
        Set<String> moduleIds = Arrays.stream(modules.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (moduleIds.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Resolve module templates
        List<ModuleTemplateEntity> moduleTemplates = moduleRepo.findByModuleIdInOrderBySortOrderAsc(moduleIds);
        if (moduleTemplates.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Resolve dependency mappings: moduleId -> [depId, ...]
        List<ModuleDependencyMappingEntity> allMappings = moduleMappingRepo.findAllByOrderBySortOrderAsc();
        Map<String, List<String>> depsByModule = allMappings.stream()
                .filter(m -> moduleIds.contains(m.getModuleId()))
                .collect(Collectors.groupingBy(
                        ModuleDependencyMappingEntity::getModuleId,
                        Collectors.mapping(ModuleDependencyMappingEntity::getDependencyId, Collectors.toList())));

        // Also collect any extra dependencies passed via the dependencies param (applied to all modules)
        Set<String> extraDeps = dependencies.isBlank() ? Set.of() :
                Arrays.stream(dependencies.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        // Generate each module as a sub-project
        Path tempDir = Files.createTempDirectory("multimodule-");
        try {
            List<String> moduleNames = new ArrayList<>();

            for (ModuleTemplateEntity module : moduleTemplates) {
                String moduleSuffix = module.getSuffix();
                String moduleArtifactId = artifactId + moduleSuffix;
                String modulePackage = packageName;
                moduleNames.add(moduleArtifactId);

                // Merge module-specific deps with extras
                Set<String> moduleDeps = new LinkedHashSet<>(depsByModule.getOrDefault(module.getModuleId(), List.of()));
                moduleDeps.addAll(extraDeps);

                // Build a request for this module
                WebProjectRequest request = new WebProjectRequest();
                request.setType(type);
                request.setLanguage(language);
                request.setBootVersion(bootVersion);
                request.setGroupId(groupId);
                request.setArtifactId(moduleArtifactId);
                request.setName(moduleArtifactId);
                request.setDescription(description);
                request.setPackageName(modulePackage);
                request.setPackaging(module.getPackaging());
                request.setJavaVersion(javaVersion);
                if (!moduleDeps.isEmpty()) {
                    request.setDependencies(new ArrayList<>(moduleDeps));
                }
                request.initialize(metadataProvider.get());

                Path moduleDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
                try {
                    // Remove the Application main class if this module shouldn't have one
                    if (!module.isHasMainClass()) {
                        deleteMainClass(moduleDir);
                        deleteTestClass(moduleDir);
                    }

                    // Copy generated module into the temp dir under the module artifact ID
                    Path targetModuleDir = tempDir.resolve(moduleArtifactId);
                    copyDirectory(moduleDir, targetModuleDir);
                } finally {
                    FileSystemUtils.deleteRecursively(moduleDir);
                }
            }

            // Generate parent POM
            String parentPom = buildParentPom(groupId, artifactId, name, description,
                    bootVersion, javaVersion, moduleNames);
            Files.writeString(tempDir.resolve("pom.xml"), parentPom, StandardCharsets.UTF_8);

            // ZIP it all up
            byte[] zipBytes = zipDirectory(tempDir, artifactId);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + artifactId + ".zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zipBytes);
        } finally {
            FileSystemUtils.deleteRecursively(tempDir);
        }
    }

    @GetMapping("/starter-multimodule.preview")
    public MultiModulePreviewResponse previewMultiModule(
            @RequestParam String modules,
            @RequestParam(defaultValue = "com.menora") String groupId,
            @RequestParam(defaultValue = "demo") String artifactId,
            @RequestParam(defaultValue = "demo") String name,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "com.menora.demo") String packageName,
            @RequestParam(defaultValue = "maven-project") String type,
            @RequestParam(defaultValue = "java") String language,
            @RequestParam(defaultValue = "3.2.1") String bootVersion,
            @RequestParam(defaultValue = "jar") String packaging,
            @RequestParam(defaultValue = "21") String javaVersion,
            @RequestParam(defaultValue = "") String dependencies
    ) throws IOException {
        Set<String> moduleIds = Arrays.stream(modules.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (moduleIds.isEmpty()) {
            return new MultiModulePreviewResponse(List.of(), List.of());
        }

        List<ModuleTemplateEntity> moduleTemplates = moduleRepo.findByModuleIdInOrderBySortOrderAsc(moduleIds);
        List<ModuleDependencyMappingEntity> allMappings = moduleMappingRepo.findAllByOrderBySortOrderAsc();
        Map<String, List<String>> depsByModule = allMappings.stream()
                .filter(m -> moduleIds.contains(m.getModuleId()))
                .collect(Collectors.groupingBy(
                        ModuleDependencyMappingEntity::getModuleId,
                        Collectors.mapping(ModuleDependencyMappingEntity::getDependencyId, Collectors.toList())));

        Set<String> extraDeps = dependencies.isBlank() ? Set.of() :
                Arrays.stream(dependencies.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        Path tempDir = Files.createTempDirectory("multimodule-preview-");
        try {
            List<String> moduleNames = new ArrayList<>();
            for (ModuleTemplateEntity module : moduleTemplates) {
                String moduleArtifactId = artifactId + module.getSuffix();
                moduleNames.add(moduleArtifactId);

                Set<String> moduleDeps = new LinkedHashSet<>(depsByModule.getOrDefault(module.getModuleId(), List.of()));
                moduleDeps.addAll(extraDeps);

                WebProjectRequest request = new WebProjectRequest();
                request.setType(type);
                request.setLanguage(language);
                request.setBootVersion(bootVersion);
                request.setGroupId(groupId);
                request.setArtifactId(moduleArtifactId);
                request.setName(moduleArtifactId);
                request.setDescription(description);
                request.setPackageName(packageName);
                request.setPackaging(module.getPackaging());
                request.setJavaVersion(javaVersion);
                if (!moduleDeps.isEmpty()) {
                    request.setDependencies(new ArrayList<>(moduleDeps));
                }
                request.initialize(metadataProvider.get());

                Path moduleDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
                try {
                    if (!module.isHasMainClass()) {
                        deleteMainClass(moduleDir);
                        deleteTestClass(moduleDir);
                    }
                    copyDirectory(moduleDir, tempDir.resolve(moduleArtifactId));
                } finally {
                    FileSystemUtils.deleteRecursively(moduleDir);
                }
            }

            String parentPom = buildParentPom(groupId, artifactId, name, description,
                    bootVersion, javaVersion, moduleNames);
            Files.writeString(tempDir.resolve("pom.xml"), parentPom, StandardCharsets.UTF_8);

            // Build preview response
            List<ProjectPreviewController.PreviewFile> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> {
                            String rel = tempDir.relativize(p).toString().replace('\\', '/');
                            files.add(new ProjectPreviewController.PreviewFile(rel, readSafely(p)));
                        });
            }
            return new MultiModulePreviewResponse(files,
                    buildChildren("", files.stream().map(ProjectPreviewController.PreviewFile::path).sorted().toList()));
        } finally {
            FileSystemUtils.deleteRecursively(tempDir);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildParentPom(String groupId, String artifactId, String name,
                                  String description, String bootVersion, String javaVersion,
                                  List<String> moduleNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        sb.append("    <modelVersion>4.0.0</modelVersion>\n\n");
        sb.append("    <parent>\n");
        sb.append("        <groupId>org.springframework.boot</groupId>\n");
        sb.append("        <artifactId>spring-boot-starter-parent</artifactId>\n");
        sb.append("        <version>").append(bootVersion).append("</version>\n");
        sb.append("        <relativePath/>\n");
        sb.append("    </parent>\n\n");
        sb.append("    <groupId>").append(groupId).append("</groupId>\n");
        sb.append("    <artifactId>").append(artifactId).append("</artifactId>\n");
        sb.append("    <version>0.0.1-SNAPSHOT</version>\n");
        sb.append("    <name>").append(escapeXml(name)).append("</name>\n");
        sb.append("    <description>").append(escapeXml(description)).append("</description>\n");
        sb.append("    <packaging>pom</packaging>\n\n");
        sb.append("    <properties>\n");
        sb.append("        <java.version>").append(javaVersion).append("</java.version>\n");
        sb.append("    </properties>\n\n");
        sb.append("    <modules>\n");
        for (String moduleName : moduleNames) {
            sb.append("        <module>").append(moduleName).append("</module>\n");
        }
        sb.append("    </modules>\n\n");
        sb.append("</project>\n");
        return sb.toString();
    }

    private String escapeXml(String s) {
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
                        } catch (IOException ignored) {}
                    });
        }
    }

    private void deleteTestClass(Path projectDir) throws IOException {
        // Delete test classes that reference the main Application class
        try (Stream<Path> walk = Files.walk(projectDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("ApplicationTests.java"))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(s -> {
                Path t = target.resolve(source.relativize(s));
                try {
                    if (Files.isDirectory(s)) {
                        Files.createDirectories(t);
                    } else {
                        Files.createDirectories(t.getParent());
                        Files.copy(s, t);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private byte[] zipDirectory(Path dir, String rootDirName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> {
                            String entryName = rootDirName + "/" + dir.relativize(p).toString().replace('\\', '/');
                            try {
                                zos.putNextEntry(new ZipEntry(entryName));
                                Files.copy(p, zos);
                                zos.closeEntry();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }
        }
        return baos.toByteArray();
    }

    private String readSafely(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[binary file]";
        }
    }

    // ── Tree builder (same logic as ProjectPreviewController) ────────────────

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
        for (Map.Entry<String, List<String>> entry : subdirs.entrySet()) {
            String dirPath = entry.getKey();
            String dirName = dirPath.contains("/") ? dirPath.substring(dirPath.lastIndexOf('/') + 1) : dirPath;
            result.add(new ProjectPreviewController.TreeNode(dirName, dirPath, "directory", buildChildren(dirPath, entry.getValue())));
        }
        for (String filePath : directFiles) {
            String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
            result.add(new ProjectPreviewController.TreeNode(fileName, filePath, "file", List.of()));
        }
        return result;
    }

    public record MultiModulePreviewResponse(
            List<ProjectPreviewController.PreviewFile> files,
            List<ProjectPreviewController.TreeNode> tree) {}
}
