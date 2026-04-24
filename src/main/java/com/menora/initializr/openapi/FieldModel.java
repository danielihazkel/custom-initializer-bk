package com.menora.initializr.openapi;

import java.util.Set;

/** A single field in a generated DTO record. */
public record FieldModel(
        String name,
        String javaType,
        boolean required,
        String description,
        Set<String> imports) {
}
