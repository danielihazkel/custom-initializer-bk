package com.menora.initializr.soap;

/**
 * Output of {@link SoapCodeGenerator}. Mirrors
 * {@link com.menora.initializr.openapi.GeneratedOpenApiFile}; the relative path
 * may contain {@code {{packagePath}}}, resolved against the project package at
 * write time.
 */
public record GeneratedSoapFile(String relativePath, String content) {
}
