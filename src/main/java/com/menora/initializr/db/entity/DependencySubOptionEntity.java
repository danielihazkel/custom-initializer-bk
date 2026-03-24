package com.menora.initializr.db.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "dependency_sub_option")
public class DependencySubOptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dependency_id", nullable = false, length = 50)
    private String dependencyId;

    @Column(name = "option_id", nullable = false, length = 50)
    private String optionId;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(length = 500)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

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
}
