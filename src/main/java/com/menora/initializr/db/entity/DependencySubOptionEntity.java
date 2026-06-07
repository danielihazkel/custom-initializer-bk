package com.menora.initializr.db.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "initializer_dependency_sub_option")
public class DependencySubOptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank @Size(max = 50)
    @Column(name = "dependency_id", nullable = false, length = 50)
    private String dependencyId;

    @NotBlank @Size(max = 50)
    @Column(name = "option_id", nullable = false, length = 50)
    private String optionId;

    @NotBlank @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String label;

    @Column(length = 500)
    private String description;

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
    public String getOptionId() { return optionId; }
    public void setOptionId(String optionId) { this.optionId = optionId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public ProjectKind getProjectKind() { return projectKind == null ? ProjectKind.BACKEND : projectKind; }
    public void setProjectKind(ProjectKind projectKind) { this.projectKind = projectKind == null ? ProjectKind.BACKEND : projectKind; }
}
