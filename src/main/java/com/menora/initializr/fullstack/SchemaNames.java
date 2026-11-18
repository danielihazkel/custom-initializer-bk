package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;
import com.menora.initializr.sql.SqlNames;

import java.util.ArrayList;
import java.util.List;

/**
 * The physical names an entity model maps to: tables, schemas, columns, join columns and
 * join tables.
 * Part of {@link EntityScaffoldContext}, split out by area.
 */
public final class SchemaNames {

    private SchemaNames() {}

    /** The database column a field maps to: the schema's own spelling when the field carries one
     *  (DDL import), else the snake_case of its name. Every column name rendered for a field —
     *  {@code @Column}, the soft-delete SQL, a referencing entity's label/count subselects — goes
     *  through here, so they always agree. */
    public static String columnOf(FieldDefinition f) {
        return SqlNames.physical(f.column() != null ? f.column() : Naming.toSnakeCase(f.name()), null);
    }

    /** The foreign-key column of a relation: its own {@code joinColumn} when set (DDL import), else
     *  {@code <snake fieldName>_id}. Used by {@code @JoinColumn}, the label subselect and the
     *  parent's child-count subselect. */
    public static String joinColumnOf(RelationDefinition r) {
        return SqlNames.physical(r.joinColumn() != null ? r.joinColumn()
                : Naming.toSnakeCase(r.fieldName()) + "_id", null);
    }

    /** The join table of a MANY_TO_MANY: named, or {@code <entity>_<field>}. */
    public static String joinTableOf(EntityDefinition owner, RelationDefinition c) {
        return SqlNames.physical(c.joinTable() != null ? c.joinTable()
                : Naming.toSnakeCase(owner.name()) + "_" + Naming.toSnakeCase(c.fieldName()), null);
    }

    /** The join table's column for the owning entity: named, or {@code <entity>_id}. */
    public static String ownerJoinColumnOf(EntityDefinition owner, RelationDefinition c) {
        return SqlNames.physical(c.joinColumn() != null ? c.joinColumn()
                : Naming.toSnakeCase(owner.name()) + "_id", null);
    }

    /** The join table's column for the other entity: named, or {@code <target>_id}. */
    public static String inverseJoinColumnOf(RelationDefinition c) {
        return SqlNames.physical(c.inverseJoinColumn() != null
                ? c.inverseJoinColumn() : Naming.toSnakeCase(c.targetEntity()) + "_id", null);
    }

    /** The entity's table: its own {@code tableName} (DDL import, or by hand) else the snake plural
     *  of its name, through {@link SqlNames#physical(String, com.menora.initializr.sql.SqlDialect)}. */
    public static String tableOf(EntityDefinition e) {
        return SqlNames.physical(e.tableName() != null ? e.tableName()
                : Naming.pluralize(Naming.toSnakeCase(e.name())), null);
    }

    /** The entity's schema through {@link SqlNames}; null when it has none. */
    public static String schemaOf(EntityDefinition e) {
        return e.schema() == null || e.schema().isBlank() ? null : SqlNames.physical(e.schema(), null);
    }

    /** {@code schema.table} (or just the table) for native SQL. */
    static String tableRefOf(EntityDefinition e) {
        String schema = schemaOf(e);
        return (schema != null ? schema + "." : "") + tableOf(e);
    }

    /**
     * True when a table-backed entity renders a quoted name (one the schema spells with exact case),
     * so the backend needs {@link SqlNames#namingStrategySource} for it to reach the database as
     * written. A view's labels are never quoted here (no dialect at render time: they fold).
     */
    public static boolean rendersQuotedName(List<EntityDefinition> entities) {
        for (EntityDefinition e : entities) {
            if (e.isView()) continue;
            List<String> names = new ArrayList<>();
            names.add(tableOf(e));
            if (schemaOf(e) != null) names.add(schemaOf(e));
            e.fields().forEach(f -> names.add(columnOf(f)));
            e.relations().forEach(r -> names.add(joinColumnOf(r)));
            if (names.stream().anyMatch(SqlNames::isQuoted)) return true;
        }
        return false;
    }
}
