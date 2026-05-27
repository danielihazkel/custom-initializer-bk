package com.menora.initializr.db.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A named bundle of templates used by the fullstack CRUD scaffolding. Each set targets
 * either the backend (BACKEND_JAVA) or the frontend (FRONTEND_REACT). The set carries
 * many {@link EntityTemplateFileEntity} rows; some are rendered once per user-defined
 * entity, others once total.
 */
@Entity
@Table(name = "entity_template_set")
public class EntityTemplateSetEntity {

    public enum Kind {
        BACKEND_JAVA,
        FRONTEND_REACT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank @Size(max = 80)
    @Column(name = "set_key", nullable = false, unique = true, length = 80)
    private String setKey;

    @NotBlank @Size(max = 150)
    @Column(nullable = false, length = 150)
    private String name;

    @Size(max = 500)
    @Column(length = 500)
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSetKey() { return setKey; }
    public void setSetKey(String setKey) { this.setKey = setKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) {
        this.description = (description == null || description.isBlank()) ? null : description;
    }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
