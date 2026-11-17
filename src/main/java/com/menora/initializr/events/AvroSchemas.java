package com.menora.initializr.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.menora.initializr.events.AsyncApiReader.AsyncApiDocument;
import com.menora.initializr.events.AsyncApiReader.AsyncApiParseException;
import com.menora.initializr.events.AsyncApiReader.EventChannel;
import com.menora.initializr.events.AsyncApiReader.EventMessage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Avro payloads of an AsyncAPI document, as the {@code .avsc} files a generated project keeps
 * under {@code src/main/avro}; the avro-maven-plugin turns them into {@code SpecificRecord}
 * classes when the project is built. Nothing here parses Avro with Avro (there is no Avro library
 * in the offline repository): the schemas are walked as trees, enough to
 * <ul>
 *   <li>give a top-level schema without a namespace the payload package, so its class has one;</li>
 *   <li>write each top-level schema to its own file, a named type defined twice the same way
 *       once (the later copy becomes a reference to it);</li>
 *   <li>list the files other files refer to, in the order the plugin must read them
 *       ({@code <imports>}: the plugin reads every file with one parser, in no fixed order);</li>
 *   <li>refuse what would not build: a payload that is not a record, a type nobody defines,
 *       a name Avro does not allow, one name defined two different ways;</li>
 *   <li>make a value of each payload record for the generated tests.</li>
 * </ul>
 * Where an Avro schema comes from: a message whose {@code schemaFormat} is
 * {@code application/vnd.apache.avro…} (its payload written in place or a {@code $ref}), and
 * every entry of {@code components.schemas} that is an Avro named type ({@code type: record},
 * {@code enum} or {@code fixed}, or {@code {schemaFormat: avro, schema}}).
 */
final class AvroSchemas {

    static final String DIRECTORY = "src/main/avro";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PRIMITIVES =
            Set.of("null", "boolean", "int", "long", "float", "double", "bytes", "string");
    private static final Set<String> NAMED_KINDS = Set.of("record", "error", "enum", "fixed");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** The plugin writes no all-arguments constructor past this many fields; the tests use it. */
    private static final int MAX_CONSTRUCTOR_FIELDS = 254;

    /** One {@code .avsc} file: {@code <full name>.avsc} under {@link #DIRECTORY}. */
    record AvroFile(String fileName, String content) {}

    /**
     * A message's payload class.
     *
     * @param javaType the record's full name, which is its class's
     * @param sample   a Java expression making a value of it for a test, or null where none is simple
     */
    record AvroType(String javaType, String sample) {}

    /** One file: a top-level schema. */
    private static final class Unit {
        final String source;
        ObjectNode schema;
        String fullName;
        final Set<String> defines = new LinkedHashSet<>();
        final Set<String> refs = new LinkedHashSet<>();

        Unit(String source, ObjectNode schema) {
            this.source = source;
            this.schema = schema;
        }
    }

    private final List<String> problems = new ArrayList<>();
    private final List<Unit> units = new ArrayList<>();
    /** Every named type: its full name → its definition, as written to its file. */
    private final Map<String, ObjectNode> named = new LinkedHashMap<>();
    /** Its definition as the document gave it, to tell an identical second definition. */
    private final Map<String, JsonNode> asGiven = new LinkedHashMap<>();
    private final Map<String, Unit> definedIn = new LinkedHashMap<>();
    private final Set<String> avroComponents = new LinkedHashSet<>();
    private final Map<String, Unit> messageUnits = new LinkedHashMap<>();
    private final List<Unit> ordered;
    private final List<Unit> imported;

