package com.menora.initializr.fullstack.schema;

import java.util.List;

/**
 * The database schema a fullstack model's generated JPA mappings expect: tables, columns, keys and
 * indexes, with no database's SQL in it. {@link SchemaBuilder} derives it from the entity model,
 * {@link SchemaSql} writes it for one database, {@link SchemaDiff} compares two of them.
 *
 * <p>Names are the physical ones the entity templates put in {@code @Table} / {@code @Column} —
 * unquoted where the database may fold them, in double quotes where the model spells exact case.
 */
public record Schema(List<Table> tables) {

    /**
     * What a column holds, independent of any database. One constant per shape the entity
     * templates map; {@link SchemaDialect#typeOf} names it for each database.
     */
    public enum ColumnType {
        /** A {@code String} with a length. */
        VARCHAR,
        /** A {@code String} without one: TEXT and MARKDOWN fields ({@code columnDefinition = "TEXT"}). */
        TEXT,
        BIGINT,
        INTEGER,
        BOOLEAN,
        /** {@code LocalDate}. */
        DATE,
        /** {@code LocalDateTime}: no zone. */
        TIMESTAMP,
        /** {@code LocalTime}. */
        TIME,
        /** {@code Instant}: a point in time. */
        INSTANT,
        /** {@code BigDecimal} as Hibernate maps it without a precision. */
        DECIMAL,
        /** {@code BigDecimal} with precision 19, scale 2. */
        MONEY,
        UUID,
        /** A file's bytes ({@code byte[]} as {@code LONG32VARBINARY}). */
        BLOB
    }

    /**
     * @param length   for {@link ColumnType#VARCHAR}; null elsewhere
     * @param identity the database generates the value ({@code GenerationType.IDENTITY})
     * @param defaultFalse a boolean the generator always writes as false first (soft delete): it
     *                 has a default, so the column can be added to a table that has rows
     */
    public record Column(String name, ColumnType type, Integer length, boolean nullable, boolean identity,
                         boolean defaultFalse) {

        public static Column of(String name, ColumnType type, boolean nullable) {
            return new Column(name, type, null, nullable, false, false);
        }

        public static Column varchar(String name, int length, boolean nullable) {
            return new Column(name, ColumnType.VARCHAR, length, nullable, false, false);
        }
    }

    public record ForeignKey(String name, List<String> columns, String refSchema, String refTable,
                             List<String> refColumns) {
    }

    public record Unique(String name, List<String> columns) {
    }

    public record Index(String name, List<String> columns) {
    }

    /**
     * @param schema the table's schema ({@code @Table(schema = …)}), or null
     * @param primaryKey its key columns, in the key's order
     */
    public record Table(String schema, String name, List<Column> columns, List<String> primaryKey,
                        List<ForeignKey> foreignKeys, List<Unique> uniques, List<Index> indexes) {

        /** {@code schema.table}, or the table alone. */
        public String ref() {
            return schema == null ? name : schema + "." + name;
        }

        public Column column(String columnName) {
            for (Column c : columns) {
                if (c.name().equals(columnName)) return c;
            }
            return null;
        }
    }

    public Table table(String ref) {
        for (Table t : tables) {
            if (t.ref().equals(ref)) return t;
        }
        return null;
    }
}
