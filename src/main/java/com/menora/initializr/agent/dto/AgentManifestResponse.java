package com.menora.initializr.agent.dto;

import com.menora.initializr.config.ExtensionMetadataController.CompatibilityRuleDto;
import com.menora.initializr.config.ExtensionMetadataController.ModuleTemplateDto;
import com.menora.initializr.config.ExtensionMetadataController.StarterTemplateDto;

import java.util.List;
import java.util.Map;

/**
 * Single shot of every catalog an agent needs before it can construct a
 * {@link ScaffoldRequest}. Returned by {@code GET /agent/manifest}.
 *
 * <p>This is a fan-out of the existing {@code /metadata/*} endpoints — same
 * underlying services, single response. Cacheable.
 */
public record AgentManifestResponse(
        int schemaVersion,
        List<String> bootVersions,
        List<String> javaVersions,
        List<String> languages,
        List<String> packagings,
        List<String> types,
        List<DependencyDto> dependencies,
        List<StarterTemplateDto> starterTemplates,
        List<ModuleTemplateDto> moduleTemplates,
        List<CompatibilityRuleDto> compatibilityRules,
        Wizards wizards,
        String defaultGroupId,
        String defaultArtifactId,
        String defaultBootVersion,
        String defaultJavaVersion
) {
    public record DependencyDto(
            String id,
            String name,
            String description,
            String groupName,
            String compatibilityRange,
            List<SubOption> subOptions
    ) {}

    public record SubOption(String id, String label, String description) {}

    public record Wizards(WizardCapability sql, WizardCapability openApi, WizardCapability soap) {}

    public record WizardCapability(
            List<String> capableDeps,
            Map<String, String> dialects
    ) {}
}
