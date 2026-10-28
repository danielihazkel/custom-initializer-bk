package com.menora.initializr.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A binary file a design-kit version ships, at its path in the generated frontend. */
@Entity
@Table(name = "initializer_design_kit_asset")
public class DesignKitAssetEntity {

    public enum Role { LOGO, FAVICON, FONT, ICON, IMAGE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(name = "target_path", nullable = false, length = 255)
    private String targetPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "blob_sha256", nullable = false, length = 64)
    private String blobSha256;

    /** JSON: {@code {family, weight, style}} for a font, {@code {alt}} for an image. */
    @Column(length = 1000)
    private String meta;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String targetPath) { this.targetPath = targetPath; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getBlobSha256() { return blobSha256; }
    public void setBlobSha256(String blobSha256) { this.blobSha256 = blobSha256; }
    public String getMeta() { return meta; }
    public void setMeta(String meta) { this.meta = meta; }
}
