package com.menora.initializr.sql;

import java.util.List;

public record TableModel(
        String name,
        String schema,
        List<ColumnModel> columns,
        List<String> pkColumns,
        List<ForeignKey> foreignKeys) {

    public boolean hasCompositePk() {
        return pkColumns.size() > 1;
    }
}
