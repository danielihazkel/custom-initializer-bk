package com.menora.initializr.db.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/** The files one download generated, zipped, named by their content (see {@code config/GeneratedSnapshots}). */
@Entity
@Table(name = "initializer_generated_snapshot")
public class GeneratedSnapshotEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 16)
    private String kind;

    @Column(name = "artifact_id", length = 255)
    private String artifactId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] content;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
}
