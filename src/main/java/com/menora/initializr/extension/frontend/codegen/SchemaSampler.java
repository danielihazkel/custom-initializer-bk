package com.menora.initializr.extension.frontend.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Synthesises a sample JSON payload for an OpenAPI schema. Used by
 * {@link MswHandlersRenderer} when no spec-level {@code example} is present —
 * the goal is "something well-formed enough that the test passes," not a
 * realistic fixture.
 *
 * <p>Rules: strings → {@code "string"}, numbers/integers → {@code 0}, booleans
 * → {@code false}, arrays → single-item array of items-sample, objects → all
 * properties populated (required + optional, since MSW callers usually want a
 * full body to assert against), enums → first literal. Refs are resolved
 * against {@code components.schemas}; cycles short-circuit to {@code null}.
 */
final class SchemaSampler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private SchemaSampler() {}

    /** Top-level entry. Returns a serialised JSON string. */
    @SuppressWarnings("rawtypes")
    static String sampleJson(OpenAPI api, Schema schema) {
        JsonNode node = sample(api, schema, new HashSet<>());
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // The node tree we build only contains primitives + arrays + objects,
            // so writeValueAsString never throws in practice — but Jackson's
            // checked signature forces a catch.
            return "null";
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonNode sample(OpenAPI api, Schema schema, Set<String> seen) {
        if (schema == null) return NODES.nullNode();

        // Spec-level example wins, regardless of type.
        if (schema.getExample() != null) {
            return MAPPER.valueToTree(schema.getExample());
        }

        // Resolve $ref against components.schemas.
        if (schema.get$ref() != null) {
            String name = TsTypeMapper.refName(schema.get$ref());
            if (name == null || seen.contains(name)) return NODES.nullNode();
            Schema resolved = (api.getComponents() != null && api.getComponents().getSchemas() != null)
                    ? api.getComponents().getSchemas().get(name)
                    : null;
            if (resolved == null) return NODES.nullNode();
            Set<String> next = new HashSet<>(seen);
            next.add(name);
            return sample(api, resolved, next);
        }

        // Enum → first literal.
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            return MAPPER.valueToTree(schema.getEnum().get(0));
        }

        // Compositions: oneOf/anyOf → first branch; allOf → merged object.
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            return sample(api, (Schema) schema.getOneOf().get(0), seen);
        }
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            return sample(api, (Schema) schema.getAnyOf().get(0), seen);
        }
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            ObjectNode merged = NODES.objectNode();
            for (Object part : schema.getAllOf()) {
                JsonNode sub = sample(api, (Schema) part, seen);
                if (sub instanceof ObjectNode subObj) {
                    subObj.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
                }
            }
            return merged;
        }

        String type = schema.getType();
        if (type == null) {
            // Inferred object when properties are present.
            if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                return sampleObject(api, schema, seen);
            }
            return NODES.nullNode();
        }

        return switch (type) {
            case "string" -> NODES.textNode("string");
            case "boolean" -> NODES.booleanNode(false);
            case "integer" -> NODES.numberNode(0);
            case "number" -> NODES.numberNode(0);
            case "array" -> sampleArray(api, schema, seen);
            case "object" -> sampleObject(api, schema, seen);
            default -> NODES.nullNode();
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonNode sampleArray(OpenAPI api, Schema schema, Set<String> seen) {
        ArrayNode arr = NODES.arrayNode();
        Schema items = schema.getItems();
        if (items != null) arr.add(sample(api, items, seen));
        return arr;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonNode sampleObject(OpenAPI api, Schema schema, Set<String> seen) {
        ObjectNode obj = NODES.objectNode();
        Map<String, Schema> props = schema.getProperties();
        if (props == null) return obj;
        List<String> ordered = new java.util.ArrayList<>(new LinkedHashMap<>(props).keySet());
        for (String name : ordered) {
            obj.set(name, sample(api, props.get(name), seen));
        }
        return obj;
    }
}
