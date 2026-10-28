package com.menora.initializr.design;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;

/**
 * Writes a kit's {@code src/index.css}: Tailwind, the kit's stylesheet imports, class-based dark
 * mode, the tokens as {@code @theme}, their dark values as {@code .dark}, the kit's
 * {@code @font-face} rules and its own CSS — always in this layout, so two kits differ only in
 * their content.
 *
 * <p>Palette references are resolved by a {@link Palette} strategy:
 * <ul>
 *   <li>{@link #values} — the selected palette's colours, for a generated project;</li>
 *   <li>{@link #MUSTACHE} — the mustache tags the legacy template sets render (the built-in kits'
 *       {@code index.css.mustache} is this writer's output in this mode, pinned by
 *       {@code BuiltInKitTemplateSyncTest});</li>
 *   <li>{@link #VARIABLES} — {@code var(--palette-*)} for the live preview, so a colour edit is one
 *       {@code :root} write rather than a recompile.</li>
 * </ul>
 */
public final class KitCssWriter {

    private KitCssWriter() {
    }

    /** Resolves one palette reference: key ({@code primary}…) and its fallback (may be null). */
    public interface Palette {
        String resolve(String key, String fallback);
    }

    public static final Palette MUSTACHE = (key, fallback) -> {
        if (fallback == null) return "{{palette." + key + "}}";
        // Only accent/error take a fallback (TokenValidator); FrontendMustacheContext sets their flags.
        String section = "hasPalette" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        return "{{#" + section + "}}{{palette." + key + "}}{{/" + section + "}}{{^" + section + "}}" + fallback + "{{/" + section + "}}";
    };

    public static final Palette VARIABLES = (key, fallback) ->
            fallback == null ? "var(--palette-" + key + ")" : "var(--palette-" + key + ", " + fallback + ")";

    /** The palette's own colours; a blank accent/error takes the reference's fallback. */
    public static Palette values(String primary, String secondary, String accent, String error) {
        return (key, fallback) -> {
            String v = switch (key) {
                case "primary" -> primary;
                case "secondary" -> secondary;
                case "accent" -> accent;
                default -> error;
            };
            return v == null || v.isBlank() ? fallback : v;
        };
    }

    /** A font file the kit ships, declared with {@code @font-face}. */
    public record FontFace(String family, int weight, String style, String url, String format) {
    }

    public static String write(DesignTokens tokens, KitManifest manifest, List<FontFace> fontFaces, String kitCss,
                               Palette palette) {
        StringBuilder out = new StringBuilder("@import \"tailwindcss\";\n");
        for (String imp : manifest.imports()) out.append("@import '").append(imp).append("';\n");
        out.append("\n/* Class-based dark mode (toggled by adding `dark` to <html>). */\n")
                .append("@custom-variant dark (&:where(.dark, .dark *));\n\n")
                .append("/*\n")
                .append(" * Design tokens (Tailwind v4 @theme). Each becomes utilities — bg-brand, text-fg, border-border,\n")
                .append(" * rounded-xl, shadow-card, font-sans, … — and the `.dark` block below re-points them for dark mode.\n")
                .append(" */\n")
                .append("@theme {\n");
        block(out, tokens.tokens(), DesignTokens.Token::value, palette);
        out.append("}\n");
        List<DesignTokens.Token> dark = tokens.tokens().stream().filter(t -> t.dark() != null).toList();
        if (!dark.isEmpty()) {
            out.append("\n.dark {\n");
            block(out, dark, DesignTokens.Token::dark, palette);
            out.append("}\n");
        }
        for (FontFace f : fontFaces) {
            out.append("\n@font-face {\n")
                    .append("  font-family: '").append(f.family()).append("';\n")
                    .append("  src: url('").append(f.url()).append("') format('").append(f.format()).append("');\n")
                    .append("  font-weight: ").append(f.weight()).append(";\n")
                    .append("  font-style: ").append(f.style()).append(";\n")
                    .append("  font-display: swap;\n")
                    .append("}\n");
        }
        String css = kitCss == null ? "" : kitCss.replace("\r\n", "\n").strip();
        if (!css.isEmpty()) out.append('\n').append(css).append('\n');
        return out.toString();
    }

    /** Declarations aligned on their values; a blank line where the group changes. */
    private static void block(StringBuilder out, List<DesignTokens.Token> tokens,
                              Function<DesignTokens.Token, String> value, Palette palette) {
        int width = tokens.stream().mapToInt(t -> t.name().length()).max().orElse(0);
        String group = null;
        boolean first = true;
        for (DesignTokens.Token t : tokens) {
            String g = t.group() == null ? "" : t.group();
            if (!first && !g.equals(group)) out.append('\n');
            group = g;
            first = false;
            out.append("  --").append(t.name()).append(':')
                    .append(" ".repeat(width - t.name().length() + 1))
                    .append(resolve(value.apply(t), palette)).append(";\n");
        }
    }

    static String resolve(String value, Palette palette) {
        Matcher m = TokenValidator.PALETTE_REF.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(palette.resolve(m.group(1), m.group(2))));
        m.appendTail(out);
        return out.toString();
    }
}
