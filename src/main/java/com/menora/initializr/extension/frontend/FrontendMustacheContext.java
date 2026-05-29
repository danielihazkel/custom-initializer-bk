package com.menora.initializr.extension.frontend;

import com.menora.initializr.config.ProjectOptionsContext;
import com.menora.initializr.db.entity.ColorPaletteEntity;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds the unified Mustache context exposed to every TEMPLATE file contribution
 * (and to the package.json / vite.config.ts baseline templates).
 *
 * Mirrors the shape used by the backend pipeline so admins can read & write
 * frontend templates with the same conventions:
 * <ul>
 *   <li>{@code projectName, description, scope, appTitle, packageJsonName}</li>
 *   <li>{@code reactVersion, nodeVersion, packageManager, typescriptVersion, viteVersion, basePath}</li>
 *   <li>{@code reactPackageVersion, reactDomPackageVersion, reactTypesVersion, reactDomTypesVersion} —
 *       the npm semver ranges that match {@code reactVersion}; used by the
 *       baseline {@code package.json} template so picking React 19 actually
 *       generates a React 19 project.</li>
 *   <li>{@code apiBaseUrl, backendArtifactId} + {@code hasBackendPair}/{@code hasBackendArtifactId} — paired-BE wiring</li>
 *   <li>{@code has<Dep>} — true for every selected dep (PascalCase, e.g. {@code hasRouterReactRouter})</li>
 *   <li>{@code opt<Dep><Option>} — true for every selected sub-option</li>
 *   <li>{@code isNpm, isPnpm} — package-manager flags for run-script hints</li>
 *   <li>{@code palette.id|name|primary|secondary|accent|error} + {@code hasPaletteAccent}/{@code hasPaletteError}</li>
 * </ul>
 */
public final class FrontendMustacheContext {

    private FrontendMustacheContext() {}

    public static Map<String, Object> build(FrontendProjectDescription desc,
                                            Set<String> selectedDepIds,
                                            ProjectOptionsContext optionsContext,
                                            ColorPaletteEntity palette) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("projectName", desc.getProjectName());
        ctx.put("description", desc.getDescription());
        ctx.put("scope", desc.getScope());
        ctx.put("appTitle", desc.getAppTitle());
        ctx.put("packageJsonName", desc.packageJsonName());
        ctx.put("reactVersion", desc.getReactVersion());
        ctx.put("reactPackageVersion", desc.getReactPackageVersion());
        ctx.put("reactDomPackageVersion", desc.getReactPackageVersion());
        ctx.put("reactTypesVersion", desc.getReactTypesVersion());
        ctx.put("reactDomTypesVersion", desc.getReactTypesVersion());
        ctx.put("nodeVersion", desc.getNodeVersion());
        ctx.put("packageManager", desc.getPackageManager());
        ctx.put("typescriptVersion", desc.getTypescriptVersion());
        ctx.put("viteVersion", desc.getViteVersion());
        ctx.put("basePath", desc.getBasePath());
        ctx.put("apiBaseUrl", desc.getApiBaseUrl());
        ctx.put("backendArtifactId", desc.getBackendArtifactId());
        ctx.put("hasBackendPair", desc.hasBackendPair());
        ctx.put("hasBackendArtifactId", desc.getBackendArtifactId() != null && !desc.getBackendArtifactId().isBlank());
        ctx.put("isNpm", "npm".equalsIgnoreCase(desc.getPackageManager()));
        ctx.put("isPnpm", "pnpm".equalsIgnoreCase(desc.getPackageManager()));

        Map<String, String> paletteMap = new LinkedHashMap<>();
        paletteMap.put("id", palette.getPaletteId());
        paletteMap.put("name", palette.getName());
        paletteMap.put("primary", palette.getPrimary());
        paletteMap.put("secondary", palette.getSecondary());
        paletteMap.put("accent", palette.getAccent() == null ? "" : palette.getAccent());
        paletteMap.put("error", palette.getError() == null ? "" : palette.getError());
        // HSL forms for shadcn/ui CSS variables (format "H S% L%" without commas).
        paletteMap.put("primaryHsl", hexToHsl(palette.getPrimary()));
        paletteMap.put("secondaryHsl", hexToHsl(palette.getSecondary()));
        paletteMap.put("accentHsl", hexToHsl(palette.getAccent()));
        paletteMap.put("errorHsl", hexToHsl(palette.getError()));
        ctx.put("palette", paletteMap);
        ctx.put("hasPaletteAccent", palette.getAccent() != null && !palette.getAccent().isBlank());
        ctx.put("hasPaletteError", palette.getError() != null && !palette.getError().isBlank());

        for (String depId : selectedDepIds) {
            ctx.put("has" + toPascalCase(depId), Boolean.TRUE);
            for (String optId : optionsContext.selectedOptions(depId)) {
                ctx.put("opt" + toPascalCase(depId) + toPascalCase(optId), Boolean.TRUE);
            }
        }
        return ctx;
    }

    /**
     * Converts a {@code #rrggbb} hex color to the {@code "H S% L%"} string shadcn/ui
     * (and other CSS-variable theme systems) expect. Returns an empty string for
     * null/blank/malformed input so the resulting CSS variable simply has no value.
     */
    static String hexToHsl(String hex) {
        if (hex == null || hex.isBlank()) return "";
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() != 6) return "";
        int r, g, b;
        try {
            r = Integer.parseInt(h.substring(0, 2), 16);
            g = Integer.parseInt(h.substring(2, 4), 16);
            b = Integer.parseInt(h.substring(4, 6), 16);
        } catch (NumberFormatException e) {
            return "";
        }
        double rd = r / 255.0, gd = g / 255.0, bd = b / 255.0;
        double max = Math.max(rd, Math.max(gd, bd));
        double min = Math.min(rd, Math.min(gd, bd));
        double l = (max + min) / 2.0;
        double hue, sat;
        if (max == min) {
            hue = 0; sat = 0;
        } else {
            double d = max - min;
            sat = l > 0.5 ? d / (2.0 - max - min) : d / (max + min);
            if (max == rd) hue = (gd - bd) / d + (gd < bd ? 6 : 0);
            else if (max == gd) hue = (bd - rd) / d + 2;
            else hue = (rd - gd) / d + 4;
            hue *= 60;
        }
        return Math.round(hue) + " " + Math.round(sat * 100) + "% " + Math.round(l * 100) + "%";
    }

    /** {@code "router-react-router"} → {@code "RouterReactRouter"}. */
    public static String toPascalCase(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        boolean upper = true;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '-' || c == '_' || c == '.') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
