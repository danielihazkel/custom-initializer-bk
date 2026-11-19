package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;
import com.menora.initializr.sql.SqlDialect;
import com.menora.initializr.sql.SqlKeywords;
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

    /** The database this thread's request generates for; none outside a request (and in unit tests). */
    private static final ThreadLocal<SqlDialect> DATABASE = new ThreadLocal<>();

    /**
     * Says which database the names are rendered for, until {@link #clearDatabase}. From then on a
     * name that is a keyword there is rendered quoted, in the case that database folds bare names
     * to ({@link SqlNames#quoteFolded}): {@code order} is {@code "ORDER"} on H2 and Oracle,
     * {@code "order"} on PostgreSQL. The mapping, the native SQL the templates splice and the
     * migration all read their names here, so they agree. A name the database takes bare stays
     * bare, one that H2 reserves included: the project's own tests, which run on H2, tell H2 that
     * the word is no keyword ({@link #freedOnH2}). With no database said, names are rendered as
     * they always were.
     */
    public static void forDatabase(SqlDialect dialect) {
        if (dialect == null) DATABASE.remove();
        else DATABASE.set(dialect);
    }

    public static void clearDatabase() {
        DATABASE.remove();
    }

    /** The database said by {@link #forDatabase}, or null. */
    public static SqlDialect database() {
        return DATABASE.get();
    }

    /**
     * {@code true} / {@code false} as the request's database writes them in SQL the templates
     * splice (the soft-delete mark) — {@link com.menora.initializr.fullstack.schema.SchemaDialect#flag}.
     * With no database said: the words, as before.
     */
    public static String booleanLiteral(boolean value) {
        SqlDialect database = DATABASE.get();
        com.menora.initializr.fullstack.schema.SchemaDialect dialect = database == null ? null
                : com.menora.initializr.fullstack.schema.SchemaDialect.ofDependency(database.depId());
        if (dialect == null) return String.valueOf(value);
        return dialect.flag(value);
    }

    /** {@code column} compared with true or false, as the request's database (and H2, for its tests) reads it. */
    public static String booleanTest(String column, boolean value) {
        SqlDialect database = DATABASE.get();
        com.menora.initializr.fullstack.schema.SchemaDialect dialect = database == null ? null
                : com.menora.initializr.fullstack.schema.SchemaDialect.ofDependency(database.depId());
        return dialect == null ? column + " = " + value : dialect.flagIs(column, value);
    }

    /** A rendered name, quoted when it is a keyword for the request's database. */
    private static String pin(String physical) {
        SqlDialect database = DATABASE.get();
        if (database == null || physical == null || SqlNames.isQuoted(physical)) return physical;
        return SqlKeywords.reserved(physical, database) ? SqlNames.quoteFolded(physical, database) : physical;
    }

    /** Every table, schema, column and join name an entity renders, as rendered. */
    private static List<String> names(EntityDefinition e) {
        List<String> names = new ArrayList<>();
        names.add(tableOf(e));
        if (schemaOf(e) != null) names.add(schemaOf(e));
        e.fields().forEach(f -> names.add(columnOf(f)));
        e.relations().forEach(r -> names.add(joinColumnOf(r)));
        e.collections().forEach(c -> {
            names.add(joinTableOf(e, c));
            names.add(ownerJoinColumnOf(e, c));
            names.add(inverseJoinColumnOf(c));
        });
        e.fields().stream().filter(FieldDefinition::multiple).forEach(f -> names.add(filesTableOf(e, f)));
        return names;
    }

    /**
     * The words H2 has to be told are no keywords, for a project on another database to run its
     * tests on H2 ({@code ;NON_KEYWORDS=…} on the test connection): the bare names of the model
     * that H2 reserves. In upper case, sorted; empty for a project on H2 (there such a name is
     * quoted) and when no database was said.
     */
    public static List<String> freedOnH2(List<EntityDefinition> entities) {
        SqlDialect database = DATABASE.get();
        if (database == null || database == SqlDialect.H2) return List.of();
        java.util.Set<String> words = new java.util.TreeSet<>();
        for (EntityDefinition e : entities) {
            if (e.isView()) continue;
            for (String name : names(e)) {
                if (!SqlNames.isQuoted(name) && SqlKeywords.reserved(name, SqlDialect.H2)) {
                    words.add(name.toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        return List.copyOf(words);
    }

    /**
     * Whether a rendered name can be written in a {@code @Formula} about another table. A quoted
     * one cannot: Hibernate reads a quoted name there as a column of the entity itself and puts
     * the entity's alias before it.
     */
    public static boolean formulaCanName(String rendered) {
        return rendered != null && rendered.indexOf('"') < 0 && rendered.indexOf((char) 96) < 0 && rendered.indexOf('[') < 0;
    }

    /** The name a field's column has before any quoting: what a keyword or system-column check reads. */
    public static String bareColumnOf(FieldDefinition f) {
        return SqlNames.unquote(SqlNames.physical(f.column() != null ? f.column() : Naming.toSnakeCase(f.name()), null));
    }

    /** The database column a field maps to: the schema's own spelling when the field carries one
     *  (DDL import), else the snake_case of its name. Every column name rendered for a field —
     *  {@code @Column}, the soft-delete SQL, a referencing entity's label/count subselects — goes
     *  through here, so they always agree. */
    public static String columnOf(FieldDefinition f) {
        return pin(SqlNames.physical(f.column() != null ? f.column() : Naming.toSnakeCase(f.name()), null));
    }

    /** The foreign-key column of a relation: its own {@code joinColumn} when set (DDL import), else
     *  {@code <snake fieldName>_id}. Used by {@code @JoinColumn}, the label subselect and the
     *  parent's child-count subselect. */
    public static String joinColumnOf(RelationDefinition r) {
        return pin(SqlNames.physical(r.joinColumn() != null ? r.joinColumn()
                : Naming.toSnakeCase(r.fieldName()) + "_id", null));
    }

    /** The join table of a MANY_TO_MANY: named, or {@code <entity>_<field>}. */
    public static String joinTableOf(EntityDefinition owner, RelationDefinition c) {
        return pin(SqlNames.physical(c.joinTable() != null ? c.joinTable()
                : Naming.toSnakeCase(owner.name()) + "_" + Naming.toSnakeCase(c.fieldName()), null));
    }

    /**
     * The table of a field that holds several files ({@code maxFiles}): {@code <entity>_<field>} —
     * the many-to-many's rule, so the two are named alike. Its columns are fixed:
     * {@code owner_id}, {@code file_order}, {@code attachment_id}, {@code file_name}.
     */
    public static String filesTableOf(EntityDefinition owner, FieldDefinition field) {
        return pin(SqlNames.physical(Naming.toSnakeCase(owner.name()) + "_" + Naming.toSnakeCase(field.name()), null));
    }

    /** {@link #filesTableOf}, schema-qualified like the entity's own table, for native SQL. */
    static String filesTableRefOf(EntityDefinition owner, FieldDefinition field) {
        String schema = schemaOf(owner);
        return (schema != null ? schema + "." : "") + filesTableOf(owner, field);
    }

    /** The join table's column for the owning entity: named, or {@code <entity>_id}. */
    public static String ownerJoinColumnOf(EntityDefinition owner, RelationDefinition c) {
        return pin(SqlNames.physical(c.joinColumn() != null ? c.joinColumn()
                : Naming.toSnakeCase(owner.name()) + "_id", null));
    }

    /** The join table's column for the other entity: named, or {@code <target>_id}. */
    public static String inverseJoinColumnOf(RelationDefinition c) {
        return pin(SqlNames.physical(c.inverseJoinColumn() != null
                ? c.inverseJoinColumn() : Naming.toSnakeCase(c.targetEntity()) + "_id", null));
    }

    /** The entity's table: its own {@code tableName} (DDL import, or by hand) else the snake plural
     *  of its name, through {@link SqlNames#physical(String, com.menora.initializr.sql.SqlDialect)}. */
    public static String tableOf(EntityDefinition e) {
        return pin(SqlNames.physical(e.tableName() != null ? e.tableName()
                : Naming.pluralize(Naming.toSnakeCase(e.name())), null));
    }

    /** The entity's schema through {@link SqlNames}; null when it has none. */
    public static String schemaOf(EntityDefinition e) {
        return e.schema() == null || e.schema().isBlank() ? null : pin(SqlNames.physical(e.schema(), null));
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
            if (names(e).stream().anyMatch(SqlNames::isQuoted)) return true;
        }
        return false;
    }
}
