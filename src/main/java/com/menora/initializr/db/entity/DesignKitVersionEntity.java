package com.menora.initializr.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One version of a design kit. A {@code DRAFT} (version null, {@code draftMarker = 1}) is the only
 * editable row of its kit; publishing numbers it and clears the marker, and from then on the row,
 * its files and its assets never change ({@code DesignKitService.requireDraft}).
 */
@Entity
@Table(name = "initializer_design_kit_version")
public class DesignKitVersionEntity {

    public enum Status { DRAFT, PUBLISHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kit_id", nullable = false)
    private Long kitId;

    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "draft_marker")
    private Integer draftMarker;

    @Column(name = "based_on_version")
    private Integer basedOnVersion;

    @Column(name = "contract_version", nullable = false)
    private int contractVersion;

    /** The token list as JSON ({@code DesignTokens}). */
    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String tokens;

    /** Imports, fonts and defaults as JSON ({@code KitManifest}). */
    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String manifest;

    @Column(length = 64)
    private String checksum;

    @Column(length = 1000)
    private String notes;

    /** Still generates for the models pinned to it, but is no longer offered. Not part of the checksum. */
    @Column(nullable = false)
    private boolean deprecated;

    @Column(name = "deprecation_note", length = 500)
    private String deprecationNote;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_by", length = 255)
    private String publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    public boolean isDraft() { return status == Status.DRAFT; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKitId() { return kitId; }
    public void setKitId(Long kitId) { this.kitId = kitId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Integer getDraftMarker() { return draftMarker; }
    public void setDraftMarker(Integer draftMarker) { this.draftMarker = draftMarker; }
    public Integer getBasedOnVersion() { return basedOnVersion; }
    public void setBasedOnVersion(Integer basedOnVersion) { this.basedOnVersion = basedOnVersion; }
    public int getContractVersion() { return contractVersion; }
    public void setContractVersion(int contractVersion) { this.contractVersion = contractVersion; }
    public String getTokens() { return tokens; }
    public void setTokens(String tokens) { this.tokens = tokens; }
    public String getManifest() { return manifest; }
    public void setManifest(String manifest) { this.manifest = manifest; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isDeprecated() { return deprecated; }
    public void setDeprecated(boolean deprecated) { this.deprecated = deprecated; }
    public String getDeprecationNote() { return deprecationNote; }
    public void setDeprecationNote(String deprecationNote) { this.deprecationNote = deprecationNote; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
}
