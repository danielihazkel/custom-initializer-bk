package com.menora.initializr.sql;

import java.util.List;
import java.util.Set;

public record TableModel(
        String name,
        String schema,
        List<ColumnModel> columns,
        List<String> pkColumns,
        List<ForeignKey> foreignKeys,
        String viewQuery,
        String sourceSql,
        Set<String> quotedNames) {

    /** {@code quotedNames}: the table/schema/column names the DDL quoted (bare, exact case), so the
     *  renderer can keep their exact spelling — see {@link SqlNames}. Never null. */
    public TableModel {
        quotedNames = quotedNames == null ? Set.of() : Set.copyOf(quotedNames);
    }

    /** Constructor without quoted names (nothing was quoted). */
    public TableModel(String name, String schema, List<ColumnModel> columns, List<String> pkColumns,
                      List<ForeignKey> foreignKeys, String viewQuery, String sourceSql) {
        this(name, schema, columns, pkColumns, foreignKeys, viewQuery, sourceSql, null);
    }

    /** The rendered form of one of this table's names (its own, its schema's, a column's, or a
     *  name its foreign keys reference) — {@link SqlNames#physical(String, boolean, SqlDialect)}
     *  with whether the DDL quoted it. */
    public String physical(String name, SqlDialect dialect) {
        return SqlNames.physical(name, name != null && quotedNames.contains(name), dialect);
    }

    /** Back-compat constructor for table-backed entities (no {@code @Subselect} view). */
    public TableModel(String name, String schema, List<ColumnModel> columns,
                      List<String> pkColumns, List<ForeignKey> foreignKeys) {
        this(name, schema, columns, pkColumns, foreignKeys, null, null);
    }

    /** Constructor for {@code @Subselect} views (carries {@code viewQuery}). */
    public TableModel(String name, String schema, List<ColumnModel> columns,
                      List<String> pkColumns, List<ForeignKey> foreignKeys, String viewQuery) {
        this(name, schema, columns, pkColumns, foreignKeys, viewQuery, null);
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
