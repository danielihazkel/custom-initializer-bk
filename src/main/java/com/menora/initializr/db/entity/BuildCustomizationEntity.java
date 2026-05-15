package com.menora.initializr.db.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.ColumnDefault;

/**
 * Build-tool customizations applied to a generated project.
 *
 * <p>Field reinterpretation by {@link CustomizationType} and {@link ProjectKind}:
 * <ul>
 *   <li>BACKEND + ADD_DEPENDENCY: {@code mavenGroupId}, {@code mavenArtifactId}, {@code version}, {@code scope}.</li>
 *   <li>BACKEND + ADD_REPOSITORY: {@code repoId}, {@code repoName}, {@code repoUrl}, {@code snapshotsEnabled}.</li>
 *   <li>BACKEND + EXCLUDE_DEPENDENCY: {@code excludeFromGroupId/ArtifactId} + {@code mavenGroupId/ArtifactId}.</li>
 *   <li>FRONTEND + ADD_NPM_DEPENDENCY: {@code mavenArtifactId} = npm package name,
 *       {@code version} = semver range, {@code scope} = "dev" (devDependencies) or empty (dependencies).</li>
 *   <li>FRONTEND + ADD_VITE_PLUGIN: {@code mavenGroupId} = import module path (e.g. "@vitejs/plugin-react"),
 *       {@code mavenArtifactId} = import binding (e.g. "react"),
 *       {@code version} = plugin call expression (e.g. "react()").</li>
 *   <li>FRONTEND + ADD_NPM_SCRIPT: {@code mavenArtifactId} = script name (e.g. "test:coverage"),
 *       {@code version} = command (e.g. "vitest run --coverage"). Merged into the
 *       {@code "scripts"} block of {@code package.json}; later contributions for the
 *       same name win, allowing admin overrides without editing the baseline.</li>
 * </ul>
 */
@Entity
@Table(name = "build_customization")
public class BuildCustomizationEntity {

    public enum CustomizationType {
        ADD_DEPENDENCY,
        ADD_REPOSITORY,
        EXCLUDE_DEPENDENCY,
        ADD_NPM_DEPENDENCY,
        ADD_VITE_PLUGIN,
        ADD_NPM_SCRIPT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** dep_id from dependency_entry, or '__common__' for customizations applied to every project */
    @NotBlank @Size(max = 50)
    @Column(name = "dependency_id", nullable = false, length = 50)
    private String dependencyId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "customization_type", nullable = false, length = 30)
    private CustomizationType customizationType;

    // For ADD_DEPENDENCY / EXCLUDE_DEPENDENCY
    @Column(name = "maven_group_id", length = 200)
    private String mavenGroupId;

    @Column(name = "maven_artifact_id", length = 200)
    private String mavenArtifactId;

    // Reinterpreted by FE customizations: ADD_NPM_SCRIPT puts the full command
    // here (often >50 chars), ADD_VITE_PLUGIN puts a plugin call expression.
    @Column(length = 2000)
    private String version;

    // For EXCLUDE_DEPENDENCY — the artifact to exclude FROM
    @Column(name = "exclude_from_group_id", length = 200)
    private String excludeFromGroupId;

    @Column(name = "exclude_from_artifact_id", length = 200)
    private String excludeFromArtifactId;

    // For ADD_REPOSITORY
    @Column(name = "repo_id", length = 50)
    private String repoId;

    @Column(name = "repo_name", length = 200)
    private String repoName;

    @Column(name = "repo_url", length = 500)
    private String repoUrl;

    @Column(name = "snapshots_enabled")
    private boolean snapshotsEnabled = false;

    /** For FRONTEND ADD_NPM_DEPENDENCY: "dev" → devDependencies, otherwise → dependencies. */
    @Column(length = 20)
    private String scope;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @NotNull
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'BACKEND'")
    @Column(name = "project_kind", nullable = false, length = 20)
    private ProjectKind projectKind = ProjectKind.BACKEND;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDependencyId() { return dependencyId; }
    public void setDependencyId(String dependencyId) { this.dependencyId = dependencyId; }
    public CustomizationType getCustomizationType() { return customizationType; }
    public void setCustomizationType(CustomizationType customizationType) { this.customizationType = customizationType; }
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
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = (scope == null || scope.isBlank()) ? null : scope; }
    public ProjectKind getProjectKind() { return projectKind == null ? ProjectKind.BACKEND : projectKind; }
    public void setProjectKind(ProjectKind projectKind) { this.projectKind = projectKind == null ? ProjectKind.BACKEND : projectKind; }
}
