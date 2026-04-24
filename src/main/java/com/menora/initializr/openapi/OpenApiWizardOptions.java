package com.menora.initializr.openapi;

import java.util.Locale;

/**
 * Per-dependency wizard options. The wizard can emit server-side
 * {@code @RestController}s, a client that consumes the spec, or both. DTO
 * records are always emitted. Nulls/blanks fall back to defaults.
 */
public record OpenApiWizardOptions(
        String apiSubPackage,
        String dtoSubPackage,
        String clientSubPackage,
        GenerationMode mode,
        String baseUrlProperty) {

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
                return CONTROLLERS;
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
