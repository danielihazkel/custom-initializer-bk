package com.menora.initializr.fullstack;

/**
 * One relationship from a user-defined entity to another entity in the same request.
 *
 * <p>v1 supports {@link RelationType#MANY_TO_ONE}: this entity owns a foreign key to
 * {@code targetEntity}. {@code fieldName} is the Java association field on the owning side
 * (e.g. {@code customer}); the generated JPA {@code @JoinColumn} is {@code <fieldName>_id}
 * and the DTO exposes the key as {@code <fieldName>Id}. {@code required} maps to a
 * non-null join column.
 */
public record RelationDefinition(
        RelationType type,
        String fieldName,
        String targetEntity,
        boolean required) {
}
