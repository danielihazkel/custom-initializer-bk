package com.menora.initializr.db.entity;

/**
 * Discriminator for catalog entries — separates the Spring Boot backend
 * catalog from the React/Vite frontend catalog. Existing rows default to
 * {@link #BACKEND} via {@code @ColumnDefault("'BACKEND'")} so the column
 * add is backwards-compatible on H2's {@code ALTER TABLE ADD COLUMN}.
 */
public enum ProjectKind {
    BACKEND,
    FRONTEND
}
