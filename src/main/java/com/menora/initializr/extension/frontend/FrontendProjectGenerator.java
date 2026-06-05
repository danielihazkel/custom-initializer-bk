package com.menora.initializr.extension.frontend;

import com.menora.initializr.config.OpenApiSpecContext;
import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.ColorPaletteEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.ColorPaletteRepository;
import com.menora.initializr.extension.frontend.codegen.HooksTsRenderer;
import com.menora.initializr.extension.frontend.codegen.MswHandlersRenderer;
import com.menora.initializr.extension.frontend.codegen.OpenApiCodegenException;
import com.menora.initializr.extension.frontend.codegen.OpenApiTsGenerator;
import io.swagger.v3.oas.models.OpenAPI;
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

    /** Dep id that opts a project into OpenAPI-driven typed client generation. */
    private static final String API_CLIENT_OPENAPI_DEP = "api-client-openapi";
    /** Companion dep id that adds React-Query hooks ({@code hooks.ts}) to the generated client. */
    private static final String TANSTACK_QUERY_DEP = "data-tanstack-query";
    /** Companion dep id that adds MSW handler stubs ({@code msw.ts}) for the test setup. */
    private static final String VITEST_RTL_DEP = "test-vitest-rtl";

    private final DependencyConfigService configService;
    private final ProjectOptionsContext optionsContext;
    private final PackageJsonBuilder packageJsonBuilder;
    private final ViteConfigBuilder viteConfigBuilder;
    private final ColorPaletteRepository colorPaletteRepo;
    private final OpenApiSpecContext openApiSpecContext;
    private final OpenApiTsGenerator openApiTsGenerator;
    private final HooksTsRenderer hooksTsRenderer;
    private final MswHandlersRenderer mswHandlersRenderer;

    public FrontendProjectGenerator(DependencyConfigService configService,
                                    ProjectOptionsContext optionsContext,
                                    PackageJsonBuilder packageJsonBuilder,
                                    ViteConfigBuilder viteConfigBuilder,
                                    ColorPaletteRepository colorPaletteRepo,
                                    OpenApiSpecContext openApiSpecContext,
                                    OpenApiTsGenerator openApiTsGenerator,
                                    HooksTsRenderer hooksTsRenderer,
                                    MswHandlersRenderer mswHandlersRenderer) {
        this.configService = configService;
        this.optionsContext = optionsContext;
        this.packageJsonBuilder = packageJsonBuilder;
        this.viteConfigBuilder = viteConfigBuilder;
        this.colorPaletteRepo = colorPaletteRepo;
        this.openApiSpecContext = openApiSpecContext;
        this.openApiTsGenerator = openApiTsGenerator;
        this.hooksTsRenderer = hooksTsRenderer;
        this.mswHandlersRenderer = mswHandlersRenderer;
    }

    /**
     * Renders the full project skeleton (file contributions + baselines + OpenAPI
     * codegen) into {@code targetDir}. Creates {@code targetDir} if it is missing
     * but never deletes it — the caller owns its lifecycle. This is the shared
     * render body behind {@link #generate} / {@link #generateFileMap} and the
     * entrypoint the fullstack generator uses to lay down the FSD substrate before
     * overlaying its per-entity CRUD files.
     */
    public void renderInto(Path targetDir, FrontendProjectDescription desc) throws IOException {
        Set<String> depIds = desc.getDependencies();
        ColorPaletteEntity palette = resolvePalette(desc.getColorPaletteId());
        Map<String, Object> ctx = FrontendMustacheContext.build(desc, depIds, optionsContext, palette);

        Files.createDirectories(targetDir);
        applyFileContributions(targetDir, depIds, ctx, desc);
        writeBaselines(targetDir, depIds, ctx);
        writeOpenApiSpec(targetDir, depIds);
        writeGeneratedTs(targetDir, depIds);
    }

    /** Generates the project, returns the ZIP bytes (containing a top-level {@code projectName/} directory). */
    public byte[] generate(FrontendProjectDescription desc) throws IOException {
        Path tempDir = Files.createTempDirectory("frontend-");
        try {
            renderInto(tempDir, desc);
            return zipDirectory(tempDir, desc.getProjectName());
        } finally {
            FileSystemUtils.deleteRecursively(tempDir);
        }
    }

    /** Same as {@link #generate} but returns the file tree (relative path → content) for previews / tests. */
    public Map<String, String> generateFileMap(FrontendProjectDescription desc) throws IOException {
        Path tempDir = Files.createTempDirectory("frontend-preview-");
        try {
            renderInto(tempDir, desc);
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

    // ── Color palette resolution ─────────────────────────────────────────────

    /**
     * Resolves the palette to inject into theme templates. Order:
     * 1. Explicit paletteId from the request, if it exists in the DB
     * 2. The palette flagged {@code isDefault=true}
     * 3. A hardcoded sentinel — guarantees generation never crashes when the
     *    {@code color_palette} table is empty (e.g. fresh install before seeding ran)
     */
    private ColorPaletteEntity resolvePalette(String paletteId) {
        if (paletteId != null && !paletteId.isBlank()) {
            var hit = colorPaletteRepo.findByPaletteId(paletteId);
            if (hit.isPresent()) return hit.get();
            log.warn("Requested colorPalette '{}' not found — falling back to default", paletteId);
        }
        var def = colorPaletteRepo.findFirstByIsDefaultTrueOrderBySortOrderAsc();
        if (def.isPresent()) return def.get();
        log.warn("No default color palette in DB — using hardcoded sentinel");
        ColorPaletteEntity sentinel = new ColorPaletteEntity();
        sentinel.setPaletteId("sentinel");
        sentinel.setName("Sentinel");
        sentinel.setPrimary("#1976d2");
        sentinel.setSecondary("#9c27b0");
        return sentinel;
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
        // A TEMPLATE that renders to blank content is a signal it wasn't applicable
        // to this request (e.g. paired-BE .env files when no apiBaseUrl is set).
        // Skip the write so we don't drop empty files into the generated project.
        if (content.isBlank()) return;
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
                configService.getBuildCustomizations(depIds, ProjectKind.FRONTEND).stream()
                        .filter(this::subOptionAllows)
                        .toList();

        String pkgJson = packageJsonBuilder.build(loadClasspath(PACKAGE_JSON_TEMPLATE), ctx, customizations);
        Files.writeString(projectRoot.resolve("package.json"), pkgJson);

        String viteCfg = viteConfigBuilder.build(loadClasspath(VITE_CONFIG_TEMPLATE), ctx, customizations);
        Files.writeString(projectRoot.resolve("vite.config.ts"), viteCfg);
    }

    /**
     * Mirrors the sub-option gating used for file contributions (see
     * {@code applyFileContributions}): a row with a {@code subOptionId} only
     * applies when the user picked that sub-option for the parent dep. Rows
     * without a {@code subOptionId} always apply.
     */
    private boolean subOptionAllows(BuildCustomizationEntity bc) {
        return bc.getSubOptionId() == null
                || optionsContext.hasOption(bc.getDependencyId(), bc.getSubOptionId());
    }

    private String loadClasspath(String resourcePath) throws IOException {
        try (var in = new ClassPathResource(resourcePath).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ── OpenAPI spec injection ──────────────────────────────────────────────
    /**
     * Writes the paired backend's OpenAPI spec into {@code openapi.yaml} at the
     * project root when {@code api-client-openapi} is selected and a non-blank
     * spec sits in {@link OpenApiSpecContext}. Multiple specs in the context
     * (one per BE dep) are merged-by-takefirst — paired flows typically carry
     * a single spec keyed under the {@code openapi} dep id.
     *
     * <p>No-op when the dep is unselected or the context is empty, so picking
     * {@code api-client-openapi} without a wizard spec degrades gracefully —
     * users can write {@code openapi.yaml} themselves and run {@code gen:api}.
     */
    private void writeOpenApiSpec(Path projectRoot, Set<String> depIds) throws IOException {
        if (!depIds.contains(API_CLIENT_OPENAPI_DEP)) return;
        if (openApiSpecContext.isEmpty()) return;
        String spec = openApiSpecContext.all().values().stream()
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
        if (spec == null) return;
        Files.writeString(projectRoot.resolve("openapi.yaml"), spec);
    }

    /**
     * Pre-generates {@code schema.ts}, {@code paths.ts}, {@code client.ts} into
     * {@code src/shared/api/generated/} from the OpenAPI spec, so the project
     * type-checks immediately after extraction (no need to run {@code gen:api}
     * first). Codegen failures degrade to a README — the user can always rerun
     * {@code pnpm gen:api} via the shipped {@code openapi-typescript} CLI.
     */
    private void writeGeneratedTs(Path projectRoot, Set<String> depIds) throws IOException {
        if (!depIds.contains(API_CLIENT_OPENAPI_DEP)) return;
        if (openApiSpecContext.isEmpty()) return;
        String spec = openApiSpecContext.all().values().stream()
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
        if (spec == null) return;

        Path generatedDir = projectRoot.resolve("src/shared/api/generated");
        Files.createDirectories(generatedDir);
        // Drop the seed .gitkeep — generated files take its place.
        Files.deleteIfExists(generatedDir.resolve(".gitkeep"));

        OpenAPI parsedSpec;
        Map<String, String> files;
        try {
            parsedSpec = openApiTsGenerator.parse(spec);
            files = new LinkedHashMap<>(openApiTsGenerator.render(parsedSpec));
        } catch (OpenApiCodegenException e) {
            log.warn("OpenAPI TS codegen failed: {} — falling back to gen:api script", e.getMessage());
            Files.writeString(generatedDir.resolve("README.md"),
                    "# Codegen failed\n\nMenora's pure-Java OpenAPI → TS codegen could not parse the\n"
                            + "supplied spec:\n\n> " + e.getMessage() + "\n\n"
                            + "Run `pnpm gen:api` to fall back to the `openapi-typescript` CLI.\n");
            return;
        }
        // Companion-dep gating: hooks.ts iff React-Query, msw.ts iff Vitest+RTL.
        if (depIds.contains(TANSTACK_QUERY_DEP)) {
            files.put("hooks.ts", hooksTsRenderer.render(parsedSpec));
        }
        if (depIds.contains(VITEST_RTL_DEP)) {
            files.put("msw.ts", mswHandlersRenderer.render(parsedSpec));
        }
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Files.writeString(generatedDir.resolve(entry.getKey()), entry.getValue());
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
