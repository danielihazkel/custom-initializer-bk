package com.menora.initializr.openapi;

import java.util.List;
import java.util.Set;

/**
 * A parameter for a generated controller or client method.
 *
 * @param name        the Java name
 * @param wireName    the name in the request — what the annotation, the query string or the
 *                    header carries ({@code X-Request-Id}, {@code born_after})
 * @param annotations what a controller writes before the binding annotation: the schema's
 *                    constraints (with the validation starter) and a date's ISO format
 * @param imports     of the type and of the annotations
 */
public record ParamModel(
        String name,
        String wireName,
        ParamLocation in,
        String javaType,
        Set<String> typeImports,
        boolean required,
        List<String> annotations,
        Set<String> annotationImports) {
    public enum ParamLocation { PATH, QUERY, HEADER, COOKIE }
}
