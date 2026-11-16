package com.menora.initializr.db.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A database an admin lets everyone read the schema of, from the SQL wizard and the fullstack
 * import (see {@code liveschema/}). Named by its parts — the reader builds the JDBC URL — and
 * without its password, which is read from the environment variable or property
 * {@link #passwordRef} names when a connection is opened.
 */
@Entity
@Table(name = "initializer_schema_source")
public class SchemaSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank @Size(max = 100)
    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Size(max = 500)
    @Column(length = 500)
    private String description;

    /** {@code POSTGRESQL}, {@code MSSQL}, {@code ORACLE} or {@code DB2} (IBM i). */
    @NotBlank
    @Pattern(regexp = "POSTGRESQL|MSSQL|ORACLE|DB2", message = "must be POSTGRESQL, MSSQL, ORACLE or DB2")
    @Column(nullable = false, length = 16)
    private String dialect;

    @NotBlank @Size(max = 253)
    @Column(nullable = false, length = 253)
    private String host;

    private Integer port;

    /** The database (PostgreSQL, SQL Server) or service name (Oracle); unused on DB2 for IBM i. */
    @Size(max = 128)
    @Column(name = "database_name", length = 128)
    private String databaseName;

    /** DB2 for IBM i: the library the connection starts in. */
    @Size(max = 128)
    @Column(length = 128)
    private String library;

    @NotBlank @Size(max = 128)
    @Column(nullable = false, length = 128)
    private String username;

    @Size(max = 255)
    @Column(name = "password_ref", length = 255)
    private String passwordRef;

    @Column(name = "trust_server_certificate", nullable = false)
    private boolean trustServerCertificate;

    /** The schema the picker opens on; blank: the one the connection starts in. */
    @Size(max = 128)
    @Column(name = "default_schema", length = 128)
    private String defaultSchema;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getLibrary() { return library; }
    public void setLibrary(String library) { this.library = library; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordRef() { return passwordRef; }
    public void setPasswordRef(String passwordRef) { this.passwordRef = passwordRef; }
    public boolean isTrustServerCertificate() { return trustServerCertificate; }
    public void setTrustServerCertificate(boolean trustServerCertificate) { this.trustServerCertificate = trustServerCertificate; }
    public String getDefaultSchema() { return defaultSchema; }
    public void setDefaultSchema(String defaultSchema) { this.defaultSchema = defaultSchema; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
