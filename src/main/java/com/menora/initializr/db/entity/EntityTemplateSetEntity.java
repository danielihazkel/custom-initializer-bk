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

    /**
     * Design system a FRONTEND_REACT set targets. Descriptive only — the templates' actual
     * rendering is whatever the set's files contain. Always null on BACKEND_JAVA sets.
     */
    public enum DesignSystem {
        TAILWIND,
        MUI,
        CHAKRA,
        MANTINE,
        SHADCN,
        NONE
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

    @Enumerated(EnumType.STRING)
    @Column(name = "design_system", length = 20)
    private DesignSystem designSystem;

    @Size(max = 50)
    @Column(name = "boot_version", length = 50)
    private String bootVersion;

    @Size(max = 10)
    @Column(name = "java_version", length = 10)
    private String javaVersion;

    @Size(max = 80)
    @Column(name = "default_palette_id", length = 80)
    private String defaultPaletteId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSetKey() { return setKey; }
    public void setSetKey(String setKey) { this.setKey = setKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) {
        this.description = blankToNull(description);
    }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public DesignSystem getDesignSystem() { return designSystem; }
    public void setDesignSystem(DesignSystem designSystem) { this.designSystem = designSystem; }
    public String getBootVersion() { return bootVersion; }
    public void setBootVersion(String bootVersion) { this.bootVersion = blankToNull(bootVersion); }
    public String getJavaVersion() { return javaVersion; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = blankToNull(javaVersion); }
    public String getDefaultPaletteId() { return defaultPaletteId; }
    public void setDefaultPaletteId(String defaultPaletteId) { this.defaultPaletteId = blankToNull(defaultPaletteId); }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
