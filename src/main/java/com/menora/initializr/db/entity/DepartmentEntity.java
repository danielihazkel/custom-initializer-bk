package com.menora.initializr.db.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * An organisational department selectable on every generator screen (backend, frontend,
 * fullstack). The {@code departmentId} is what templates see as {@code {{department}}} —
 * it lands in k8s names (namespace, image repo, cert secret), so it is restricted to a
 * lower-case DNS-style slug.
 */
@Entity
@Table(name = "initializer_department")
public class DepartmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank @Size(max = 50)
    @Pattern(regexp = "^[a-z][a-z0-9-]*$",
            message = "must be lower-case letters, digits and '-', starting with a letter (e.g. lts)")
    @Column(name = "department_id", nullable = false, unique = true, length = 50)
    private String departmentId;

    @NotBlank @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    @JsonProperty("isDefault")
    public boolean isDefault() { return isDefault; }
    @JsonProperty("isDefault")
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
}
