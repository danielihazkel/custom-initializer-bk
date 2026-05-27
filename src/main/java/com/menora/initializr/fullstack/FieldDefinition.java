package com.menora.initializr.fullstack;

import java.util.List;

/**
 * One field on a user-defined entity. Submitted by clients as JSON; converted from
 * the wire form by {@link FullstackRequestValidator}.
 */
public record FieldDefinition(
        String name,
        FieldType type,
        boolean primaryKey,
        boolean generated,
        boolean required,
        boolean unique,
        Integer length,
        List<String> enumValues) {
}
