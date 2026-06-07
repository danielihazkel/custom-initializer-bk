package com.menora.initializr.fullstack;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Wire format for POST {@code /starter-fullstack.zip}. Mirrors
 * {@code WizardStarterController.WizardStarterRequest} for project metadata.
 *
 * <p>Field types accept canonical names ({@code "STRING"}, {@code "LOCAL_DATE"})
 * or Java-style names ({@code "String"}, {@code "LocalDate"}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FullstackStarterRequest(
        String groupId,
        String artifactId,
        String name,
        String description,
        String packageName,
        String domainPackage,
        String type,
        String language,
        String bootVersion,
        String packaging,
        String javaVersion,
        String version,
        String configurationFileFormat,
        List<String> dependencies,
        Map<String, List<String>> opts,
        String backendTemplateSet,
        String frontendTemplateSet,
        String colorPalette,
        List<EntityDefinitionDto> entities) {

    public record EntityDefinitionDto(
            String name,
            String tableName,
            List<FieldDefinitionDto> fields,
            List<RelationDefinitionDto> relations) {}

    public record RelationDefinitionDto(
            String type,
            String fieldName,
            String targetEntity,
            Boolean required) {}

    public record FieldDefinitionDto(
            String name,
            String type,
            Boolean primaryKey,
            Boolean generated,
            Boolean required,
            Boolean unique,
            Integer length,
            Long min,
            Long max,
            String pattern,
            Boolean email,
            List<String> enumValues) {}
}
