package com.menora.initializr;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden per-file hashes of the whole fullstack zip for every seeded example, on both frontend
 * sets, in English and in Hebrew/RTL, with the example's layout and in the classic shell.
 *
 * <p>These manifests make every byte change visible in review: a template edit that changes output
 * fails here, naming each added, removed and changed file per case, and writes that case's files
 * under {@code target/golden-actual/} so they can be diffed. Content is hashed after
 * {@code \r\n} → {@code \n}, because a Windows checkout renders CRLF templates and CI does not.
 *
 * <p>After an intended change, regenerate with
 * {@code mvn test -Dtest=FullstackGoldenOutputTests -Dgolden.update=true} and review the
 * {@code src/test/resources/golden/fullstack/*.sha} diff.
 */
@IntegrationTest
@Tag("slow") // left out of mvn test -Pquick
class FullstackGoldenOutputTests {

    private static final List<String> FRONTEND_SETS = List.of("react-tailwind-crud", "react-menora-digital-crud");
    /** The second Spring Boot line the generator offers (DataSeeder.seedVersionsIfMissing). */
    static final String BOOT_35 = "3.5.16";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void everyExampleGeneratesItsPinnedBytes() throws Exception {
        GoldenManifests golden = manifests();
        for (Map<String, Object> example : FullstackExampleRequests.examples()) {
            boolean hasPages = FullstackExampleRequests.hasPages(example);
            for (String set : FRONTEND_SETS) {
                for (String locale : List.of("en", "he")) {
                    for (boolean layout : hasPages ? List.of(true, false) : List.of(false)) {
                        String name = example.get("exampleId") + "__" + set + "__" + locale
                                + "__" + (layout ? "layout" : "classic");
                        golden.pin(name, generate(FullstackExampleRequests.body(example, set, locale, layout)));
                    }
                }
            }
            // The other Spring Boot line, once: what differs there is the backend's pom and its
            // controller tests, not the look or the language.
            if ("approvals".equals(example.get("exampleId"))) {
                golden.pin("approvals__react-tailwind-crud__en__layout__boot35",
                        generate(FullstackExampleRequests.body(example, "react-tailwind-crud", "en", true, BOOT_35)));
            }
        }
        golden.finish();
    }

    /**
     * The fullstack manifests. The model file names the templates it was rendered with: pinning
     * that id would change every manifest whenever any template changes, and hide which cases a
     * template edit really reaches — so it is pinned as "an id is there".
     */
    private static GoldenManifests manifests() {
        return new GoldenManifests("fullstack", Path.of("target", "golden-actual"), (path, text) ->
                path.endsWith("/menora-model.json")
                        ? text.replaceAll("\"templatesHash\" : \"[0-9a-f]{64}\"", "\"templatesHash\" : \"<templates>\"")
                        : text);
    }

    /** Generation is deterministic, or a golden hash would be meaningless. */
    @Test
    void generationIsDeterministic() throws Exception {
        Map<String, Object> example = FullstackExampleRequests.examples().stream()
                .filter(e -> "approvals".equals(e.get("exampleId"))).findFirst().orElseThrow();
        Map<String, Object> body = FullstackExampleRequests.body(example, "react-menora-digital-crud", "he", true);
        assertThat(manifests().hashes(generate(body))).isEqualTo(manifests().hashes(generate(body)));
    }

    private Map<String, byte[]> generate(Map<String, Object> body) throws java.io.IOException {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(restTemplate, body);
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        return FullstackExampleRequests.unzip(response.getBody());
    }
}
