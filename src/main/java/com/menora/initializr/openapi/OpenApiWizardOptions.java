package com.menora.initializr.openapi;

import com.menora.initializr.config.WizardArgumentException;

import java.util.Locale;

/**
 * Per-dependency wizard options. The wizard can emit server-side
 * {@code @RestController}s, a client that consumes the spec, or both. DTO
 * records are always emitted. Nulls/blanks fall back to defaults.
 *
 * <p>{@code clientKind}: what the generated client calls through — {@code RestTemplate} (the
 * default, as before) or Spring 6.1's {@code RestClient}. {@code copySpec}: keep the document in
 * the project, under {@code src/main/resources/openapi/}.
 */
public record OpenApiWizardOptions(
        String apiSubPackage,
        String dtoSubPackage,
        String clientSubPackage,
        GenerationMode mode,
        String baseUrlProperty,
        ClientKind clientKind,
        boolean copySpec) {

    /** The options before the client kind and the spec copy existed. */
    public OpenApiWizardOptions(String apiSubPackage, String dtoSubPackage, String clientSubPackage,
                                GenerationMode mode, String baseUrlProperty) {
        this(apiSubPackage, dtoSubPackage, clientSubPackage, mode, baseUrlProperty, null, false);
    }

    public enum ClientKind {
        REST_TEMPLATE, REST_CLIENT;

        /** {@code restTemplate} / {@code restClient}, in any case, with or without the underscore. */
        public static ClientKind parse(String raw) {
            if (raw == null || raw.isBlank()) return REST_TEMPLATE;
            String key = raw.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            return switch (key) {
                case "resttemplate" -> REST_TEMPLATE;
                case "restclient" -> REST_CLIENT;
                default -> throw new WizardArgumentException(
                        "Unknown OpenAPI wizard clientKind: '" + raw + "'. Valid values: restTemplate, restClient");
            };
        }
    }

    public ClientKind clientKindOrDefault() {
        return clientKind == null ? ClientKind.REST_TEMPLATE : clientKind;
    }

    public static final String DEFAULT_API_PKG = "api";
    public static final String DEFAULT_DTO_PKG = "dto";
    public static final String DEFAULT_CLIENT_PKG = "client";
    public static final String DEFAULT_BASE_URL_PROPERTY = "openapi.client.base-url";

    public enum GenerationMode {
        CONTROLLERS, CLIENT, BOTH;

        public static GenerationMode parse(String raw) {
            if (raw == null || raw.isBlank()) return CONTROLLERS;
            try {
                return GenerationMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new WizardArgumentException(
                        "Unknown OpenAPI wizard mode: '" + raw + "'. Valid values: CONTROLLERS, CLIENT, BOTH");
            }
        }
    }

    public String apiPackageOrDefault() {
        return (apiSubPackage == null || apiSubPackage.isBlank()) ? DEFAULT_API_PKG : apiSubPackage;
    }

    public String dtoPackageOrDefault() {
        return (dtoSubPackage == null || dtoSubPackage.isBlank()) ? DEFAULT_DTO_PKG : dtoSubPackage;
    }

    public String clientPackageOrDefault() {
        return (clientSubPackage == null || clientSubPackage.isBlank()) ? DEFAULT_CLIENT_PKG : clientSubPackage;
    }

    public GenerationMode modeOrDefault() {
        return mode == null ? GenerationMode.CONTROLLERS : mode;
    }

    public String baseUrlPropertyOrDefault() {
        return (baseUrlProperty == null || baseUrlProperty.isBlank())
                ? DEFAULT_BASE_URL_PROPERTY : baseUrlProperty;
    }

    public boolean emitControllers() {
        GenerationMode m = modeOrDefault();
        return m == GenerationMode.CONTROLLERS || m == GenerationMode.BOTH;
    }

    public boolean emitClient() {
        GenerationMode m = modeOrDefault();
        return m == GenerationMode.CLIENT || m == GenerationMode.BOTH;
    }
}
