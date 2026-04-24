package com.menora.initializr.db.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "generation_event")
public class GenerationEventEntity {

    public enum Status { SUCCESS, FAILURE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(nullable = false, length = 64)
    private String endpoint;

    @Column(name = "artifact_id", length = 255)
    private String artifactId;

    @Column(name = "group_id", length = 255)
    private String groupId;

    @Column(name = "boot_version", length = 32)
    private String bootVersion;

    @Column(name = "java_version", length = 16)
    private String javaVersion;

    @Column(length = 16)
    private String packaging;

    @Column(length = 16)
    private String language;

    @Lob
    @Column(name = "dependency_ids")
    private String dependencyIds;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "remote_addr", length = 64)
    private String remoteAddr;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getEventTimestamp() { return eventTimestamp; }
    public void setEventTimestamp(Instant eventTimestamp) { this.eventTimestamp = eventTimestamp; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getBootVersion() { return bootVersion; }
    public void setBootVersion(String bootVersion) { this.bootVersion = bootVersion; }
    public String getJavaVersion() { return javaVersion; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }
    public String getPackaging() { return packaging; }
    public void setPackaging(String packaging) { this.packaging = packaging; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getDependencyIds() { return dependencyIds; }
    public void setDependencyIds(String dependencyIds) { this.dependencyIds = dependencyIds; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getRemoteAddr() { return remoteAddr; }
    public void setRemoteAddr(String remoteAddr) { this.remoteAddr = remoteAddr; }
}
