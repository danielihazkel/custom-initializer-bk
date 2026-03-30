package com.menora.initializr.db.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "module_template")
public class ModuleTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank @Size(max = 50)
    @Column(name = "module_id", nullable = false, unique = true, length = 50)
    private String moduleId;

    @NotBlank @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String label;

    @Column(length = 500)
    private String description;

    /** Appended to artifactId, e.g. "-api" → "myapp-api" */
    @NotBlank @Size(max = 50)
    @Column(nullable = false, length = 50)
    private String suffix;

    @NotBlank @Size(max = 20)
    @Column(nullable = false, length = 20)
    private String packaging = "jar";

    /** Only one module should have this set to true — it gets the @SpringBootApplication class */
    @Column(name = "has_main_class", nullable = false)
    private boolean hasMainClass = false;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
