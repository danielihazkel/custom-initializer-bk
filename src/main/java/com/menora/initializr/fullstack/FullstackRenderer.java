package com.menora.initializr.fullstack;

import com.menora.initializr.db.entity.EntityTemplateFileEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.samskivert.mustache.Mustache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Renders an {@link com.menora.initializr.db.entity.EntityTemplateSetEntity}'s files into
 * a target directory. Used both by {@code EntityScaffoldContributor} (backend, runs inside
 * the Spring Initializr generation pipeline) and by {@code FullstackStarterController}
 * (frontend, runs inline outside the pipeline).
 */
public final class FullstackRenderer {

    // escapeHTML=false: we render Java, TypeScript, JSON, YAML, TS/TSX — never HTML.
    private static final Mustache.Compiler MUSTACHE = Mustache.compiler().escapeHTML(false);

    private FullstackRenderer() {}

    public static void render(
            List<EntityTemplateFileEntity> files,
            Map<String, Object> projectContext,
            List<EntityDefinition> entities,
            Path targetRoot) throws IOException {
        for (EntityTemplateFileEntity file : files) {
            if (file.isPerEntity()) {
                for (EntityDefinition entity : entities) {
                    Map<String, Object> ctx = EntityScaffoldContext.buildEntityContext(projectContext, entity);
                    // Per-entity files evaluate their gate against the per-entity context, so a
                    // per-entity flag (e.g. hasCompositePk) can gate a file for some entities only.
                    if (isGatedOut(file, ctx)) {
                        continue;
                    }
                    writeOne(file, ctx, targetRoot);
                }
            } else {
                if (isGatedOut(file, projectContext)) {
                    continue;
                }
                writeOne(file, projectContext, targetRoot);
            }
        }
    }

    /**
     * A file with a non-blank {@code gatedBy} is rendered only when that flag is truthy in the
     * supplied context. Project-level files are gated on the project context (e.g. an opt-in
     * {@code optScaffoldTests}); per-entity files are gated on the per-entity context, which is a
     * superset of the project context — so a project flag still works, and a per-entity flag
     * (e.g. {@code hasCompositePk}) becomes available too.
     */
    private static boolean isGatedOut(EntityTemplateFileEntity file, Map<String, Object> context) {
        String gate = file.getGatedBy();
        if (gate == null || gate.isBlank()) {
            return false;
        }
        Object v = context.get(gate);
        return !(Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v)));
    }

    private static void writeOne(EntityTemplateFileEntity file, Map<String, Object> ctx, Path targetRoot) throws IOException {
        String relativePath = renderString(file.getPathTemplate(), ctx, file.getSubstitutionType());
        String content = file.getContent() == null ? "" :
                renderString(file.getContent(), ctx, file.getSubstitutionType());
        Path target = targetRoot.resolve(relativePath);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static String renderString(String input, Map<String, Object> ctx,
                                       FileContributionEntity.SubstitutionType subType) {
        if (subType == FileContributionEntity.SubstitutionType.MUSTACHE) {
            return MUSTACHE.compile(input).execute(ctx);
        }
        return input;
    }
}
