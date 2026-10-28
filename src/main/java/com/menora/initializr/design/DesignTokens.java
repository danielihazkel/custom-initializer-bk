package com.menora.initializr.design;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A kit's design tokens, in the order they are written to {@code src/index.css}. Each token is a
 * Tailwind v4 theme variable without its leading dashes ({@code color-brand} → {@code --color-brand}
 * → {@code bg-brand}, {@code text-brand}, …). {@code dark} is the value under the {@code .dark}
 * class; {@code group} only lays the CSS out (a blank line where it changes) and groups the editor.
 *
 * <p>A value may reference the selected colour palette: {@code {palette.primary}},
 * {@code {palette.secondary}}, and — because a palette may lack them — {@code {palette.accent|#16a34a}}
 * / {@code {palette.error|#dc2626}} with a fallback. See {@link TokenValidator} for the grammar.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DesignTokens(@JsonProperty("$schema") String schema, List<Token> tokens) {

    public static final String SCHEMA = "menora-design-tokens/1";

    public DesignTokens {
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Token(String name, String type, String value, String dark, String group) {
    }
}
