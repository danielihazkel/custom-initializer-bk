package com.menora.initializr.fullstack;

import java.util.List;

/**
 * One user-defined entity. The {@code tableName} is optional — when null,
 * {@link Naming#pluralize(String)} of the snake_case name is used. {@code relations}
 * holds {@link RelationType#MANY_TO_ONE} foreign keys to other entities in the request.
 */
public record EntityDefinition(
        String name,
        String tableName,
        List<FieldDefinition> fields,
        List<RelationDefinition> relations) {

    public EntityDefinition {
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    /** Convenience for the common no-relations case (keeps existing call sites terse). */
    public EntityDefinition(String name, String tableName, List<FieldDefinition> fields) {
        this(name, tableName, fields, List.of());
    }
}
