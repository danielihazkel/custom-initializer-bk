package com.menora.initializr.db.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One selectable version for one {@link VersionKind} — replaces the YAML
 * blocks {@code initializr.java-versions}, {@code initializr.boot-versions},
 * {@code frontend.react-versions}, {@code frontend.node-versions}, and
 * {@code frontend.package-managers}.
 *
 * <p>{@code npmSemver}/{@code typesSemver} are populated only for
 * {@link VersionKind#REACT} rows — they carry the semver ranges the generated
 * {@code package.json} pins for {@code react}/{@code react-dom} and
 * {@code @types/react}. Null on every other kind.
 */
@Entity
@Table(name = "initializer_version_definition",
        uniqueConstraints = @UniqueConstraint(name = "uk_version_kind_id",
                columnNames = {"kind", "version_id"}))
public class VersionDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VersionKind kind;

    @NotBlank @Size(max = 64)
    @Column(name = "version_id", nullable = false, length = 64)
    private String versionId;

    @NotBlank @Size(max = 128)
    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false)
    private boolean enabled = true;

    @Size(max = 64)
    @Column(name = "npm_semver", length = 64)
    private String npmSemver;

    @Size(max = 64)
    @Column(name = "types_semver", length = 64)
    private String typesSemver;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public VersionKind getKind() { return kind; }
    public void setKind(VersionKind kind) { this.kind = kind; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    @JsonProperty("isDefault")
    public boolean isDefault() { return isDefault; }
    @JsonProperty("isDefault")
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNpmSemver() { return npmSemver; }
    public void setNpmSemver(String npmSemver) { this.npmSemver = blankToNull(npmSemver); }
    public String getTypesSemver() { return typesSemver; }
    public void setTypesSemver(String typesSemver) { this.typesSemver = blankToNull(typesSemver); }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
