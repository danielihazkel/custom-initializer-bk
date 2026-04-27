package com.menora.initializr.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.menora.initializr.config.WizardStarterController.OpenApiOptionsDto;
import com.menora.initializr.config.WizardStarterController.SoapOptionsDto;
import com.menora.initializr.config.WizardStarterController.SqlDepOptionsDto;

import java.util.List;
import java.util.Map;

/**
 * Single JSON request shape covering all three generation modes
 * ({@code starter}, {@code wizard}, {@code multimodule}). Field semantics match
 * {@link com.menora.initializr.config.WizardStarterController.WizardStarterRequest}
 * — the agent contract is intentionally a superset of the existing wizard body.
 *
 * <p>{@link #mode()} selects the underlying generator. {@link #modules()} is only
 * consulted when mode is {@code multimodule}. Wizard fields are only consulted
 * when mode is {@code wizard} (the default).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScaffoldRequest(
        String mode,
        String groupId, String artifactId, String name, String description,
        String packageName, String type, String language, String bootVersion,
        String packaging, String javaVersion, String version,
        String configurationFileFormat,
        List<String> dependencies,
        List<String> modules,
        Map<String, List<String>> opts,
        Map<String, String> sqlByDep,
        Map<String, SqlDepOptionsDto> sqlOptions,
        Map<String, String> specByDep,
        Map<String, OpenApiOptionsDto> openApiOptions,
        Map<String, String> wsdlByDep,
        Map<String, SoapOptionsDto> soapOptions
) {
    public String resolvedMode() {
        if (mode == null || mode.isBlank()) return "wizard";
        return mode.toLowerCase();
    }
}
