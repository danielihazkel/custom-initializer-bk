package com.menora.initializr.db.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One change to a catalog row (V31), as {@code CatalogHistoryRecorder} wrote it. Read-only here:
 * rows are inserted with JDBC inside the transaction of the change they describe.
 */
@Entity
@Immutable
@Table(name = "admin_catalog_change")
public class CatalogChangeEntity {

    public enum Action { CREATE, UPDATE, DELETE }

    @Id
    private Long id;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    @Column(name = "table_name", nullable = false, length = 64)
    private String tableName;

    @Column(name = "row_id", nullable = false, length = 64)
    private String rowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Action action;

    private String label;

    @Lob
    @Column(name = "before_json")
    private String beforeJson;

    @Lob
    @Column(name = "after_json")
    private String afterJson;

    @Column(name = "changed_fields", length = 1024)
    private String changedFields;

    public Long getId() { return id; }
    public Instant getChangedAt() { return changedAt; }
    public String getActor() { return actor; }
    public String getSource() { return source; }
    public String getRequestId() { return requestId; }
    public String getEntityType() { return entityType; }
    public String getTableName() { return tableName; }
    public String getRowId() { return rowId; }
    public Action getAction() { return action; }
    public String getLabel() { return label; }
    public String getBeforeJson() { return beforeJson; }
    public String getAfterJson() { return afterJson; }
    public String getChangedFields() { return changedFields; }
}
