package com.menora.initializr.extension.frontend;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Plain DTO carrying the form values for a frontend project generation request.
 * Equivalent role to {@code io.spring.initializr.generator.project.ProjectDescription}
 * but for the React/Vite path — no framework coupling.
 */
public class FrontendProjectDescription {

    private String projectName = "demo";
    /** Optional npm scope without the {@code @} prefix (e.g. {@code "menora"}); empty for no scope. */
    private String scope = "";
    private String description = "";
    private String appTitle = "Demo";
    private String reactVersion = "18";
    private String nodeVersion = "20";
    private String packageManager = "pnpm";
    private String typescriptVersion = "5.4.5";
    private String viteVersion = "5.2.0";
    /** npm semver range for {@code react} / {@code react-dom} — set by the controller from {@code version_definition}. */
    private String reactPackageVersion = "^18.3.1";
    /** npm semver range for {@code @types/react} / {@code @types/react-dom} — set by the controller from {@code version_definition}. */
    private String reactTypesVersion = "^18.3.3";
    private String basePath = "/";
    /** Palette id from the {@code color_palette} table; empty string falls back to the default palette. */
    private String colorPaletteId = "";
    /**
     * Optional base URL of a paired backend (e.g. {@code "http://localhost:8080"}).
     * When set, {@code .env.development}/{@code .env.example} and a Vite {@code /api}
     * proxy block are emitted so the generated FE can call its BE without CORS in dev.
     * Empty string means "no paired backend" — env files and proxy are omitted.
     */
    private String apiBaseUrl = "";
    /** Optional human-readable identifier of the paired backend (e.g. {@code "demo-api"}); surfaced in README only. */
    private String backendArtifactId = "";
    private final Set<String> dependencies = new LinkedHashSet<>();

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope == null ? "" : scope; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description == null ? "" : description; }
    public String getAppTitle() { return appTitle; }
    public void setAppTitle(String appTitle) { this.appTitle = appTitle; }
    public String getReactVersion() { return reactVersion; }
    public void setReactVersion(String reactVersion) { this.reactVersion = reactVersion; }
    public String getNodeVersion() { return nodeVersion; }
    public void setNodeVersion(String nodeVersion) { this.nodeVersion = nodeVersion; }
    public String getPackageManager() { return packageManager; }
    public void setPackageManager(String packageManager) { this.packageManager = packageManager; }
    public String getTypescriptVersion() { return typescriptVersion; }
    public void setTypescriptVersion(String typescriptVersion) { this.typescriptVersion = typescriptVersion; }
    public String getViteVersion() { return viteVersion; }
    public void setViteVersion(String viteVersion) { this.viteVersion = viteVersion; }
    public String getReactPackageVersion() { return reactPackageVersion; }
    public void setReactPackageVersion(String reactPackageVersion) { this.reactPackageVersion = reactPackageVersion; }
    public String getReactTypesVersion() { return reactTypesVersion; }
    public void setReactTypesVersion(String reactTypesVersion) { this.reactTypesVersion = reactTypesVersion; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = (basePath == null || basePath.isBlank()) ? "/" : basePath; }
    public String getColorPaletteId() { return colorPaletteId; }
    public void setColorPaletteId(String colorPaletteId) { this.colorPaletteId = colorPaletteId == null ? "" : colorPaletteId; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) {
        if (apiBaseUrl == null) { this.apiBaseUrl = ""; return; }
        String trimmed = apiBaseUrl.trim();
        if (trimmed.isEmpty()) { this.apiBaseUrl = ""; return; }
        // Reject anything that isn't a plain http(s) URL — keeps the value safe to
        // splice into vite.config.ts and .env files without further escaping.
        if (!trimmed.matches("^https?://[A-Za-z0-9._:\\-/]+$")) {
            throw new IllegalArgumentException("apiBaseUrl must be an http(s) URL: " + apiBaseUrl);
        }
        // Strip trailing slash so concatenated paths don't double up.
        this.apiBaseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
    public String getBackendArtifactId() { return backendArtifactId; }
    public void setBackendArtifactId(String backendArtifactId) {
        this.backendArtifactId = backendArtifactId == null ? "" : backendArtifactId.trim();
    }
    /** True when {@link #apiBaseUrl} is set — drives env-file + vite-proxy emission. */
    public boolean hasBackendPair() { return apiBaseUrl != null && !apiBaseUrl.isEmpty(); }
    public Set<String> getDependencies() { return dependencies; }

    /** {@code "menora"} + {@code "my-app"} → {@code "@menora/my-app"}; empty scope → {@code "my-app"}. */
    public String packageJsonName() {
        return scope == null || scope.isBlank()
                ? projectName
                : "@" + scope + "/" + projectName;
    }
}
