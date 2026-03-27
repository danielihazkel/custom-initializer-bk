package com.menora.initializr.db.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

@Entity
@Table(name = "dependency_entry")
public class DependencyEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    @JsonIgnoreProperties({"entries", "hibernateLazyInitializer"})
    private DependencyGroupEntity group;

    @Column(name = "dep_id", nullable = false, unique = true, length = 50)
    private String depId;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public DependencyGroupEntity getGroup() { return group; }
    public void setGroup(DependencyGroupEntity group) { this.group = group; }
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
