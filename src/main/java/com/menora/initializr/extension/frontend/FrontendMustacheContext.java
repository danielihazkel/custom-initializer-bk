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
        ctx.put("nodeVersion", desc.getNodeVersion());
        ctx.put("packageManager", desc.getPackageManager());
        ctx.put("typescriptVersion", desc.getTypescriptVersion());
        ctx.put("viteVersion", desc.getViteVersion());
        ctx.put("basePath", desc.getBasePath());
        ctx.put("isNpm", "npm".equalsIgnoreCase(desc.getPackageManager()));
        ctx.put("isPnpm", "pnpm".equalsIgnoreCase(desc.getPackageManager()));

        Map<String, String> paletteMap = new LinkedHashMap<>();
        paletteMap.put("id", palette.getPaletteId());
        paletteMap.put("name", palette.getName());
        paletteMap.put("primary", palette.getPrimary());
        paletteMap.put("secondary", palette.getSecondary());
        paletteMap.put("accent", palette.getAccent() == null ? "" : palette.getAccent());
        paletteMap.put("error", palette.getError() == null ? "" : palette.getError());
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
