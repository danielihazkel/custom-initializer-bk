package com.menora.initializr.extension.frontend;

import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.samskivert.mustache.Mustache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Orchestrates generation of a React + TypeScript + Vite + FSD project skeleton.
 *
 * <p>Mirrors {@code DynamicProjectGenerationConfiguration} for the backend path:
 * walks file contributions, renders Mustache, and zips the result. The two
 * pieces that diverge from a Maven build are delegated to
 * {@link PackageJsonBuilder} and {@link ViteConfigBuilder}.
 */
@Service
public class FrontendProjectGenerator {

    private static final Logger log = LoggerFactory.getLogger(FrontendProjectGenerator.class);
    private static final Mustache.Compiler MUSTACHE = Mustache.compiler().escapeHTML(false);

    private static final String PACKAGE_JSON_TEMPLATE = "templates/frontend/fe-package-base.mustache";
    private static final String VITE_CONFIG_TEMPLATE = "templates/frontend/fe-vite-config.mustache";

    private final DependencyConfigService configService;
    private final ProjectOptionsContext optionsContext;
    private final PackageJsonBuilder packageJsonBuilder;
    private final ViteConfigBuilder viteConfigBuilder;

    public FrontendProjectGenerator(DependencyConfigService configService,
                                    ProjectOptionsContext optionsContext,
                                    PackageJsonBuilder packageJsonBuilder,
                                    ViteConfigBuilder viteConfigBuilder) {
        this.configService = configService;
        this.optionsContext = optionsContext;
        this.packageJsonBuilder = packageJsonBuilder;
        this.viteConfigBuilder = viteConfigBuilder;
    }

    /** Generates the project, returns the ZIP bytes (containing a top-level {@code projectName/} directory). */
    public byte[] generate(FrontendProjectDescription desc) throws IOException {
        Set<String> depIds = desc.getDependencies();
        Map<String, Object> ctx = FrontendMustacheContext.build(desc, depIds, optionsContext);

        Path tempDir = Files.createTempDirectory("frontend-");
        try {
            applyFileContributions(tempDir, depIds, ctx, desc);
            writeBaselines(tempDir, depIds, ctx);
            return zipDirectory(tempDir, desc.getProjectName());
        } finally {
            FileSystemUtils.deleteRecursively(tempDir);
        }
    }

    /** Same as {@link #generate} but returns the file tree (relative path → content) for previews / tests. */
    public Map<String, String> generateFileMap(FrontendProjectDescription desc) throws IOException {
        Set<String> depIds = desc.getDependencies();
        Map<String, Object> ctx = FrontendMustacheContext.build(desc, depIds, optionsContext);

        Path tempDir = Files.createTempDirectory("frontend-preview-");
        try {
            applyFileContributions(tempDir, depIds, ctx, desc);
            writeBaselines(tempDir, depIds, ctx);
            Map<String, String> out = new LinkedHashMap<>();
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.filter(Files::isRegularFile).sorted().forEach(p -> {
                    String rel = tempDir.relativize(p).toString().replace('\\', '/');
                    try {
                        out.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            return out;
        } finally {
            FileSystemUtils.deleteRecursively(tempDir);
        }
    }

    // ── File contributions ───────────────────────────────────────────────────

    private void applyFileContributions(Path projectRoot,
                                        Set<String> depIds,
                                        Map<String, Object> ctx,
                                        FrontendProjectDescription desc) throws IOException {

        List<FileContributionEntity> contributions =
                configService.getFileContributions(depIds, ProjectKind.FRONTEND);
        log.info("frontend generation: depIds={} fileContributions={}", depIds, contributions.size());

        // Pass 1 — apply writes/merges, skipping DELETEs.
        for (FileContributionEntity fc : contributions) {
            if (fc.getSubOptionId() != null
                    && !optionsContext.hasOption(fc.getDependencyId(), fc.getSubOptionId())) {
                continue;
            }
            String targetRel = resolveTargetPath(fc.getTargetPath(), desc);
            Path target = projectRoot.resolve(targetRel);
            switch (fc.getFileType()) {
                case STATIC_COPY -> writeStatic(fc.getContent(), target);
                case TEMPLATE -> writeTemplate(fc, ctx, target);
                case YAML_MERGE -> mergeYaml(fc.getContent(), target);
                case DELETE -> { /* deferred */ }
            }
        }

        // Pass 2 — apply DELETEs after all writes.
        for (FileContributionEntity fc : contributions) {
            if (fc.getFileType() != FileContributionEntity.FileType.DELETE) continue;
            if (fc.getSubOptionId() != null
                    && !optionsContext.hasOption(fc.getDependencyId(), fc.getSubOptionId())) {
                continue;
            }
            Files.deleteIfExists(projectRoot.resolve(resolveTargetPath(fc.getTargetPath(), desc)));
        }
    }

    private String resolveTargetPath(String targetPath, FrontendProjectDescription desc) {
        // Allow placeholders in target paths the same way backend resolves {{packagePath}}.
        return targetPath
                .replace("{{projectName}}", desc.getProjectName());
    }

    private void writeStatic(String content, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content == null ? "" : content);
    }

    private void writeTemplate(FileContributionEntity fc, Map<String, Object> ctx, Path target) throws IOException {
        String content = fc.getSubstitutionType() == FileContributionEntity.SubstitutionType.MUSTACHE
                ? MUSTACHE.compile(fc.getContent() == null ? "" : fc.getContent()).execute(ctx)
                : (fc.getContent() == null ? "" : fc.getContent());
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private void mergeYaml(String newContent, Path target) throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> merged;
        if (Files.exists(target)) {
            Map<String, Object> existing = yaml.load(Files.readString(target));
            Map<String, Object> incoming = yaml.load(newContent);
            merged = deepMerge(existing, incoming);
        } else {
            merged = yaml.load(newContent);
        }
        Files.createDirectories(target.getParent());
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        Files.writeString(target, new Yaml(opts).dump(merged));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            Object b = result.get(entry.getKey());
            Object o = entry.getValue();
            if (b instanceof Map && o instanceof Map) {
                result.put(entry.getKey(), deepMerge((Map<String, Object>) b, (Map<String, Object>) o));
            } else {
                result.put(entry.getKey(), o);
            }
        }
        return result;
    }

    // ── Baselines: package.json + vite.config.ts ────────────────────────────

    private void writeBaselines(Path projectRoot, Set<String> depIds, Map<String, Object> ctx) throws IOException {
        List<BuildCustomizationEntity> customizations =
                configService.getBuildCustomizations(depIds, ProjectKind.FRONTEND);

        String pkgJson = packageJsonBuilder.build(loadClasspath(PACKAGE_JSON_TEMPLATE), ctx, customizations);
        Files.writeString(projectRoot.resolve("package.json"), pkgJson);

        String viteCfg = viteConfigBuilder.build(loadClasspath(VITE_CONFIG_TEMPLATE), ctx, customizations);
        Files.writeString(projectRoot.resolve("vite.config.ts"), viteCfg);
    }

    private String loadClasspath(String resourcePath) throws IOException {
        try (var in = new ClassPathResource(resourcePath).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ── ZIP ──────────────────────────────────────────────────────────────────

    private byte[] zipDirectory(Path dir, String rootDirName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).sorted().forEach(p -> {
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
}
