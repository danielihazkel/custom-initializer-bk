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
                    writeOne(file, ctx, targetRoot);
                }
            } else {
                writeOne(file, projectContext, targetRoot);
            }
        }
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
