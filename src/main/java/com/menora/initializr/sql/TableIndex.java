package com.menora.initializr.sql;

import java.util.List;

/**
 * A plain index a script declares with {@code CREATE INDEX}: its columns in key order, each
 * ascending unless it says {@code DESC}. {@code name} is null for an index the script left
 * unnamed. A unique index is not one of these — it is the table's unique constraint.
 */
public record TableIndex(String name, List<Column> columns) {

    public TableIndex {
        columns = List.copyOf(columns);
    }

    /** One key column of the index, by the name the table spells it. */
    public record Column(String name, boolean descending) {}
}
