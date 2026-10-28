package com.menora.initializr.design;

import java.util.List;

/**
 * A kit version as generation needs it, fully fetched (open-in-view is off): its tokens, manifest,
 * kit CSS, asset bytes and component files (slots and helpers). {@code version} is null for a
 * draft an admin is trying out.
 */
public record ResolvedKit(String key, Integer version, String name, String baseSetKey, String defaultPaletteId,
                          DesignTokens tokens, KitManifest manifest, String css, List<Asset> assets, List<File> files) {

    public ResolvedKit {
        assets = assets == null ? List.of() : List.copyOf(assets);
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** A component file: {@code SLOT} replaces the base set's file at {@code path}, {@code HELPER} is the kit's own. */
    public record File(String path, String role, String content) {
    }

    public record Asset(String targetPath, String role, String contentType, String meta, byte[] content) {
    }

    /** {@code key@version}, or {@code key@draft}. */
    public String ref() {
        return key + "@" + (version == null ? "draft" : version);
    }
}
