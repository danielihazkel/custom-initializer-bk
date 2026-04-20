package com.menora.initializr.openapi;

/**
 * Per-dependency wizard options. {@code apiSubPackage} holds generated controllers;
 * {@code dtoSubPackage} holds generated records. Nulls/blanks fall back to the
 * defaults {@code "api"} / {@code "dto"}.
 */
public record OpenApiWizardOptions(String apiSubPackage, String dtoSubPackage) {

    public static final String DEFAULT_API_PKG = "api";
    public static final String DEFAULT_DTO_PKG = "dto";

    public String apiPackageOrDefault() {
        return (apiSubPackage == null || apiSubPackage.isBlank()) ? DEFAULT_API_PKG : apiSubPackage;
    }

    public String dtoPackageOrDefault() {
        return (dtoSubPackage == null || dtoSubPackage.isBlank()) ? DEFAULT_DTO_PKG : dtoSubPackage;
    }
}
