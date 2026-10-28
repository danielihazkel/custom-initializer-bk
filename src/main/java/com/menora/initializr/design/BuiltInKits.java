package com.menora.initializr.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The kits that ship with the generator, defined on the classpath under
 * {@code templates/design-kits/<key>/kit.json} (+ its kit CSS, component files and assets, each
 * read from a classpath {@code source} so nothing is copied). The seeder publishes each as a
 * read-only kit; customizing one means cloning it.
 */
public final class BuiltInKits {

    public static final List<String> KEYS = List.of("tailwind-default", "menora-digital");

    /** The house kit on a fresh database: a request naming no kit generates with it (see V25). */
    public static final String HOUSE = "tailwind-default";

    /** The built-in kit that implements a set's design system: such a set generates as the kit. */
    public static final java.util.Map<EntityTemplateSetEntity.DesignSystem, String> FOR_DESIGN_SYSTEM =
            java.util.Map.of(EntityTemplateSetEntity.DesignSystem.MENORA_DIGITAL, "menora-digital");
    static final String DIR = "templates/design-kits/";

    private BuiltInKits() {
    }

    /** kit.json as written on the classpath. */
    public record Definition(String key, String name, String description, String baseSet, Integer sortOrder,
                             String defaultPaletteId, DesignTokens tokens, KitManifest manifest, String css,
                             List<FileSource> files, List<AssetSource> assets) {
        public Definition {
            files = files == null ? List.of() : List.copyOf(files);
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }

    /** A component file (a slot replacement or a helper) at {@code path}, read from {@code source}. */
    public record FileSource(String path, String source) {
    }

    public record AssetSource(String targetPath, String role, String source, String meta) {
    }

    /** A built-in kit with its CSS, component files and asset bytes read. */
    public record Loaded(Definition definition, String css, List<LoadedFile> files, List<LoadedAsset> assets) {
    }

    public record LoadedFile(String path, String content) {
    }

    public record LoadedAsset(String targetPath, String role, String meta, byte[] content) {
    }

    public static Loaded load(String key, ObjectMapper mapper) {
        try {
            Definition d;
            try (InputStream in = new ClassPathResource(DIR + key + "/kit.json").getInputStream()) {
                d = mapper.readValue(in, Definition.class);
            }
            String css = d.css() == null ? "" : read(DIR + key + "/" + d.css());
            List<LoadedFile> files = new java.util.ArrayList<>();
            for (FileSource f : d.files()) files.add(new LoadedFile(f.path(), read(f.source())));
            List<LoadedAsset> assets = d.assets().stream()
                    .map(a -> new LoadedAsset(a.targetPath(), a.role(), a.meta(), bytes(a.source())))
                    .toList();
            return new Loaded(d, css, List.copyOf(files), assets);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read built-in design kit " + key, e);
        }
    }

    private static String read(String path) throws IOException {
        // Text as the checkout has it, like the template sets' own rows (the CSS writer normalizes
        // line endings), so a kit's component file is written with the bytes its set would write.
        return new String(bytes(path), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }
}
