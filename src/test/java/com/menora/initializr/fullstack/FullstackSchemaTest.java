package com.menora.initializr.fullstack;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@code GET /metadata/fullstack/schema} to {@code src/test/resources/golden/fullstack-schema.json}.
 *
 * <p>That file is what the editor is built from — the UI checks in a copy
 * ({@code ui/src/components/fullstack/schema/fullstack-schema.json}, refreshed with
 * {@code npm run sync:schema}) and derives its limits, lists and name rules from it. So a change
 * to a validator constant shows here as a diff to review, and then has to be carried to the UI:
 * regenerate with {@code mvn test -Dtest=FullstackSchemaTest -Dgolden.update=true}, then run the
 * UI's sync script.
 */
class FullstackSchemaTest {

    private static final Path GOLDEN = Path.of("src", "test", "resources", "golden", "fullstack-schema.json");

    private static String render() throws Exception {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentArraysWith(new DefaultIndenter("  ", "\n"));
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        return new ObjectMapper().writer(printer).writeValueAsString(FullstackSchema.document()) + "\n";
    }

    @Test
    void theSchemaIsTheCheckedInDocument() throws Exception {
        String actual = render();
        if (Boolean.getBoolean("golden.update")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.exists(GOLDEN)).as("no " + GOLDEN + " — run with -Dgolden.update=true").isTrue();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expected = mapper.readTree(Files.readString(GOLDEN, StandardCharsets.UTF_8));
        assertThat(mapper.readTree(actual)).as("The fullstack schema changed. If intended, re-run with "
                + "-Dgolden.update=true, review the diff, and run `npm run sync:schema` in ui/.").isEqualTo(expected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void itCarriesWhatTheValidatorsEnforce() {
        Map<String, Object> doc = FullstackSchema.document();
        assertThat((List<String>) doc.get("pageTypes")).contains("entity-list", "dashboard", "inbox", "form");
        assertThat((List<String>) doc.get("widgetKinds")).contains("kpi", "pivot", "activity");
        assertThat((Map<String, Object>) doc.get("limits"))
                .containsEntry("maxPages", FullstackPageValidator.MAX_PAGES)
                .containsEntry("maxTransitions", FullstackRequestValidator.MAX_TRANSITIONS);
        Map<String, Object> names = (Map<String, Object>) doc.get("names");
        assertThat((Iterable<String>) names.get("reservedEntityNames")).contains("page", "dashboard");
        assertThat((Iterable<String>) names.get("reservedKeywords")).contains("class", "record");

        // Every option has a key the validator knows, and the per-entity ones are the override keys.
        List<Map<String, Object>> options = (List<Map<String, Object>>) doc.get("scaffoldOptions");
        assertThat(options).extracting(o -> o.get("key")).containsExactlyElementsOf(ScaffoldOption.keys());
        assertThat(options.stream().filter(o -> (Boolean) o.get("perEntity")).map(o -> (String) o.get("key")).toList())
                .containsExactlyElementsOf(EntityScaffoldContext.SCAFFOLD_OPT_FLAGS.keySet());

        // Every alias the schema lists parses to the type it is listed under.
        for (Map<String, Object> type : (List<Map<String, Object>>) doc.get("fieldTypes")) {
            for (String alias : (List<String>) type.get("aliases")) {
                assertThat(FieldType.forWireString(alias).name()).isEqualTo(type.get("type"));
            }
        }
    }
}
