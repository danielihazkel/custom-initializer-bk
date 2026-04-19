package com.menora.initializr.sql;

/**
 * Per-table wizard options, supplied by the UI. {@code generateRepository}
 * defaults to {@code true} when the UI omits the flag for a table.
 */
public record SqlTableOptions(String name, boolean generateRepository) {
}
