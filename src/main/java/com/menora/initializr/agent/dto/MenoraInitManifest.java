package com.menora.initializr.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Schema of the {@code .menora-init.json} file that ships at the root of every
 * generated project. Agents diff their working tree against this manifest to
 * tell scaffold-owned files (sha matches) from agent-edited (sha differs) and
 * agent-added (path absent) ones.
 *
 * <p>Schema is versioned via {@link #schemaVersion()} so future generators can
 * evolve the shape without breaking older agents.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MenoraInitManifest(
        int schemaVersion,
        Generator generator,
        Inputs inputs,
        PairedInputs paired,
        List<FileEntry> files
) {
    /** Single-project constructor (legacy shape — leaves {@link #paired()} null). */
    public MenoraInitManifest(int schemaVersion, Generator generator, Inputs inputs, List<FileEntry> files) {
        this(schemaVersion, generator, inputs, null, files);
    }

    /** Paired-project constructor (leaves {@link #inputs()} null). */
    public MenoraInitManifest(int schemaVersion, Generator generator, PairedInputs paired, List<FileEntry> files) {
        this(schemaVersion, generator, null, paired, files);
    }

    public record Generator(String name, String version, String generatedAt) {}

    public record Inputs(
            String mode,
            String groupId,
            String artifactId,
            String name,
            String packageName,
            String bootVersion,
            String javaVersion,
            String packaging,
            String language,
            String type,
            String version,
            List<String> dependencies,
            List<String> modules,
            Map<String, List<String>> opts,
            Wizards wizards
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Wizards(
            Map<String, Object> sql,
            Map<String, Object> openApi,
            Map<String, Object> soap
    ) {}

    /**
     * Top-level inputs block for paired generations. {@link #backend()} mirrors
     * the existing {@link Inputs} shape; {@link #frontend()} carries the FE
     * description in a parallel form. {@link #apiBaseUrl()} captures the resolved
     * URL written into FE {@code .env.development} so agents can re-derive the
     * dev-time link without re-running the wizard.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PairedInputs(
            Inputs backend,
            FrontendInputs frontend,
            String apiBaseUrl
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FrontendInputs(
            String projectName,
            String description,
            String scope,
            String appTitle,
            String reactVersion,
            String nodeVersion,
            String packageManager,
            String basePath,
            String colorPalette,
            String apiBaseUrl,
            String backendArtifactId,
            List<String> dependencies,
            Map<String, List<String>> opts
    ) {}

    public record FileEntry(String path, String sha256) {}
}
