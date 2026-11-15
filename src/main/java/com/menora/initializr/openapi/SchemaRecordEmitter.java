package com.menora.initializr.openapi;

import com.menora.initializr.config.WizardTemplates;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import javax.lang.model.SourceVersion;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * JSON-Schema-shaped component schemas → Java types: the half of the OpenAPI wizard that has
 * nothing to do with HTTP. Kept apart so that anything else described by such schemas (an
 * AsyncAPI document's message payloads) gets its types from the same code.
 *
 * <p>What a schema becomes:
 * <ul>
 *   <li>an object (properties, or an {@code allOf} whose parts are merged) — a record;</li>
 *   <li>a string with {@code enum} — a Java enum that keeps each wire value ({@code @JsonValue});</li>
 *   <li>a {@code oneOf}/{@code anyOf} over object schemas — a sealed interface its records
 *       implement, told apart by the discriminator, or by their properties without one;</li>
 *   <li>anything else with a name (a string with a format, an array, a map) — no type of its
 *       own: a reference to it is the Java type it stands for.</li>
 * </ul>
 * An object or an enum written in place, inside a property, gets a type named after where it is
 * ({@code Pet.status} → {@code PetStatus}).
 */
public final class SchemaRecordEmitter {

    private SchemaRecordEmitter() {
    }

    /** A Java type and the imports it needs. */
    public record JavaTypeRef(String typeName, Set<String> imports) {}

    private static final JavaTypeRef OBJECT = new JavaTypeRef("Object", Set.of());
    private static final JavaTypeRef STRING = new JavaTypeRef("String", Set.of());

    /**
     * Names a generated type may not take: the types the generated code itself names, in the DTO
     * package's files and in the controllers and clients that import them.
     */
    private static final Set<String> RESERVED_TYPE_NAMES = Set.of(
            "Object", "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
            "Number", "Void", "Enum", "Record", "Override", "Deprecated", "Class", "Iterable",
            "List", "Map", "Set", "Optional", "UUID", "BigDecimal", "LocalDate", "LocalDateTime", "OffsetDateTime",
            "Valid", "NotNull", "Size", "Min", "Max", "Pattern", "Email", "DecimalMin", "DecimalMax",
            "JsonProperty", "JsonValue", "JsonCreator", "JsonTypeInfo", "JsonSubTypes",
            "RestController", "RequestMapping", "Validated", "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping", "RequestBody", "PathVariable", "RequestParam", "RequestHeader",
            "CookieValue", "ResponseStatus", "HttpStatus", "RequestMethod", "DateTimeFormat", "Component", "Value",
            "HttpEntity", "HttpHeaders", "HttpMethod", "ResponseEntity", "RestTemplate", "RestClient",
            "UriComponentsBuilder", "ParameterizedTypeReference", "Configuration", "Bean");

    /** Every {@code components.schemas} entry that becomes a type, by type name — without constraints. */
    public static Map<String, SchemaModel> schemas(OpenAPI api) {
        return read(api, false, "").models();
    }

    /**
     * Reads the document's component schemas.
     *
     * @param validation whether the validation starter is on the build: constraints are written
     * @param dtoPackage the package the types go to (a reference to one imports it from there)
     */
    public static SchemaSet read(OpenAPI api, boolean validation, String dtoPackage) {
        return new SchemaSet(api, validation, dtoPackage);
    }

    private enum Shape { RECORD, ENUM, UNION, ALIAS }

    /**
     * The types of one document. It grows while the document is read: an object written in place
     * (a property, a request body) is registered when it is first met.
     */
    @SuppressWarnings("rawtypes")
    public static final class SchemaSet {

        private final boolean validation;
        private final String dtoPackage;
        private final Map<String, Schema> components = new LinkedHashMap<>();
        private final Map<String, String> classNames = new LinkedHashMap<>();
        private final Map<String, Shape> shapes = new LinkedHashMap<>();
        private final Map<String, SchemaModel> models = new LinkedHashMap<>();
        /** Case-insensitive: two types differing only in case are one file on Windows. */
        private final Set<String> taken = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        private final Set<String> enumNames = new LinkedHashSet<>();
        private final Set<String> cascadable = new LinkedHashSet<>();
        private final Map<Schema, JavaTypeRef> inline = new IdentityHashMap<>();

        private SchemaSet(OpenAPI api, boolean validation, String dtoPackage) {
            this.validation = validation;
            this.dtoPackage = dtoPackage == null ? "" : dtoPackage;
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            api.getComponents().getSchemas().forEach((raw, schema) -> {
                if (schema == null) return;
                components.put(raw, schema);
                classNames.put(raw, reserve(toPascalCase(raw)));
            });
            // What each becomes. A union needs to know its members are records: second.
            components.forEach((raw, schema) -> shapes.put(raw, shapeOf(schema)));
            components.forEach((raw, schema) -> {
                if (shapes.get(raw) == Shape.UNION && !isUnionOfRecords(schema)) shapes.put(raw, Shape.ALIAS);
            });
            // A name that stands for another type is not a type: its file name is free again.
            shapes.forEach((raw, shape) -> {
                if (shape == Shape.ALIAS) taken.remove(classNames.get(raw));
                if (shape == Shape.ENUM) enumNames.add(classNames.get(raw));
                if (shape == Shape.RECORD || shape == Shape.UNION) cascadable.add(classNames.get(raw));
            });
            components.forEach((raw, schema) -> {
                String name = classNames.get(raw);
                switch (shapes.get(raw)) {
                    case RECORD -> models.put(name, buildRecord(name, schema));
                    case ENUM -> models.put(name, buildEnum(name, schema));
                    case UNION -> models.put(name, buildUnion(name, schema));
                    case ALIAS -> { }
                }
            });
            // A record says which unions it belongs to.
            for (SchemaModel union : List.copyOf(models.values())) {
                if (union.kind() != SchemaModel.Kind.UNION) continue;
                for (SchemaModel.Subtype sub : union.subtypes()) {
                    models.computeIfPresent(sub.typeName(), (k, record) -> record.withParent(union.name()));
                }
            }
        }

        /** Every type to write, by name: the components in the document's order, each after the types written inside it. */
        public Map<String, SchemaModel> models() {
            return Collections.unmodifiableMap(models);
        }

        /**
         * The Java type of {@code schema}. {@code hint} names the type to create when the schema
         * is an object or an enum written in place; null where no type may be created (the
         * schema then reads as {@code Object} or {@code String}).
         */
        public JavaTypeRef typeOf(Schema schema, String hint) {
            return typeOf(schema, hint, new LinkedHashSet<>());
        }

        /**
         * The type of a path, query, header or cookie parameter. An enum stays a {@code String}:
         * Spring binds a parameter to an enum by its constant's name, and the constants here are
         * not the wire values ({@code in-progress} cannot be one).
         */
        public JavaTypeRef parameterTypeOf(Schema schema) {
            JavaTypeRef type = typeOf(schema, null);
            if (enumNames.contains(type.typeName())) return STRING;
            if (type.typeName().startsWith("List<") && enumNames.contains(inner(type.typeName()))) {
                return new JavaTypeRef("List<String>", Set.of("java.util.List"));
            }
            return type;
        }

        /**
         * The schema a reference or a one-part {@code allOf} stands for — where its format and
         * its constraints are.
         */
        public Schema effective(Schema schema) {
            Set<Schema> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Schema s = schema;
            while (s != null && seen.add(s)) {
                if (s.get$ref() != null && !s.get$ref().isBlank()) {
                    Schema target = components.get(refName(s.get$ref()));
                    if (target == null) return s;
                    s = target;
                } else if (s.getAllOf() != null && s.getAllOf().size() == 1 && isEmpty(s.getProperties())) {
                    s = (Schema) s.getAllOf().get(0);
                } else {
                    return s;
                }
            }
            return s;
        }

        /**
         * The constraint annotations {@code schema} declares for a value of {@code type}, their
         * imports added to {@code imports}. Empty without the validation starter.
         */
        public List<String> constraintsOf(Schema schema, JavaTypeRef type, boolean required, Set<String> imports) {
            List<String> out = new ArrayList<>();
            if (!validation) return out;
            Schema s = effective(schema);
            boolean nullable = isNullable(schema) || isNullable(s);
            if (required && !nullable) add(out, imports, "@NotNull", "NotNull");
            if (s == null) return out;
            String java = type.typeName();
            if (java.equals("String")) {
                String size = sizeOf(s.getMinLength(), s.getMaxLength());
                if (size != null) add(out, imports, size, "Size");
                if (s.getPattern() != null && !s.getPattern().isBlank()) {
                    add(out, imports, "@Pattern(regexp = \"" + javaString(s.getPattern()) + "\")", "Pattern");
                }
                if ("email".equals(s.getFormat())) add(out, imports, "@Email", "Email");
            } else if (java.equals("Integer") || java.equals("Long")) {
                bound(out, imports, s.getMinimum(), exclusiveMin(s), true, true);
                bound(out, imports, s.getMaximum(), exclusiveMax(s), false, true);
            } else if (java.equals("Double") || java.equals("Float")) {
                bound(out, imports, s.getMinimum(), exclusiveMin(s), true, false);
                bound(out, imports, s.getMaximum(), exclusiveMax(s), false, false);
            } else if (java.startsWith("List<")) {
                String size = sizeOf(s.getMinItems(), s.getMaxItems());
                if (size != null) add(out, imports, size, "Size");
            }
            // A nested object is checked too.
            String element = java.startsWith("List<") ? inner(java)
                    : java.startsWith("Map<String, ") ? java.substring("Map<String, ".length(), java.length() - 1) : java;
            if (cascadable.contains(element)) {
                out.add("@Valid");
                imports.add("jakarta.validation.Valid");
            }
            return out;
        }

        // ── Classification ────────────────────────────────────────────────────

        private Shape shapeOf(Schema s) {
            if (s.get$ref() != null && !s.get$ref().isBlank()) return Shape.ALIAS;
            if (isStringEnum(s)) return Shape.ENUM;
            if (!alternatives(s).isEmpty()) return alternatives(s).size() > 1 ? Shape.UNION : Shape.ALIAS;
            if (!isEmpty(s.getProperties()) || (s.getAllOf() != null && !s.getAllOf().isEmpty())) return Shape.RECORD;
            return Shape.ALIAS;
        }

        /** The members of a {@code oneOf}/{@code anyOf}, without a bare {@code null} (3.1's way to say nullable). */
        private static List<Schema> alternatives(Schema s) {
            List<Schema> raw = !isEmpty(s.getOneOf()) ? s.getOneOf() : !isEmpty(s.getAnyOf()) ? s.getAnyOf() : List.of();
            List<Schema> out = new ArrayList<>();
            for (Schema member : raw) {
                if (member == null) continue;
                if (member.get$ref() == null && "null".equals(typeName(member))) continue;
                out.add(member);
            }
            return out;
        }

        private boolean isUnionOfRecords(Schema s) {
            Set<String> seen = new LinkedHashSet<>();
            for (Schema member : alternatives(s)) {
                String ref = member.get$ref();
                if (ref == null || ref.isBlank()) return false;
                String raw = refName(ref);
                if (shapes.get(raw) != Shape.RECORD || !seen.add(raw)) return false;
            }
            return true;
        }

        // ── Builders ──────────────────────────────────────────────────────────

        private SchemaModel buildRecord(String className, Schema schema) {
            Map<String, Schema> props = new LinkedHashMap<>();
            Set<String> required = new LinkedHashSet<>();
            collect(schema, props, required, Collections.newSetFromMap(new IdentityHashMap<>()));

            List<FieldModel> fields = new ArrayList<>();
            Set<String> usedNames = new LinkedHashSet<>();
            for (var e : props.entrySet()) {
                String wire = e.getKey();
                Schema prop = e.getValue();
                String java = unique(javaName(wire), usedNames);
                JavaTypeRef type = typeOf(prop, className + toPascalCase(wire));
                Set<String> imports = new LinkedHashSet<>(type.imports());
                List<String> annotations = new ArrayList<>();
                if (!java.equals(wire)) {
                    annotations.add("@JsonProperty(\"" + javaString(wire) + "\")");
                    imports.add("com.fasterxml.jackson.annotation.JsonProperty");
                }
                annotations.addAll(constraintsOf(prop, type, required.contains(wire), imports));
                fields.add(new FieldModel(java, wire, type.typeName(), required.contains(wire),
                        prop == null ? null : prop.getDescription(), List.copyOf(annotations), imports));
            }
            return new SchemaModel(className, fields, schema.getDescription());
        }

        /** The properties of {@code s} and of every {@code allOf} part, a reference followed; a later part overrides. */
        private void collect(Schema s, Map<String, Schema> props, Set<String> required, Set<Schema> seen) {
            if (s == null || !seen.add(s)) return;
            if (s.get$ref() != null && !s.get$ref().isBlank()) {
                collect(components.get(refName(s.get$ref())), props, required, seen);
                return;
            }
            if (s.getAllOf() != null) {
                for (Object part : s.getAllOf()) collect((Schema) part, props, required, seen);
            }
            if (s.getProperties() != null) {
                Map<String, Schema> own = s.getProperties();
                props.putAll(own);
            }
            if (s.getRequired() != null) {
                for (Object name : s.getRequired()) required.add(String.valueOf(name));
            }
        }

        private SchemaModel buildEnum(String className, Schema schema) {
            List<SchemaModel.EnumConstant> constants = new ArrayList<>();
            Set<String> used = new LinkedHashSet<>();
            for (Object value : schema.getEnum()) {
                if (value == null) continue;
                String wire = String.valueOf(value);
                constants.add(new SchemaModel.EnumConstant(unique(constantName(wire), used), wire));
            }
            return SchemaModel.ofEnum(className, constants, schema.getDescription());
        }

        private SchemaModel buildUnion(String className, Schema schema) {
            String property = schema.getDiscriminator() == null ? null : schema.getDiscriminator().getPropertyName();
            if (property != null && property.isBlank()) property = null;
            Map<String, String> valueOf = new LinkedHashMap<>();
            if (property != null && schema.getDiscriminator().getMapping() != null) {
                schema.getDiscriminator().getMapping().forEach((value, ref) -> valueOf.putIfAbsent(refName(ref), value));
            }
            List<SchemaModel.Subtype> subtypes = new ArrayList<>();
            boolean inSubtypes = property != null;
            for (Schema member : alternatives(schema)) {
                String raw = refName(member.get$ref());
                subtypes.add(new SchemaModel.Subtype(classNames.get(raw),
                        property == null ? null : valueOf.getOrDefault(raw, raw)));
                if (property != null) {
                    Map<String, Schema> props = new LinkedHashMap<>();
                    collect(components.get(raw), props, new LinkedHashSet<>(), Collections.newSetFromMap(new IdentityHashMap<>()));
                    inSubtypes &= props.containsKey(property);
                }
            }
            return new SchemaModel(className, SchemaModel.Kind.UNION, List.of(), schema.getDescription(),
                    List.of(), List.of(), subtypes, property, inSubtypes);
        }

        // ── Types ─────────────────────────────────────────────────────────────

        private JavaTypeRef typeOf(Schema schema, String hint, Set<String> aliasTrail) {
            if (schema == null) return OBJECT;
            String ref = schema.get$ref();
            if (ref != null && !ref.isBlank()) {
                String raw = refName(ref);
                Shape shape = shapes.get(raw);
                // Outside components.schemas (the parser resolves those it can): named as before.
                if (shape == null) return new JavaTypeRef(toPascalCase(raw), Set.of());
                if (shape != Shape.ALIAS) return local(classNames.get(raw));
                if (!aliasTrail.add(raw)) return OBJECT;
                return typeOf(components.get(raw), classNames.get(raw), aliasTrail);
            }
            JavaTypeRef known = inline.get(schema);
            if (known != null) return known;

            String type = typeName(schema);
            if ("array".equals(type)) {
                JavaTypeRef item = typeOf(schema.getItems(), hint == null ? null : hint + "Item", aliasTrail);
                Set<String> imports = new LinkedHashSet<>(item.imports());
                imports.add("java.util.List");
                return new JavaTypeRef("List<" + item.typeName() + ">", imports);
            }
            if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
                // One part and nothing beside it: a reference wrapped to carry a description.
                if (schema.getAllOf().size() == 1 && isEmpty(schema.getProperties())) {
                    return typeOf((Schema) schema.getAllOf().get(0), hint, aliasTrail);
                }
                return hint == null ? OBJECT : registerRecord(hint, schema);
            }
            List<Schema> alternatives = alternatives(schema);
            if (!alternatives.isEmpty()) {
                // "this or null" is this; a choice written in place has no name to give a type.
                return alternatives.size() == 1 ? typeOf(alternatives.get(0), hint, aliasTrail) : OBJECT;
            }
            if (isStringEnum(schema)) return hint == null ? STRING : registerEnum(hint, schema);
            if ("boolean".equals(type)) return new JavaTypeRef("Boolean", Set.of());
            if ("integer".equals(type)) {
                return new JavaTypeRef("int64".equals(schema.getFormat()) ? "Long" : "Integer", Set.of());
            }
            if ("number".equals(type)) {
                return new JavaTypeRef("float".equals(schema.getFormat()) ? "Float" : "Double", Set.of());
            }
            if ("string".equals(type)) {
                String format = schema.getFormat() == null ? "" : schema.getFormat();
                return switch (format) {
                    case "date" -> new JavaTypeRef("LocalDate", Set.of("java.time.LocalDate"));
                    // RFC 3339: a date-time always says its offset, which a LocalDateTime cannot hold.
                    case "date-time" -> new JavaTypeRef("OffsetDateTime", Set.of("java.time.OffsetDateTime"));
                    case "uuid" -> new JavaTypeRef("UUID", Set.of("java.util.UUID"));
                    case "binary", "byte" -> new JavaTypeRef("byte[]", Set.of());
                    default -> STRING;
                };
            }
            if (!isEmpty(schema.getProperties())) {
                return hint == null ? OBJECT : registerRecord(hint, schema);
            }
            Object additional = schema.getAdditionalProperties();
            if (additional instanceof Schema valueSchema) {
                JavaTypeRef value = typeOf(valueSchema, hint == null ? null : hint + "Value", aliasTrail);
                Set<String> imports = new LinkedHashSet<>(value.imports());
                imports.add("java.util.Map");
                return new JavaTypeRef("Map<String, " + value.typeName() + ">", imports);
            }
            if ("object".equals(type) || Boolean.TRUE.equals(additional)) {
                return new JavaTypeRef("Map<String, Object>", Set.of("java.util.Map"));
            }
            return OBJECT;
        }

        private JavaTypeRef registerRecord(String hint, Schema schema) {
            String name = reserve(toPascalCase(hint));
            JavaTypeRef type = local(name);
            inline.put(schema, type);
            cascadable.add(name);
            models.put(name, buildRecord(name, schema));
            return type;
        }

        private JavaTypeRef registerEnum(String hint, Schema schema) {
            String name = reserve(toPascalCase(hint));
            JavaTypeRef type = local(name);
            inline.put(schema, type);
            enumNames.add(name);
            models.put(name, buildEnum(name, schema));
            return type;
        }

        private JavaTypeRef local(String className) {
            return new JavaTypeRef(className, dtoPackage.isEmpty() ? Set.of() : Set.of(dtoPackage + "." + className));
        }

        private String reserve(String wanted) {
            String name = RESERVED_TYPE_NAMES.contains(wanted) ? wanted + "Dto" : wanted;
            String candidate = name;
            for (int n = 2; !taken.add(candidate); n++) candidate = name + n;
            return candidate;
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /** The type's source, in package {@code fullPackage} ({@code templates/wizard/openapi/}). */
    public static String render(SchemaModel model, String fullPackage) {
        return switch (model.kind()) {
            case RECORD -> renderRecord(model, fullPackage);
            case ENUM -> renderEnum(model, fullPackage);
            case UNION -> renderUnion(model, fullPackage);
        };
    }

    /** A record's source ({@code Record.java}). */
    public static String renderRecord(SchemaModel s, String fullPackage) {
        Set<String> imports = new TreeSet<>();
        for (FieldModel f : s.fields()) {
            for (String imp : f.imports()) {
                if (!samePackage(imp, fullPackage)) imports.add(imp);
            }
        }
        // A member of a union inherits the interface's type information, and would then be read
        // as "one of the union" even where a field or a request body says exactly which record it
        // is ({"id": 1} could not be told apart from its siblings). On its own it is its properties.
        boolean inUnion = !s.parents().isEmpty();
        if (inUnion) imports.add("com.fasterxml.jackson.annotation.JsonTypeInfo");

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPackage);
        view.put("imports", imports);
        view.put("hasImports", !imports.isEmpty());
        putDescription(view, s.description());
        view.put("name", s.name());
        view.put("inUnion", inUnion);
        view.put("implementsClause", inUnion ? " implements " + String.join(", ", s.parents()) : "");
        List<Map<String, Object>> fields = new ArrayList<>();
        for (int i = 0; i < s.fields().size(); i++) {
            FieldModel f = s.fields().get(i);
            Map<String, Object> fv = new LinkedHashMap<>();
            // Each with a trailing space: the template writes them straight before the type.
            fv.put("annotations", f.annotations().stream().map(a -> a + " ").reduce("", String::concat));
            fv.put("javaType", f.javaType());
            fv.put("name", f.name());
            fv.put("last", i == s.fields().size() - 1);
            fields.add(fv);
        }
        view.put("fields", fields);
        view.put("hasFields", !fields.isEmpty());
        return WizardTemplates.render("openapi/Record.java", view);
    }

    private static String renderEnum(SchemaModel s, String fullPackage) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPackage);
        putDescription(view, s.description());
        view.put("name", s.name());
        List<Map<String, Object>> constants = new ArrayList<>();
        for (int i = 0; i < s.constants().size(); i++) {
            SchemaModel.EnumConstant c = s.constants().get(i);
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("name", c.name());
            cv.put("valueLiteral", "\"" + javaString(c.value()) + "\"");
            cv.put("last", i == s.constants().size() - 1);
            constants.add(cv);
        }
        view.put("constants", constants);
        return WizardTemplates.render("openapi/Enum.java", view);
    }

    private static String renderUnion(SchemaModel s, String fullPackage) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", fullPackage);
        putDescription(view, s.description());
        view.put("name", s.name());
        boolean discriminated = s.discriminator() != null;
        // The property is the records' own when they declare it; otherwise Jackson adds it.
        view.put("typeInfo", !discriminated
                ? "@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)"
                : s.discriminatorInSubtypes()
                ? "@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = \""
                        + javaString(s.discriminator()) + "\", visible = true)"
                : "@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = \"" + javaString(s.discriminator()) + "\")");
        List<Map<String, Object>> subtypes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < s.subtypes().size(); i++) {
            SchemaModel.Subtype sub = s.subtypes().get(i);
            names.add(sub.typeName());
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("line", discriminated
                    ? "@JsonSubTypes.Type(value = " + sub.typeName() + ".class, name = \"" + javaString(sub.discriminatorValue()) + "\")"
                    : "@JsonSubTypes.Type(" + sub.typeName() + ".class)");
            sv.put("last", i == s.subtypes().size() - 1);
            subtypes.add(sv);
        }
        view.put("subtypes", subtypes);
        view.put("permits", String.join(", ", names));
        return WizardTemplates.render("openapi/Union.java", view);
    }

    private static void putDescription(Map<String, Object> view, String description) {
        boolean described = description != null && !description.isBlank();
        view.put("hasDescription", described);
        view.put("descriptionJavadoc", described ? escapeJavadoc(description) : null);
    }

    private static boolean samePackage(String imported, String pkg) {
        return !pkg.isEmpty() && imported.startsWith(pkg + ".") && imported.indexOf('.', pkg.length() + 1) < 0;
    }

    // ── Schema reading helpers ────────────────────────────────────────────────

    /** {@code type}, or — an OpenAPI 3.1 document — the one of {@code types} that is not {@code null}. */
    @SuppressWarnings("rawtypes")
    static String typeName(Schema s) {
        if (s.getType() != null) return s.getType();
        if (s.getTypes() != null) {
            for (Object t : s.getTypes()) {
                if (!"null".equals(t)) return String.valueOf(t);
            }
        }
        if (s.getItems() != null) return "array";
        return null;
    }

    @SuppressWarnings("rawtypes")
    private static boolean isNullable(Schema s) {
        if (s == null) return false;
        return Boolean.TRUE.equals(s.getNullable()) || (s.getTypes() != null && s.getTypes().contains("null"));
    }

    /** An {@code enum} of strings: what becomes a Java enum. A numeric enum stays its number. */
    @SuppressWarnings("rawtypes")
    private static boolean isStringEnum(Schema s) {
        if (isEmpty(s.getEnum())) return false;
        String type = typeName(s);
        if (type != null && !"string".equals(type)) return false;
        boolean any = false;
        for (Object value : s.getEnum()) {
            if (value == null) continue;
            if (!(value instanceof String)) return false;
            any = true;
        }
        return any;
    }

    @SuppressWarnings("rawtypes")
    private static boolean exclusiveMin(Schema s) {
        return Boolean.TRUE.equals(s.getExclusiveMinimum());
    }

    @SuppressWarnings("rawtypes")
    private static boolean exclusiveMax(Schema s) {
        return Boolean.TRUE.equals(s.getExclusiveMaximum());
    }

    private static String sizeOf(Integer min, Integer max) {
        boolean hasMin = min != null && min > 0;
        if (!hasMin && max == null) return null;
        if (hasMin && max != null) return "@Size(min = " + min + ", max = " + max + ")";
        return hasMin ? "@Size(min = " + min + ")" : "@Size(max = " + max + ")";
    }

    private static void bound(List<String> out, Set<String> imports, BigDecimal value, boolean exclusive,
                              boolean lower, boolean integral) {
        if (value == null) return;
        boolean whole = value.stripTrailingZeros().scale() <= 0;
        if (integral && whole && !exclusive && value.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) {
            long v = value.longValueExact();
            String literal = v > Integer.MAX_VALUE || v < Integer.MIN_VALUE ? v + "L" : String.valueOf(v);
            add(out, imports, (lower ? "@Min(" : "@Max(") + literal + ")", lower ? "Min" : "Max");
            return;
        }
        String name = lower ? "DecimalMin" : "DecimalMax";
        add(out, imports, "@" + name + "(value = \"" + value.toPlainString() + "\"" + (exclusive ? ", inclusive = false" : "") + ")", name);
    }

    private static void add(List<String> out, Set<String> imports, String annotation, String simpleName) {
        out.add(annotation);
        imports.add("jakarta.validation.constraints." + simpleName);
    }

    private static boolean isEmpty(java.util.Collection<?> c) {
        return c == null || c.isEmpty();
    }

    private static boolean isEmpty(Map<?, ?> m) {
        return m == null || m.isEmpty();
    }

    private static String inner(String generic) {
        return generic.substring(generic.indexOf('<') + 1, generic.length() - 1);
    }

    static String refName(String ref) {
        int slash = ref.lastIndexOf('/');
        return slash < 0 ? ref : ref.substring(slash + 1);
    }

    // ── Names ─────────────────────────────────────────────────────────────────

    public static String toPascalCase(String raw) {
        if (raw == null || raw.isEmpty()) return "Unnamed";
        String[] parts = raw.split("[_\\-\\s.]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        String out = sb.toString().replaceAll("[^A-Za-z0-9]", "");
        return out.isEmpty() ? "Unnamed" : out;
    }

    public static String toCamelCase(String raw) {
        String pascal = toPascalCase(raw);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    /** A name on the wire as a Java member name: camel-cased, never a keyword, never starting with a digit. */
    public static String javaName(String wire) {
        String name = toCamelCase(wire);
        if (!Character.isJavaIdentifierStart(name.charAt(0))) name = "_" + name;
        return SourceVersion.isKeyword(name) ? name + "_" : name;
    }

    /** {@code inProgress} / {@code in-progress} / {@code 2xl} → {@code IN_PROGRESS} / {@code IN_PROGRESS} / {@code _2XL}. */
    static String constantName(String value) {
        String s = value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return "EMPTY";
        return Character.isDigit(s.charAt(0)) ? "_" + s : s;
    }

    private static String unique(String wanted, Set<String> used) {
        String candidate = wanted;
        for (int n = 2; !used.add(candidate); n++) candidate = wanted + n;
        return candidate;
    }

    /** Text safe inside a Java string literal. */
    public static String javaString(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    /** Text safe inside a one-line Javadoc comment. */
    public static String escapeJavadoc(String s) {
        return s.replace("*/", "*\\/").replace("\n", " ").replace("\r", " ");
    }
}
