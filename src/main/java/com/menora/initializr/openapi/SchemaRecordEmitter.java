package com.menora.initializr.openapi;

import com.menora.initializr.config.WizardTemplates;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.DateSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * JSON-Schema-shaped component schemas → Java records: the half of the OpenAPI wizard that has
 * nothing to do with HTTP. Kept apart so that anything else described by such schemas (an
 * AsyncAPI document's message payloads) gets its records from the same code.
 */
public final class SchemaRecordEmitter {

    private SchemaRecordEmitter() {
    }

    /** A Java type and the imports it needs. */
    public record JavaTypeRef(String typeName, Set<String> imports) {}

    /** Every {@code components.schemas} entry as a record model, in the document's order, by record name. */
    public static Map<String, SchemaModel> schemas(OpenAPI api) {
        Map<String, SchemaModel> out = new LinkedHashMap<>();
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
            return out;
        }
        for (var e : api.getComponents().getSchemas().entrySet()) {
            String rawName = e.getKey();
            Schema<?> schema = e.getValue();
            SchemaModel model = toSchemaModel(rawName, schema);
            out.put(model.name(), model);
        }
        return out;
    }

    @SuppressWarnings("rawtypes")
    static SchemaModel toSchemaModel(String rawName, Schema schema) {
        String className = toPascalCase(rawName);
        List<FieldModel> fields = new ArrayList<>();
        if (schema instanceof ComposedSchema) {
            // Fallback: emit an empty record with a TODO marker (recorded via description).
            return new SchemaModel(className, List.of(), "TODO: unsupported schema composition (allOf/oneOf/anyOf)");
        }
        Set<String> required = schema.getRequired() == null ? Set.of() : new LinkedHashSet<>(schema.getRequired());
        Map<String, Schema> props = schema.getProperties() == null ? Map.of() : schema.getProperties();
        for (var e : props.entrySet()) {
            String propName = e.getKey();
            Schema<?> propSchema = e.getValue();
            JavaTypeRef tr = mapType(propSchema);
            fields.add(new FieldModel(
                    toCamelCase(propName),
                    tr.typeName(),
                    required.contains(propName),
                    propSchema.getDescription(),
                    tr.imports()));
        }
        return new SchemaModel(className, fields, schema.getDescription());
    }

    /** The record's source, in package {@code fullPackage} ({@code templates/wizard/openapi/Record.java}). */
    public static String renderRecord(SchemaModel s, String fullPackage) {
        Set<String> imports = new TreeSet<>();
        for (FieldModel f : s.fields()) imports.addAll(f.imports());

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPackage);
        view.put("imports", imports);
        view.put("hasImports", !imports.isEmpty());
        boolean described = s.description() != null && !s.description().isBlank();
        view.put("hasDescription", described);
        view.put("descriptionJavadoc", described ? escapeJavadoc(s.description()) : null);
        view.put("name", s.name());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (int i = 0; i < s.fields().size(); i++) {
            FieldModel f = s.fields().get(i);
            Map<String, Object> fv = new LinkedHashMap<>();
            fv.put("javaType", f.javaType());
            fv.put("name", f.name());
            fv.put("last", i == s.fields().size() - 1);
            fields.add(fv);
        }
        view.put("fields", fields);
        view.put("hasFields", !fields.isEmpty());
        return WizardTemplates.render("openapi/Record.java", view);
    }

    @SuppressWarnings("rawtypes")
    public static JavaTypeRef mapType(Schema schema) {
        if (schema == null) return new JavaTypeRef("Object", Set.of());
        String ref = schema.get$ref();
        if (ref != null && !ref.isBlank()) {
            String name = refName(ref);
            return new JavaTypeRef(toPascalCase(name), Set.of());
        }
        if (schema instanceof ArraySchema arr) {
            Schema<?> items = arr.getItems();
            JavaTypeRef inner = mapType(items);
            Set<String> imports = new LinkedHashSet<>(inner.imports());
            imports.add("java.util.List");
            return new JavaTypeRef("List<" + inner.typeName() + ">", imports);
        }
        if (schema instanceof ComposedSchema) {
            return new JavaTypeRef("Object", Set.of());
        }
        if (schema instanceof BooleanSchema) {
            return new JavaTypeRef("Boolean", Set.of());
        }
        if (schema instanceof IntegerSchema || "integer".equals(schema.getType())) {
            String fmt = schema.getFormat();
            if ("int64".equals(fmt)) return new JavaTypeRef("Long", Set.of());
            return new JavaTypeRef("Integer", Set.of());
        }
        if (schema instanceof NumberSchema || "number".equals(schema.getType())) {
            String fmt = schema.getFormat();
            if ("float".equals(fmt)) return new JavaTypeRef("Float", Set.of());
            return new JavaTypeRef("Double", Set.of());
        }
        if (schema instanceof UUIDSchema) {
            return new JavaTypeRef("UUID", Set.of("java.util.UUID"));
        }
        if (schema instanceof DateSchema) {
            return new JavaTypeRef("LocalDate", Set.of("java.time.LocalDate"));
        }
        if (schema instanceof DateTimeSchema) {
            return new JavaTypeRef("LocalDateTime", Set.of("java.time.LocalDateTime"));
        }
        if (schema instanceof StringSchema || "string".equals(schema.getType())) {
            String fmt = schema.getFormat();
            if (fmt == null) return new JavaTypeRef("String", Set.of());
            return switch (fmt) {
                case "date" -> new JavaTypeRef("LocalDate", Set.of("java.time.LocalDate"));
                case "date-time" -> new JavaTypeRef("LocalDateTime", Set.of("java.time.LocalDateTime"));
                case "uuid" -> new JavaTypeRef("UUID", Set.of("java.util.UUID"));
                case "binary", "byte" -> new JavaTypeRef("byte[]", Set.of());
                default -> new JavaTypeRef("String", Set.of());
            };
        }
        if (schema instanceof ObjectSchema) {
            return new JavaTypeRef("Object", Set.of());
        }
        return new JavaTypeRef("Object", Set.of());
    }

    private static String refName(String ref) {
        int slash = ref.lastIndexOf('/');
        return slash < 0 ? ref : ref.substring(slash + 1);
    }

    public static String toPascalCase(String raw) {
        if (raw == null || raw.isEmpty()) return "Unnamed";
        String[] parts = raw.split("[_\\-\\s.]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        String out = sb.length() == 0 ? "Unnamed" : sb.toString();
        return out.replaceAll("[^A-Za-z0-9]", "");
    }

    public static String toCamelCase(String raw) {
        String pascal = toPascalCase(raw);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    /** Text safe inside a one-line Javadoc comment. */
    public static String escapeJavadoc(String s) {
        return s.replace("*/", "*\\/").replace("\n", " ").replace("\r", " ");
    }
}
