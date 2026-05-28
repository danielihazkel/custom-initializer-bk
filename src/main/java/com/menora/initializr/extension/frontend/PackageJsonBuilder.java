package com.menora.initializr.extension.frontend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.samskivert.mustache.Mustache;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the final {@code package.json} string for a generated frontend project.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Render the baseline JSON template (typically loaded from
 *       {@code templates/frontend/fe-package-base.mustache}) through Mustache
 *       so {@code {{packageJsonName}}}, {@code {{description}}}, etc. resolve.</li>
 *   <li>Parse the resulting JSON to a Jackson tree.</li>
 *   <li>Apply each {@link BuildCustomizationEntity.CustomizationType#ADD_NPM_DEPENDENCY}
 *       row: {@code mavenArtifactId} = package name, {@code version} = semver range,
 *       {@code scope} = {@code "dev"} → devDependencies, otherwise → dependencies.</li>
 *   <li>Apply each {@link BuildCustomizationEntity.CustomizationType#ADD_NPM_SCRIPT}
 *       row: {@code mavenArtifactId} = script name, {@code version} = command. Merged
 *       into the {@code "scripts"} block; later rows win on name collision so admins
 *       can override baseline scripts without editing the template.</li>
 *   <li>Alphabetise keys within dependencies / devDependencies / scripts for stable diffs.</li>
 *   <li>Pretty-print and return.</li>
 * </ol>
 */
@Component
public class PackageJsonBuilder {

    private static final Mustache.Compiler MUSTACHE = Mustache.compiler().escapeHTML(false);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public String build(String baselineTemplate,
                        Map<String, Object> mustacheContext,
                        List<BuildCustomizationEntity> customizations) throws IOException {

        String rendered = MUSTACHE.compile(baselineTemplate).execute(mustacheContext);
        ObjectNode root = (ObjectNode) MAPPER.readTree(rendered);

        ObjectNode deps = (ObjectNode) root.get("dependencies");
        if (deps == null) {
            deps = root.putObject("dependencies");
        }
        ObjectNode devDeps = (ObjectNode) root.get("devDependencies");
        if (devDeps == null) {
            devDeps = root.putObject("devDependencies");
        }
        ObjectNode scripts = (ObjectNode) root.get("scripts");
        if (scripts == null) {
            scripts = root.putObject("scripts");
        }

        for (BuildCustomizationEntity bc : customizations) {
            switch (bc.getCustomizationType()) {
                case ADD_NPM_DEPENDENCY -> {
                    String pkg = bc.getMavenArtifactId();
                    String ver = bc.getVersion();
                    if (pkg == null || pkg.isBlank() || ver == null || ver.isBlank()) continue;
                    boolean dev = "dev".equalsIgnoreCase(bc.getScope());
                    (dev ? devDeps : deps).put(pkg, ver);
                }
                case ADD_NPM_SCRIPT -> {
                    String name = bc.getMavenArtifactId();
                    String cmd = bc.getVersion();
                    if (name == null || name.isBlank() || cmd == null || cmd.isBlank()) continue;
                    scripts.put(name, cmd);
                }
                default -> { /* other types handled elsewhere (e.g. ViteConfigBuilder) */ }
            }
        }

        sortKeys(root, "dependencies");
        sortKeys(root, "devDependencies");
        sortKeys(root, "scripts");

        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    private static void sortKeys(ObjectNode parent, String fieldName) {
        ObjectNode node = (ObjectNode) parent.get(fieldName);
        if (node == null || node.isEmpty()) return;
        Map<String, com.fasterxml.jackson.databind.JsonNode> sorted = new TreeMap<>();
        node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
        ObjectNode replacement = MAPPER.createObjectNode();
        sorted.forEach(replacement::set);
        parent.set(fieldName, replacement);
    }
}