    AvroSchemas(AsyncApiDocument doc, String defaultNamespace) {
        JsonNode components = doc.root().path("components").path("schemas");
        Map<String, Unit> byComponent = new LinkedHashMap<>();
        // Components that are Avro, in the document's order.
        for (Iterator<Map.Entry<String, JsonNode>> it = components.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode schema = avroComponent(e.getValue());
            if (schema == null) continue;
            avroComponents.add(e.getKey());
            Unit unit = unitOf(schema, "components.schemas." + e.getKey());
            if (unit != null) byComponent.put(e.getKey(), unit);
        }
        // The messages' payloads: a component, somewhere else in the document, or written in place.
        Map<String, Unit> byPointer = new LinkedHashMap<>();
        for (EventChannel channel : doc.channels()) {
            for (EventMessage message : channel.messages()) {
                if (!message.avro() || messageUnits.containsKey(message.name())) continue;
                JsonNode payload = message.payload();
                String ref = payload.path("$ref").asText("");
                String where = "the Avro payload of message '" + message.name() + "'";
                Unit unit;
                if (ref.startsWith("#/components/schemas/")) {
                    String key = ref.substring("#/components/schemas/".length()).replace("~1", "/").replace("~0", "~");
                    unit = byComponent.get(key);
                    if (unit == null && !avroComponents.contains(key)) {
                        JsonNode target = components.path(key);
                        if (target.isMissingNode()) {
                            problems.add(capitalized(where) + " refers to '" + ref + "', which the document does not contain");
                            continue;
                        }
                        // Not recognisably Avro on its own, but a message says it is.
                        avroComponents.add(key);
                        JsonNode schema = target.has("schemaFormat") && target.has("schema") ? target.path("schema") : target;
                        unit = unitOf(schema, "components.schemas." + key);
                        if (unit != null) byComponent.put(key, unit);
                    }
                } else if (!ref.isEmpty()) {
                    JsonNode target = ref.startsWith("#/") ? doc.root().at(ref.substring(1)) : JSON.missingNode();
                    if (target.isMissingNode()) {
                        problems.add(capitalized(where) + " refers to '" + ref + "', which the document does not contain");
                        continue;
                    }
                    unit = byPointer.containsKey(ref) ? byPointer.get(ref) : unitOf(target, ref.substring(2));
                    byPointer.put(ref, unit);
                } else {
                    unit = unitOf(payload, where);
                }
                if (unit == null) continue;
                if (!isRecord(unit.schema)) {
                    problems.add(capitalized(where) + " is " + describe(unit.schema)
                            + ": the wizard sends Avro records only (a record can hold a single field)");
                    continue;
                }
                messageUnits.put(message.name(), unit);
            }
        }
        // Namespaces first (a nested type's full name depends on them), then every name.
        for (Unit unit : units) {
            if (unit.schema.has("name") && unit.schema.path("name").isTextual()
                    && !unit.schema.path("name").asText().contains(".") && !unit.schema.path("namespace").isTextual()) {
                unit.schema = withNamespace(unit.schema, defaultNamespace);
            }
        }
        for (Unit unit : new ArrayList<>(units)) {
            JsonNode walked = walk(unit.schema, null, unit, unit.source, true);
            if (walked instanceof TextNode alias) {
                // Defined the same way in an earlier file: no file of its own, its messages use that type.
                unit.fullName = alias.asText();
                units.remove(unit);
                continue;
            }
            unit.schema = (ObjectNode) walked;
        }
        for (Unit unit : units) {
            for (String ref : unit.refs) {
                if (!named.containsKey(ref)) {
                    problems.add(capitalized(unit.source) + " refers to the Avro type '" + ref
                            + "', which no schema in the document defines");
                }
            }
        }
        this.ordered = order();
        Set<Unit> needed = new LinkedHashSet<>();
        for (Unit unit : units) {
            for (String ref : unit.refs) {
                Unit owner = definedIn.get(ref);
                if (owner != null && owner != unit) needed.add(owner);
            }
        }
        this.imported = ordered.stream().filter(needed::contains).toList();
        if (!problems.isEmpty()) throw new AsyncApiParseException(problems);
    }

    boolean isEmpty() {
        return units.isEmpty();
    }

    /** The keys of {@code components.schemas} that are Avro: not the JSON payload types'. */
    Set<String> components() {
        return avroComponents;
    }

    List<AvroFile> files() {
        List<AvroFile> out = new ArrayList<>();
        for (Unit unit : ordered) {
            try {
                String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(unit.schema).replace("\r\n", "\n");
                out.add(new AvroFile(unit.fullName + ".avsc", text + "\n"));
            } catch (JsonProcessingException e) {
                throw new AsyncApiParseException("The Avro schema " + unit.fullName + " could not be written");
            }
        }
        return out;
    }

    /** The files the plugin must read before the others, in that order (names under {@link #DIRECTORY}). */
    List<String> imports() {
        return imported.stream().map(u -> u.fullName + ".avsc").toList();
    }

