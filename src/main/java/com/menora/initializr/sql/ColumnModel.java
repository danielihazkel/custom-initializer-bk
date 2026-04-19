package com.menora.initializr.sql;

public record ColumnModel(
        String name,
        String rawType,
        Integer precision,
        Integer scale,
        boolean nullable,
        boolean isPk,
        boolean isAutoIncrement,
        boolean isForeignKey) {
}
