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
 *   <li>{@code defaults} — what the generator UI switches on when the kit is picked;</li>
 *   <li>{@code traits} — what the generator UI needs to know about the kit's components: which
 *       look its fallback sketch draws ({@code tailwind} or {@code menora}), and whether the
 *       kit's {@code Shell} honours the request's {@code nav} options (when it does not, the
 *       UI does not offer them). Absent = a tailwind-like shell that honours them.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KitManifest(List<String> imports, Fonts fonts, Defaults defaults, Traits traits) {

    public KitManifest {
        imports = imports == null ? List.of() : List.copyOf(imports);
    }

    public KitManifest(List<String> imports, Fonts fonts, Defaults defaults) {
        this(imports, fonts, defaults, null);
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

    /** The sketches the layout designer can draw a kit with (until the real app has compiled). */
    public static final List<String> SKETCHES = List.of("tailwind", "menora");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Traits(String sketch, Boolean navOptions) {
    }
}
