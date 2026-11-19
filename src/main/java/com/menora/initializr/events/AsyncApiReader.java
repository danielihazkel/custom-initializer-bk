package com.menora.initializr.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads an AsyncAPI document (2.x or 3.x, YAML or JSON) into the channels an application sends
 * to and receives from, each with the messages it carries.
 *
 * <p>There is no AsyncAPI library in the offline repository, and the wizard needs little of the
 * specification: the document is read as a tree. The two major versions say the same thing in
 * different places —
 * <ul>
 *   <li><b>2.x</b>: {@code channels.<address>.publish|subscribe.message}. The words are from the
 *       point of view of a <em>client</em> of the described application: {@code subscribe} is what
 *       clients may subscribe to, so the application <em>sends</em> it; {@code publish} is what
 *       clients may publish, so the application <em>receives</em> it.</li>
 *   <li><b>3.x</b>: {@code channels.<id>} has an {@code address} and its {@code messages};
 *       {@code operations.<id>} has an {@code action} — {@code send} or {@code receive}, from the
 *       application's own point of view — and names a channel.</li>
 * </ul>
 * Both come out as {@link EventChannel#sends()} / {@link EventChannel#receives()}: what the
 * described application does. A project that is the other party swaps them (the wizard's options).
 */
public final class AsyncApiReader {

    private AsyncApiReader() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A document that cannot be used; each message says what and where. A 400 from the wizard. */
    public static class AsyncApiParseException extends RuntimeException {
        private final List<String> messages;

        public AsyncApiParseException(List<String> messages) {
            super(String.join("; ", messages));
            this.messages = List.copyOf(messages);
        }

        public AsyncApiParseException(String message) {
            this(List.of(message));
        }

        public List<String> messages() {
            return messages;
        }
    }

    /**
     * @param root     the whole document
     * @param version  its {@code asyncapi} value
     * @param channels in the document's order
     */
    public record AsyncApiDocument(JsonNode root, String version, List<EventChannel> channels) {

        public EventChannel channel(String name) {
            for (EventChannel c : channels) {
                if (c.name().equals(name)) return c;
            }
            return null;
        }
    }

    /**
     * @param name     what the document calls the channel: its key under {@code channels}
     * @param address  where it is on the broker (the Kafka topic): 3.x's {@code address}, 2.x's key
     * @param sends    the described application sends to it
     * @param receives the described application receives from it
     * @param problem  why no code can be written for it (a parameterized address, Avro and JSON
     *                messages on one channel), or null
     */
    public record EventChannel(String name, String address, String description, boolean sends, boolean receives,
                               List<EventMessage> messages, String problem) {

        /** Every message it carries is an Avro record (and there is at least one). */
        public boolean avro() {
            return !messages.isEmpty() && messages.stream().allMatch(EventMessage::avro);
        }
    }

    /**
     * @param name              the message's name: its {@code name}, else its key, else the channel's
     * @param payload           the payload's schema as written (it may be a {@code $ref}), or null when
     *                          the message declares none
     * @param avro              the payload is an Avro schema ({@code schemaFormat: application/vnd.apache.avro…}),
     *                          not JSON Schema
     * @param headers           its {@code headers}: an object schema whose properties are the Kafka
     *                          headers it carries (traits merged in), or null
     * @param correlationHeader the header its {@code correlationId} is in ({@code $message.header#/x}), or null
     * @param key               its Kafka key's schema ({@code bindings.kafka.key}), or null
     */
    public record EventMessage(String name, String title, JsonNode payload, boolean avro,
                               JsonNode headers, String correlationHeader, JsonNode key) {

        public EventMessage(String name, String title, JsonNode payload, boolean avro) {
            this(name, title, payload, avro, null, null, null);
        }

        public EventMessage(String name, String title, JsonNode payload) {
            this(name, title, payload, false);
        }

        /** Whether its producer and listener carry headers (some declared, or a correlation id). */
        public boolean hasHeaders() {
            return headers != null || correlationHeader != null;
        }
    }

    public static AsyncApiDocument read(String document) {
        JsonNode root = parse(document);
        String version = root.path("asyncapi").asText("");
        if (version.isBlank()) {
            throw new AsyncApiParseException("Not an AsyncAPI document: it has no 'asyncapi' version field");
        }
        boolean v3 = version.startsWith("3.");
        if (!v3 && !version.startsWith("2.")) {
            throw new AsyncApiParseException("AsyncAPI " + version + " is not supported: the wizard reads 2.x and 3.x documents");
        }
        JsonNode channels = root.path("channels");
        if (!channels.isObject() || channels.isEmpty()) {
            throw new AsyncApiParseException("The AsyncAPI document declares no channels");
        }
        Reader reader = new Reader(root);
        List<EventChannel> out = v3 ? reader.channels3(channels) : reader.channels2(channels);
        if (!reader.problems.isEmpty()) throw new AsyncApiParseException(reader.problems);
        return new AsyncApiDocument(root, version, out);
    }

    /** YAML or JSON (JSON is YAML) into a tree; nothing but plain data is constructed. */
    private static JsonNode parse(String document) {
        if (document == null || document.isBlank()) {
            throw new AsyncApiParseException("The AsyncAPI document is empty");
        }
        Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(50);
            loaded = new Yaml(new SafeConstructor(options)).load(document);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new AsyncApiParseException("The AsyncAPI document is neither YAML nor JSON: " + firstLine(message));
        }
        if (!(loaded instanceof Map)) {
            throw new AsyncApiParseException("Not an AsyncAPI document: expected an object at the top");
        }
        return JSON.valueToTree(loaded);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static final class Reader {
        private final JsonNode root;
        private final List<String> problems = new ArrayList<>();

        Reader(JsonNode root) {
            this.root = root;
        }

        // ── 2.x ───────────────────────────────────────────────────────────────

        List<EventChannel> channels2(JsonNode channels) {
            List<EventChannel> out = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = channels.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String address = e.getKey();
                JsonNode channel = resolve(e.getValue(), "channels." + address);
                // The client's words: what a client subscribes to, the application sends.
                JsonNode sent = channel.path("subscribe");
                JsonNode received = channel.path("publish");
                Map<String, EventMessage> messages = new LinkedHashMap<>();
                for (JsonNode operation : List.of(sent, received)) {
                    if (!operation.isObject()) continue;
                    JsonNode message = operation.path("message");
                    if (message.path("oneOf").isArray()) {
                        for (JsonNode one : message.path("oneOf")) addMessage(messages, one, null, address);
                    } else if (message.isObject()) {
                        addMessage(messages, message, operation.path("operationId").asText(null), address);
                    }
                }
                out.add(new EventChannel(address, address, text(channel, "description"), sent.isObject(), received.isObject(),
                        List.copyOf(messages.values()), problemOf(address, messages)));
            }
            return out;
        }

        // ── 3.x ───────────────────────────────────────────────────────────────

        List<EventChannel> channels3(JsonNode channels) {
            // Which channels an operation sends to or receives from, and with which of its messages.
            Map<String, Boolean> sends = new LinkedHashMap<>();
            Map<String, Boolean> receives = new LinkedHashMap<>();
            Map<String, Set<String>> limited = new LinkedHashMap<>();
            JsonNode operations = root.path("operations");
            for (Iterator<Map.Entry<String, JsonNode>> it = operations.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode operation = resolve(e.getValue(), "operations." + e.getKey());
                String ref = operation.path("channel").path("$ref").asText("");
                if (!ref.startsWith("#/channels/")) continue;
                String channelName = unescape(ref.substring("#/channels/".length()));
                String action = operation.path("action").asText("").toLowerCase(Locale.ROOT);
                if (action.equals("send")) sends.put(channelName, true);
                else if (action.equals("receive")) receives.put(channelName, true);
                else problems.add("Operation '" + e.getKey() + "' has action '" + operation.path("action").asText("")
                            + "': expected send or receive");
                if (operation.path("messages").isArray()) {
                    Set<String> names = limited.computeIfAbsent(channelName, k -> new LinkedHashSet<>());
                    for (JsonNode message : operation.path("messages")) {
                        String messageRef = message.path("$ref").asText("");
                        int at = messageRef.lastIndexOf("/messages/");
                        if (at >= 0) names.add(unescape(messageRef.substring(at + "/messages/".length())));
                    }
                }
            }
            List<EventChannel> out = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = channels.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = e.getKey();
                JsonNode channel = resolve(e.getValue(), "channels." + name);
                // No address: the channel's key is where it is.
                String address = channel.path("address").isTextual() ? channel.path("address").asText() : name;
                Map<String, EventMessage> messages = new LinkedHashMap<>();
                Set<String> only = limited.get(name);
                for (Iterator<Map.Entry<String, JsonNode>> mit = channel.path("messages").fields(); mit.hasNext(); ) {
                    Map.Entry<String, JsonNode> m = mit.next();
                    if (only != null && !only.isEmpty() && !only.contains(m.getKey())) continue;
                    addMessage(messages, m.getValue(), m.getKey(), name);
                }
                out.add(new EventChannel(name, address, text(channel, "description"),
                        sends.containsKey(name), receives.containsKey(name), List.copyOf(messages.values()), problemOf(address, messages)));
            }
            return out;
        }

        // ── Shared ────────────────────────────────────────────────────────────

        private void addMessage(Map<String, EventMessage> into, JsonNode written, String fallbackName, String channel) {
            String refName = null;
            String ref = written.path("$ref").asText("");
            if (!ref.isEmpty()) refName = unescape(ref.substring(ref.lastIndexOf('/') + 1));
            JsonNode written0 = resolve(written, "a message of channel '" + channel + "'");
            if (!written0.isObject()) return;
            String name = written0.path("name").isTextual() ? written0.path("name").asText()
                    : refName != null ? refName
                    : fallbackName != null ? fallbackName
                    : channel;
            // Its traits first: what the message says itself wins, and their headers join its own.
            JsonNode message = withTraits((ObjectNode) written0, name);
            JsonNode payload = message.path("payload");
            String format = message.path("schemaFormat").asText(null);
            // 3.x's other way to say it: payload: { schemaFormat, schema }.
            if (payload.isObject() && payload.has("schemaFormat") && payload.has("schema")) {
                format = payload.path("schemaFormat").asText(null);
                payload = payload.path("schema");
            }
            boolean avro = format != null && isAvro(format);
            if (format != null && !avro && !isJsonSchema(format)) {
                problems.add("Message '" + name + "' has schemaFormat '" + format
                        + "': the wizard reads JSON Schema and Avro payloads (AsyncAPI's own format, JSON Schema, "
                        + "OpenAPI or application/vnd.apache.avro)");
                return;
            }
            if (avro) {
                // An Avro schema is sometimes given as its JSON text.
                if (payload.isTextual() && payload.asText().trim().startsWith("{")) {
                    try {
                        payload = JSON.readTree(payload.asText());
                    } catch (Exception e) {
                        problems.add("Message '" + name + "' has an Avro schema that is not valid JSON");
                        return;
                    }
                }
                if (payload.isMissingNode() || payload.isNull()) {
                    problems.add("Message '" + name + "' says its payload is Avro but gives no schema");
                    return;
                }
            }
            into.putIfAbsent(name, new EventMessage(name, text(message, "title") != null ? text(message, "title")
                    : text(message, "summary"), payload.isMissingNode() || payload.isNull() ? null : payload, avro,
                    headersOf(message, name), correlationHeaderOf(message, name), keyOf(message, name)));
        }

        /**
         * The message with its {@code traits} applied: each trait's fields that the message does
         * not set, and the properties of each trait's {@code headers} that its own do not name.
         * (A 2.x trait may be written {@code [trait, bindings]}: the first is the trait.)
         */
        private JsonNode withTraits(ObjectNode message, String name) {
            if (!message.path("traits").isArray() || message.path("traits").isEmpty()) return message;
            ObjectNode out = message.deepCopy();
            out.remove("traits");
            for (JsonNode written : message.path("traits")) {
                JsonNode trait = resolve(written.isArray() ? written.path(0) : written, "a trait of message '" + name + "'");
                for (Iterator<Map.Entry<String, JsonNode>> it = trait.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> f = it.next();
                    if (!out.has(f.getKey())) {
                        out.set(f.getKey(), f.getValue());
                    } else if (f.getKey().equals("headers")) {
                        JsonNode own = resolve(out.get("headers"), "the headers of message '" + name + "'");
                        JsonNode theirs = resolve(f.getValue(), "the headers of a trait of message '" + name + "'");
                        if (own.path("properties").isObject() && theirs.path("properties").isObject()) {
                            ObjectNode merged = own.deepCopy();
                            ObjectNode properties = (ObjectNode) merged.get("properties");
                            theirs.path("properties").fields().forEachRemaining(h -> {
                                if (!properties.has(h.getKey())) properties.set(h.getKey(), h.getValue());
                            });
                            out.set("headers", merged);
                        }
                    }
                }
            }
            return out;
        }

        /** Its headers' schema: an object with properties (3.x's {schemaFormat, schema} unwrapped), or null. */
        private JsonNode headersOf(JsonNode message, String name) {
            JsonNode headers = resolve(message.path("headers"), "the headers of message '" + name + "'");
            if (headers.path("schema").isObject()) headers = resolve(headers.path("schema"), "the headers of message '" + name + "'");
            return headers.path("properties").isObject() && !headers.path("properties").isEmpty() ? headers : null;
        }

        /** The header a {@code correlationId} is in ({@code $message.header#/correlationId}); one in the payload is not a header. */
        private String correlationHeaderOf(JsonNode message, String name) {
            JsonNode correlation = resolve(message.path("correlationId"), "the correlationId of message '" + name + "'");
            String location = text(correlation, "location");
            if (location == null || !location.startsWith("$message.header#/")) return null;
            String header = unescape(location.substring("$message.header#/".length()));
            return header.isBlank() || header.contains("/") ? null : header;
        }

        /** Its Kafka key's schema ({@code bindings.kafka.key}), or null. */
        private JsonNode keyOf(JsonNode message, String name) {
            JsonNode bindings = resolve(message.path("bindings"), "the bindings of message '" + name + "'");
            JsonNode key = bindings.path("kafka").path("key");
            if (key.isMissingNode() || key.isNull()) return null;
            JsonNode schema = resolve(key, "the Kafka key of message '" + name + "'");
            return schema.isObject() ? schema : null;
        }

        private static boolean isJsonSchema(String format) {
            String f = format.toLowerCase(Locale.ROOT);
            return f.startsWith("application/vnd.aai.asyncapi") || f.startsWith("application/schema+json")
                    || f.startsWith("application/schema+yaml") || f.startsWith("application/vnd.oai.openapi");
        }

        /**
         * A Kafka topic is a fixed name: an address with a parameter in it is not one. One channel's
         * messages travel either as Avro or as JSON: a listener cannot tell the two apart.
         */
        private static String problemOf(String address, Map<String, EventMessage> messages) {
            if (address.contains("{")) return "its address has a parameter ('" + address + "'), which a Kafka topic cannot have";
            long avro = messages.values().stream().filter(EventMessage::avro).count();
            if (avro > 0 && avro < messages.size()) {
                return "it carries Avro and JSON messages together, and one topic is read one way";
            }
            return null;
        }

        /** Follows {@code $ref}s inside the document. One that points outside it is a problem. */
        private JsonNode resolve(JsonNode node, String where) {
            JsonNode current = node;
            for (int hops = 0; hops < 20; hops++) {
                String ref = current.path("$ref").asText("");
                if (ref.isEmpty()) return current;
                if (!ref.startsWith("#/")) {
                    problems.add(where + " refers to '" + ref + "', which is not part of the document given. "
                            + "Give one self-contained AsyncAPI document.");
                    return JSON.createObjectNode();
                }
                JsonNode target = root.at(ref.substring(1));
                if (target.isMissingNode()) {
                    problems.add(where + " refers to '" + ref + "', which the document does not contain");
                    return JSON.createObjectNode();
                }
                current = target;
            }
            problems.add(where + " is a reference that never ends");
            return JSON.createObjectNode();
        }

        /** A JSON Pointer segment back to the key it names. */
        private static String unescape(String segment) {
            return segment.replace("~1", "/").replace("~0", "~");
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.path(field);
            return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
        }
    }

    /** {@code application/vnd.apache.avro;version=…}, and its {@code +json} / {@code +yaml} forms. */
    static boolean isAvro(String format) {
        return format.toLowerCase(Locale.ROOT).startsWith("application/vnd.apache.avro");
    }

    /** {@code components.schemas} of the document, or an empty object. */
    static ObjectNode schemasOf(JsonNode root) {
        JsonNode schemas = root.path("components").path("schemas");
        return schemas.isObject() ? ((ObjectNode) schemas).deepCopy() : JSON.createObjectNode();
    }
}
