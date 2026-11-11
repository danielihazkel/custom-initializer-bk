package com.menora.initializr.fullstack;

/**
 * One relationship from a user-defined entity to another entity in the same request.
 *
 * <p>v1 supports {@link RelationType#MANY_TO_ONE}: this entity owns a foreign key to
 * {@code targetEntity}. {@code fieldName} is the Java association field on the owning side
 * (e.g. {@code customer}); the generated JPA {@code @JoinColumn} is {@code joinColumn}, else {@code <fieldName>_id},
 * and the DTO exposes the key as {@code <fieldName>Id}. {@code required} maps to a
 * non-null join column.
 *
 * <p>{@code joinColumn} is the foreign-key column exactly as the schema spells it (set by the DDL
 * import, or by hand); null derives it as {@code <snake fieldName>_id}.
 *
 * <p>A {@link RelationType#MANY_TO_MANY} is the owning side of a link through a join table: this
 * entity holds a set of {@code targetEntity} rows, the DTO carries their ids ({@code <fieldName>Ids})
 * and labels. {@code joinTable}, {@code joinColumn} (this entity's key in it) and
 * {@code inverseJoinColumn} (the target's) name the join table, each derived when null. Kept
 * apart from the foreign keys: an entity's {@code relations} are its MANY_TO_ONEs, its
 * {@code collections} these.
 *
 * <p>A {@link RelationType#ONE_TO_MANY} is the other side of a MANY_TO_ONE that
 * {@code targetEntity} declares, read from here: this entity gets the list of the rows that
 * point at it and their count, named {@code fieldName}. {@code mappedBy} is that MANY_TO_ONE's
 * field name (resolved by the validator when the request leaves it out and there is only one).
 * It owns no column. Kept in an entity's {@code children}.
 *
 * <p>{@code label} is what the generated UI calls the relation (a column heading, a form label,
 * a filter); null falls back to the field name.
 */
public record RelationDefinition(
        RelationType type,
        String fieldName,
        String targetEntity,
        boolean required,
        String joinColumn,
        String joinTable,
        String inverseJoinColumn,
        String mappedBy,
        String label) {

    /** Every property but {@code label}. */
    public RelationDefinition(RelationType type, String fieldName, String targetEntity, boolean required,
                              String joinColumn, String joinTable, String inverseJoinColumn, String mappedBy) {
        this(type, fieldName, targetEntity, required, joinColumn, joinTable, inverseJoinColumn, mappedBy, null);
    }

    /** Every property but {@code mappedBy} (a MANY_TO_ONE or a MANY_TO_MANY has none). */
    public RelationDefinition(RelationType type, String fieldName, String targetEntity, boolean required,
                              String joinColumn, String joinTable, String inverseJoinColumn) {
        this(type, fieldName, targetEntity, required, joinColumn, joinTable, inverseJoinColumn, null);
    }

    /** The same ONE_TO_MANY, naming the MANY_TO_ONE it is the other side of. */
    public RelationDefinition withMappedBy(String link) {
        return new RelationDefinition(type, fieldName, targetEntity, required, joinColumn, joinTable,
                inverseJoinColumn, link, label);
    }

    /** Back-compat constructor for a MANY_TO_ONE (no join table). */
    public RelationDefinition(RelationType type, String fieldName, String targetEntity, boolean required,
                              String joinColumn) {
        this(type, fieldName, targetEntity, required, joinColumn, null, null);
    }

    /** Back-compat constructor without {@code joinColumn} (derived from the field name). */
    public RelationDefinition(RelationType type, String fieldName, String targetEntity, boolean required) {
        this(type, fieldName, targetEntity, required, null);
    }
}
