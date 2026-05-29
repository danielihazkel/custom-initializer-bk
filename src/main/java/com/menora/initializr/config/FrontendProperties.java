package com.menora.initializr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code frontend:} block of application.yml — pinned tooling
 * versions (TypeScript / Vite) and form defaults.
 *
 * <p>Version lists (React / Node / package manager) and per-React semver
 * mappings are admin-managed and live in the {@code version_definition}
 * table — see {@link com.menora.initializr.db.VersionService}.
 */
@Component
@ConfigurationProperties(prefix = "frontend")
public class FrontendProperties {

    private Pinned pinned = new Pinned();
    private Defaults defaults = new Defaults();

    public Pinned getPinned() { return pinned; }
    public void setPinned(Pinned pinned) { this.pinned = pinned; }
    public Defaults getDefaults() { return defaults; }
    public void setDefaults(Defaults defaults) { this.defaults = defaults; }

    public static class Pinned {
        private String typescript = "5.4.5";
        private String vite = "5.2.0";
        public String getTypescript() { return typescript; }
        public void setTypescript(String typescript) { this.typescript = typescript; }
        public String getVite() { return vite; }
        public void setVite(String vite) { this.vite = vite; }
    }

    public static class Defaults {
        private String projectName = "demo";
        private String description = "Menora React + TS + Vite project";
        private String appTitle = "Demo";
        private String scope = "";
        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getAppTitle() { return appTitle; }
        public void setAppTitle(String appTitle) { this.appTitle = appTitle; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }
}
