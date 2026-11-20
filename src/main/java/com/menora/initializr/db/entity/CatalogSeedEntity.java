package com.menora.initializr.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * What this database remembers about one catalog row a release ships — see
 * {@link com.menora.initializr.db.CatalogSync}, which is the only reader and writer.
 */
@Entity
@Table(name = "initializer_catalog_seed")
public class CatalogSeedEntity {

    /**
     * Which catalog table the shipped row belongs to — in the order the sync writes them: a row
     * comes after what it needs (an entry after its group, an option, a rule, a file and a build
     * row after their dependency).
     */
    public enum RowTable {
        DEPENDENCY_GROUP, DEPENDENCY_ENTRY, SUB_OPTION, COMPATIBILITY_RULE, FILE_CONTRIBUTION, BUILD_CUSTOMIZATION
    }

    /** sha256 of the shipped row's identity. */
    @Id
    @Column(name = "seed_key", nullable = false, length = 64)
    private String seedKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "row_table", nullable = false, length = 30)
    private RowTable rowTable;

    /** The identity in words, for the log and the admin. */
    @Column(nullable = false, length = 1000)
    private String label;

    /**
     * Fingerprint of the shipped row as it was last written to this database. Null: the
     * database's row was never the shipped one — it differed, or was missing, when a release
     * first looked.
     */
    @Column(name = "applied_hash", length = 64)
    private String appliedHash;

    /** An admin chose to keep their own row: it is no longer reported as differing. */
    @Column(nullable = false)
    private boolean kept = false;

    /**
     * The database row that stands for the shipped one — kept for a dependency group, which an
     * admin may rename and which is still the same group then. Null for the other tables.
     */
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CatalogSeedEntity() {}

    public CatalogSeedEntity(String seedKey, RowTable rowTable, String label) {
        this.seedKey = seedKey;
        this.rowTable = rowTable;
        this.label = label;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public String getSeedKey() { return seedKey; }
    public RowTable getRowTable() { return rowTable; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getAppliedHash() { return appliedHash; }
    public void setAppliedHash(String appliedHash) { this.appliedHash = appliedHash; }
    public boolean isKept() { return kept; }
    public void setKept(boolean kept) { this.kept = kept; }
    public Long getRowId() { return rowId; }
    public void setRowId(Long rowId) { this.rowId = rowId; }
    public Instant getUpdatedAt() { return updatedAt; }
}
