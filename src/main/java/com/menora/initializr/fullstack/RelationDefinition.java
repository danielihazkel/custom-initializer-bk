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
 */
public record RelationDefinition(
        RelationType type,
        String fieldName,
        String targetEntity,
        boolean required,
        String joinColumn) {

    /** Back-compat constructor without {@code joinColumn} (derived from the field name). */
    public RelationDefinition(RelationType type, String fieldName, String targetEntity, boolean required) {
        this(type, fieldName, targetEntity, required, null);
    }
}
