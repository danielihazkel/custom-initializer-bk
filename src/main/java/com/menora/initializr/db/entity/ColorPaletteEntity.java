package com.menora.initializr.db.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A named color palette surfaced in the Frontend wizard for palette-aware design
 * systems (MUI / Chakra / Mantine). Stored as 6-digit hex strings; the wizard /
 * generator decides how to apply them per design system.
 */
@Entity
@Table(name = "color_palette")
public class ColorPaletteEntity {

    private static final String HEX_PATTERN = "^#[0-9a-fA-F]{6}$";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank @Size(max = 80)
    @Column(name = "palette_id", nullable = false, unique = true, length = 80)
    private String paletteId;

    @NotBlank @Size(max = 150)
    @Column(nullable = false, length = 150)
    private String name;

    @Size(max = 500)
    @Column(length = 500)
    private String description;

    @NotBlank @Pattern(regexp = HEX_PATTERN)
    @Column(name = "primary_color", nullable = false, length = 7)
    private String primary;

    @NotBlank @Pattern(regexp = HEX_PATTERN)
    @Column(name = "secondary_color", nullable = false, length = 7)
    private String secondary;

    @Pattern(regexp = HEX_PATTERN)
    @Column(length = 7)
    private String accent;

    @Pattern(regexp = HEX_PATTERN)
    @Column(name = "error_color", length = 7)
    private String error;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPaletteId() { return paletteId; }
    public void setPaletteId(String paletteId) { this.paletteId = paletteId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = blankToNull(description); }
    public String getPrimary() { return primary; }
    public void setPrimary(String primary) { this.primary = primary; }
    public String getSecondary() { return secondary; }
    public void setSecondary(String secondary) { this.secondary = secondary; }
    public String getAccent() { return accent; }
    public void setAccent(String accent) { this.accent = blankToNull(accent); }
    public String getError() { return error; }
    public void setError(String error) { this.error = blankToNull(error); }
    @JsonProperty("isDefault")
    public boolean isDefault() { return isDefault; }
    @JsonProperty("isDefault")
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
