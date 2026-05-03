package com.menora.initializr.ai;

import java.util.Set;

/**
 * Path validation for AI-generated file contributions. Defends against path
 * traversal and clobbering files the framework owns. Used both at AI-response
 * parse time (to reject upstream output) and again inside the contributor (in
 * case a malicious request body bypasses the AI step).
 */
public final class SafePath {

    /** Files the framework writes — refusing to overwrite keeps the build sane. */
    private static final Set<String> RESERVED = Set.of(
            "pom.xml",
            "mvnw",
            "mvnw.cmd",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradlew",
            "gradlew.bat",
            "src/main/resources/application.yml",
            "src/main/resources/application.yaml",
            "src/main/resources/application.properties"
    );

    private SafePath() {}

    /**
     * Returns the cleaned relative path. Throws {@link IllegalArgumentException}
     * if the path is unsafe.
     */
    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        String path = raw.replace('\\', '/').trim();
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("path must be relative: " + raw);
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException("path must not contain '..': " + raw);
        }
        if (path.length() > 500) {
            throw new IllegalArgumentException("path too long: " + raw);
        }
        // Windows drive letters and other absolute-ish prefixes
        if (path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("path must be relative: " + raw);
        }
        if (RESERVED.contains(path)) {
            throw new IllegalArgumentException("path overwrites a reserved file: " + raw);
        }
        if (path.startsWith(".mvn/") || path.startsWith(".gradle/")) {
            throw new IllegalArgumentException("path overwrites a reserved file: " + raw);
        }
        return path;
    }
}
