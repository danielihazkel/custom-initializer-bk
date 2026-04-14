package com.menora.initializr.db.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "dependency_entry")
public class DependencyEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    @JsonIgnoreProperties({"entries", "hibernateLazyInitializer"})
    private DependencyGroupEntity group;

    @NotBlank @Size(max = 50)
    @Column(name = "dep_id", nullable = false, unique = true, length = 50)
    private String depId;

    @NotBlank @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "maven_group_id", length = 200)
    private String mavenGroupId;

    @Column(name = "maven_artifact_id", length = 200)
    private String mavenArtifactId;

    @Column(length = 50)
    private String version;

    @Column(length = 20)
    private String scope;

    @Column(length = 50)
    private String repository;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "compatibility_range", length = 100)
    private String compatibilityRange;

    @Column(name = "is_starter", nullable = false)
    private boolean starter = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public DependencyGroupEntity getGroup() { return group; }
    public void setGroup(DependencyGroupEntity group) { this.group = group; }
    public String getDepId() { return depId; }
    public void setDepId(String depId) { this.depId = depId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = blankToNull(description); }
    public String getMavenGroupId() { return mavenGroupId; }
    public void setMavenGroupId(String mavenGroupId) { this.mavenGroupId = blankToNull(mavenGroupId); }
    public String getMavenArtifactId() { return mavenArtifactId; }
    public void setMavenArtifactId(String mavenArtifactId) { this.mavenArtifactId = blankToNull(mavenArtifactId); }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = blankToNull(version); }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = blankToNull(scope); }
    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = blankToNull(repository); }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getCompatibilityRange() { return compatibilityRange; }
    public void setCompatibilityRange(String compatibilityRange) { this.compatibilityRange = blankToNull(compatibilityRange); }
    public boolean isStarter() { return starter; }
    public void setStarter(boolean starter) { this.starter = starter; }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
