package com.menora.initializr.db.entity;

/**
 * Discriminator for {@link VersionDefinitionEntity} — keeps all selectable
 * version lists (previously split across {@code initializr.*} and
 * {@code frontend.*} YAML blocks) in one admin-managed table.
 */
public enum VersionKind {
    JAVA,
    BOOT,
    REACT,
    NODE,
    PACKAGE_MANAGER
}
