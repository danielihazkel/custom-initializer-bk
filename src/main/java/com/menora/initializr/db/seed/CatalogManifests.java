package com.menora.initializr.db.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Jackson record DTOs for the backend dependency catalog manifests under
 * {@code src/main/resources/catalog/}. The {@code DataSeeder} reads these on
 * first-startup seeding instead of hard-coding the catalog in Java. Content files
 * (templates, static-configs) still live on the classpath; manifests reference them
 * by path via {@code contentResource}.
 *
 * <p>All records are {@link JsonIgnoreProperties}({@code ignoreUnknown = true}) so the
 * JSON can carry documentation-only keys without breaking deserialization.
 */
public final class CatalogManifests {

    private CatalogManifests() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DependenciesManifest(List<GroupDef> groups) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupDef(String name, int sortOrder, List<EntryDef> entries) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntryDef(String depId, String name, String description,
                           String mavenGroupId, String mavenArtifactId, String version,
                           String scope, String repository, int sortOrder,
                           String compatibilityRange, Boolean starter) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileContribDef(String depId, String fileType, String contentResource,
                                 String content, String targetPath, String substitutionType,
                                 String javaVersion, String subOptionId, int sortOrder) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildCustomDef(String depId, String type, String mavenGroupId,
                                 String mavenArtifactId, String version,
                                 String excludeFromGroupId, String excludeFromArtifactId,
                                 String repoId, String repoName, String repoUrl,
                                 Boolean snapshotsEnabled, String scope, String subOptionId,
                                 int sortOrder) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubOptionDef(String depId, String optionId, String label,
                               String description, int sortOrder) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompatibilityDef(String sourceDepId, String targetDepId, String relationType,
                                   String description, int sortOrder) {}
}
