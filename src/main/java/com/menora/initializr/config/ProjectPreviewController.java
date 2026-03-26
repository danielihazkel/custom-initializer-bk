package com.menora.initializr.config;

import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * GET /starter.preview — returns the generated project as JSON (file list + tree)
 * instead of a ZIP. Accepts the same query parameters as /starter.zip.
 *
 * The filter (InitializrWebConfiguration) already handles opts-* params and
 * populates ProjectOptionsContext before this method is called, so sub-option
 * gating works identically to /starter.zip.
 */
@RestController
public class ProjectPreviewController {

    private final ProjectGenerationInvoker<ProjectRequest> invoker;
    private final InitializrMetadataProvider metadataProvider;

    public ProjectPreviewController(ProjectGenerationInvoker<ProjectRequest> invoker,
                                    InitializrMetadataProvider metadataProvider) {
        this.invoker = invoker;
        this.metadataProvider = metadataProvider;
    }

    @GetMapping("/starter.preview")
    public PreviewResponse preview(@ModelAttribute WebProjectRequest request) throws IOException {
        request.initialize(metadataProvider.get());
        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        try {
            List<PreviewFile> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(projectDir)) {
                walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        String rel = projectDir.relativize(p).toString().replace('\\', '/');
                        files.add(new PreviewFile(rel, readSafely(p)));
                    });
            }
            return new PreviewResponse(files, buildChildren("", files.stream().map(PreviewFile::path).sorted().toList()));
        } finally {
            FileSystemUtils.deleteRecursively(projectDir);
        }
    }

    // ── Tree builder ──────────────────────────────────────────────────────────

    private List<TreeNode> buildChildren(String prefix, List<String> paths) {
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

        List<TreeNode> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : subdirs.entrySet()) {
            String dirPath = entry.getKey();
            String dirName = dirPath.contains("/") ? dirPath.substring(dirPath.lastIndexOf('/') + 1) : dirPath;
            result.add(new TreeNode(dirName, dirPath, "directory", buildChildren(dirPath, entry.getValue())));
        }
        for (String filePath : directFiles) {
            String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
            result.add(new TreeNode(fileName, filePath, "file", List.of()));
        }
        return result;
    }

    private String readSafely(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[binary file]";
        }
    }

    // ── Response records ─────────────────────────────────────────────────────

    public record PreviewResponse(List<PreviewFile> files, List<TreeNode> tree) {}
    public record PreviewFile(String path, String content) {}
    public record TreeNode(String name, String path, String type, List<TreeNode> children) {}
}
