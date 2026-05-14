package com.menora.initializr.extension.frontend;

import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.samskivert.mustache.Mustache;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the final {@code vite.config.ts} string.
 *
 * <p>Reads all {@link BuildCustomizationEntity.CustomizationType#ADD_VITE_PLUGIN} rows
 * for the selected dependencies and assembles two pre-rendered strings, which are
 * then injected into the baseline template alongside the regular Mustache context:
 * <ul>
 *   <li>{@code vitePluginImports} — one {@code import X from 'Y';} line per unique import.</li>
 *   <li>{@code vitePluginCalls} — comma-separated plugin expressions for the {@code plugins[]} array.</li>
 * </ul>
 *
 * <p>Field reinterpretation per row:
 * <ul>
 *   <li>{@code mavenGroupId}: import module path (e.g. {@code "@vitejs/plugin-react"}).</li>
 *   <li>{@code mavenArtifactId}: import binding (e.g. {@code "react"}).</li>
 *   <li>{@code version}: plugin call expression (e.g. {@code "react()"}).</li>
 * </ul>
 */
@Component
public class ViteConfigBuilder {

    private static final Mustache.Compiler MUSTACHE = Mustache.compiler().escapeHTML(false);

    public String build(String baselineTemplate,
                        Map<String, Object> mustacheContext,
                        List<BuildCustomizationEntity> customizations) {

        Set<String> importLines = new LinkedHashSet<>();
        List<String> pluginCalls = new java.util.ArrayList<>();

        for (BuildCustomizationEntity bc : customizations) {
            if (bc.getCustomizationType() != BuildCustomizationEntity.CustomizationType.ADD_VITE_PLUGIN) {
                continue;
            }
            String importPath = bc.getMavenGroupId();
            String importBinding = bc.getMavenArtifactId();
            String pluginCall = bc.getVersion();
            if (importPath == null || importBinding == null || pluginCall == null) continue;
            importLines.add("import " + importBinding + " from '" + importPath + "';");
            pluginCalls.add(pluginCall);
        }

        Map<String, Object> ctx = new LinkedHashMap<>(mustacheContext);
        ctx.put("vitePluginImports", String.join("\n", importLines));
        ctx.put("vitePluginCalls", String.join(", ", pluginCalls));

        return MUSTACHE.compile(baselineTemplate).execute(ctx);
    }
}
