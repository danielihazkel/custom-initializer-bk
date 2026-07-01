package com.menora.initializr.fullstack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One user-defined entity. The {@code tableName} is optional — when null,
 * {@link Naming#pluralize(String)} of the snake_case name is used. {@code schema} is the
 * optional SQL schema the table lives in (e.g. {@code entv} from {@code CREATE TABLE entv.test});
 * null/blank means no {@code @Table(schema=…)} is emitted. {@code relations} holds
 * {@link RelationType#MANY_TO_ONE} foreign keys to other entities in the request.
 *
 * <p>{@code readOnly} entities generate GET-only scaffolding (no create/update/delete).
 * {@code viewQuery}, when set, is a raw SELECT that maps the entity to a Hibernate
 * {@code @Immutable}/{@code @Subselect} view instead of a table — a view is always read-only.
 *
 * <p>{@code sourceSql} is informational provenance: the originating {@code CREATE TABLE}
 * statement a table-backed entity was imported from. It is surfaced read-only in the editor
 * and is <em>not</em> used during generation. Views carry their source SELECT in
 * {@code viewQuery} instead, so {@code sourceSql} stays null for them.
 */
public record EntityDefinition(
        String name,
        String tableName,
        String schema,
        List<FieldDefinition> fields,
        List<RelationDefinition> relations,
        boolean readOnly,
        String viewQuery,
        String sourceSql,
        List<String> listViews,
        String label,
        String labelPlural) {

    /** The list-view modes the generated entity page may render. */
    private static final Set<String> KNOWN_VIEWS = Set.of("table", "cards", "kanban", "calendar");

    public EntityDefinition {
        relations = relations == null ? List.of() : List.copyOf(relations);
        viewQuery = (viewQuery == null || viewQuery.isBlank()) ? null : viewQuery;
        sourceSql = (sourceSql == null || sourceSql.isBlank()) ? null : sourceSql;
        label = (label == null || label.isBlank()) ? null : label.trim();
        labelPlural = (labelPlural == null || labelPlural.isBlank()) ? null : labelPlural.trim();
        if (viewQuery != null) readOnly = true;
        // The list-view modes the generated entity page generates — an ordered, deduped subset of
        // {table, cards, kanban, calendar}; the first is the page's initial mode. A toggle is emitted
        // only when more than one is enabled. Unknown values are dropped; an empty/null set defaults
        // to [table]. "kanban"/"calendar" are further down-graded at render time when the entity lacks
        // the field each needs (see EntityScaffoldContext: viewKanban / viewCalendar).
        LinkedHashSet<String> norm = new LinkedHashSet<>();
        if (listViews != null) {
            for (String v : listViews) {
                if (v == null) continue;
                String lv = v.trim().toLowerCase(Locale.ROOT);
                if (KNOWN_VIEWS.contains(lv)) norm.add(lv);
            }
        }
        if (norm.isEmpty()) norm.add("table");
        listViews = List.copyOf(norm);
    }

    /** First enabled view — the generated page's initial mode. Back-compat for single-view readers. */
    public String listView() {
        return listViews.get(0);
    }

    /** Overload without the entity display {@code label}/{@code labelPlural} (both default to null). */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery, String sourceSql, List<String> listViews) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listViews, null, null);
    }

    /** Overload without {@code listViews} (defaults to [table]). */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery, String sourceSql) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, List.of("table"), null, null);
    }

    /** Overload without {@code sourceSql}/{@code listViews} — keeps existing call sites terse. */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, null, List.of("table"), null, null);
    }

    /** Convenience for a table-backed, read-write entity (the common case). */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations) {
        this(name, tableName, schema, fields, relations, false, null, null, List.of("table"), null, null);
    }

    /** Back-compat overload for schemaless entities (keeps existing call sites terse). */
    public EntityDefinition(String name, String tableName, List<FieldDefinition> fields,
                            List<RelationDefinition> relations) {
        this(name, tableName, null, fields, relations);
    }

    /** Convenience for the common no-schema, no-relations case. */
    public EntityDefinition(String name, String tableName, List<FieldDefinition> fields) {
        this(name, tableName, null, fields, List.of());
    }

    /** True when this entity maps to a SELECT via {@code @Subselect} rather than a table. */
    public boolean isView() {
        return viewQuery != null;
    }
}
