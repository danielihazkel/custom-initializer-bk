package com.menora.initializr.sql;

import java.util.Set;

/**
 * A resolved Java type for a SQL column. {@code simpleName} is what the field
 * declares (e.g. {@code LocalDateTime}); {@code imports} are the fully-qualified
 * classes that must appear in the entity file's import block.
 */
public record JavaType(String simpleName, Set<String> imports) {

    public static JavaType of(String simpleName, String... imports) {
        return new JavaType(simpleName, Set.of(imports));
    }

    public static JavaType langType(String simpleName) {
        return new JavaType(simpleName, Set.of());
    }
}
