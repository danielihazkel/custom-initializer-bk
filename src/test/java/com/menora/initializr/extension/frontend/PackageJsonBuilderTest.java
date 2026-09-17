package com.menora.initializr.extension.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PackageJsonBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PackageJsonBuilder builder = new PackageJsonBuilder();

    private static BuildCustomizationEntity npmDep(String pkg, String version, String scope) {
        BuildCustomizationEntity bc = new BuildCustomizationEntity();
        bc.setCustomizationType(BuildCustomizationEntity.CustomizationType.ADD_NPM_DEPENDENCY);
        bc.setMavenArtifactId(pkg);
        bc.setVersion(version);
        bc.setScope(scope);
        return bc;
    }

    private static BuildCustomizationEntity npmScript(String name, String command) {
        BuildCustomizationEntity bc = new BuildCustomizationEntity();
        bc.setCustomizationType(BuildCustomizationEntity.CustomizationType.ADD_NPM_SCRIPT);
        bc.setMavenArtifactId(name);
        bc.setVersion(command);
        return bc;
    }

    @Test
    void build_appliesCustomizationsOverBaseline() throws Exception {
        String template = "{\"name\":\"{{name}}\",\"scripts\":{\"lint\":\"eslint .\"},"
                + "\"dependencies\":{\"react\":\"^18.0.0\"}}";
        String out = builder.build(template, Map.of("name", "demo"), List.of(
                npmDep("react", "^19.0.0", null),
                npmDep("eslint", "^9.0.0", "dev"),
                npmScript("lint", "eslint . --max-warnings=0")));
        JsonNode root = MAPPER.readTree(out);

        assertThat(root.get("name").asText()).isEqualTo("demo");
        // build() lets customization rows override the baseline (admin overrides).
        assertThat(root.get("dependencies").get("react").asText()).isEqualTo("^19.0.0");
        assertThat(root.get("devDependencies").get("eslint").asText()).isEqualTo("^9.0.0");
        assertThat(root.get("scripts").get("lint").asText()).isEqualTo("eslint . --max-warnings=0");
    }

    @Test
    void merge_addsOnlyAbsentDevToolingAndKeepsExistingPins() throws Exception {
        // The fullstack overlay's package.json: pins vite/tailwind, lacks the substrate tooling.
        String overlay = "{\"name\":\"demo-frontend\",\"scripts\":{\"build\":\"tsc -b && vite build\"},"
                + "\"dependencies\":{\"react\":\"^18.3.1\"},"
                + "\"devDependencies\":{\"vite\":\"^5.3.4\"}}";
        String out = builder.merge(overlay, List.of(
                npmDep("vite", "^5.2.0", "dev"),             // present -> overlay pin wins
                npmDep("eslint", "^9.10.0", "dev"),          // absent dev -> added
                npmDep("husky", "^9.1.5", "dev"),            // absent dev -> added
                npmScript("build", "vite build"),            // present -> overlay script wins
                npmScript("lint:fix", "eslint . --fix")));   // absent -> added
        JsonNode root = MAPPER.readTree(out);

        assertThat(root.get("devDependencies").get("vite").asText()).isEqualTo("^5.3.4");
        assertThat(root.get("devDependencies").get("eslint").asText()).isEqualTo("^9.10.0");
        assertThat(root.get("devDependencies").get("husky").asText()).isEqualTo("^9.1.5");
        assertThat(root.get("scripts").get("build").asText()).isEqualTo("tsc -b && vite build");
        assertThat(root.get("scripts").get("lint:fix").asText()).isEqualTo("eslint . --fix");
        // Untouched fields survive the round trip.
        assertThat(root.get("name").asText()).isEqualTo("demo-frontend");
        assertThat(root.get("dependencies").get("react").asText()).isEqualTo("^18.3.1");
    }

    @Test
    void merge_leavesRuntimeDependenciesToTheOverlay() throws Exception {
        // Runtime rows (fonts, UI libs, even react itself) are the overlay's call: a set that
        // ships Assistant instead of Inter must not get Inter back from the __common__ rows.
        String overlay = "{\"dependencies\":{\"@fontsource/assistant\":\"^5.0.0\"},\"devDependencies\":{}}";
        String out = builder.merge(overlay, List.of(
                npmDep("@fontsource/inter", "^5.0.18", null),
                npmDep("react", "^19.0.0", ""),
                npmDep("prettier", "^3.3.3", "dev")));
        JsonNode root = MAPPER.readTree(out);

        assertThat(root.get("dependencies").has("@fontsource/inter")).isFalse();
        assertThat(root.get("dependencies").has("react")).isFalse();
        assertThat(root.get("dependencies").get("@fontsource/assistant").asText()).isEqualTo("^5.0.0");
        assertThat(root.get("devDependencies").get("prettier").asText()).isEqualTo("^3.3.3");
    }

    @Test
    void merge_doesNotDuplicateAPackagePinnedInTheOtherBlock() throws Exception {
        // The overlay lists a package as a runtime dep; the catalog row says dev. Neither block
        // should end up with a second copy.
        String overlay = "{\"dependencies\":{\"clsx\":\"^2.1.1\"},\"devDependencies\":{}}";
        String out = builder.merge(overlay, List.of(npmDep("clsx", "^2.0.0", "dev")));
        JsonNode root = MAPPER.readTree(out);

        assertThat(root.get("dependencies").get("clsx").asText()).isEqualTo("^2.1.1");
        assertThat(root.get("devDependencies").has("clsx")).isFalse();
    }

    @Test
    void merge_createsMissingBlocks() throws Exception {
        String out = builder.merge("{\"name\":\"x\"}", List.of(
                npmDep("eslint", "^9.10.0", "dev"), npmScript("lint", "eslint .")));
        JsonNode root = MAPPER.readTree(out);

        assertThat(root.get("devDependencies").get("eslint").asText()).isEqualTo("^9.10.0");
        assertThat(root.get("scripts").get("lint").asText()).isEqualTo("eslint .");
        assertThat(root.get("dependencies").isEmpty()).isTrue();
    }
}
