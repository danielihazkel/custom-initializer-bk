package com.menora.initializr.config;

import io.spring.initializr.metadata.InitializrMetadata;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    private static void fillMissing(WebProjectRequest r, InitializrMetadata metadata) {
        if (blank(r.getType())) r.setType(metadata.getTypes().getDefault().getId());
        if (blank(r.getLanguage())) r.setLanguage(metadata.getLanguages().getDefault().getId());
        if (blank(r.getPackaging())) r.setPackaging(metadata.getPackagings().getDefault().getId());
        if (blank(r.getJavaVersion())) r.setJavaVersion(metadata.getJavaVersions().getDefault().getId());
        if (blank(r.getBootVersion())) r.setBootVersion(metadata.getBootVersions().getDefault().getId());
        if (blank(r.getGroupId())) r.setGroupId(metadata.getGroupId().getContent());
        if (blank(r.getArtifactId())) r.setArtifactId(metadata.getArtifactId().getContent());
        if (blank(r.getName())) r.setName(r.getArtifactId());
        if (r.getDescription() == null) r.setDescription(metadata.getDescription().getContent());
        if (blank(r.getPackageName())) r.setPackageName(r.getGroupId() + "." + r.getArtifactId());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @GetMapping("/starter.preview")
    public PreviewResponse preview(@ModelAttribute WebProjectRequest request) throws IOException {
        // Do NOT call request.initialize(metadata) here — it unconditionally
        // overwrites all properties with defaults, undoing @ModelAttribute binding
        // (e.g. javaVersion reverts to "21" even when the user selected "17").
        // Only fill in "version" which the frontend doesn't send.
        InitializrMetadata metadata = metadataProvider.get();
        if (request.getVersion() == null || request.getVersion().isEmpty()) {
            request.setVersion((String) metadata.defaults().get("version"));
        }
        // What the caller left out is the metadata's default, as /starter.zip does (a URL that
        // named only its dependencies was a 400, "Text must not be null"). Only the missing ones.
        fillMissing(request, metadata);
        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        try {
            List<PreviewFile> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(projectDir)) {
                walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        String rel = projectDir.relativize(p).toString().replace('\\', '/');
                        files.add(new PreviewFile(rel, GeneratedProjectFiles.readSafely(p)));
                    });
            }
            return new PreviewResponse(files, PreviewTreeBuilder.buildTree(
                    files.stream().map(PreviewFile::path).sorted().toList()));
        } finally {
            // cleanTempFiles both deletes the tree and drops the entry the invoker
            // registered for it — plain deleteRecursively leaks that map entry.
            invoker.cleanTempFiles(projectDir);
        }
    }


    // ── Response records ─────────────────────────────────────────────────────

    public record PreviewResponse(List<PreviewFile> files, List<TreeNode> tree) {}
    public record PreviewFile(String path, String content) {}
    public record TreeNode(String name, String path, String type, List<TreeNode> children) {}
}
