package com.menora.initializr.extension.frontend.codegen;

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps an OpenAPI {@link Schema} to a TypeScript type expression.
 *
 * <p>Coverage target: the 80% of typical internal APIs. Supports primitives,
 * arrays, refs, enums, nullable, oneOf/anyOf (as unions), allOf (as
 * intersection), and inline objects. Anything outside that map degrades to
 * {@code unknown} with a short TS comment — users always have
 * {@code pnpm gen:api} as a regen escape hatch.
 */
final class TsTypeMapper {

    private TsTypeMapper() {}

    /**
     * Produces a TS type expression for a Schema. {@code schemaRefPrefix} is the
     * import path prefix for refs (e.g. "Schema." when paths.ts imports schemas
     * as namespace; empty string when emitting inside schemas.ts itself).
     */
    @SuppressWarnings("rawtypes")
    static String map(Schema schema, String schemaRefPrefix) {
        if (schema == null) return "unknown";

        // $ref to a named component schema.
        if (schema.get$ref() != null) {
            String name = refName(schema.get$ref());
            return name == null
                    ? "unknown /* codegen: unresolved ref */"
                    : (schemaRefPrefix.isEmpty() ? name : schemaRefPrefix + name);
        }

        // Enum: union of literals — works for strings, numbers, integers.
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            return mapEnum(schema, schemaRefPrefix);
        }

