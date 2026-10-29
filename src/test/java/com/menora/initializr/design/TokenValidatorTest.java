package com.menora.initializr.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The token grammar keeps every value inside its declaration, and the CSS writer's layout. */
class TokenValidatorTest {

    private final BuiltInKits.Loaded base = BuiltInKits.load("tailwind-default", new ObjectMapper());

    @Test
    void builtInKitsAreValid() {
        for (String key : BuiltInKits.KEYS) {
            BuiltInKits.Loaded k = BuiltInKits.load(key, new ObjectMapper());
            assertThat(TokenValidator.validate(k.definition().tokens(), k.definition().manifest(), k.css())).as(key).isEmpty();
        }
    }

    @Test
    void valuesCannotEscapeTheirDeclaration() {
        for (String bad : List.of("red; } body { color: red", "url(http://x/y.png)", "#fff /* x */", "@import 'x'",
                "red !important", "expression(alert(1))", "{other.token}", "a\\62", "var(--x); color: red", "rgb(1,2,3")) {
            assertThat(problems("color-brand", bad)).as(bad).isNotEmpty();
        }
    }

    @Test
    void acceptsColoursFontsAndPaletteReferences() {
        for (String ok : List.of("#0f766e", "#725a891f", "rgba(55, 56, 78, 0.10)", "color-mix(in srgb, {palette.primary} 85%, black)",
                "{palette.accent|#16a34a}", "var(--color-ink)", "transparent")) {
            assertThat(problems("color-brand", ok)).as(ok).isEmpty();
        }
        assertThat(problems("color-brand", "{palette.accent}")).singleElement().asString().contains("needs a fallback");
        assertThat(problems("color-brand", "{palette.primary|#fff}")).singleElement().asString().contains("takes no fallback");
        assertThat(problems("color-brand", "12px")).singleElement().asString().contains("not a colour");
        assertThat(problems("font-sans", "'Inter', -apple-system, sans-serif")).isEmpty();
    }

    @Test
    void namesAreTailwindThemeVariablesAndRequiredOnesStay() {
        List<DesignTokens.Token> tokens = new ArrayList<>(base.definition().tokens().tokens());
        tokens.removeIf(t -> t.name().equals("color-fg"));
        tokens.add(new DesignTokens.Token("--color-x", null, "#fff", null, null));
        tokens.add(new DesignTokens.Token("radius-card", null, "12px", null, null));
        List<String> problems = TokenValidator.validate(new DesignTokens(DesignTokens.SCHEMA, tokens), null, null);
        assertThat(problems).anyMatch(p -> p.contains("'--color-x'")).anyMatch(p -> p.contains("'color-fg' is required"))
                .noneMatch(p -> p.contains("radius-card"));
    }

    @Test
    void manifestAndKitCssStayOffline() {
        KitManifest manifest = new KitManifest(List.of("https://cdn/x.css"),
                new KitManifest.Fonts("google-fonts", "latest", List.of(450), "two\nlines"), new KitManifest.Defaults("fr", null));
        List<String> problems = TokenValidator.validate(base.definition().tokens(), manifest,
                "@import url('https://fonts.googleapis.com/x');\n.a { background: url(//cdn/x.png) }");
        assertThat(problems).hasSizeGreaterThanOrEqualTo(7)
                .anyMatch(p -> p.contains("import 'https://cdn/x.css'"))
                .anyMatch(p -> p.contains("@fontsource package"))
                .anyMatch(p -> p.contains("may not @import"))
                .anyMatch(p -> p.contains("remote URLs"));
    }

    @Test
    void cssWriterLaysTokensOutAndResolvesThePalette() {
        DesignTokens tokens = new DesignTokens(DesignTokens.SCHEMA, List.of(
                new DesignTokens.Token("color-brand", null, "{palette.primary}", "#a393ff", "Brand"),
                new DesignTokens.Token("color-success", null, "{palette.accent|#16a34a}", null, "Brand"),
                new DesignTokens.Token("radius-xl", null, "20px", null, "Radius")));
        KitManifest manifest = new KitManifest(List.of("./shared/ui/acme/tokens.css"), null, null);
        String css = KitCssWriter.write(tokens, manifest, List.of(), ".x { color: red; }\r\n",
                KitCssWriter.values("#111111", "#222222", null, null));
        assertThat(css).startsWith("@import \"tailwindcss\";\n@import './shared/ui/acme/tokens.css';\n")
                .contains("@theme {\n  --color-brand:   #111111;\n  --color-success: #16a34a;\n\n  --radius-xl:     20px;\n}\n")
                .contains("\n.dark {\n  --color-brand: #a393ff;\n}\n")
                .endsWith("\n.x { color: red; }\n");
        assertThat(KitCssWriter.write(tokens, manifest, List.of(), "", KitCssWriter.VARIABLES))
                .contains("--color-brand:   var(--palette-primary);")
                .contains("--color-success: var(--palette-accent, #16a34a);");
    }

    @Test
    void aKitKeepingTheBaseShellGetsTheShellTokensItLacks() {
        DesignTokens older = new DesignTokens(DesignTokens.SCHEMA, List.of(
                new DesignTokens.Token("color-brand", null, "#123456", null, "Brand"),
                new DesignTokens.Token("color-on-shell", null, "#000000", null, "Shell")));
        DesignTokens filled = TokenSchema.withShellDefaults(older, false);
        assertThat(filled.tokens()).extracting(DesignTokens.Token::name)
                .containsExactly("color-brand", "color-on-shell", "color-shell", "color-shell-end", "color-on-shell-muted");
        // What the shell drew before the tokens existed: the brand → gold band.
        assertThat(filled.tokens().get(2).value()).isEqualTo("var(--color-brand)");
        assertThat(filled.tokens().get(3).value()).isEqualTo("var(--color-gold)");
        assertThat(TokenSchema.withShellDefaults(older, true)).isSameAs(older);
        // The built-in default kit names them all, so it gains nothing.
        assertThat(TokenSchema.withShellDefaults(base.definition().tokens(), false)).isSameAs(base.definition().tokens());
    }

    private static List<String> problems(String name, String value) {
        List<DesignTokens.Token> tokens = new ArrayList<>();
        for (TokenSchema.Required r : TokenSchema.REQUIRED) {
            tokens.add(new DesignTokens.Token(r.name(), null, r.type().equals("font") ? "Arial" : "#ffffff", null, null));
        }
        tokens.removeIf(t -> t.name().equals(name));
        tokens.add(new DesignTokens.Token(name, null, value, null, null));
        return TokenValidator.validate(new DesignTokens(DesignTokens.SCHEMA, tokens), null, null);
    }
}
