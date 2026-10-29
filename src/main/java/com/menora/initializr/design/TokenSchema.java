package com.menora.initializr.design;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The tokens the generator's own files use — the shared components and screens of every frontend
 * set draw with {@code bg-brand}, {@code text-fg}, {@code border-border}, … — so every kit must
 * define them. A kit may add more tokens in Tailwind's theme namespaces (its own components can
 * use them), never anything else.
 */
public final class TokenSchema {

    private TokenSchema() {
    }

    public record Required(String name, String type, String description) {
    }

    public static final List<Required> REQUIRED = List.of(
            new Required("color-brand", "color", "Identity colour: active nav, focus, charts, links"),
            new Required("color-brand-deep", "color", "Brand hover/pressed shade"),
            new Required("color-primary", "color", "Primary action fill (New, Save, Edit)"),
            new Required("color-primary-deep", "color", "Primary action hover"),
            new Required("color-on-primary", "color", "Text on a primary action"),
            new Required("color-ink", "color", "Dark band of the classic dashboard's heading"),
            new Required("color-ink-soft", "color", "Sidebar active/hover item; end of the dashboard heading band"),
            new Required("color-gold", "color", "Highlight accent: active nav marker and icon"),
            new Required("color-success", "color", "Positive state"),
            new Required("color-danger", "color", "Destructive / error state"),
            new Required("color-canvas", "color", "Page ground"),
            new Required("color-surface", "color", "Cards, tables, drawers"),
            new Required("color-surface-2", "color", "Subtle fills, hover rows"),
            new Required("color-fg", "color", "Body text"),
            new Required("color-muted", "color", "Secondary text"),
            new Required("color-border", "color", "Hairlines and borders"),
            new Required("color-ring", "color", "Focus ring"),
            new Required("font-sans", "font", "The app's typeface stack"));

    /** A token the base set's own {@code Shell} paints with, filled in when a kit does not define it. */
    public record Defaulted(String name, String type, String description, String value) {
    }

    /**
     * The shell's colours. A kit that keeps its base set's {@code Shell} and does not name one of
     * these gets its default (which is what the shell drew before they existed, so a kit made
     * earlier keeps its look); a kit with its own {@code Shell} paints with whatever it likes.
     */
    public static final String SHELL_SLOT = "src/shared/ui/Shell.tsx";

    public static final List<Defaulted> SHELL = List.of(
            new Defaulted("color-shell", "color", "Shell band: the sidebar, or the top bar", "var(--color-brand)"),
            new Defaulted("color-shell-end", "color", "Where the shell band's gradient ends (its bottom edge)", "var(--color-gold)"),
            new Defaulted("color-on-shell", "color", "Text and hairlines on the shell band", "#ffffff"),
            new Defaulted("color-on-shell-muted", "color", "Nav links and section labels on the shell band", "#cbd5e1"));

    /** The kit's tokens plus the shell defaults it lacks, unless it brings its own {@code Shell}. */
    public static DesignTokens withShellDefaults(DesignTokens tokens, boolean ownShell) {
        if (ownShell) return tokens;
        java.util.Set<String> named = new java.util.HashSet<>();
        for (DesignTokens.Token t : tokens.tokens()) named.add(t.name());
        List<DesignTokens.Token> missing = SHELL.stream()
                .filter(d -> !named.contains(d.name()))
                .map(d -> new DesignTokens.Token(d.name(), d.type(), d.value(), null, "Shell"))
                .toList();
        if (missing.isEmpty()) return tokens;
        List<DesignTokens.Token> all = new java.util.ArrayList<>(tokens.tokens());
        all.addAll(missing);
        return new DesignTokens(tokens.schema(), all);
    }

    /** Tailwind v4 theme namespaces a kit token may live in. */
    public static final Pattern NAME = Pattern.compile(
            "^(color|font|text|radius|shadow|spacing|leading|tracking|font-weight|ease|blur|drop-shadow|inset-shadow)"
                    + "-[a-z0-9]+(-[a-z0-9]+)*$");

    public static final List<String> TYPES = List.of("color", "font", "radius", "shadow", "length", "number");

    /** The type a token in a namespace must have. */
    public static String typeOf(String name) {
        if (name.startsWith("color-")) return "color";
        if (name.startsWith("font-weight-")) return "number";
        if (name.startsWith("font-")) return "font";
        if (name.startsWith("radius-")) return "radius";
        if (name.startsWith("shadow-") || name.startsWith("drop-shadow-") || name.startsWith("inset-shadow-")) return "shadow";
        return "length";
    }
}