        // Compositions.
        if (schema instanceof ComposedSchema cs) {
            String composed = mapComposition(cs, schemaRefPrefix);
            if (composed != null) return wrapNullable(schema, composed);
        }
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            return wrapNullable(schema, joinUnion(schema.getOneOf(), schemaRefPrefix));
        }
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            return wrapNullable(schema, joinUnion(schema.getAnyOf(), schemaRefPrefix));
        }
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            return wrapNullable(schema, joinIntersection(schema.getAllOf(), schemaRefPrefix));
        }

        String type = schema.getType();
        if (type == null) {
            // No explicit type — if it has properties, treat as object.
            if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                return wrapNullable(schema, mapInlineObject(schema, schemaRefPrefix));
            }
            return "unknown";
        }

        return switch (type) {
            case "string" -> wrapNullable(schema, "string");
            case "boolean" -> wrapNullable(schema, "boolean");
            case "integer", "number" -> wrapNullable(schema, "number");
            case "array" -> wrapNullable(schema, mapArray(schema, schemaRefPrefix));
            case "object" -> wrapNullable(schema, mapInlineObject(schema, schemaRefPrefix));
            default -> "unknown /* codegen: unsupported type " + type + " */";
        };
    }

    /** Maps a top-level component schema to a TS declaration body (without leading "export type Name = "). */
    @SuppressWarnings("rawtypes")
    static String mapTopLevel(Schema schema, String selfName) {
        // For top-level, we emit refs without prefix (same file).
        String mapped = map(schema, "");
        // Avoid `export type X = X` (would happen if Schema is a $ref to itself).
        if (mapped.equals(selfName)) return "unknown /* codegen: self-referential ref */";
        return mapped;
    }

    @SuppressWarnings("rawtypes")
    private static String mapArray(Schema schema, String prefix) {
        Schema items;
        if (schema instanceof ArraySchema arr) {
            items = arr.getItems();
        } else {
            items = schema.getItems();
        }
        if (items == null) return "unknown[]";
        String itemType = map(items, prefix);
        // Wrap union/intersection items in parens so `T | null` becomes `(T | null)[]`.
        if (itemType.contains("|") || itemType.contains("&")) {
            return "(" + itemType + ")[]";
        }
        return itemType + "[]";
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String mapInlineObject(Schema schema, String prefix) {
        Map<String, Schema> props = schema.getProperties();
        if (props == null || props.isEmpty()) {
            // additionalProperties: handle the common "any object" case.
            Object addl = schema.getAdditionalProperties();
            if (addl instanceof Schema valueSchema) {
                return "Record<string, " + map(valueSchema, prefix) + ">";
            }
            if (Boolean.TRUE.equals(addl)) return "Record<string, unknown>";
            return "Record<string, never>";
        }
        List<String> required = schema.getRequired() == null ? List.of() : schema.getRequired();
        StringBuilder sb = new StringBuilder("{ ");
        boolean first = true;
        for (Map.Entry<String, Schema> entry : props.entrySet()) {
            if (!first) sb.append("; ");
            first = false;
            String name = entry.getKey();
            boolean req = required.contains(name);
            sb.append(safeIdentifier(name)).append(req ? "" : "?").append(": ").append(map(entry.getValue(), prefix));
        }
        sb.append(" }");
        return sb.toString();
    }

    @SuppressWarnings("rawtypes")
    private static String mapEnum(Schema schema, String prefix) {
        List<?> values = schema.getEnum();
        List<String> literals = new ArrayList<>();
        boolean isString = "string".equals(schema.getType());
        for (Object v : values) {
            if (v == null) continue;
            if (isString || v instanceof String) literals.add("'" + ((String) v).replace("'", "\\'") + "'");
            else literals.add(v.toString());
        }
        if (literals.isEmpty()) return "never";
        String union = String.join(" | ", literals);
        if (Boolean.TRUE.equals(schema.getNullable())) union = union + " | null";
        return prefix.isEmpty() ? union : union;
    }

    @SuppressWarnings("rawtypes")
    private static String mapComposition(ComposedSchema cs, String prefix) {
        if (cs.getOneOf() != null && !cs.getOneOf().isEmpty()) {
            String tagged = mapDiscriminatedUnion(cs, prefix);
            if (tagged != null) return tagged;
            return joinUnion(cs.getOneOf(), prefix);
        }
        if (cs.getAnyOf() != null && !cs.getAnyOf().isEmpty()) {
            return joinUnion(cs.getAnyOf(), prefix);
        }
        if (cs.getAllOf() != null && !cs.getAllOf().isEmpty()) {
            return joinIntersection(cs.getAllOf(), prefix);
        }
        return null;
    }

    /**
     * Renders a {@code oneOf} as a tagged union when the schema declares a
     * {@link Discriminator}. Each branch becomes {@code ({ <prop>: '<value>' } & <Member>)}
     * so TS can narrow the union on the discriminator property.
     *
     * <p>When {@code discriminator.mapping} is present, the literal comes from the
     * mapping key whose value matches the branch's {@code $ref}; otherwise the
     * literal is the ref name. Branches without a $ref fall back to a plain union
     * member with no tag (so the consumer still gets the type, just no narrowing).
     */
    @SuppressWarnings("rawtypes")
    private static String mapDiscriminatedUnion(ComposedSchema cs, String prefix) {
        Discriminator d = cs.getDiscriminator();
        if (d == null || d.getPropertyName() == null || d.getPropertyName().isBlank()) return null;
        String prop = d.getPropertyName();
        Map<String, String> mapping = d.getMapping();

        List<String> parts = new ArrayList<>();
        for (Schema member : cs.getOneOf()) {
            String memberType = map(member, prefix);
            String literal = discriminatorLiteralFor(member, mapping);
            if (literal == null) {
                parts.add(memberType);
            } else {
                parts.add("({ " + safeIdentifier(prop) + ": '" + literal.replace("'", "\\'") + "' } & "
                        + memberType + ")");
            }
        }
        return parts.isEmpty() ? null : String.join(" | ", parts);
    }

    /**
     * Resolves the literal value used to tag a {@code oneOf} branch. Prefers
     * the mapping key whose value's last segment matches the branch's ref name;
     * falls back to the ref name itself; returns {@code null} for inline schemas
     * (no narrowing possible).
     */
    @SuppressWarnings("rawtypes")
    private static String discriminatorLiteralFor(Schema member, Map<String, String> mapping) {
        String memberRefName = refName(member.get$ref());
        if (memberRefName == null) return null;
        if (mapping != null) {
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                String mappedName = refName(e.getValue());
                if (mappedName == null) continue;
                if (mappedName.equals(memberRefName)) return e.getKey();
            }
        }
        return memberRefName;
    }

    @SuppressWarnings("rawtypes")
    private static String joinUnion(List<Schema> schemas, String prefix) {
        List<String> parts = new ArrayList<>();
        for (Schema s : schemas) parts.add(map(s, prefix));
        return parts.isEmpty() ? "never" : String.join(" | ", parts);
    }

    @SuppressWarnings("rawtypes")
    private static String joinIntersection(List<Schema> schemas, String prefix) {
        List<String> parts = new ArrayList<>();
        for (Schema s : schemas) parts.add(map(s, prefix));
        if (parts.isEmpty()) return "Record<string, never>";
        if (parts.size() == 1) return parts.get(0);
        // Wrap each in parens to keep precedence sane when mixing unions in members.
        return String.join(" & ", parts.stream().map(p -> "(" + p + ")").toList());
    }

    @SuppressWarnings("rawtypes")
    private static String wrapNullable(Schema schema, String type) {
        if (!Boolean.TRUE.equals(schema.getNullable())) return type;
        return type + " | null";
    }

    /** Pulls "Pet" out of "#/components/schemas/Pet". */
    static String refName(String ref) {
        if (ref == null) return null;
        int slash = ref.lastIndexOf('/');
        if (slash < 0 || slash == ref.length() - 1) return null;
        return ref.substring(slash + 1);
    }

    /** Quotes property names that aren't safe TS identifiers. */
    private static String safeIdentifier(String name) {
        if (name.isEmpty()) return "''";
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return "'" + name.replace("'", "\\'") + "'";
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return "'" + name.replace("'", "\\'") + "'";
        }
        return name;
    }

    /** Convenience: build an ordered map of paramName → TS type from a list of OpenAPI parameters. */
    @SuppressWarnings("rawtypes")
    static Map<String, String> paramTypes(List<io.swagger.v3.oas.models.parameters.Parameter> params, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params == null) return out;
        for (var p : params) {
            if (p == null || p.getName() == null) continue;
            String t = p.getSchema() == null ? "unknown" : map(p.getSchema(), prefix);
            String name = p.getName();
            String key = Boolean.TRUE.equals(p.getRequired()) ? name : (safeIdentifier(name) + "?");
            // safeIdentifier added quotes if needed; re-derive the unquoted name for the `?` form
            if (key.endsWith("?") && !key.startsWith("'")) {
                out.put(name + "?", t);
            } else if (Boolean.TRUE.equals(p.getRequired())) {
                out.put(safeIdentifier(name), t);
            } else {
                out.put(safeIdentifier(name) + "?", t);
            }
        }
        return out;
    }
}
