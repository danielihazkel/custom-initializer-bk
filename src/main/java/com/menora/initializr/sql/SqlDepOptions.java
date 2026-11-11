package com.menora.initializr.sql;

import java.util.List;

/**
 * Wizard options for a single DB driver. {@code subPackage} defaults to
 * {@code "entity"} when omitted. {@code tables} carries per-table flags;
 * a table absent from the list is treated as {@code generateRepository=true}.
 * {@code apiMode} controls whether a REST stack (Service/Controller, optional
 * DTO/mapper) is generated on top of the entities — defaults to
 * {@link SqlApiMode#NONE} (entities only).
 *
 * <p>{@code flywayBaseline}: with the Flyway dependency selected, whether the pasted DDL becomes
 * the project's first migration (null: yes). {@code generateTests}: a controller slice test per
 * generated REST controller.
 */
public record SqlDepOptions(String subPackage, List<SqlTableOptions> tables, SqlApiMode apiMode,
                            Boolean flywayBaseline, boolean generateTests) {

    public SqlDepOptions {
        if (subPackage == null || subPackage.isBlank()) {
            subPackage = "entity";
        }
        if (tables == null) {
            tables = List.of();
        }
        if (apiMode == null) {
            apiMode = SqlApiMode.NONE;
        }
    }

    /** The options before the Flyway baseline and the tests existed. */
    public SqlDepOptions(String subPackage, List<SqlTableOptions> tables, SqlApiMode apiMode) {
        this(subPackage, tables, apiMode, null, false);
    }

    /** Back-compat convenience for callers that don't generate a REST stack. */
    public SqlDepOptions(String subPackage, List<SqlTableOptions> tables) {
        this(subPackage, tables, SqlApiMode.NONE);
    }

    /** Whether the DDL is written as the Flyway baseline when Flyway is on the build. */
    public boolean flywayBaselineOrDefault() {
        return flywayBaseline == null || flywayBaseline;
    }

    public boolean generateRepositoryFor(String tableName) {
        return tables.stream()
                .filter(t -> t.name().equalsIgnoreCase(tableName))
                .findFirst()
                .map(SqlTableOptions::generateRepository)
                .orElse(true);
    }
}
