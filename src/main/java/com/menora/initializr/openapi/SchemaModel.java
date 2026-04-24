package com.menora.initializr.openapi;

import java.util.List;

/** A parsed OpenAPI component schema ready for DTO record rendering. */
public record SchemaModel(
        String name,
        List<FieldModel> fields,
        String description) {
}
