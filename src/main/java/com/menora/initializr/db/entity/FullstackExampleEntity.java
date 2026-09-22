package com.menora.initializr.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * A ready-made entity model offered under the Fullstack tab's "Start from → Examples".
 * {@code entities} is the JSON array of wire entities (the {@code entities} of a
 * {@code POST /starter-fullstack.zip} body) stored as text; the admin controller validates it
 * with the generator's own validator before saving, so a stored example always generates.
 */
@Entity
@Table(name = "initializer_fullstack_example")
public class FullstackExampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "example_id", nullable = false, unique = true, length = 50)
    private String exampleId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    /** Material Symbols icon name shown on the example card. */
    @Column(length = 60)
    private String icon;

    @Lob
    @Column(nullable = false)
    private String entities;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false)
    private boolean enabled = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getExampleId() { return exampleId; }
    public void setExampleId(String exampleId) { this.exampleId = exampleId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getEntities() { return entities; }
    public void setEntities(String entities) { this.entities = entities; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
