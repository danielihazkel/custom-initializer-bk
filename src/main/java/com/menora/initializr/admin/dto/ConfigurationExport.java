package com.menora.initializr.admin.dto;

import java.util.List;

public class ConfigurationExport {

    private int version = 1;
    private String exportedAt;
    private List<GroupExport> dependencyGroups;
    private List<EntryExport> dependencyEntries;
    private List<FileContribExport> fileContributions;
    private List<BuildCustomExport> buildCustomizations;
    private List<SubOptionExport> subOptions;
    private List<CompatibilityExport> compatibilityRules;
    private List<TemplateExport> starterTemplates;
    private List<TemplateDepExport> starterTemplateDeps;
    private List<ModuleExport> moduleTemplates;
    private List<ModuleMappingExport> moduleDependencyMappings;

    // ── Getters/Setters ──────────────────────────────────────────────────────

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getExportedAt() { return exportedAt; }
    public void setExportedAt(String exportedAt) { this.exportedAt = exportedAt; }
    public List<GroupExport> getDependencyGroups() { return dependencyGroups; }
    public void setDependencyGroups(List<GroupExport> dependencyGroups) { this.dependencyGroups = dependencyGroups; }
    public List<EntryExport> getDependencyEntries() { return dependencyEntries; }
    public void setDependencyEntries(List<EntryExport> dependencyEntries) { this.dependencyEntries = dependencyEntries; }
    public List<FileContribExport> getFileContributions() { return fileContributions; }
    public void setFileContributions(List<FileContribExport> fileContributions) { this.fileContributions = fileContributions; }
    public List<BuildCustomExport> getBuildCustomizations() { return buildCustomizations; }
    public void setBuildCustomizations(List<BuildCustomExport> buildCustomizations) { this.buildCustomizations = buildCustomizations; }
    public List<SubOptionExport> getSubOptions() { return subOptions; }
    public void setSubOptions(List<SubOptionExport> subOptions) { this.subOptions = subOptions; }
    public List<CompatibilityExport> getCompatibilityRules() { return compatibilityRules; }
    public void setCompatibilityRules(List<CompatibilityExport> compatibilityRules) { this.compatibilityRules = compatibilityRules; }
    public List<TemplateExport> getStarterTemplates() { return starterTemplates; }
    public void setStarterTemplates(List<TemplateExport> starterTemplates) { this.starterTemplates = starterTemplates; }
    public List<TemplateDepExport> getStarterTemplateDeps() { return starterTemplateDeps; }
    public void setStarterTemplateDeps(List<TemplateDepExport> starterTemplateDeps) { this.starterTemplateDeps = starterTemplateDeps; }
    public List<ModuleExport> getModuleTemplates() { return moduleTemplates; }
    public void setModuleTemplates(List<ModuleExport> moduleTemplates) { this.moduleTemplates = moduleTemplates; }
    public List<ModuleMappingExport> getModuleDependencyMappings() { return moduleDependencyMappings; }
    public void setModuleDependencyMappings(List<ModuleMappingExport> moduleDependencyMappings) { this.moduleDependencyMappings = moduleDependencyMappings; }

    // ── Inner DTOs ───────────────────────────────────────────────────────────

    public static class GroupExport {
        private String name;
        private int sortOrder;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    }

    public static class EntryExport {
        private String groupName;
        private String depId;
        private String name;
        private String description;
        private String mavenGroupId;
        private String mavenArtifactId;
        private String version;
        private String scope;
        private String repository;
        private int sortOrder;
        private String compatibilityRange;

