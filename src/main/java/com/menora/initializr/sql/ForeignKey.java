package com.menora.initializr.sql;

import java.util.List;

public record ForeignKey(
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns) {
}
