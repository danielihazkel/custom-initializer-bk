package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;

import java.util.Locale;
import java.util.Map;

/**
 * Text spliced into generated code: TS, JSX and Java literal escapes, and the humanized
 * labels made from identifiers.
 * Part of {@link EntityScaffoldContext}, split out by area.
 */
public final class TemplateText {

    private TemplateText() {}

    /** {@code markPaid} → "Mark paid": a transition's name as words, when it has no label. */
    static String humanizeCamel(String name) {
        String words = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return words.isEmpty() ? name : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** {@code submitForReview} → "Submit for review". */
    static String humanizeIdentifier(String name) {
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    static String tsString(String s) {
        return "'" + escapeTsSingleQuoted(s).replace("\r", "").replace("\n", "\\n") + "'";
    }

    /** Escapes a string for embedding in a Java or JS double-quoted string literal
     *  (backslash and double-quote only — both languages share C-style escaping). */
    static String escapeStringLiteral(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Escapes a string for embedding in a single-quoted TS/JS literal (backslash and apostrophe —
     *  Hebrew labels often carry a geresh). */
    static String escapeTsSingleQuoted(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** What the generated UI calls a relation: its label, else its field name in PascalCase (what
     *  every relation was called before it could carry one). */
    static String labelOf(RelationDefinition r) {
        return r.label() != null && !r.label().isBlank() ? r.label() : Naming.toPascalCase(r.fieldName());
    }

    /** Matches an {@code &} that JSX would read as the start of a character reference. */
    private static final java.util.regex.Pattern JSX_ENTITY_START =
            java.util.regex.Pattern.compile("&(?=#?[A-Za-z0-9]+;)");

    /** User text as the body of a single-quoted TS literal: {@link #escapeTsSingleQuoted} plus
     *  line breaks, which a literal cannot hold. */
    static String tsLiteralBody(String s) {
        return escapeTsSingleQuoted(s).replace("\r", "").replace("\n", "\\n");
    }

    /** User text inside a double-quoted JSX attribute ({@code label="…"}). JSX reads no backslash
     *  escapes there, only character references — so a double quote becomes {@code &quot;} and an
     *  {@code &} that would start a reference {@code &amp;}. Text without those keeps its bytes. */
    static String escapeJsxAttr(String s) {
        return JSX_ENTITY_START.matcher(s).replaceAll("&amp;")
                .replace("\"", "&quot;").replace("\r", "").replace("\n", " ");
    }

    /** User text as JSX children ({@code <h1>…</h1>}): braces and angle brackets would be read as
     *  an expression or a tag, so they become character references. */
    static String escapeJsxText(String s) {
        return JSX_ENTITY_START.matcher(s).replaceAll("&amp;")
                .replace("{", "&#123;").replace("}", "&#125;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r", "").replace("\n", " ");
    }

    /** Puts a display text under {@code key} (raw, for places that take any text) together with
     *  the forms a template may splice: {@code <key>Ts} inside a single-quoted TS literal,
     *  {@code <key>Attr} inside a double-quoted JSX attribute, {@code <key>Jsx} as JSX children
     *  and {@code <key>Comment} inside a block comment. A template never splices the raw text
     *  into code — {@code TemplateSpliceGuardTest} checks that. */
    public static void putText(Map<String, Object> view, String key, String text) {
        view.put(key, text);
        view.put(key + "Ts", text == null ? null : tsLiteralBody(text));
        view.put(key + "Attr", text == null ? null : escapeJsxAttr(text));
        view.put(key + "Jsx", text == null ? null : escapeJsxText(text));
        view.put(key + "Comment", text == null ? null
                : text.replace("*/", "* /").replace("\r", "").replace("\n", " "));
    }

    /** Fallback display label for an enum constant without a user label:
     *  {@code IN_PROGRESS} -> {@code In progress}. */
    static String humanizeConstant(String constant) {
        String words = constant.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        if (words.isEmpty()) return constant;
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Escapes for a Java double-quoted literal (also newlines/tabs, unlike {@link #escapeStringLiteral},
     *  because a TEXT default may span lines). */
    static String escapeJavaLiteral(String s) {
        return escapeStringLiteral(s).replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
