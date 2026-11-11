package com.menora.initializr.openapi;

import java.util.List;

/**
 * A type the OpenAPI wizard writes into the DTO package: a record (an object schema), an enum
 * (a string schema with {@code enum}) or a sealed interface (a {@code oneOf}/{@code anyOf} over
 * object schemas).
 *
 * @param fields        a record's components
 * @param constants     an enum's constants
 * @param parents       the sealed interfaces a record implements
 * @param subtypes      a union's permitted records
 * @param discriminator a union's discriminator property (wire name), or null when the subtype is
 *                      told from the properties present
 * @param discriminatorInSubtypes whether every subtype declares the discriminator as a property
 */
public record SchemaModel(
        String name,
        Kind kind,
        List<FieldModel> fields,
        String description,
        List<EnumConstant> constants,
        List<String> parents,
        List<Subtype> subtypes,
        String discriminator,
        boolean discriminatorInSubtypes) {

    public enum Kind { RECORD, ENUM, UNION }

    /** {@code name} is the Java constant, {@code value} what travels on the wire. */
    public record EnumConstant(String name, String value) {}

    /** {@code discriminatorValue} is null for a union without a discriminator. */
    public record Subtype(String typeName, String discriminatorValue) {}

    /** A record with nothing but its fields. */
    public SchemaModel(String name, List<FieldModel> fields, String description) {
        this(name, Kind.RECORD, fields, description, List.of(), List.of(), List.of(), null, false);
    }

    static SchemaModel ofEnum(String name, List<EnumConstant> constants, String description) {
        return new SchemaModel(name, Kind.ENUM, List.of(), description, constants, List.of(), List.of(), null, false);
    }

    SchemaModel withParent(String parent) {
        if (parents.contains(parent)) return this;
        List<String> next = new java.util.ArrayList<>(parents);
        next.add(parent);
        return new SchemaModel(name, kind, fields, description, constants, List.copyOf(next), subtypes,
                discriminator, discriminatorInSubtypes);
    }
}
