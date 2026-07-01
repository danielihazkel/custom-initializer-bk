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
            String schema,
            List<FieldDefinitionDto> fields,
            List<RelationDefinitionDto> relations,
            Boolean readOnly,
            String viewQuery,
            // Informational provenance the UI round-trips (the originating CREATE TABLE an
            // entity was imported from). Accepted so deserialization doesn't fail, but the
            // validator/generator deliberately ignores it — it never affects generated output.
            String sourceSql,
            // Deprecated single-view field — the initial list view ("table"/"cards"/…). Still accepted
            // for back-compat (treated as a one-element listViews); prefer listViews below.
            String listView,
            // The list-view modes the generated entity page generates (subset of table/cards/kanban/
            // calendar, ordered; first = initial). A toggle is emitted only when 2+ are enabled.
            // Null/empty falls back to [listView] if present, else [table].
            List<String> listViews,
            // Optional entity-level display labels. Null/blank falls back to the PascalCase name
            // (and derived plural) — mirrors the per-field {@code label}. {@code labelPlural} is used
            // for plural surfaces (nav, list H1, dashboard); auto-pluralizing a localized/Hebrew
            // {@code label} is meaningless, so the plural is supplied explicitly.
            String label,
            String labelPlural) {

        /** Back-compat overload for table-backed entities (no readOnly/viewQuery/sourceSql/listView(s)). */
        public EntityDefinitionDto(String name, String tableName, String schema,
                                   List<FieldDefinitionDto> fields, List<RelationDefinitionDto> relations) {
            this(name, tableName, schema, fields, relations, null, null, null, null, null, null, null);
        }

        /** Back-compat overload without {@code sourceSql}/{@code listView(s)}. */
        public EntityDefinitionDto(String name, String tableName, String schema,
                                   List<FieldDefinitionDto> fields, List<RelationDefinitionDto> relations,
                                   Boolean readOnly, String viewQuery) {
            this(name, tableName, schema, fields, relations, readOnly, viewQuery, null, null, null, null, null);
        }

        /** Back-compat overload without {@code listView(s)}. */
        public EntityDefinitionDto(String name, String tableName, String schema,
                                   List<FieldDefinitionDto> fields, List<RelationDefinitionDto> relations,
                                   Boolean readOnly, String viewQuery, String sourceSql) {
            this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, null, null, null, null);
        }

        /** Back-compat overload with the legacy single {@code listView} but no {@code listViews}. */
        public EntityDefinitionDto(String name, String tableName, String schema,
                                   List<FieldDefinitionDto> fields, List<RelationDefinitionDto> relations,
                                   Boolean readOnly, String viewQuery, String sourceSql, String listView) {
            this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listView, null, null, null);
        }

        /** Back-compat overload without the entity display {@code label}/{@code labelPlural}. */
        public EntityDefinitionDto(String name, String tableName, String schema,
                                   List<FieldDefinitionDto> fields, List<RelationDefinitionDto> relations,
                                   Boolean readOnly, String viewQuery, String sourceSql, String listView,
                                   List<String> listViews) {
            this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listView, listViews,
                    null, null);
        }
    }

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
            List<String> enumValues,
            Boolean searchable,
            Boolean filterable,
            String label,
            Boolean readOnly) {

        /** Back-compat constructor without the {@code searchable}/{@code filterable} opt-out flags
         *  or the {@code label}/{@code readOnly} per-field props (all default). */
        public FieldDefinitionDto(String name, String type, Boolean primaryKey, Boolean generated,
                                  Boolean required, Boolean unique, Integer length, Long min, Long max,
                                  String pattern, Boolean email, List<String> enumValues) {
            this(name, type, primaryKey, generated, required, unique, length, min, max,
                    pattern, email, enumValues, null, null, null, null);
        }

        /** Back-compat constructor without the {@code label}/{@code readOnly} per-field props (both default). */
        public FieldDefinitionDto(String name, String type, Boolean primaryKey, Boolean generated,
                                  Boolean required, Boolean unique, Integer length, Long min, Long max,
                                  String pattern, Boolean email, List<String> enumValues,
                                  Boolean searchable, Boolean filterable) {
            this(name, type, primaryKey, generated, required, unique, length, min, max,
                    pattern, email, enumValues, searchable, filterable, null, null);
        }
    }
}