    /** The class of an Avro message's payload, or null for a message that is not Avro. */
    AvroType typeOf(EventMessage message) {
        Unit unit = messageUnits.get(message.name());
        if (unit == null) return null;
        return new AvroType(unit.fullName, sample(unit.fullName, new LinkedHashSet<>()));
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /** The Avro schema a component is, or null for one that is not recognisably Avro. */
    private static JsonNode avroComponent(JsonNode component) {
        if (component.has("schemaFormat") && component.has("schema")) {
            String format = component.path("schemaFormat").asText("");
            return AsyncApiReader.isAvro(format) ? component.path("schema") : null;
        }
        JsonNode type = component.path("type");
        return type.isTextual() && NAMED_KINDS.contains(type.asText()) && component.path("name").isTextual() ? component : null;
    }

    private Unit unitOf(JsonNode schema, String source) {
        JsonNode s = schema;
        if (s.isTextual() && s.asText().trim().startsWith("{")) {
            try {
                s = JSON.readTree(s.asText());
            } catch (JsonProcessingException e) {
                problems.add(capitalized(source) + " is not valid JSON");
                return null;
            }
        }
        if (!s.isObject() || !s.path("name").isTextual() || !NAMED_KINDS.contains(s.path("type").asText(""))) {
            // A primitive, a union or an array at the top: no file of its own, and no record for a message.
            Unit unit = new Unit(source, s.isObject() ? ((ObjectNode) s).deepCopy() : wrap(s));
            return unit;
        }
        Unit unit = new Unit(source, ((ObjectNode) s).deepCopy());
        units.add(unit);
        return unit;
    }

    private static ObjectNode wrap(JsonNode s) {
        ObjectNode o = JSON.createObjectNode();
        o.set("type", s.deepCopy());
        return o;
    }

    private static boolean isRecord(ObjectNode schema) {
        String type = schema.path("type").asText("");
        return (type.equals("record") || type.equals("error")) && schema.path("name").isTextual();
    }

    private static String describe(ObjectNode schema) {
        JsonNode type = schema.path("type");
        if (type.isArray()) return "a union";
        if (type.isObject()) return "a " + type.path("type").asText("schema");
        return "a" + (type.asText("").matches("[aeiou].*") ? "n " : " ") + type.asText("schema with no type");
    }

    private static ObjectNode withNamespace(ObjectNode schema, String namespace) {
        ObjectNode out = JSON.createObjectNode();
        if (schema.has("type")) out.set("type", schema.get("type"));
        out.set("name", schema.get("name"));
        out.put("namespace", namespace);
        for (Iterator<Map.Entry<String, JsonNode>> it = schema.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!out.has(e.getKey())) out.set(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * Records the names a schema defines and refers to. Returns the schema, with a named type
     * already defined the same way replaced by its name.
     *
     * @param ns the enclosing namespace, which a name without one takes
     */
    private JsonNode walk(JsonNode node, String ns, Unit unit, String where, boolean top) {
        if (node.isTextual()) {
            String name = node.asText();
            if (!PRIMITIVES.contains(name)) unit.refs.add(qualify(name, ns));
            return node;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode branch : node) out.add(walk(branch, ns, unit, where, false));
            return out;
        }
        if (!node.isObject()) {
            problems.add(capitalized(where) + " has a type that is neither a name, a union nor a schema: " + node);
            return node;
        }
        ObjectNode schema = (ObjectNode) node;
        JsonNode type = schema.path("type");
        if (type.isMissingNode()) {
            problems.add(capitalized(where) + " has a schema with no type");
            return node;
        }
        if (!type.isTextual()) {
            // {"type": {...}} or {"type": [...]}: the schema is the inner one.
            ObjectNode out = schema.deepCopy();
            out.set("type", walk(type, ns, unit, where, false));
            return out;
        }
        String kind = type.asText();
        if (NAMED_KINDS.contains(kind)) return walkNamed(schema, kind, ns, unit, where, top);
        switch (kind) {
            case "array" -> {
                ObjectNode out = schema.deepCopy();
                out.set("items", walk(schema.path("items"), ns, unit, where + " (its items)", false));
                return out;
            }
            case "map" -> {
                ObjectNode out = schema.deepCopy();
                out.set("values", walk(schema.path("values"), ns, unit, where + " (its values)", false));
                return out;
            }
            default -> {
                // A primitive with attributes (a logical type), or a name.
                if (!PRIMITIVES.contains(kind)) unit.refs.add(qualify(kind, ns));
                return node;
            }
        }
    }

    private JsonNode walkNamed(ObjectNode schema, String kind, String ns, Unit unit, String where, boolean top) {
        String name = schema.path("name").asText("");
        if (name.isEmpty()) {
            problems.add(capitalized(where) + " has a " + kind + " with no name");
            return schema;
        }
        String space = name.contains(".") ? name.substring(0, name.lastIndexOf('.'))
                : schema.path("namespace").isTextual() ? schema.path("namespace").asText() : ns;
        if (space != null && space.isEmpty()) space = null;
        String simple = name.substring(name.lastIndexOf('.') + 1);
        String full = space == null ? simple : space + "." + simple;
        for (String segment : full.split("\\.", -1)) {
            if (!NAME.matcher(segment).matches()) {
                problems.add(capitalized(where) + " names a " + kind + " '" + full
                        + "', which Avro does not allow (letters, digits and _, not starting with a digit)");
                return schema;
            }
        }
        if (space == null) {
            problems.add(capitalized(where) + " defines the " + kind + " '" + full + "' in no namespace: "
                    + "its class would have no package. Give it a namespace.");
            return schema;
        }
        JsonNode comparable = comparable(schema);
        if (named.containsKey(full)) {
            if (asGiven.get(full).equals(comparable)) {
                unit.refs.add(full);
                return new TextNode(full);
            }
            problems.add(capitalized(where) + " defines '" + full + "' differently from "
                    + definedIn.get(full).source + ": one name, one definition");
            return schema;
        }
        // Defined here: claimed before the fields are read, so a record may refer to itself.
        named.put(full, schema);
        asGiven.put(full, comparable);
        definedIn.put(full, unit);
        unit.defines.add(full);
        if (top) unit.fullName = full;
        ObjectNode out = schema.deepCopy();
        switch (kind) {
            case "record", "error" -> {
                JsonNode fields = schema.path("fields");
                if (!fields.isArray()) {
                    problems.add(capitalized(where) + ": the record '" + full + "' has no fields list");
                    break;
                }
                ArrayNode walked = JSON.createArrayNode();
                Set<String> seen = new LinkedHashSet<>();
                for (JsonNode field : fields) {
                    String fieldName = field.path("name").asText("");
                    if (!NAME.matcher(fieldName).matches()) {
                        problems.add(capitalized(where) + ": the record '" + full + "' has a field named '" + fieldName
                                + "', which Avro does not allow (letters, digits and _, not starting with a digit)");
                        walked.add(field);
                        continue;
                    }
                    if (!seen.add(fieldName)) {
                        problems.add(capitalized(where) + ": the record '" + full + "' has two fields named '" + fieldName + "'");
                    }
                    if (!field.has("type")) {
                        problems.add(capitalized(where) + ": field '" + fieldName + "' of '" + full + "' has no type");
                        walked.add(field);
                        continue;
                    }
                    ObjectNode f = field.deepCopy();
                    f.set("type", walk(field.path("type"), space, unit, where + " (field '" + fieldName + "' of " + simple + ")", false));
                    walked.add(f);
                }
                out.set("fields", walked);
            }
            case "enum" -> {
                JsonNode symbols = schema.path("symbols");
                if (!symbols.isArray() || symbols.isEmpty()) {
                    problems.add(capitalized(where) + ": the enum '" + full + "' has no symbols");
                } else {
                    for (JsonNode symbol : symbols) {
                        if (!NAME.matcher(symbol.asText("")).matches()) {
                            problems.add(capitalized(where) + ": the enum '" + full + "' has the symbol '" + symbol.asText("")
                                    + "', which Avro does not allow (letters, digits and _, not starting with a digit)");
                        }
                    }
                }
            }
            case "fixed" -> {
                if (!schema.path("size").canConvertToInt() || schema.path("size").asInt() < 0) {
                    problems.add(capitalized(where) + ": the fixed '" + full + "' has no size");
                }
            }
            default -> { }
        }
        named.put(full, out);
        return out;
    }

    /** A definition as compared with another: where the namespace came from does not matter. */
    private static JsonNode comparable(ObjectNode schema) {
        ObjectNode c = schema.deepCopy();
        c.remove("namespace");
        return c;
    }

    private static String qualify(String name, String ns) {
        return name.contains(".") || ns == null ? name : ns + "." + name;
    }

    /** Dependencies first; a cycle between files is a problem (the plugin reads one file at a time). */
    private List<Unit> order() {
        List<Unit> out = new ArrayList<>();
        Set<Unit> done = new LinkedHashSet<>();
        Set<Unit> visiting = new LinkedHashSet<>();
        for (Unit unit : units) visit(unit, out, done, visiting);
        return out;
    }

    private void visit(Unit unit, List<Unit> out, Set<Unit> done, Set<Unit> visiting) {
        if (done.contains(unit)) return;
        if (!visiting.add(unit)) {
            problems.add("The Avro schemas " + unit.fullName + " and the ones it refers to refer to each other across "
                    + "schemas. Define the types they share inside one of them.");
            return;
        }
        for (String ref : unit.refs) {
            Unit owner = definedIn.get(ref);
            if (owner != null && owner != unit) visit(owner, out, done, visiting);
        }
        visiting.remove(unit);
        if (done.add(unit)) out.add(unit);
    }

    // ── Values for the generated tests ────────────────────────────────────────

    /** {@code new a.b.Order(1L, "text", …)}, or null where a value is not simple to make. */
    private String sample(String fullName, Set<String> visiting) {
        ObjectNode schema = named.get(fullName);
        if (schema == null || !visiting.add(fullName)) return null;
        try {
            String kind = schema.path("type").asText("");
            if (kind.equals("enum")) return fullName + ".values()[0]";
            if (!kind.equals("record") && !kind.equals("error")) return null;
            JsonNode fields = schema.path("fields");
            if (fields.size() > MAX_CONSTRUCTOR_FIELDS) return null;
            String space = fullName.contains(".") ? fullName.substring(0, fullName.lastIndexOf('.')) : null;
            List<String> args = new ArrayList<>();
            for (JsonNode field : fields) {
                String value = valueOf(field.path("type"), space, visiting);
                if (value == null) return null;
                args.add(value);
            }
            return "new " + fullName + "(" + String.join(", ", args) + ")";
        } finally {
            visiting.remove(fullName);
        }
    }

    private String valueOf(JsonNode type, String ns, Set<String> visiting) {
        if (type.isArray()) {
            for (JsonNode branch : type) {
                if (branch.isTextual() && branch.asText().equals("null")) return "null";
            }
            return type.isEmpty() ? null : valueOf(type.get(0), ns, visiting);
        }
        if (type.isTextual()) {
            String name = type.asText();
            return PRIMITIVES.contains(name) ? primitive(name) : sample(qualify(name, ns), visiting);
        }
        if (!type.isObject()) return null;
        JsonNode inner = type.path("type");
        if (!inner.isTextual()) return valueOf(inner, ns, visiting);
        String kind = inner.asText();
        String logical = type.path("logicalType").asText("");
        if (!logical.isEmpty()) return logical(kind, logical, type);
        return switch (kind) {
            case "array" -> "java.util.List.of()";
            case "map" -> "java.util.Map.of()";
            case "record", "error", "enum", "fixed" -> {
                String name = type.path("name").asText("");
                String space = name.contains(".") ? null
                        : type.path("namespace").isTextual() ? type.path("namespace").asText() : ns;
                yield sample(qualify(name, space), visiting);
            }
            default -> PRIMITIVES.contains(kind) ? primitive(kind) : sample(qualify(kind, ns), visiting);
        };
    }

    private static String primitive(String name) {
        return switch (name) {
            case "null" -> "null";
            case "boolean" -> "true";
            case "int" -> "1";
            case "long" -> "1L";
            case "float" -> "1.5f";
            case "double" -> "1.5";
            case "string" -> "\"text\"";
            // Empty: a buffer's position is not part of what is compared after a round trip.
            case "bytes" -> "java.nio.ByteBuffer.wrap(new byte[0])";
            default -> null;
        };
    }

    /** The Java types avro-maven-plugin 1.11 writes for a logical type (decimals enabled). */
    private static String logical(String kind, String logical, JsonNode type) {
        return switch (logical) {
            case "date" -> kind.equals("int") ? "java.time.LocalDate.of(2024, 1, 1)" : null;
            case "time-millis", "time-micros" -> "java.time.LocalTime.of(1, 0)";
            case "timestamp-millis", "timestamp-micros" -> "java.time.Instant.ofEpochSecond(0)";
            case "local-timestamp-millis", "local-timestamp-micros" -> "java.time.LocalDateTime.of(2024, 1, 1, 0, 0)";
            case "decimal" -> kind.equals("bytes")
                    ? "java.math.BigDecimal.ONE.setScale(" + Math.max(0, type.path("scale").asInt(0)) + ")" : null;
            // uuid and the rest: which Java type the plugin writes depends on its settings.
            default -> null;
        };
    }

    private static String capitalized(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
