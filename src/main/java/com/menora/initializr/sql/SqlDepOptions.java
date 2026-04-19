package com.menora.initializr.sql;

import java.util.List;

/**
 * Wizard options for a single DB driver. {@code subPackage} defaults to
 * {@code "entity"} when omitted. {@code tables} carries per-table flags;
 * a table absent from the list is treated as {@code generateRepository=true}.
 */
public record SqlDepOptions(String subPackage, List<SqlTableOptions> tables) {

    public SqlDepOptions {
        if (subPackage == null || subPackage.isBlank()) {
            subPackage = "entity";
        }
        if (tables == null) {
            tables = List.of();
        }
    }

    public boolean generateRepositoryFor(String tableName) {
        return tables.stream()
                .filter(t -> t.name().equalsIgnoreCase(tableName))
                .findFirst()
                .map(SqlTableOptions::generateRepository)
                .orElse(true);
    }
}
