package com.menora.initializr.db.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A named color palette selectable by users when generating a frontend project.
 * Injected into the generated theme.ts via Mustache substitution.
 *
 * <p>Frontend-only — no projectKind discriminator is needed because palettes
 * have no meaning for the backend generator path.
 */
@Entity
@Table(name = "initializer_color_palette")
public class ColorPaletteEntity {

    private static final String HEX_PATTERN = "^#[0-9a-fA-F]{6}$";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank @Size(max = 50)
    @Column(name = "palette_id", nullable = false, unique = true, length = 50)
    private String paletteId;

    @NotBlank @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @NotBlank
    @Pattern(regexp = HEX_PATTERN, message = "must be a 6-digit hex color, e.g. #1976d2")
    @Column(name = "primary_hex", nullable = false, length = 7)
    private String primary;

    @NotBlank
    @Pattern(regexp = HEX_PATTERN, message = "must be a 6-digit hex color, e.g. #9c27b0")
    @Column(name = "secondary_hex", nullable = false, length = 7)
    private String secondary;

    @Pattern(regexp = HEX_PATTERN, message = "must be a 6-digit hex color or empty")
    @Column(name = "accent_hex", length = 7)
    private String accent;

    @Pattern(regexp = HEX_PATTERN, message = "must be a 6-digit hex color or empty")
    @Column(name = "error_hex", length = 7)
    private String error;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

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
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    @JsonProperty("isDefault")
    public boolean isDefault() { return isDefault; }
    @JsonProperty("isDefault")
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
