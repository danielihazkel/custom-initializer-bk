package com.menora.initializr.design;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a kit version before it is saved or published. Token values are spliced into the
 * generated {@code src/index.css}, so they are held to a small grammar that cannot leave the
 * declaration: no {@code ;}, braces, {@code @}, {@code /}, backslash, {@code url(} or colon — only
 * colour/length/shadow/font syntax, {@code var(--x)} and palette references.
 */
public final class TokenValidator {

    private TokenValidator() {
    }

    /** {@code {palette.primary}} or {@code {palette.accent|#16a34a}}. */
    static final Pattern PALETTE_REF = Pattern.compile("\\{palette\\.(primary|secondary|accent|error)(?:\\|(#[0-9a-fA-F]{3,8}))?}");
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9#.,%()'\" _-]+$");
    private static final Pattern VAR = Pattern.compile("var\\(--[a-z0-9-]+\\)");
    private static final Pattern COLOR_START = Pattern.compile(
            "^(#[0-9a-fA-F]{3,8}|(rgb|rgba|hsl|hsla|oklch|oklab|color-mix|var)\\(.*|white|black|transparent|currentColor)$");
    private static final Pattern FONT = Pattern.compile("^(('[^']+'|\"[^\"]+\"|-?[A-Za-z][A-Za-z0-9 -]*)(, ?|$))+$");
    private static final Pattern IMPORT = Pattern.compile("^\\./shared/ui/[a-z0-9_-]+(/[a-z0-9_-]+)*\\.css$");
    private static final Pattern FONT_PACKAGE = Pattern.compile("^@fontsource(-variable)?/[a-z0-9-]+$");
    private static final Pattern SEMVER_RANGE = Pattern.compile("^[\\^~]?\\d+\\.\\d+\\.\\d+$");
    static final int MAX_VALUE = 300;
    static final int MAX_TOKENS = 200;
    static final int MAX_CSS = 64 * 1024;

    /** Every problem with the version's tokens, manifest and kit CSS; empty when it may be published. */
    public static List<String> validate(DesignTokens tokens, KitManifest manifest, String kitCss) {
        List<String> errors = new ArrayList<>();
        if (tokens == null) {
            errors.add("tokens are required");
            return errors;
        }
        if (!DesignTokens.SCHEMA.equals(tokens.schema())) {
            errors.add("tokens: $schema must be '" + DesignTokens.SCHEMA + "'");
        }
        if (tokens.tokens().size() > MAX_TOKENS) errors.add("tokens: at most " + MAX_TOKENS + " tokens");
        Set<String> seen = new HashSet<>();
        for (DesignTokens.Token t : tokens.tokens()) {
            String name = t.name() == null ? "" : t.name();
            if (!TokenSchema.NAME.matcher(name).matches()) {
                errors.add("token '" + name + "': not a Tailwind theme variable name (color-*, font-*, radius-*, shadow-*, …)");
                continue;
            }
            if (!seen.add(name)) errors.add("token '" + name + "' is defined twice");
            String type = TokenSchema.typeOf(name);
            if (t.type() != null && !t.type().equals(type)) {
                errors.add("token '" + name + "': type must be '" + type + "'");
            }
            checkValue(name, "value", t.value(), type, errors);
            if (t.dark() != null) checkValue(name, "dark value", t.dark(), type, errors);
            if (t.group() != null && (t.group().length() > 60 || t.group().contains("*/"))) {
                errors.add("token '" + name + "': group must be at most 60 characters");
            }
        }
        for (TokenSchema.Required r : TokenSchema.REQUIRED) {
            if (!seen.contains(r.name())) errors.add("token '" + r.name() + "' is required (" + r.description() + ")");
        }
        if (manifest != null) checkManifest(manifest, errors);
        if (kitCss != null) checkCss(kitCss, errors);
        return errors;
    }

    private static void checkValue(String name, String what, String value, String type, List<String> errors) {
        String where = "token '" + name + "' " + what;
        if (value == null || value.isBlank()) {
            errors.add(where + " is required");
            return;
        }
        if (value.length() > MAX_VALUE) {
            errors.add(where + " is longer than " + MAX_VALUE + " characters");
            return;
        }
        Matcher ref = PALETTE_REF.matcher(value);
        StringBuilder plain = new StringBuilder();
        while (ref.find()) {
            String key = ref.group(1);
            boolean optional = key.equals("accent") || key.equals("error");
            if (optional && ref.group(2) == null) {
                errors.add(where + ": {palette." + key + "} needs a fallback ({palette." + key + "|#rrggbb}) — a palette may not define it");
            } else if (!optional && ref.group(2) != null) {
                errors.add(where + ": {palette." + key + "} takes no fallback — every palette defines it");
            }
            ref.appendReplacement(plain, "#000000");
        }
        ref.appendTail(plain);
        String stripped = VAR.matcher(plain.toString()).replaceAll("var(--x)");
        if (stripped.contains("{") || stripped.contains("}")) {
            errors.add(where + ": only {palette.primary|secondary|accent|error} references are allowed");
            return;
        }
        if (!SAFE.matcher(stripped).matches() || stripped.contains("url(") || stripped.contains("expression")) {
            errors.add(where + ": contains characters a CSS value here may not use (; { } @ / \\ : url())");
            return;
        }
        if (!balanced(stripped)) {
            errors.add(where + ": unbalanced parentheses or quotes");
            return;
        }
        String v = stripped.trim();
        switch (type) {
            case "color" -> {
                if (!COLOR_START.matcher(v).matches()) {
                    errors.add(where + ": not a colour (#hex, rgb(), hsl(), color-mix(), var(), a palette reference)");
                }
            }
            case "font" -> {
                if (!FONT.matcher(v).matches()) errors.add(where + ": not a font-family list ('Inter', Arial, sans-serif)");
            }
            default -> { /* lengths, shadows, numbers: the character rules above are the check */ }
        }
    }

    private static boolean balanced(String s) {
        int depth = 0;
        boolean single = false, dbl = false;
        for (char c : s.toCharArray()) {
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (!single && !dbl && c == '(') depth++;
            else if (!single && !dbl && c == ')' && --depth < 0) return false;
        }
        return depth == 0 && !single && !dbl;
    }

    private static void checkManifest(KitManifest m, List<String> errors) {
        for (String imp : m.imports()) {
            if (imp == null || !IMPORT.matcher(imp).matches()) {
                errors.add("manifest: import '" + imp + "' must be a stylesheet under ./shared/ui/ (./shared/ui/<kit>/tokens.css)");
            }
        }
        KitManifest.Fonts f = m.fonts();
        if (f != null) {
            if (f.packageName() == null || !FONT_PACKAGE.matcher(f.packageName()).matches()) {
                errors.add("manifest: fonts.package must be an @fontsource package (@fontsource/inter)");
            }
            if (f.version() == null || !SEMVER_RANGE.matcher(f.version()).matches()) {
                errors.add("manifest: fonts.version must be a version like ^5.0.18");
            }
            if (f.weights().isEmpty()) errors.add("manifest: fonts.weights needs at least one weight");
            for (Integer w : f.weights()) {
                if (w == null || w < 100 || w > 900 || w % 100 != 0) errors.add("manifest: font weight " + w + " must be 100–900 in steps of 100");
            }
            if (f.comment() != null && (f.comment().length() > 200 || f.comment().contains("\n"))) {
                errors.add("manifest: fonts.comment must be one line of at most 200 characters");
            }
        }
        KitManifest.Defaults d = m.defaults();
        if (d != null && d.locale() != null && !d.locale().equals("en") && !d.locale().equals("he")) {
            errors.add("manifest: defaults.locale must be 'en' or 'he'");
        }
    }

    private static void checkCss(String css, List<String> errors) {
        if (css.length() > MAX_CSS) errors.add("kit CSS is larger than " + (MAX_CSS / 1024) + " KB");
        String lower = css.toLowerCase();
        if (lower.contains("@import")) errors.add("kit CSS may not @import (the generated app is offline; add a manifest import instead)");
        if (lower.matches("(?s).*url\\(\\s*['\"]?(https?:|//).*")) errors.add("kit CSS may not load remote URLs (the generated app is offline)");
        if (!balancedBraces(css)) errors.add("kit CSS has unbalanced braces");
    }

    private static boolean balancedBraces(String css) {
        int depth = 0;
        for (char c : css.toCharArray()) {
            if (c == '{') depth++;
            else if (c == '}' && --depth < 0) return false;
        }
        return depth == 0;
    }
}
