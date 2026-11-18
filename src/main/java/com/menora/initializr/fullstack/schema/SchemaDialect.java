package com.menora.initializr.fullstack.schema;

import com.menora.initializr.fullstack.schema.Schema.Column;
import com.menora.initializr.fullstack.schema.Schema.ColumnType;

import java.util.Collection;

/**
 * The databases a generated backend can be given a migration for, and how each spells a column.
 *
 * <p>The type names are the ones Hibernate's own dialect writes for the mapping the entity
 * templates use — so a schema created from the migration is the schema Hibernate would have
 * created, and {@code ddl-auto: validate} accepts it. {@code SchemaDialectTest} holds this table
 * to that: it has Hibernate export a fixture entity for each dialect and compares every column.
 */
public enum SchemaDialect {
    H2("h2"),
    POSTGRESQL("postgresql"),
    ORACLE("oracle"),
    MSSQL("mssql"),
    DB2("db2");

    private final String dependencyId;

    SchemaDialect(String dependencyId) {
        this.dependencyId = dependencyId;
    }

    /** The catalog dependency that selects this database. */
    public String dependencyId() {
        return dependencyId;
    }

    /**
     * The database a project's migration is written for: the first of PostgreSQL, Oracle, SQL
     * Server and DB2 among its dependencies — a project that names one of those beside H2 runs on
     * it and uses H2 for its tests at most — else H2.
     */
    public static SchemaDialect of(Collection<String> dependencyIds) {
        for (SchemaDialect dialect : new SchemaDialect[] {POSTGRESQL, ORACLE, MSSQL, DB2}) {
            if (dependencyIds.contains(dialect.dependencyId)) return dialect;
        }
        return H2;
    }

    /** The column's type as this database spells it, without NOT NULL or identity. */
    public String typeOf(Column column) {
        ColumnType type = column.type();
        return switch (this) {
            case H2 -> switch (type) {
                case VARCHAR -> "varchar(" + column.length() + ")";
                case TEXT -> "text";
                case BIGINT -> "bigint";
                case INTEGER -> "integer";
                case BOOLEAN -> "boolean";
                case DATE -> "date";
                case TIMESTAMP -> "timestamp(6)";
                case TIME -> "time(6)";
                case INSTANT -> "timestamp(6) with time zone";
                case DECIMAL -> "numeric(38,2)";
                case MONEY -> "numeric(19,2)";
                case UUID -> "uuid";
                case BLOB -> "blob";
            };
            case POSTGRESQL -> switch (type) {
                case VARCHAR -> "varchar(" + column.length() + ")";
                case TEXT -> "text";
                case BIGINT -> "bigint";
                case INTEGER -> "integer";
                case BOOLEAN -> "boolean";
                case DATE -> "date";
                case TIMESTAMP -> "timestamp(6)";
                case TIME -> "time(6)";
                case INSTANT -> "timestamp(6) with time zone";
                case DECIMAL -> "numeric(38,2)";
                case MONEY -> "numeric(19,2)";
                case UUID -> "uuid";
                case BLOB -> "bytea";
            };
            case ORACLE -> switch (type) {
                case VARCHAR -> "varchar2(" + column.length() + " char)";
                case TEXT -> "clob";
                case BIGINT -> "number(19,0)";
                case INTEGER -> "number(10,0)";
                case BOOLEAN -> "number(1,0)";
                case DATE -> "date";
                case TIMESTAMP -> "timestamp(6)";
                case TIME -> "timestamp(6)";
                case INSTANT -> "timestamp(6) with time zone";
                case DECIMAL -> "number(38,2)";
                case MONEY -> "number(19,2)";
                case UUID -> "raw(16)";
                case BLOB -> "blob";
            };
            case MSSQL -> switch (type) {
                case VARCHAR -> "varchar(" + column.length() + ")";
                case TEXT -> "varchar(max)";
                case BIGINT -> "bigint";
                case INTEGER -> "int";
                case BOOLEAN -> "bit";
                case DATE -> "date";
                case TIMESTAMP -> "datetime2(6)";
                case TIME -> "time";
                case INSTANT -> "datetimeoffset(6)";
                case DECIMAL -> "numeric(38,2)";
                case MONEY -> "numeric(19,2)";
                case UUID -> "uniqueidentifier";
                case BLOB -> "varbinary(max)";
            };
            case DB2 -> switch (type) {
                case VARCHAR -> "varchar(" + column.length() + ")";
                case TEXT -> "clob";
                case BIGINT -> "bigint";
                case INTEGER -> "integer";
                case BOOLEAN -> "boolean";
                case DATE -> "date";
                case TIMESTAMP -> "timestamp(6)";
                case TIME -> "time";
                case INSTANT -> "timestamp(6)";
                case DECIMAL -> "decimal(31,2)";
                case MONEY -> "decimal(19,2)";
                case UUID -> "binary(16)";
                case BLOB -> "blob";
            };
        };
    }

