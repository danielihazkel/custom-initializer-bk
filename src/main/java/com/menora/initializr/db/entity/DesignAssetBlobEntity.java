package com.menora.initializr.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/** Binary asset content, keyed by its sha256 so each distinct file is stored once. */
@Entity
@Table(name = "initializer_design_asset_blob")
public class DesignAssetBlobEntity {

    @Id
    @Column(length = 64)
    private String sha256;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Lob
    @Column(nullable = false, columnDefinition = "BLOB")
    private byte[] content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
