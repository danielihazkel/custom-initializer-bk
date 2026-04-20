package com.menora.initializr.openapi;

/**
 * Mirrors {@link com.menora.initializr.sql.GeneratedJavaFile}. The relative path
 * may contain {@code {{packagePath}}}, which callers resolve against the project
 * package at write time.
 */
public record GeneratedOpenApiFile(String relativePath, String content) {
}
