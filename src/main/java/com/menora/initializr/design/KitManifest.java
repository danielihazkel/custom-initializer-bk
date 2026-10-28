package com.menora.initializr.design;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The non-token parts of a kit version:
 * <ul>
 *   <li>{@code imports} — stylesheets {@code src/index.css} imports after Tailwind
 *       ({@code ./shared/ui/menora/tokens.css}), relative to {@code src/};</li>
 *   <li>{@code fonts} — the self-hosted {@code @fontsource} package the app imports in
 *       {@code src/main.tsx}, with its weights (an uploaded font file is an asset instead);</li>
 *   <li>{@code defaults} — what the generator UI switches on when the kit is picked.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KitManifest(List<String> imports, Fonts fonts, Defaults defaults) {

    public KitManifest {
        imports = imports == null ? List.of() : List.copyOf(imports);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fonts(@JsonProperty("package") String packageName, String version, List<Integer> weights,
                        String comment) {
        public Fonts {
            weights = weights == null ? List.of() : List.copyOf(weights);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Defaults(String locale, Boolean rtl) {
    }
}
