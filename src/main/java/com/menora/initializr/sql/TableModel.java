package com.menora.initializr.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public record TableModel(
        String name,
        String schema,
        List<ColumnModel> columns,
        List<String> pkColumns,
        List<ForeignKey> foreignKeys,
        String viewQuery,
        String sourceSql,
        Set<String> quotedNames,
        List<List<String>> uniqueConstraints,
        Map<String, List<String>> enumValues,
        List<TableIndex> indexes,
        List<String> notes) {

    /**
     * {@code quotedNames}: the table/schema/column names the DDL quoted (bare, exact case), so the
     * renderer can keep their exact spelling — see {@link SqlNames}. {@code uniqueConstraints}:
     * the UNIQUE constraints over more than one column (a single-column one is the column's own
     * {@code isUnique}). {@code enumValues}: the columns a {@code CHECK (col IN (...))} limits to
     * a fixed set of values, by lower-case column name. {@code indexes}: the plain indexes declared
     * with {@code CREATE INDEX}. {@code notes}: what the script says of this table that the
     * generated code cannot (a partial index, one over an expression), a sentence each. None of
     * them is ever null.
     */
    public TableModel {
        quotedNames = quotedNames == null ? Set.of() : Set.copyOf(quotedNames);
        uniqueConstraints = uniqueConstraints == null ? List.of() : List.copyOf(uniqueConstraints);
        enumValues = enumValues == null ? Map.of() : Map.copyOf(enumValues);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** Constructor without indexes and notes. */
    public TableModel(String name, String schema, List<ColumnModel> columns, List<String> pkColumns,
                      List<ForeignKey> foreignKeys, String viewQuery, String sourceSql, Set<String> quotedNames,
                      List<List<String>> uniqueConstraints, Map<String, List<String>> enumValues) {
        this(name, schema, columns, pkColumns, foreignKeys, viewQuery, sourceSql, quotedNames, uniqueConstraints,
                enumValues, null, null);
    }

    /** Constructor without the constraints a column cannot carry itself. */
    public TableModel(String name, String schema, List<ColumnModel> columns, List<String> pkColumns,
                      List<ForeignKey> foreignKeys, String viewQuery, String sourceSql, Set<String> quotedNames) {
        this(name, schema, columns, pkColumns, foreignKeys, viewQuery, sourceSql, quotedNames, null, null);
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

    /** The values a CHECK limits {@code column} to, or null when it is free. */
    public List<String> enumValuesOf(String column) {
        return column == null ? null : enumValues.get(column.toLowerCase(java.util.Locale.ROOT));
    }

    // ── Constraints declared after the table (ALTER TABLE … ADD …) ───────────────────────────

    /** This table with {@code columns} as its primary key (each also NOT NULL, as a key is). */
    public TableModel withPrimaryKey(List<String> keyColumns) {
        Set<String> keys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        keys.addAll(keyColumns);
        List<ColumnModel> fixed = new ArrayList<>();
        List<String> canonical = new ArrayList<>();
        for (String key : keyColumns) {
            columns.stream().filter(c -> c.name().equalsIgnoreCase(key)).findFirst()
                    .ifPresent(c -> canonical.add(c.name()));
        }
        for (ColumnModel c : columns) {
            boolean key = keys.contains(c.name());
            fixed.add(new ColumnModel(c.name(), c.rawType(), c.precision(), c.scale(),
                    key ? false : c.nullable(), key || c.isPk(), c.isAutoIncrement(), c.isForeignKey(), c.isUnique()));
        }
        return new TableModel(name, schema, fixed, canonical.isEmpty() ? pkColumns : canonical, foreignKeys,
                viewQuery, sourceSql, quotedNames, uniqueConstraints, enumValues, indexes, notes);
    }

    /** This table with one more foreign key (its columns flagged as foreign-key columns). */
    public TableModel withForeignKey(ForeignKey fk) {
        Set<String> fkColumns = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        fkColumns.addAll(fk.columns());
        List<ColumnModel> fixed = new ArrayList<>();
        for (ColumnModel c : columns) {
            fixed.add(new ColumnModel(c.name(), c.rawType(), c.precision(), c.scale(), c.nullable(), c.isPk(),
                    c.isAutoIncrement(), c.isForeignKey() || fkColumns.contains(c.name()), c.isUnique()));
        }
        List<ForeignKey> fks = new ArrayList<>(foreignKeys);
        fks.add(fk);
        return new TableModel(name, schema, fixed, pkColumns, fks, viewQuery, sourceSql, quotedNames,
                uniqueConstraints, enumValues, indexes, notes);
    }

    /** This table with a UNIQUE over {@code uniqueColumns}: one column is flagged itself, several are kept together. */
    public TableModel withUnique(List<String> uniqueColumns) {
        if (uniqueColumns.size() == 1) {
            List<ColumnModel> fixed = new ArrayList<>();
            for (ColumnModel c : columns) {
                boolean hit = c.name().equalsIgnoreCase(uniqueColumns.get(0));
                fixed.add(new ColumnModel(c.name(), c.rawType(), c.precision(), c.scale(), c.nullable(), c.isPk(),
                        c.isAutoIncrement(), c.isForeignKey(), c.isUnique() || hit));
            }
            return new TableModel(name, schema, fixed, pkColumns, foreignKeys, viewQuery, sourceSql, quotedNames,
                    uniqueConstraints, enumValues, indexes, notes);
        }
        List<List<String>> all = new ArrayList<>(uniqueConstraints);
        all.add(List.copyOf(uniqueColumns));
        return new TableModel(name, schema, columns, pkColumns, foreignKeys, viewQuery, sourceSql, quotedNames,
                all, enumValues, indexes, notes);
    }

    /** This table with one more plain index. */
    public TableModel withIndex(TableIndex index) {
        List<TableIndex> all = new ArrayList<>(indexes);
        all.add(index);
        return new TableModel(name, schema, columns, pkColumns, foreignKeys, viewQuery, sourceSql, quotedNames,
                uniqueConstraints, enumValues, all, notes);
    }

    /** This table with one more sentence about what of it the generated code does not carry. */
    public TableModel withNote(String note) {
        List<String> all = new ArrayList<>(notes);
        all.add(note);
        return new TableModel(name, schema, columns, pkColumns, foreignKeys, viewQuery, sourceSql, quotedNames,
                uniqueConstraints, enumValues, indexes, all);
    }

    /** This view with its columns typed and the names its query quoted ({@link SqlScriptParser}). */
    TableModel withViewColumns(List<ColumnModel> typed, Set<String> quoted) {
        return new TableModel(name, schema, typed, pkColumns, foreignKeys, viewQuery, sourceSql, quoted,
                uniqueConstraints, enumValues, indexes, notes);
    }

    /** This table with {@code values} as the only values of {@code column}. */
    public TableModel withEnum(String column, List<String> values) {
        Map<String, List<String>> all = new LinkedHashMap<>(enumValues);
        all.put(column.toLowerCase(java.util.Locale.ROOT), List.copyOf(values));
        return new TableModel(name, schema, columns, pkColumns, foreignKeys, viewQuery, sourceSql, quotedNames,
                uniqueConstraints, all, indexes, notes);
    }
}
