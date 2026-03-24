package com.menora.initializr.db.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "build_customization")
public class BuildCustomizationEntity {

    public enum CustomizationType {
        ADD_DEPENDENCY,
        ADD_REPOSITORY,
        EXCLUDE_DEPENDENCY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** dep_id from dependency_entry, or '__common__' for customizations applied to every project */
    @Column(name = "dependency_id", nullable = false, length = 50)
    private String dependencyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "customization_type", nullable = false, length = 30)
    private CustomizationType customizationType;

    // For ADD_DEPENDENCY / EXCLUDE_DEPENDENCY
    @Column(name = "maven_group_id", length = 200)
    private String mavenGroupId;

    @Column(name = "maven_artifact_id", length = 200)
    private String mavenArtifactId;

    @Column(length = 50)
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

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

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
}
