package com.menora.initializr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds the {@code frontend:} block of application.yml — versions exposed in the
 * frontend tab's dropdowns + pinned TS/Vite versions + form defaults.
 */
@Component
@ConfigurationProperties(prefix = "frontend")
public class FrontendProperties {

    private List<Option> reactVersions = new ArrayList<>();
    private List<Option> nodeVersions = new ArrayList<>();
    private List<Option> packageManagers = new ArrayList<>();
    private Pinned pinned = new Pinned();
    private Defaults defaults = new Defaults();

    public List<Option> getReactVersions() { return reactVersions; }
    public void setReactVersions(List<Option> reactVersions) { this.reactVersions = reactVersions; }
    public List<Option> getNodeVersions() { return nodeVersions; }
    public void setNodeVersions(List<Option> nodeVersions) { this.nodeVersions = nodeVersions; }
    public List<Option> getPackageManagers() { return packageManagers; }
    public void setPackageManagers(List<Option> packageManagers) { this.packageManagers = packageManagers; }
    public Pinned getPinned() { return pinned; }
    public void setPinned(Pinned pinned) { this.pinned = pinned; }
    public Defaults getDefaults() { return defaults; }
    public void setDefaults(Defaults defaults) { this.defaults = defaults; }

    public String defaultReactVersion() { return defaultOf(reactVersions, "18"); }
    public String defaultNodeVersion() { return defaultOf(nodeVersions, "20"); }
    public String defaultPackageManager() { return defaultOf(packageManagers, "pnpm"); }

    private static String defaultOf(List<Option> opts, String fallback) {
        return opts.stream().filter(Option::isDefault).map(Option::getId).findFirst()
                .orElseGet(() -> opts.isEmpty() ? fallback : opts.get(0).getId());
    }

    public static class Option {
        private String id;
        private String name;
        private boolean isDefault;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isDefault() { return isDefault; }
        public void setDefault(boolean aDefault) { isDefault = aDefault; }
    }

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
