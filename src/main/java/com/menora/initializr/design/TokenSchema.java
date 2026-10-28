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
            new Required("color-ink", "color", "Dark chrome (sidebar, header band)"),
            new Required("color-ink-soft", "color", "Raised dark chrome"),
            new Required("color-gold", "color", "Highlight accent"),
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
