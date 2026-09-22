package com.menora.initializr.fullstack;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
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
        // Optional overrides for the generated dashboard header. Null/blank falls back to the
        // built-in copy ("Welcome to <artifactId>" and a default blurb) — resolved in the template
        // with a Mustache inverted-section fallback, since Mustache has no default operator.
        String dashboardTitle,
        String dashboardOverview,
        // Language of the generated frontend's chrome strings (nav, buttons, toasts, validation
        // messages): "en" (default) or "he". Frontend-only; independent of the `rtl` scaffold opt.
        String locale,
        // Department id (see DepartmentResolver) exposed to both halves' templates as
        // {{department}}; null/blank → the default department.
        String department,
        List<EntityDefinitionDto> entities,
        // Optional frontend page layout (dashboards, entity lists, tabbed pages). Null/empty keeps
        // the classic shell: one dashboard plus one list page per entity. See FullstackPageValidator.
        List<PageDefinitionDto> pages) {

    /** Back-compat constructor without {@code pages} (the classic one-page-per-entity layout). */
    public FullstackStarterRequest(
            String groupId, String artifactId, String name, String description, String packageName,
            String domainPackage, String type, String language, String bootVersion, String packaging,
            String javaVersion, String version, String configurationFileFormat, List<String> dependencies,
            Map<String, List<String>> opts, String backendTemplateSet, String frontendTemplateSet,
            String colorPalette, String dashboardTitle, String dashboardOverview, String locale,
            String department, List<EntityDefinitionDto> entities) {
        this(groupId, artifactId, name, description, packageName, domainPackage, type, language,
                bootVersion, packaging, javaVersion, version, configurationFileFormat, dependencies,
                opts, backendTemplateSet, frontendTemplateSet, colorPalette, dashboardTitle,
                dashboardOverview, locale, department, entities, null);
    }

    /** Back-compat constructor without the optional {@code dashboardTitle}/{@code dashboardOverview}
     *  dashboard-header overrides (both default to null → the template's built-in copy) and without
     *  {@code locale} (defaults to English). */
    public FullstackStarterRequest(
            String groupId, String artifactId, String name, String description, String packageName,
            String domainPackage, String type, String language, String bootVersion, String packaging,
            String javaVersion, String version, String configurationFileFormat, List<String> dependencies,
            Map<String, List<String>> opts, String backendTemplateSet, String frontendTemplateSet,
            String colorPalette, List<EntityDefinitionDto> entities) {
        this(groupId, artifactId, name, description, packageName, domainPackage, type, language,
                bootVersion, packaging, javaVersion, version, configurationFileFormat, dependencies,
                opts, backendTemplateSet, frontendTemplateSet, colorPalette, null, null, null, null, entities);
    }

    /** Back-compat constructor without {@code locale} (defaults to English). */
    public FullstackStarterRequest(
            String groupId, String artifactId, String name, String description, String packageName,
            String domainPackage, String type, String language, String bootVersion, String packaging,
            String javaVersion, String version, String configurationFileFormat, List<String> dependencies,
            Map<String, List<String>> opts, String backendTemplateSet, String frontendTemplateSet,
            String colorPalette, String dashboardTitle, String dashboardOverview,
            List<EntityDefinitionDto> entities) {
        this(groupId, artifactId, name, description, packageName, domainPackage, type, language,
                bootVersion, packaging, javaVersion, version, configurationFileFormat, dependencies,
                opts, backendTemplateSet, frontendTemplateSet, colorPalette, dashboardTitle,
                dashboardOverview, null, null, entities);
    }

    /** Back-compat constructor without {@code department} (defaults to the default department). */
    public FullstackStarterRequest(
            String groupId, String artifactId, String name, String description, String packageName,
            String domainPackage, String type, String language, String bootVersion, String packaging,
            String javaVersion, String version, String configurationFileFormat, List<String> dependencies,
            Map<String, List<String>> opts, String backendTemplateSet, String frontendTemplateSet,
            String colorPalette, String dashboardTitle, String dashboardOverview, String locale,
            List<EntityDefinitionDto> entities) {
        this(groupId, artifactId, name, description, packageName, domainPackage, type, language,
                bootVersion, packaging, javaVersion, version, configurationFileFormat, dependencies,
                opts, backendTemplateSet, frontendTemplateSet, colorPalette, dashboardTitle,
                dashboardOverview, locale, null, entities);
    }

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
            String labelPlural,
            // Per-entity overrides of the project-wide {@code opts.scaffold} flags, keyed by the
            // scaffold option name ({@code audit}, {@code softDelete}, {@code csvExport},
            // {@code bulkDelete}, {@code bulkUpdate}, {@code tests}) -> true/false. An absent key
            // inherits the project setting; an unknown key is a 400. Null/empty = no overrides.
            Map<String, Boolean> opts) {

        /** Back-compat overload for table-backed entities (no readOnly/viewQuery/sourceSql/listView(s)). */
        public EntityDefinitionDto(String name, String tableName, String schema,
                                   List<FieldDefinitionDto> fields, List<RelationDefinitionDto> relations) {
            this(name, tableName, schema, fields, relations, null, null, null, null, null, null, null, null);
        }

        /** Back-compat overload without {@code sourceSql}/{@code listView(s)}. */
        public EntityDefinitionDto(String name, String tableName, String schema,
                                   List<FieldDefinitionDto> fields, List<RelationDefinitionDto> relations,
                                   Boolean readOnly, String viewQuery) {
            this(name, tableName, schema, fields, relations, readOnly, viewQuery, null, null, null, null, null, null);
        }

        /** Back-compat overload without {@code listView(s)}. */
        public EntityDefinitionDto(String name, String tableName, String schema,
                                   List<FieldDefinitionDto> fields, List<RelationDefinitionDto> relations,
                                   Boolean readOnly, String viewQuery, String sourceSql) {
            this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, null, null, null, null, null);
        }

        /** Back-compat overload with the legacy single {@code listView} but no {@code listViews}. */
        public EntityDefinitionDto(String name, String tableName, String schema,
                                   List<FieldDefinitionDto> fields, List<RelationDefinitionDto> relations,
                                   Boolean readOnly, String viewQuery, String sourceSql, String listView) {
            this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listView, null, null, null, null);
        }

        /** Back-compat overload without the entity display {@code label}/{@code labelPlural}. */
        public EntityDefinitionDto(String name, String tableName, String schema,
                                   List<FieldDefinitionDto> fields, List<RelationDefinitionDto> relations,
                                   Boolean readOnly, String viewQuery, String sourceSql, String listView,
                                   List<String> listViews) {
            this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listView, listViews,
                    null, null, null);
        }

        /** Back-compat overload without the per-entity scaffold {@code opts} overrides. */
        public EntityDefinitionDto(String name, String tableName, String schema,
                                   List<FieldDefinitionDto> fields, List<RelationDefinitionDto> relations,
                                   Boolean readOnly, String viewQuery, String sourceSql, String listView,
                                   List<String> listViews, String label, String labelPlural) {
            this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listView, listViews,
                    label, labelPlural, null);
        }
    }

    /**
     * One page of the generated frontend. {@code type} is {@code entity-list}, {@code dashboard} or
     * {@code tabs}; which of the other properties apply depends on it (see FullstackPageValidator).
     */
    public record PageDefinitionDto(
            String id,
            String type,
            String title,
            String description,
            // Hidden pages stay out of the navigation; a tabs page can still embed them.
            Boolean hidden,
            // entity-list: the entity whose list page this is, and optional fixed filter values
            // (enum/boolean field name -> value) the page opens with.
            String entity,
            Map<String, String> presetFilter,
            // dashboard
            List<WidgetDto> widgets,
            // tabs
            List<TabDto> tabs,
            // master-detail: the parent list, the child entity shown for the selected parent, and
            // the child's MANY_TO_ONE field that links them (optional when there is only one).
            String parent,
            String child,
            String via,
            // record: the child entities shown as tabs under the record (default: every entity
            // with a MANY_TO_ONE to it). The record entity itself goes in `entity`.
            List<String> childTabs) {

        /** Back-compat constructor for the phase-1 page types (no master-detail/record props). */
        public PageDefinitionDto(String id, String type, String title, String description, Boolean hidden,
                                 String entity, Map<String, String> presetFilter, List<WidgetDto> widgets,
                                 List<TabDto> tabs) {
            this(id, type, title, description, hidden, entity, presetFilter, widgets, tabs, null, null, null, null);
        }
    }

    /** A dashboard widget: {@code kpi} (record count), {@code bar} (count by an enum/boolean
     *  field) or {@code recent} (the latest rows). */
    public record WidgetDto(
            String kind,
            String entity,
            String title,
            String agg,
            String groupBy,
            Integer limit) {}

    /** One tab of a {@code tabs} page, embedding another (non-tabs) page by id. */
    public record TabDto(String title, String page) {}

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
            BigDecimal min,
            BigDecimal max,
            String pattern,
            Boolean email,
            List<String> enumValues,
            Boolean searchable,
            Boolean filterable,
            String label,
            Boolean readOnly,
            // Optional default value, as a string in the field type's natural wire form
            // ("draft", "1", "true", "ACTIVE", "2024-01-01", ...). Type-checked by the validator;
            // rendered as a Java field initializer and as the form's initial value for a new row.
            String defaultValue,
            // Optional display label per enum constant (constant -> label, e.g. {"OPEN": "פתוח"}).
            // Keys are matched case-insensitively against enumValues; only valid on ENUM fields.
            // A constant without a label falls back to a humanized form ("IN_PROGRESS" -> "In progress").
            Map<String, String> enumLabels) {

        /** Back-compat constructor without {@code enumLabels} (no custom labels). */
        public FieldDefinitionDto(String name, String type, Boolean primaryKey, Boolean generated,
                                  Boolean required, Boolean unique, Integer length, BigDecimal min, BigDecimal max,
                                  String pattern, Boolean email, List<String> enumValues,
                                  Boolean searchable, Boolean filterable, String label, Boolean readOnly,
                                  String defaultValue) {
            this(name, type, primaryKey, generated, required, unique, length, min, max,
                    pattern, email, enumValues, searchable, filterable, label, readOnly, defaultValue, null);
        }

        /** Back-compat constructor without the {@code searchable}/{@code filterable} opt-out flags
         *  or the {@code label}/{@code readOnly}/{@code defaultValue} per-field props (all default). */
        public FieldDefinitionDto(String name, String type, Boolean primaryKey, Boolean generated,
                                  Boolean required, Boolean unique, Integer length, BigDecimal min, BigDecimal max,
                                  String pattern, Boolean email, List<String> enumValues) {
            this(name, type, primaryKey, generated, required, unique, length, min, max,
                    pattern, email, enumValues, null, null, null, null, null);
        }

        /** Back-compat constructor without the {@code label}/{@code readOnly}/{@code defaultValue}
         *  per-field props (all default). */
        public FieldDefinitionDto(String name, String type, Boolean primaryKey, Boolean generated,
                                  Boolean required, Boolean unique, Integer length, BigDecimal min, BigDecimal max,
                                  String pattern, Boolean email, List<String> enumValues,
                                  Boolean searchable, Boolean filterable) {
            this(name, type, primaryKey, generated, required, unique, length, min, max,
                    pattern, email, enumValues, searchable, filterable, null, null, null);
        }

        /** Back-compat constructor without {@code defaultValue} (no default). */
        public FieldDefinitionDto(String name, String type, Boolean primaryKey, Boolean generated,
                                  Boolean required, Boolean unique, Integer length, BigDecimal min, BigDecimal max,
                                  String pattern, Boolean email, List<String> enumValues,
                                  Boolean searchable, Boolean filterable, String label, Boolean readOnly) {
            this(name, type, primaryKey, generated, required, unique, length, min, max,
                    pattern, email, enumValues, searchable, filterable, label, readOnly, null);
        }
    }
}
