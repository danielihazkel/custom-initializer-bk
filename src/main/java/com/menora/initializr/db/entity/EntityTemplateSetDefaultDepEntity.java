package com.menora.initializr.db.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A dependency ID that should be pre-selected whenever its owning
 * {@link EntityTemplateSetEntity} (kind=BACKEND_JAVA) is chosen in the fullstack wizard.
 * The {@link com.menora.initializr.config.FullstackStarterController} merges these
 * with any explicit request {@code dependencies} before applying its hard-coded minimum.
 *
 * <p>Stores {@code setId} as a plain {@code Long} rather than a {@code @ManyToOne}
 * relationship — matches the {@link EntityTemplateFileEntity#getSetId()} style and the
 * {@link FileContributionEntity#getDependencyId()} precedent.
 */
@Entity
@Table(name = "entity_template_set_default_dep",
        uniqueConstraints = @UniqueConstraint(name = "uk_entity_template_set_default_dep_set_dep",
                columnNames = {"set_id", "dep_id"}))
public class EntityTemplateSetDefaultDepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "set_id", nullable = false)
    private Long setId;

    @NotBlank @Size(max = 120)
    @Column(name = "dep_id", nullable = false, length = 120)
    private String depId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSetId() { return setId; }
    public void setSetId(Long setId) { this.setId = setId; }
    public String getDepId() { return depId; }
    public void setDepId(String depId) { this.depId = depId; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
