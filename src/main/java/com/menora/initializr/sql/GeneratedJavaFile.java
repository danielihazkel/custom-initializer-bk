package com.menora.initializr.sql;

/**
 * A single Java source file produced by {@link SqlEntityGenerator}. The
 * {@code relativePath} may contain the {@code {{packagePath}}} placeholder
 * which callers resolve against the project package.
 */
public record GeneratedJavaFile(String relativePath, String content) {
}
