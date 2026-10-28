package com.menora.initializr.design;

import com.menora.initializr.db.entity.DesignKitAssetEntity;
import com.menora.initializr.fullstack.FullstackPageValidator;
import com.menora.initializr.fullstack.PageDefinition;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a design kit can do with this generator, read from the generator's own enums and
 * validators rather than written down twice: the tokens it must define, the page types and widget
 * kinds the layout designer offers, the nav icons, and the limits. The Design Studio and the
 * generator UI read it from {@code GET /metadata/design/contract}; {@code DesignContractSnapshotTest}
 * pins it, so a change here is a deliberate one.
 */
public final class DesignContract {

    private DesignContract() {
    }

    public static Map<String, Object> describe() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("contractVersion", DesignKitService.CONTRACT_VERSION);
        c.put("runtimeProtocol", DesignRuntimeFilter.PROTOCOL_VERSION);
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("schema", DesignTokens.SCHEMA);
        tokens.put("required", TokenSchema.REQUIRED);
        tokens.put("namePattern", TokenSchema.NAME.pattern());
        tokens.put("types", TokenSchema.TYPES);
        tokens.put("paletteReferences", List.of("{palette.primary}", "{palette.secondary}",
                "{palette.accent|<fallback>}", "{palette.error|<fallback>}"));
        c.put("tokenSchema", tokens);
        c.put("pageTypes", Arrays.stream(PageDefinition.Type.values()).map(PageDefinition.Type::wire).toList());
        c.put("widgetKinds", Arrays.stream(PageDefinition.WidgetKind.values()).map(PageDefinition.WidgetKind::wire).toList());
        c.put("chartKinds", Arrays.stream(PageDefinition.ChartKind.values()).map(PageDefinition.ChartKind::wire).toList());
        c.put("aggs", Arrays.stream(PageDefinition.Agg.values()).map(PageDefinition.Agg::wire).toList());
        c.put("buckets", Arrays.stream(PageDefinition.Bucket.values()).map(PageDefinition.Bucket::wire).toList());
        c.put("dateRanges", Arrays.stream(PageDefinition.DateRange.values()).map(PageDefinition.DateRange::wire).toList());
        c.put("navStyles", Arrays.stream(PageDefinition.NavStyle.values()).map(PageDefinition.NavStyle::wire).toList());
        c.put("navIcons", FullstackPageValidator.NAV_ICONS);
        c.put("assetRoles", Arrays.stream(DesignKitAssetEntity.Role.values()).map(Enum::name).toList());
        c.put("assetTypes", List.of("image/png", "image/jpeg", "image/webp", "image/svg+xml", "image/x-icon", "font/woff2", "font/woff"));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("assetBytes", AssetValidator.MAX_BYTES);
        limits.put("versionAssetBytes", AssetValidator.MAX_VERSION_BYTES);
        limits.put("kitCssBytes", TokenValidator.MAX_CSS);
        limits.put("tokens", TokenValidator.MAX_TOKENS);
        limits.put("tokenValueChars", TokenValidator.MAX_VALUE);
        c.put("limits", limits);
        // A kit's own component files (P3). The slots themselves depend on the base set's rows, so
        // they are listed per kit (GET /admin/design/kits/{key}/components), not here.
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("slotPattern", KitSourceValidator.SLOT.pattern());
        components.put("helperPattern", KitSourceValidator.HELPER.pattern());
        components.put("allowedImports", KitSourceValidator.PACKAGES);
        components.put("alsoAllowed", List.of("@shared/…", "relative paths inside src/shared"));
        components.put("refused", List.copyOf(KitSourceValidator.BANNED.values()));
        components.put("slotExports", "exactly the exports of the file it replaces");
        components.put("maxFileBytes", KitSourceValidator.MAX_FILE_BYTES);
        components.put("maxFiles", KitSourceValidator.MAX_FILES);
        components.put("navIcons", "manifest navIcons {module, names}: up to " + KitManifest.MAX_NAV_ICONS
                + " components named <Name>Icon exported by one .tsx helper; pages may then name them as nav icons");
        components.put("thumbnail", "an asset at kit/thumbnail.<png|jpg|webp|svg> (role THUMBNAIL) pictures the kit in the generator; it is not written into the app");
        c.put("components", components);
        return c;
    }
}
