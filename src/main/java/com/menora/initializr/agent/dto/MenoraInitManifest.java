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
        List<FileEntry> files
) {
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

    public record FileEntry(String path, String sha256) {}
}
