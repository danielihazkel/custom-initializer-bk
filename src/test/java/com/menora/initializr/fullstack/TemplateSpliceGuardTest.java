package com.menora.initializr.fullstack;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A label is whatever the user typed — Hebrew abbreviations carry {@code '} and {@code "} — so a
 * frontend template may never splice one raw into TypeScript or JSX. {@link
 * EntityScaffoldContext#putText} puts each display text with its escaped forms
 * ({@code <key>Ts} for a single-quoted literal, {@code <key>Attr} for a double-quoted JSX
 * attribute, {@code <key>Jsx} for JSX children, {@code <key>Comment} for a block comment); this
 * test fails on a template that reaches for the raw key instead.
 */
class TemplateSpliceGuardTest {

    private static final List<String> FRONTEND_SETS = List.of("react-tailwind-crud", "react-menora-digital-crud");

    /** The keys {@code putText} fills with user text. A section tag ({@code {{#key}}}) only tests
     *  for presence and is fine; a variable tag renders the raw text. */
    private static final Pattern RAW_TEXT = Pattern.compile(
            "\\{\\{\\{?\\s*(label|entityLabel|entityLabelPlural|dashboardTitle|dashboardOverview)\\s*\\}?\\}\\}");

    @Test
    void frontendTemplatesNeverSpliceRawDisplayText() throws Exception {
        List<String> offences = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        int scanned = 0;
        for (String set : FRONTEND_SETS) {
            for (Resource r : resolver.getResources("classpath:templates/fullstack/" + set + "/*")) {
                String name = r.getFilename();
                if (name == null || name.equals("manifest.json")) continue;
                String text = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                scanned++;
                String[] lines = text.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    Matcher m = RAW_TEXT.matcher(lines[i]);
                    while (m.find()) {
                        offences.add(set + "/" + name + ":" + (i + 1) + " " + m.group()
                                + " — use the Ts / Attr / Jsx / Comment form");
                    }
                }
            }
        }
        assertThat(scanned).as("templates scanned").isGreaterThan(80);
        assertThat(offences).as("raw display text spliced into generated code").isEmpty();
    }

    @Test
    void escapesForEachPlaceATemplateSplicesText() {
        // A single-quoted TS literal: backslash, apostrophe and line breaks.
        assertThat(EntityScaffoldContext.tsLiteralBody("מס' \\ a\r\nb")).isEqualTo("מס\\' \\\\ a\\nb");
        assertThat(EntityScaffoldContext.tsLiteralBody("מע\"מ")).isEqualTo("מע\"מ");
        // A JSX attribute reads character references, not backslash escapes.
        assertThat(EntityScaffoldContext.escapeJsxAttr("מע\"מ")).isEqualTo("מע&quot;מ");
        assertThat(EntityScaffoldContext.escapeJsxAttr("it's a \\ <b> {c}")).isEqualTo("it's a \\ <b> {c}");
        // Only an ampersand that would start a reference is escaped: plain text keeps its bytes.
        assertThat(EntityScaffoldContext.escapeJsxAttr("Terms & Conditions")).isEqualTo("Terms & Conditions");
        assertThat(EntityScaffoldContext.escapeJsxAttr("a &amp; b &#39; c")).isEqualTo("a &amp;amp; b &amp;#39; c");
        // JSX children: braces start an expression, angle brackets a tag.
        assertThat(EntityScaffoldContext.escapeJsxText("a {b} <c> 'd' \"e\""))
                .isEqualTo("a &#123;b&#125; &lt;c&gt; 'd' \"e\"");
        assertThat(EntityScaffoldContext.escapeJsxText("R&D &lt;")).isEqualTo("R&D &amp;lt;");
    }
}
