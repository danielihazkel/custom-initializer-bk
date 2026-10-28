package com.menora.initializr.design;

import java.util.List;

/**
 * A kit version as generation needs it, fully fetched (open-in-view is off): its tokens, manifest,
 * kit CSS and asset bytes. {@code version} is null for a draft an admin is trying out.
 */
public record ResolvedKit(String key, Integer version, String name, String baseSetKey, String defaultPaletteId,
                          DesignTokens tokens, KitManifest manifest, String css, List<Asset> assets) {

    public ResolvedKit {
        assets = assets == null ? List.of() : List.copyOf(assets);
    }

    public record Asset(String targetPath, String role, String contentType, String meta, byte[] content) {
    }

    /** {@code key@version}, or {@code key@draft}. */
    public String ref() {
        return key + "@" + (version == null ? "draft" : version);
    }
}
