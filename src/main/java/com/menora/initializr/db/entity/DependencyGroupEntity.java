package com.menora.initializr.db.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.ColumnDefault;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "dependency_group")
public class DependencyGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @NotNull
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'BACKEND'")
    @Column(name = "project_kind", nullable = false, length = 20)
    private ProjectKind projectKind = ProjectKind.BACKEND;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<DependencyEntryEntity> entries = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public ProjectKind getProjectKind() { return projectKind == null ? ProjectKind.BACKEND : projectKind; }
    public void setProjectKind(ProjectKind projectKind) { this.projectKind = projectKind == null ? ProjectKind.BACKEND : projectKind; }
    public List<DependencyEntryEntity> getEntries() { return entries; }
    public void setEntries(List<DependencyEntryEntity> entries) { this.entries = entries; }
}
