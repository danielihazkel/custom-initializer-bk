package com.menora.initializr.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A design kit: the look of a generated fullstack frontend (tokens, fonts, assets, kit CSS) on top
 * of one frontend template set. Its content lives in {@link DesignKitVersionEntity} rows — at most
 * one draft and any number of immutable published versions.
 */
@Entity
@Table(name = "initializer_design_kit")
public class DesignKitEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kit_key", nullable = false, length = 50)
    private String kitKey;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "base_set_key", nullable = false, length = 100)
    private String baseSetKey;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(name = "default_palette_id", length = 50)
    private String defaultPaletteId;

    @Column(name = "cloned_from", length = 80)
    private String clonedFrom;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKitKey() { return kitKey; }
    public void setKitKey(String kitKey) { this.kitKey = kitKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBaseSetKey() { return baseSetKey; }
    public void setBaseSetKey(String baseSetKey) { this.baseSetKey = baseSetKey; }
    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }
    public String getDefaultPaletteId() { return defaultPaletteId; }
    public void setDefaultPaletteId(String defaultPaletteId) { this.defaultPaletteId = defaultPaletteId; }
    public String getClonedFrom() { return clonedFrom; }
    public void setClonedFrom(String clonedFrom) { this.clonedFrom = clonedFrom; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
