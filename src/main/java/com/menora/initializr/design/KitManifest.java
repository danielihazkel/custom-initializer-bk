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
 *   <li>{@code navIcons} — icons the kit adds to the ones a page may put in the nav: components
 *       named {@code <Name>Icon}, exported by one of its helper files ({@code module}); the
 *       generated shell imports the ones in use from there instead of lucide-react.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KitManifest(List<String> imports, Fonts fonts, Defaults defaults, Traits traits, NavIcons navIcons) {

    public KitManifest {
        imports = imports == null ? List.of() : List.copyOf(imports);
    }

    public KitManifest(List<String> imports, Fonts fonts, Defaults defaults) {
        this(imports, fonts, defaults, null, null);
    }

    public KitManifest(List<String> imports, Fonts fonts, Defaults defaults, Traits traits) {
        this(imports, fonts, defaults, traits, null);
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

    /** A kit nav icon's name: a component name ending in {@code Icon}, so it never meets a lucide one. */
    public static final java.util.regex.Pattern NAV_ICON_NAME = java.util.regex.Pattern.compile("^[A-Z][A-Za-z0-9]{0,38}Icon$");
    public static final int MAX_NAV_ICONS = 24;

    /** {@code module}: the helper file that exports every name ({@code src/shared/ui/acme/navIcons.tsx}). */
    public record NavIcons(String module, List<String> names) {
        public NavIcons {
            names = names == null ? List.of() : List.copyOf(names);
        }
    }
}
