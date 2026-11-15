package com.menora.initializr.fullstack.schema;

import com.menora.initializr.fullstack.schema.Schema.Column;
import com.menora.initializr.fullstack.schema.Schema.ForeignKey;
import com.menora.initializr.fullstack.schema.Schema.Index;
import com.menora.initializr.fullstack.schema.Schema.Table;
import com.menora.initializr.fullstack.schema.Schema.Unique;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link Schema} as one database's SQL: the statements of a Flyway migration.
 *
 * <p>Tables come first, each with its key and unique constraints; foreign keys and indexes after
 * all of them, so tables that refer to each other (or to themselves) need no particular order.
 */
public final class SchemaSql {

    private SchemaSql() {}

    /** Where the generated backend keeps its first migration. */
    public static final String BASELINE_PATH = "src/main/resources/db/migration/V1__init.sql";

    /** The whole schema, as the first migration of a new project. */
    public static String baseline(Schema schema, SchemaDialect dialect) {
        StringBuilder sql = new StringBuilder();
        sql.append("-- The schema of this project's entities, as the Menora Initializr generated it for ")
                .append(label(dialect)).append(".\n")
                .append("-- Flyway runs it once, on an empty database. Do not edit it after it has run anywhere:\n")
                .append("-- add a new migration (V2__..., or a timestamp like the generator's own updates) instead.\n");
        Set<String> schemas = new LinkedHashSet<>();
        for (Table table : schema.tables()) {
            if (table.schema() != null) schemas.add(table.schema());
        }
        for (String name : schemas) {
            sql.append('\n');
            if (dialect.createsSchemas()) {
                sql.append("create schema if not exists ").append(name).append(";\n");
            } else {
                sql.append("-- The schema ").append(name).append(" must exist before this runs.\n");
            }
        }
        for (Table table : schema.tables()) {
            sql.append('\n').append(createTable(table, dialect));
        }
        List<String> after = new ArrayList<>();
        for (Table table : schema.tables()) after.addAll(constraintsAndIndexes(table, dialect));
        if (!after.isEmpty()) sql.append('\n');
        for (String statement : after) sql.append(statement).append('\n');
        return sql.toString();
    }

    /** {@code create table …;} with the key and the unique constraints, ending in a newline. */
    static String createTable(Table table, SchemaDialect dialect) {
        List<String> lines = new ArrayList<>();
        for (Column column : table.columns()) lines.add(columnDefinition(column, dialect));
        if (!table.primaryKey().isEmpty()) {
            lines.add("constraint " + SchemaBuilder.constraint("pk", table.name())
                    + " primary key (" + String.join(", ", table.primaryKey()) + ")");
        }
        for (Unique unique : table.uniques()) {
            if (uniqueIsInline(table, unique, dialect)) lines.add(uniqueClause(unique));
        }
        return "create table " + table.ref() + " (\n    " + String.join(",\n    ", lines) + "\n);\n";
    }

    /** {@code name type [identity] [default …] [not null]}, in the order all five databases take. */
    static String columnDefinition(Column column, SchemaDialect dialect) {
        StringBuilder definition = new StringBuilder(column.name()).append(' ').append(dialect.typeOf(column));
        if (column.identity()) definition.append(' ').append(dialect.identityClause());
        if (column.defaultFalse()) definition.append(" default ").append(dialect.falseLiteral());
        if (!column.nullable()) definition.append(" not null");
        return definition.toString();
    }

    static String uniqueClause(Unique unique) {
        return "constraint " + unique.name() + " unique (" + String.join(", ", unique.columns()) + ")";
    }

    /**
     * Whether a unique constraint is written inside {@code create table}: always, except over a
     * column that can be null on a database whose unique constraints do not take nulls.
     */
    static boolean uniqueIsInline(Table table, Unique unique, SchemaDialect dialect) {
        if (dialect.uniqueConstraintTakesNulls()) return true;
        for (String name : unique.columns()) {
            Column column = table.column(name);
            if (column != null && column.nullable()) return false;
        }
        return true;
    }

    /** The unique index that stands in for such a constraint: nulls are not compared. */
    static String createUniqueIndex(Table table, Unique unique, SchemaDialect dialect) {
        String columns = String.join(", ", unique.columns());
        if (dialect == SchemaDialect.DB2) {
            return "create unique index " + unique.name() + " on " + table.ref() + " (" + columns + ") exclude null keys;";
        }
        List<String> notNull = new ArrayList<>();
        for (String name : unique.columns()) notNull.add(name + " is not null");
        return "create unique nonclustered index " + unique.name() + " on " + table.ref() + " (" + columns
                + ") where " + String.join(" and ", notNull) + ";";
    }

    /** What follows the tables: unique indexes, foreign keys, then indexes — each with its semicolon. */
    static List<String> constraintsAndIndexes(Table table, SchemaDialect dialect) {
        List<String> statements = new ArrayList<>();
        for (Unique unique : table.uniques()) {
            if (!uniqueIsInline(table, unique, dialect)) statements.add(createUniqueIndex(table, unique, dialect));
        }
        for (ForeignKey fk : table.foreignKeys()) statements.add(addForeignKey(table, fk));
        for (Index index : table.indexes()) statements.add(createIndex(table, index));
        return statements;
    }

    static String addForeignKey(Table table, ForeignKey fk) {
        String referenced = fk.refSchema() == null ? fk.refTable() : fk.refSchema() + "." + fk.refTable();
        return "alter table " + table.ref() + " add constraint " + fk.name() + " foreign key ("
                + String.join(", ", fk.columns()) + ") references " + referenced + " ("
                + String.join(", ", fk.refColumns()) + ");";
    }

    static String createIndex(Table table, Index index) {
        return "create index " + index.name() + " on " + table.ref() + " (" + String.join(", ", index.columns()) + ");";
    }

    static String label(SchemaDialect dialect) {
        return switch (dialect) {
            case H2 -> "H2";
            case POSTGRESQL -> "PostgreSQL";
            case ORACLE -> "Oracle";
            case MSSQL -> "SQL Server";
            case DB2 -> "DB2";
        };
    }
}
