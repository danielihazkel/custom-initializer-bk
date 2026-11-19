package com.menora.initializr.liveschema;

import java.util.List;

/**
 * What the live schema reader takes from a database's catalog: tables and views with their
 * columns (type, default, comment), keys, foreign keys, unique constraints, indexes and the
 * {@code CHECK (col IN …)} lists. Names are exactly as the database stores them (PostgreSQL
 * lower case, Oracle and DB2 upper case).
 */
public final class LiveSchemaModel {

    private LiveSchemaModel() {}

    public enum Kind { TABLE, VIEW }

    /** One entry of a schema's table list. */
    public record TableRef(String name, Kind kind) {}

    /**
     * A column as the driver describes it. {@code typeName} is the driver's {@code TYPE_NAME};
     * {@code size}/{@code digits} its {@code COLUMN_SIZE}/{@code DECIMAL_DIGITS} (null when the
     * driver reports none). {@code defaultValue} is the default as the catalog keeps it (an
     * expression in the database's own words), {@code comment} the column's comment; both null
     * when there is none.
     */
    public record Column(String name, String typeName, Integer size, Integer digits,
                         boolean nullable, boolean autoIncrement, String defaultValue, String comment) {

        public Column(String name, String typeName, Integer size, Integer digits, boolean nullable, boolean autoIncrement) {
            this(name, typeName, size, digits, nullable, autoIncrement, null, null);
        }
    }

    /** A foreign key, its columns in key order. */
    public record ForeignKey(List<String> columns, String referencedSchema, String referencedTable,
                             List<String> referencedColumns) {}

    /** {@code CHECK (column IN (values))}, read from whatever form the database keeps it in. */
    public record CheckIn(String column, List<String> values) {}

    /** One key column of an index. */
    public record IndexColumn(String name, boolean descending) {}

    /**
     * An index that is not a key or a unique constraint of the table. {@code skipped} says why
     * the script cannot carry it — it is partial, over an expression, or of a kind
     * {@code CREATE INDEX name ON t (columns)} does not describe — and is null for a plain index.
     */
    public record Index(String name, boolean unique, List<IndexColumn> columns, String skipped) {}

    public record Table(String schema, String name, Kind kind, List<Column> columns,
                        List<String> primaryKey, List<ForeignKey> foreignKeys,
                        List<List<String>> uniqueKeys, List<CheckIn> checks, List<Index> indexes, String comment) {

        public Table(String schema, String name, Kind kind, List<Column> columns, List<String> primaryKey,
                     List<ForeignKey> foreignKeys, List<List<String>> uniqueKeys, List<CheckIn> checks) {
            this(schema, name, kind, columns, primaryKey, foreignKeys, uniqueKeys, checks, List.of(), null);
        }

        public Column column(String name) {
            for (Column c : columns) if (c.name().equalsIgnoreCase(name)) return c;
            return null;
        }
    }

    /** What one read produced: the tables and views asked for, and what could not be read. */
    public record Schema(String schema, List<Table> tables, List<String> warnings) {}
}
