package com.menora.initializr.sql;

import java.util.List;

public record TableModel(
        String name,
        String schema,
        List<ColumnModel> columns,
        List<String> pkColumns,
        List<ForeignKey> foreignKeys,
        String viewQuery) {

    /** Back-compat constructor for table-backed entities (no {@code @Subselect} view). */
    public TableModel(String name, String schema, List<ColumnModel> columns,
                      List<String> pkColumns, List<ForeignKey> foreignKeys) {
        this(name, schema, columns, pkColumns, foreignKeys, null);
    }

    public boolean hasCompositePk() {
        return pkColumns.size() > 1;
    }

    /** True when this entity maps to a SELECT via {@code @Immutable}/{@code @Subselect}
     *  rather than a {@code @Table}. */
    public boolean isView() {
        return viewQuery != null;
    }
}
