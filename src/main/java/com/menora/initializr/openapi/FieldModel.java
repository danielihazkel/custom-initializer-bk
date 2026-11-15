package com.menora.initializr.openapi;

import java.util.List;
import java.util.Set;

/**
 * A single field in a generated DTO record.
 *
 * @param name        the Java name
 * @param jsonName    the property's name on the wire
 * @param annotations what stands before the component: {@code @JsonProperty} when the two names
 *                    differ, and — with the validation starter — the schema's constraints
 * @param imports     of the type and of the annotations
 */
public record FieldModel(
        String name,
        String jsonName,
        String javaType,
        boolean required,
        String description,
        List<String> annotations,
        Set<String> imports) {

    /** A field named on the wire as in Java, with nothing before it. */
    public FieldModel(String name, String javaType, boolean required, String description, Set<String> imports) {
        this(name, name, javaType, required, description, List.of(), imports);
    }
}
