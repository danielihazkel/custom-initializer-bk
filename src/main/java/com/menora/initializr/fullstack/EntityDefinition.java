package com.menora.initializr.fullstack;

import java.util.List;

/**
 * One user-defined entity. The {@code tableName} is optional — when null,
 * {@link Naming#pluralize(String)} of the snake_case name is used.
 */
public record EntityDefinition(
        String name,
        String tableName,
        List<FieldDefinition> fields) {
}
