package com.menora.initializr.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The kits that ship with the generator, defined on the classpath under
 * {@code templates/design-kits/<key>/kit.json} (+ its kit CSS and assets). The seeder publishes
 * each as a read-only kit; customizing one means cloning it.
 */
public final class BuiltInKits {

    public static final List<String> KEYS = List.of("tailwind-default", "menora-digital");
    static final String DIR = "templates/design-kits/";

    private BuiltInKits() {
    }

    /** kit.json as written on the classpath. */
    public record Definition(String key, String name, String description, String baseSet, Integer sortOrder,
                             String defaultPaletteId, DesignTokens tokens, KitManifest manifest, String css,
                             List<AssetSource> assets) {
        public Definition {
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }

    public record AssetSource(String targetPath, String role, String source, String meta) {
    }

    /** A built-in kit with its CSS and asset bytes read. */
    public record Loaded(Definition definition, String css, List<LoadedAsset> assets) {
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
            List<LoadedAsset> assets = d.assets().stream()
                    .map(a -> new LoadedAsset(a.targetPath(), a.role(), a.meta(), bytes(a.source())))
                    .toList();
            return new Loaded(d, css, assets);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read built-in design kit " + key, e);
        }
    }

    private static String read(String path) throws IOException {
        // Text as the checkout has it; the CSS writer normalizes line endings.
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
