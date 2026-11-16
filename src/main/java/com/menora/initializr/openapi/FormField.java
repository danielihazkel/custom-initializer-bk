package com.menora.initializr.openapi;

import java.util.List;
import java.util.Set;

/**
 * One property of a {@code multipart/form-data} or {@code application/x-www-form-urlencoded}
 * request body. Such a body is not a JSON document: the controller takes each property as a
 * parameter of its own, as Spring binds them, and the client sends them as form parts.
 *
 * @param name        the Java name
 * @param wireName    the part's or field's name in the request
 * @param kind        what it is sent as
 * @param javaType    the controller's type ({@code MultipartFile} for a file)
 * @param clientType  the client's type ({@code Resource} for a file)
 * @param annotations what a controller writes before the binding annotation (constraints, a date's format)
 */
public record FormField(
        String name,
        String wireName,
        Part kind,
        String javaType,
        Set<String> imports,
        String clientType,
        Set<String> clientImports,
        boolean required,
        List<String> annotations,
        Set<String> annotationImports) {

    public enum Part {
        /** {@code format: binary}: a file. */
        FILE,
        /** An array of {@code format: binary}: several files under one name. */
        FILES,
        /** An object in a multipart body: a part of its own, in JSON. */
        JSON,
        /** Anything else: a plain field. */
        FIELD
    }
}
