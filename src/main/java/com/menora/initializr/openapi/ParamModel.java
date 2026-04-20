package com.menora.initializr.openapi;

import java.util.Set;

/** A parameter for a generated controller method. */
public record ParamModel(
        String name,
        ParamLocation in,
        String javaType,
        Set<String> imports,
        boolean required) {

    public enum ParamLocation { PATH, QUERY, HEADER, COOKIE }
}