    /** What follows the type of a column the database numbers itself. */
    public String identityClause() {
        return switch (this) {
            // Oracle was `generated as identity` (ALWAYS) until Hibernate 6.6 — which Boot 3.5
            // brings — started writing BY DEFAULT; both insert the same way for IDENTITY ids, and
            // BY DEFAULT also lets a person insert a row with an id of their own.
            case H2, POSTGRESQL, DB2, ORACLE -> "generated by default as identity";
            case MSSQL -> "identity";
        };
    }

    /** The literal for false in a column default. */
    public String falseLiteral() {
        return switch (this) {
            case H2, POSTGRESQL, DB2 -> "false";
            case ORACLE, MSSQL -> "0";
        };
    }

    /** Whether {@code CREATE SCHEMA IF NOT EXISTS} can be written; elsewhere the schema must exist. */
    public boolean createsSchemas() {
        return this == H2 || this == POSTGRESQL;
    }

    /** {@code ADD COLUMN} is one word short on Oracle and SQL Server. */
    public String addColumn() {
        return switch (this) {
            case H2, POSTGRESQL, DB2 -> "add column";
            case ORACLE, MSSQL -> "add";
        };
    }

    /**
     * Whether a unique constraint may cover a column that can be null. DB2 refuses one, and SQL
     * Server lets only one row have the null; on those two it is written as a unique index that
     * leaves nulls out, as Hibernate's own dialects do.
     */
    public boolean uniqueConstraintTakesNulls() {
        return this != DB2 && this != MSSQL;
    }

    /** {@code alter table t …} that lets the column be null again. */
    public String dropNotNull(String table, Column column) {
        return "alter table " + table + switch (this) {
            case H2, POSTGRESQL, DB2 -> " alter column " + column.name() + " drop not null";
            case ORACLE -> " modify " + column.name() + " null";
            case MSSQL -> " alter column " + column.name() + " " + typeOf(column) + " null";
        };
    }

    /** {@code alter table t …} that requires a value in the column. */
    public String setNotNull(String table, Column column) {
        return "alter table " + table + switch (this) {
            case H2, POSTGRESQL, DB2 -> " alter column " + column.name() + " set not null";
            case ORACLE -> " modify " + column.name() + " not null";
            case MSSQL -> " alter column " + column.name() + " " + typeOf(column) + " not null";
        };
    }

    /** {@code alter table t …} that gives the column its new type. */
    public String alterType(String table, Column column) {
        return "alter table " + table + switch (this) {
            case H2, DB2 -> " alter column " + column.name() + " set data type " + typeOf(column);
            case POSTGRESQL -> " alter column " + column.name() + " type " + typeOf(column);
            case ORACLE -> " modify " + column.name() + " " + typeOf(column);
            case MSSQL -> " alter column " + column.name() + " " + typeOf(column) + (column.nullable() ? " null" : " not null");
        };
    }

    /** {@code drop index …}: SQL Server names the table, the others the index alone. */
    public String dropIndex(String schema, String table, String index) {
        if (this == MSSQL) return "drop index " + index + " on " + table;
        return "drop index " + (schema == null ? "" : schema + ".") + index;
    }
}
