package com.menora.initializr.extension.frontend.codegen;

/**
 * Thrown by {@link OpenApiTsGenerator#generate(String)} when the spec is
 * unparseable or empty. The caller is expected to log a warning and ship a
 * fallback README pointing at {@code pnpm gen:api} so generation never aborts
 * over a malformed user-supplied spec.
 */
public class OpenApiCodegenException extends RuntimeException {
    public OpenApiCodegenException(String message) {
        super(message);
    }
}
