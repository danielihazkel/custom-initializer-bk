package com.menora.initializr.liveschema;

import java.util.List;

/**
 * What the live schema reader takes from a database's catalog: tables and views with their
 * columns, keys, foreign keys, unique constraints and the {@code CHECK (col IN …)} lists. Names
 * are exactly as the database stores them (PostgreSQL lower case, Oracle and DB2 upper case).
 */
public final class LiveSchemaModel {

    private LiveSchemaModel() {}

    public enum Kind { TABLE, VIEW }

    /** One entry of a schema's table list. */
    public record TableRef(String name, Kind kind) {}

    /**
     * A column as the driver describes it. {@code typeName} is the driver's {@code TYPE_NAME};
     * {@code size}/{@code digits} its {@code COLUMN_SIZE}/{@code DECIMAL_DIGITS} (null when the
     * driver reports none).
     */
    public record Column(String name, String typeName, Integer size, Integer digits,
                         boolean nullable, boolean autoIncrement) {}

    /** A foreign key, its columns in key order. */
    public record ForeignKey(List<String> columns, String referencedSchema, String referencedTable,
                             List<String> referencedColumns) {}

    /** {@code CHECK (column IN (values))}, read from whatever form the database keeps it in. */
    public record CheckIn(String column, List<String> values) {}

    public record Table(String schema, String name, Kind kind, List<Column> columns,
                        List<String> primaryKey, List<ForeignKey> foreignKeys,
                        List<List<String>> uniqueKeys, List<CheckIn> checks) {

        public Column column(String name) {
            for (Column c : columns) if (c.name().equalsIgnoreCase(name)) return c;
            return null;
        }
    }

    /** What one read produced: the tables and views asked for, and what could not be read. */
    public record Schema(String schema, List<Table> tables, List<String> warnings) {}
}
