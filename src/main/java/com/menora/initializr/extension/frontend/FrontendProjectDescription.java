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
    private String basePath = "/";
    /** Palette id from the {@code color_palette} table; empty string falls back to the default palette. */
    private String colorPaletteId = "";
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
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = (basePath == null || basePath.isBlank()) ? "/" : basePath; }
    public String getColorPaletteId() { return colorPaletteId; }
    public void setColorPaletteId(String colorPaletteId) { this.colorPaletteId = colorPaletteId == null ? "" : colorPaletteId; }
    public Set<String> getDependencies() { return dependencies; }

    /** {@code "menora"} + {@code "my-app"} → {@code "@menora/my-app"}; empty scope → {@code "my-app"}. */
    public String packageJsonName() {
        return scope == null || scope.isBlank()
                ? projectName
                : "@" + scope + "/" + projectName;
    }
}
