package com.menora.initializr.design;

import com.menora.initializr.FullstackExampleRequests;
import com.menora.initializr.TestInvokerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Generating with a custom design kit: its tokens, font, CSS and assets reach the frontend. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class FullstackDesignKitTests {

    private static final AtomicInteger SEQ = new AtomicInteger();
    static final byte[] WOFF2 = woff2();
    static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>"
            .getBytes(StandardCharsets.UTF_8);

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private DesignKitService service;

    @Test
    void customKitRestylesTheFrontend() throws Exception {
        String key = acmeKit("#0f766e");
        Map<String, String> files = generate(Map.of("designKit", key));

        String css = files.get("frontend/src/index.css");
        assertThat(css)
                .contains("--color-brand:")
                .contains(" #0f766e;")
                .containsPattern("\\.dark \\{\\n  --color-canvas: +#101820;") // the dark override
                .contains("@font-face {\n  font-family: 'Brand Sans';\n  src: url('./assets/fonts/brand.woff2') format('woff2');")
                .contains(".acme-banner { color: var(--color-brand); }")
                .doesNotContain("{palette.");
        assertThat(files.get("frontend/src/main.tsx"))
                .contains("import '@fontsource/roboto/400.css'\nimport '@fontsource/roboto/700.css'\n")
                .doesNotContain("@fontsource/inter");
        assertThat(files.get("frontend/package.json"))
                .contains("\"@fontsource/roboto\" : \"^5.0.8\"")
                .doesNotContain("@fontsource/inter");
        assertThat(files).containsKeys("frontend/src/assets/fonts/brand.woff2", "frontend/public/acme-logo.svg");
        assertThat(files.get("menora-model.json")).contains("\"designKit\" : \"" + key + "\"");
    }

    @Test
    void aPinnedVersionKeepsItsLookAfterARepublish() throws Exception {
        String key = acmeKit("#0f766e");
        service.openDraft(key, null, "test");
        service.saveDraft(key, new DesignKitService.SaveDraft(withBrand(service.draft(key).tokens(), "#b91c1c"), null, null, null));
        service.publish(key, "red", "test");

        assertThat(generate(Map.of("designKit", key + "@1")).get("frontend/src/index.css")).contains(" #0f766e;");
        assertThat(generate(Map.of("designKit", key)).get("frontend/src/index.css")).contains(" #b91c1c;");
    }

    @Test
    void aKitThatCannotGenerateIsABadRequest() throws Exception {
        String key = acmeKit("#0f766e");
        assertBadRequest(Map.of("designKit", "no-such-kit"), "Unknown design kit 'no-such-kit'");
        assertBadRequest(Map.of("designKit", key + "@9"), "has no version 9");
        assertBadRequest(Map.of("designKit", key + "@draft"), "drafts can only be tried");
        // (react-menora-digital-crud would fit: it stands for the menora-digital kit, on the same base set.)
        assertBadRequest(Map.of("designKit", key, "frontendTemplateSet", "spring-jpa-crud"),
                "styles the frontend set 'react-tailwind-crud', not 'spring-jpa-crud'");
        assertBadRequest(Map.of("designKit", "Bad Key"), "is not a design kit");
    }

    /** A published clone of the default kit with a brand colour, dark canvas, font, CSS and assets. */
    private String acmeKit(String brand) {
        String key = "acme-" + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        service.create(new DesignKitService.CreateKit(key, "Acme", null, "tailwind-default"), "test");
        DesignTokens tokens = withBrand(service.draft(key).tokens(), brand);
        List<DesignTokens.Token> list = new ArrayList<>();
        for (DesignTokens.Token t : tokens.tokens()) {
            list.add(t.name().equals("color-canvas") ? new DesignTokens.Token(t.name(), t.type(), t.value(), "#101820", t.group()) : t);
        }
        KitManifest manifest = new KitManifest(List.of(),
                new KitManifest.Fonts("@fontsource/roboto", "^5.0.8", List.of(400, 700), null), null);
        service.saveDraft(key, new DesignKitService.SaveDraft(new DesignTokens(DesignTokens.SCHEMA, list), manifest,
                service.draft(key).css() + "\n.acme-banner { color: var(--color-brand); }\n", null));
        service.putAsset(key, "src/assets/fonts/brand.woff2", "FONT", "{\"family\":\"Brand Sans\",\"weight\":400}", WOFF2);
        service.putAsset(key, "public/acme-logo.svg", "LOGO", null, SVG);
        service.publish(key, "first", "test");
        return key;
    }

    static DesignTokens withBrand(DesignTokens tokens, String brand) {
        return new DesignTokens(DesignTokens.SCHEMA, tokens.tokens().stream()
                .map(t -> t.name().equals("color-brand") ? new DesignTokens.Token(t.name(), t.type(), brand, t.dark(), t.group()) : t)
                .toList());
    }

    private Map<String, String> generate(Map<String, Object> extra) throws Exception {
        Map<String, Object> body = body(extra);
        ResponseEntity<byte[]> r = FullstackExampleRequests.post(rest, body);
        assertThat(r.getStatusCode()).as(r.getBody() == null ? "" : new String(r.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Map<String, String> files = new java.util.TreeMap<>();
        FullstackExampleRequests.unzip(r.getBody()).forEach((path, bytes) ->
                files.put(path.substring(path.indexOf('/') + 1), new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n")));
        return files;
    }

    private void assertBadRequest(Map<String, Object> extra, String message) {
        ResponseEntity<byte[]> r = FullstackExampleRequests.post(rest, body(extra));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new String(r.getBody(), StandardCharsets.UTF_8)).contains(message);
    }

    private static Map<String, Object> body(Map<String, Object> extra) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("artifactId", "kitted");
        body.put("packageName", "com.menora.kitted");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "Note", "fields", List.of(
                Map.of("name", "id", "type", "Long", "primaryKey", true, "generated", true),
                Map.of("name", "title", "type", "String")))));
        body.putAll(extra);
        return body;
    }

    private static byte[] woff2() {
        byte[] b = new byte[64];
        b[0] = 'w';
        b[1] = 'O';
        b[2] = 'F';
        b[3] = '2';
        return b;
    }
}
