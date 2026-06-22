package com.menora.initializr.fullstack;

import java.util.List;

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
 */
public record EntityDefinition(
        String name,
        String tableName,
        String schema,
        List<FieldDefinition> fields,
        List<RelationDefinition> relations,
        boolean readOnly,
        String viewQuery) {

    public EntityDefinition {
        relations = relations == null ? List.of() : List.copyOf(relations);
        viewQuery = (viewQuery == null || viewQuery.isBlank()) ? null : viewQuery;
        if (viewQuery != null) readOnly = true;
    }

    /** Convenience for a table-backed, read-write entity (the common case). */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations) {
        this(name, tableName, schema, fields, relations, false, null);
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
