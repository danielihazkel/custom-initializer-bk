package com.menora.initializr.db.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "dependency_compatibility")
public class DependencyCompatibilityEntity {

    public enum RelationType {
        REQUIRES,
        CONFLICTS,
        RECOMMENDS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank @Size(max = 50)
    @Column(name = "source_dep_id", nullable = false, length = 50)
    private String sourceDepId;

    @NotBlank @Size(max = 50)
    @Column(name = "target_dep_id", nullable = false, length = 50)
    private String targetDepId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 20)
    private RelationType relationType;

    @Column(length = 500)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourceDepId() { return sourceDepId; }
    public void setSourceDepId(String sourceDepId) { this.sourceDepId = sourceDepId; }
    public String getTargetDepId() { return targetDepId; }
    public void setTargetDepId(String targetDepId) { this.targetDepId = targetDepId; }
    public RelationType getRelationType() { return relationType; }
    public void setRelationType(RelationType relationType) { this.relationType = relationType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