        public String getGroupName() { return groupName; }
        public void setGroupName(String groupName) { this.groupName = groupName; }
        public String getDepId() { return depId; }
        public void setDepId(String depId) { this.depId = depId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getMavenGroupId() { return mavenGroupId; }
        public void setMavenGroupId(String mavenGroupId) { this.mavenGroupId = mavenGroupId; }
        public String getMavenArtifactId() { return mavenArtifactId; }
        public void setMavenArtifactId(String mavenArtifactId) { this.mavenArtifactId = mavenArtifactId; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public String getRepository() { return repository; }
        public void setRepository(String repository) { this.repository = repository; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
        public String getCompatibilityRange() { return compatibilityRange; }
        public void setCompatibilityRange(String compatibilityRange) { this.compatibilityRange = compatibilityRange; }
    }

    public static class FileContribExport {
        private String dependencyId;
        private String fileType;
        private String content;
        private String targetPath;
        private String substitutionType;
        private String javaVersion;
        private String subOptionId;
        private int sortOrder;

        public String getDependencyId() { return dependencyId; }
        public void setDependencyId(String dependencyId) { this.dependencyId = dependencyId; }
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getTargetPath() { return targetPath; }
        public void setTargetPath(String targetPath) { this.targetPath = targetPath; }
        public String getSubstitutionType() { return substitutionType; }
        public void setSubstitutionType(String substitutionType) { this.substitutionType = substitutionType; }
        public String getJavaVersion() { return javaVersion; }
        public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }
        public String getSubOptionId() { return subOptionId; }
        public void setSubOptionId(String subOptionId) { this.subOptionId = subOptionId; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    }

    public static class BuildCustomExport {
        private String dependencyId;
        private String customizationType;
        private String mavenGroupId;
        private String mavenArtifactId;
        private String version;
        private String excludeFromGroupId;
        private String excludeFromArtifactId;
        private String repoId;
        private String repoName;
        private String repoUrl;
        private boolean snapshotsEnabled;
        private int sortOrder;

        public String getDependencyId() { return dependencyId; }
        public void setDependencyId(String dependencyId) { this.dependencyId = dependencyId; }
        public String getCustomizationType() { return customizationType; }
        public void setCustomizationType(String customizationType) { this.customizationType = customizationType; }
        public String getMavenGroupId() { return mavenGroupId; }
        public void setMavenGroupId(String mavenGroupId) { this.mavenGroupId = mavenGroupId; }
        public String getMavenArtifactId() { return mavenArtifactId; }
        public void setMavenArtifactId(String mavenArtifactId) { this.mavenArtifactId = mavenArtifactId; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getExcludeFromGroupId() { return excludeFromGroupId; }
        public void setExcludeFromGroupId(String excludeFromGroupId) { this.excludeFromGroupId = excludeFromGroupId; }
        public String getExcludeFromArtifactId() { return excludeFromArtifactId; }
        public void setExcludeFromArtifactId(String excludeFromArtifactId) { this.excludeFromArtifactId = excludeFromArtifactId; }
        public String getRepoId() { return repoId; }
        public void setRepoId(String repoId) { this.repoId = repoId; }
        public String getRepoName() { return repoName; }
        public void setRepoName(String repoName) { this.repoName = repoName; }
        public String getRepoUrl() { return repoUrl; }
        public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
        public boolean isSnapshotsEnabled() { return snapshotsEnabled; }
        public void setSnapshotsEnabled(boolean snapshotsEnabled) { this.snapshotsEnabled = snapshotsEnabled; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    }

    public static class SubOptionExport {
        private String dependencyId;
        private String optionId;
        private String label;
        private String description;
        private int sortOrder;

        public String getDependencyId() { return dependencyId; }
        public void setDependencyId(String dependencyId) { this.dependencyId = dependencyId; }
        public String getOptionId() { return optionId; }
        public void setOptionId(String optionId) { this.optionId = optionId; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    }

    public static class CompatibilityExport {
        private String sourceDepId;
        private String targetDepId;
        private String relationType;
        private String description;
        private int sortOrder;

        public String getSourceDepId() { return sourceDepId; }
        public void setSourceDepId(String sourceDepId) { this.sourceDepId = sourceDepId; }
        public String getTargetDepId() { return targetDepId; }
        public void setTargetDepId(String targetDepId) { this.targetDepId = targetDepId; }
        public String getRelationType() { return relationType; }
        public void setRelationType(String relationType) { this.relationType = relationType; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    }

    public static class TemplateExport {
        private String templateId;
        private String name;
        private String description;
        private String icon;
        private String color;
        private String bootVersion;
        private String javaVersion;
        private String packaging;
        private int sortOrder;

        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getIcon() { return icon; }
        public void setIcon(String icon) { this.icon = icon; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public String getBootVersion() { return bootVersion; }
        public void setBootVersion(String bootVersion) { this.bootVersion = bootVersion; }
        public String getJavaVersion() { return javaVersion; }
        public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }
        public String getPackaging() { return packaging; }
        public void setPackaging(String packaging) { this.packaging = packaging; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    }

    public static class TemplateDepExport {
        private String templateId;
        private String depId;
        private String subOptions;

        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        public String getDepId() { return depId; }
        public void setDepId(String depId) { this.depId = depId; }
        public String getSubOptions() { return subOptions; }
        public void setSubOptions(String subOptions) { this.subOptions = subOptions; }
    }

    public static class ModuleExport {
        private String moduleId;
        private String label;
        private String description;
        private String suffix;
        private String packaging;
        private boolean hasMainClass;
        private int sortOrder;

        public String getModuleId() { return moduleId; }
        public void setModuleId(String moduleId) { this.moduleId = moduleId; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getSuffix() { return suffix; }
        public void setSuffix(String suffix) { this.suffix = suffix; }
        public String getPackaging() { return packaging; }
        public void setPackaging(String packaging) { this.packaging = packaging; }
        public boolean isHasMainClass() { return hasMainClass; }
        public void setHasMainClass(boolean hasMainClass) { this.hasMainClass = hasMainClass; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    }

    public static class ModuleMappingExport {
        private String dependencyId;
        private String moduleId;
        private int sortOrder;

        public String getDependencyId() { return dependencyId; }
        public void setDependencyId(String dependencyId) { this.dependencyId = dependencyId; }
        public String getModuleId() { return moduleId; }
        public void setModuleId(String moduleId) { this.moduleId = moduleId; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    }
}
