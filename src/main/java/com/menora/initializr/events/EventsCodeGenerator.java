package com.menora.initializr.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.menora.initializr.config.WizardArgumentException;
import com.menora.initializr.config.WizardTemplates;
import com.menora.initializr.events.AsyncApiReader.AsyncApiDocument;
import com.menora.initializr.events.AsyncApiReader.AsyncApiParseException;
import com.menora.initializr.events.AsyncApiReader.EventChannel;
import com.menora.initializr.events.AsyncApiReader.EventMessage;
import com.menora.initializr.gen.Naming;
import com.menora.initializr.openapi.SchemaModel;
import com.menora.initializr.openapi.SchemaRecordEmitter;
import com.menora.initializr.openapi.SchemaRecordEmitter.JavaTypeRef;
import com.menora.initializr.openapi.SchemaRecordEmitter.SchemaSet;
import com.menora.initializr.openapi.SelfContainedDocument;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The events wizard: an AsyncAPI document → Kafka code. For each channel chosen, a
 * {@code <Channel>Producer} ({@code KafkaTemplate}) and/or a {@code <Channel>Listener}
 * ({@code @KafkaListener}); for the payloads, the same Java types the OpenAPI wizard writes
 * ({@link SchemaRecordEmitter}).
 *
 * <p>The messages travel as JSON text. The catalog's Kafka dependency configures
 * {@code KafkaTemplate<String, String>} and String (de)serializers, and that is left alone: the
 * generated classes turn a payload into JSON and back with the application's {@code ObjectMapper}.
 * A topic's name is a property ({@code app.events.topics.<channel>}), set to the channel's
 * address in {@code application.yaml}.
 */
@Service
public class EventsCodeGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code relativePath} may contain {@code {{packagePath}}}, resolved when the file is written. */
    public record GeneratedEventFile(String relativePath, String content) {}

    /**
     * What the wizard's drawer shows of a channel before anything is generated.
     *
     * @param format {@code avro} when its messages are Avro records, else {@code json}
     */
    public record DetectedChannel(String name, String address, boolean sends, boolean receives,
                                  List<String> messages, String problem, String format) {}

    /** The version of Avro (and of its Maven plugin) a project with Avro payloads is given. */
    public static final String AVRO_VERSION = "1.11.4";

    /** @param validation the validation starter is on the build: payload constraints are written */
    public record ProjectFacts(boolean validation) {
        public static final ProjectFacts NONE = new ProjectFacts(false);
    }

    /** For the drawer: the document's channels, what the described application does with each. */
    public List<DetectedChannel> detectChannels(String document) {
        if (document == null || document.isBlank()) return List.of();
        List<DetectedChannel> out = new ArrayList<>();
        for (EventChannel c : AsyncApiReader.read(document).channels()) {
            out.add(new DetectedChannel(c.name(), c.address(), c.sends(), c.receives(),
                    c.messages().stream().map(EventMessage::name).toList(), c.problem(), c.avro() ? "avro" : "json"));
        }
        return out;
    }

    /**
     * The {@code .avsc} files the avro-maven-plugin must read before the others (names under
     * {@code src/main/avro}), or null when the document has no Avro schema — the build then gets
     * neither Avro nor the plugin.
     */
    public List<String> avroImports(String document, String packageName, EventsWizardOptions options) {
        if (document == null || document.isBlank()) return null;
        EventsWizardOptions opts = options == null ? EventsWizardOptions.DEFAULTS : options;
        AvroSchemas avro = new AvroSchemas(AsyncApiReader.read(document), packageName + "." + opts.payloadPackageOrDefault());
        return avro.isEmpty() ? null : avro.imports();
    }

    public List<GeneratedEventFile> generate(String document, String packageName, EventsWizardOptions options,
                                             ProjectFacts projectFacts) {
        if (document == null || document.isBlank()) return List.of();
        EventsWizardOptions opts = options == null ? EventsWizardOptions.DEFAULTS : options;
        ProjectFacts facts = projectFacts == null ? ProjectFacts.NONE : projectFacts;
        AsyncApiDocument doc = AsyncApiReader.read(document);
        List<Planned> plan = plan(doc, opts);

        String payloadPackage = packageName + "." + opts.payloadPackageOrDefault();
        String eventsPackage = packageName + "." + opts.packageOrDefault();
        AvroSchemas avro = new AvroSchemas(doc, payloadPackage);
        Payloads payloads = new Payloads(doc, facts.validation(), payloadPackage, avro.components());

        List<GeneratedEventFile> out = new ArrayList<>();
        Map<String, String> topics = new LinkedHashMap<>();
        List<Map<String, Object>> rendered = new ArrayList<>();
        Set<String> usedStems = new LinkedHashSet<>();
        boolean anyAvro = false;
        for (Planned p : plan) {
            String stem = uniqueStem(p.channel().name(), usedStems);
            String topicKey = Naming.toKebabCase(stem);
            topics.put(topicKey, p.channel().address());
            Map<String, Object> view;
            if (p.channel().avro()) {
                anyAvro = true;
                view = avroChannelView(p.channel(), stem, topicKey, avro, eventsPackage, opts);
            } else {
                List<MessageType> messages = new ArrayList<>();
                for (EventMessage m : p.channel().messages()) messages.add(payloads.typeOf(m));
                view = channelView(p.channel(), stem, topicKey, messages, eventsPackage, opts);
            }
            view.put("avro", p.channel().avro());
            view.put("produce", p.produce());
            view.put("consume", p.consume());
            rendered.add(view);
        }
        // The Avro schemas: the plugin makes their classes when the project is built.
        for (AvroSchemas.AvroFile f : avro.files()) {
            out.add(new GeneratedEventFile(AvroSchemas.DIRECTORY + "/" + f.fileName(), f.content()));
        }
        // The payload types: every schema the document names, and each payload written in place.
        String payloadPath = opts.payloadPackageOrDefault().replace('.', '/');
        for (SchemaModel model : payloads.schemas.models().values()) {
            out.add(new GeneratedEventFile("src/main/java/{{packagePath}}/" + payloadPath + "/" + model.name() + ".java",
                    SchemaRecordEmitter.render(model, payloadPackage)));
        }
        String eventsPath = opts.packageOrDefault().replace('.', '/');
        for (Map<String, Object> view : rendered) {
            String stem = (String) view.get("stem");
            String kind = (boolean) view.get("avro") ? "events/Avro" : "events/";
            if ((boolean) view.get("produce")) {
                out.add(new GeneratedEventFile("src/main/java/{{packagePath}}/" + eventsPath + "/" + stem + "Producer.java",
                        WizardTemplates.render(kind + "Producer.java", view)));
                if (opts.generateTests()) {
                    out.add(new GeneratedEventFile("src/test/java/{{packagePath}}/" + eventsPath + "/" + stem + "ProducerTest.java",
                            WizardTemplates.render(kind + "ProducerTest.java", view)));
                }
            }
            if ((boolean) view.get("consume")) {
                out.add(new GeneratedEventFile("src/main/java/{{packagePath}}/" + eventsPath + "/" + stem + "Listener.java",
                        WizardTemplates.render(kind + "Listener.java", view)));
                if (opts.generateTests()) {
                    out.add(new GeneratedEventFile("src/test/java/{{packagePath}}/" + eventsPath + "/" + stem + "ListenerTest.java",
                            WizardTemplates.render(kind + "ListenerTest.java", view)));
                }
            }
        }
        if (anyAvro) {
            out.add(new GeneratedEventFile("src/main/java/{{packagePath}}/" + eventsPath + "/AvroKafkaConfig.java",
                    WizardTemplates.render("events/AvroKafkaConfig.java", Map.of("package", eventsPackage))));
        }
        out.add(new GeneratedEventFile("src/main/resources/application.yaml", topicsYaml(topics)));
        return out;
    }

    // ── Which channels, in which direction ────────────────────────────────────

    private record Planned(EventChannel channel, boolean produce, boolean consume) {}

    private static List<Planned> plan(AsyncApiDocument doc, EventsWizardOptions opts) {
        List<Planned> out = new ArrayList<>();
        if (opts.channels().isEmpty()) {
            // As the application the document describes. A channel no code can be written for is left out.
            for (EventChannel c : doc.channels()) {
                if (c.problem() == null && (c.sends() || c.receives())) out.add(new Planned(c, c.sends(), c.receives()));
            }
        } else {
            Set<String> seen = new LinkedHashSet<>();
            for (EventsWizardOptions.ChannelChoice choice : opts.channels()) {
                EventChannel c = choice.name() == null ? null : doc.channel(choice.name());
                if (c == null) {
                    throw new WizardArgumentException("Unknown channel '" + choice.name() + "'. The document has: "
                            + String.join(", ", doc.channels().stream().map(EventChannel::name).toList()));
                }
                if (!seen.add(c.name())) {
                    throw new WizardArgumentException("Channel '" + c.name() + "' is listed twice");
                }
                if (!choice.produce() && !choice.consume()) continue;
                if (c.problem() != null) {
                    throw new WizardArgumentException("No code can be written for channel '" + c.name() + "': " + c.problem());
                }
                out.add(new Planned(c, choice.produce(), choice.consume()));
            }
        }
        if (out.isEmpty()) {
            throw new WizardArgumentException("No channel to generate: the document's operations name none this wizard "
                    + "can write code for, and none was chosen. Say which to produce to or consume from (eventOptions.channels).");
        }
        return out;
    }

    private static String uniqueStem(String channelName, Set<String> used) {
        String stem = SchemaRecordEmitter.toPascalCase(channelName.replaceAll("[^A-Za-z0-9]+", " ").trim());
        if (stem.isEmpty() || stem.equals("Unnamed")) stem = "Channel";
        if (Character.isDigit(stem.charAt(0))) stem = "Channel" + stem;
        String candidate = stem;
        for (int n = 2; !used.add(candidate); n++) candidate = stem + n;
        return candidate;
    }

    // ── Payload types ─────────────────────────────────────────────────────────

    /** A message's payload as Java: its type, and an expression that makes a value of it for a test. */
    private record MessageType(String messageName, String title, JavaTypeRef type, String sample) {}

    /**
     * The document's schemas as a schema set. The payload schemas are JSON Schema, which is what
     * an OpenAPI 3.1 document's schemas are: they are put under {@code components.schemas} of
     * one — each payload written in place under its message's name — and read by the same parser
     * and emitter as the OpenAPI wizard.
     */
    private static final class Payloads {
        private final AsyncApiDocument doc;
        private final SchemaSet schemas;
        private final Map<String, String> schemaOfMessage = new LinkedHashMap<>();

        /** @param avroComponents the keys of {@code components.schemas} that are Avro, which are not JSON Schema */
        Payloads(AsyncApiDocument doc, boolean validation, String payloadPackage, Set<String> avroComponents) {
            this.doc = doc;
            ObjectNode componentSchemas = AsyncApiReader.schemasOf(doc.root());
            componentSchemas.remove(avroComponents);
            for (EventChannel channel : doc.channels()) {
                for (EventMessage message : channel.messages()) {
                    if (message.avro() || message.payload() == null || schemaOfMessage.containsKey(message.name())) continue;
                    String ref = message.payload().path("$ref").asText("");
                    if (ref.startsWith("#/components/schemas/")) {
                        schemaOfMessage.put(message.name(), ref.substring("#/components/schemas/".length()));
                        continue;
                    }
                    JsonNode schema = message.payload();
                    if (!ref.isEmpty()) {
                        // A reference to somewhere else in the document: what it points at is the schema.
                        schema = ref.startsWith("#/") ? doc.root().at(ref.substring(1)) : JSON.missingNode();
                        if (schema.isMissingNode()) {
                            throw new AsyncApiParseException("The payload of message '" + message.name() + "' refers to '" + ref
                                    + "', which the document does not contain");
                        }
                    }
                    String name = SchemaRecordEmitter.toPascalCase(message.name().replaceAll("[^A-Za-z0-9]+", " ").trim());
                    if (componentSchemas.has(name)) name = name + "Payload";
                    String candidate = name;
                    for (int n = 2; componentSchemas.has(candidate); n++) candidate = name + n;
                    componentSchemas.set(candidate, schema.deepCopy());
                    schemaOfMessage.put(message.name(), candidate);
                }
            }
            ObjectNode openApi = JSON.createObjectNode();
            openApi.put("openapi", "3.1.0");
            openApi.putObject("info").put("title", "payloads").put("version", "1");
            openApi.putObject("paths");
            openApi.putObject("components").set("schemas", componentSchemas);
            String text;
            try {
                text = JSON.writeValueAsString(openApi);
            } catch (JsonProcessingException e) {
                throw new AsyncApiParseException("The document's schemas could not be read: " + e.getOriginalMessage());
            }
            // A payload schema may refer to a URL or a file, which the parser would fetch.
            SelfContainedDocument.Checked checked;
            try {
                checked = SelfContainedDocument.check(text);
            } catch (SelfContainedDocument.ExternalReferenceException e) {
                throw new AsyncApiParseException(e.messages());
            }
            SwaggerParseResult result = new OpenAPIV3Parser().readContents(checked.text(), null, checked.parseOptions());
            if (result.getOpenAPI() == null) {
                List<String> messages = result.getMessages() == null || result.getMessages().isEmpty()
                        ? List.of("The document's schemas could not be read") : result.getMessages();
                throw new AsyncApiParseException(messages);
            }
            this.schemas = SchemaRecordEmitter.read(result.getOpenAPI(), validation, payloadPackage);
        }

        MessageType typeOf(EventMessage message) {
            String schemaName = schemaOfMessage.get(message.name());
            // No payload declared: whatever JSON it is.
            JavaTypeRef type = schemaName == null ? new JavaTypeRef("Object", Set.of())
                    : schemas.typeOf(new Schema<>().$ref("#/components/schemas/" + schemaName), null);
            return new MessageType(message.name(), message.title(), type, sampleOf(type));
        }

        /** An expression for a value of the type, for the generated tests; null where none is simple. */
        private String sampleOf(JavaTypeRef type) {
            String java = type.typeName();
            SchemaModel model = schemas.models().get(java);
            if (model != null) {
                return switch (model.kind()) {
                    // Every component null: the JSON round trip is what the test is about.
                    case RECORD -> "new " + java + "(" + String.join(", ", Collections.nCopies(model.fields().size(), "null")) + ")";
                    case ENUM -> java + ".values()[0]";
                    case UNION -> null;
                };
            }
            if (java.startsWith("List<")) return "List.of()";
            if (java.startsWith("Map<")) return "Map.of()";
            return switch (java) {
                case "String" -> "\"text\"";
                case "Integer" -> "1";
                case "Long" -> "1L";
                case "Double" -> "1.5";
                case "Float" -> "1.5f";
                case "Boolean" -> "true";
                default -> null;
            };
        }
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    private static Map<String, Object> channelView(EventChannel channel, String stem, String topicKey,
                                                   List<MessageType> messages, String eventsPackage,
                                                   EventsWizardOptions opts) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", eventsPackage);
        view.put("stem", stem);
        view.put("addressJavadoc", SchemaRecordEmitter.escapeJavadoc(channel.address()));
        view.put("addressString", SchemaRecordEmitter.javaString(channel.address()));
        boolean described = channel.description() != null;
        view.put("hasDescription", described);
        view.put("descriptionJavadoc", described ? SchemaRecordEmitter.escapeJavadoc(channel.description()) : null);
        // Written whole: "${" beside a Mustache tag would read as a triple brace.
        view.put("topicPlaceholder", "${app.events.topics." + topicKey + "}");
        view.put("topicProperty", "app.events.topics." + topicKey);
        view.put("groupId", opts.groupId() == null || opts.groupId().isBlank()
                ? "${spring.kafka.consumer.group-id}" : SchemaRecordEmitter.javaString(opts.groupId().trim()));

        // A channel with one kind of message reads and writes that type; with none declared, any JSON.
        List<MessageType> typed = messages.isEmpty()
                ? List.of(new MessageType(channel.name(), null, new JavaTypeRef("Object", Set.of()), null)) : messages;
        boolean single = typed.size() == 1;

        Set<String> typeImports = new TreeSet<>();
        List<Map<String, Object>> sends = new ArrayList<>();
        Set<String> usedMethods = new LinkedHashSet<>();
        for (MessageType m : typed) {
            typeImports.addAll(m.type().imports());
            Map<String, Object> mv = new LinkedHashMap<>();
            String method = single ? "send"
                    : "send" + SchemaRecordEmitter.toPascalCase(m.messageName().replaceAll("[^A-Za-z0-9]+", " ").trim());
            String candidate = method;
            for (int n = 2; !usedMethods.add(candidate); n++) candidate = method + n;
            mv.put("method", candidate);
            mv.put("type", m.type().typeName());
            mv.put("hasTitle", m.title() != null);
            mv.put("titleJavadoc", m.title() == null ? null : SchemaRecordEmitter.escapeJavadoc(m.title()));
            mv.put("hasSample", m.sample() != null);
            mv.put("sample", m.sample());
            mv.put("readBack", readExpression("mapper", "sent.getValue()", m.type().typeName()));
            mv.put("testName", candidate + "WritesJsonToTheTopic");
            sends.add(mv);
        }
        view.put("sends", sends);

        // The listener: the one type, or the tree when several kinds of message share the channel.
        MessageType only = single ? typed.get(0) : null;
        boolean typedListener = only != null && !only.type().typeName().equals("Object");
        String listenerType = typedListener ? only.type().typeName() : "JsonNode";
        view.put("listenerType", listenerType);
        view.put("listenerRead", typedListener
                ? readExpression("objectMapper", "record.value()", listenerType)
                : "objectMapper.readTree(record.value())");
        view.put("listenerIsTree", !typedListener);
        view.put("hasCandidates", !single);
        view.put("candidates", String.join(", ", typed.stream().map(m -> m.type().typeName()).distinct().toList()));
        view.put("listenerSample", typedListener && only.sample() != null ? only.sample() : null);
        view.put("hasListenerSample", typedListener && only.sample() != null);
        // "{}" is a message for a tree; a typed listener is tried only with a value the test can make.
        view.put("listenerReadable", !typedListener || only.sample() != null);

        Set<String> producerImports = new TreeSet<>(typeImports);
        producerImports.addAll(List.of(
                "com.fasterxml.jackson.core.JsonProcessingException",
                "com.fasterxml.jackson.databind.ObjectMapper",
                "java.util.concurrent.CompletableFuture",
                "org.springframework.beans.factory.annotation.Value",
                "org.springframework.kafka.core.KafkaTemplate",
                "org.springframework.kafka.support.SendResult",
                "org.springframework.stereotype.Component"));
        producerImports.removeIf(i -> isIn(i, eventsPackage));
        view.put("producerImports", producerImports);

        Set<String> listenerImports = new TreeSet<>();
        if (typedListener) listenerImports.addAll(only.type().imports());
        else listenerImports.add("com.fasterxml.jackson.databind.JsonNode");
        if (typedListener && listenerType.indexOf('<') >= 0) listenerImports.add("com.fasterxml.jackson.core.type.TypeReference");
        listenerImports.addAll(List.of(
                "com.fasterxml.jackson.core.JsonProcessingException",
                "com.fasterxml.jackson.databind.ObjectMapper",
                "org.apache.kafka.clients.consumer.ConsumerRecord",
                "org.slf4j.Logger",
                "org.slf4j.LoggerFactory",
                "org.springframework.kafka.annotation.KafkaListener",
                "org.springframework.stereotype.Component"));
        listenerImports.removeIf(i -> isIn(i, eventsPackage));
        view.put("listenerImports", listenerImports);

        // The tests name the payload types too (and a generic one's TypeReference).
        Set<String> testImports = new TreeSet<>(typeImports);
        if (typed.stream().anyMatch(m -> m.sample() != null && m.type().typeName().indexOf('<') >= 0)) {
            testImports.add("com.fasterxml.jackson.core.type.TypeReference");
        }
        if (typed.stream().anyMatch(m -> "List.of()".equals(m.sample()))) testImports.add("java.util.List");
        if (typed.stream().anyMatch(m -> "Map.of()".equals(m.sample()))) testImports.add("java.util.Map");
        testImports.removeIf(i -> isIn(i, eventsPackage));
        view.put("testImports", testImports);
        view.put("hasTestImports", !testImports.isEmpty());
        view.put("hasAnySample", typed.stream().anyMatch(m -> m.sample() != null));
        return view;
    }

    /**
     * A channel whose messages are Avro records: their classes are the ones the avro-maven-plugin
     * makes from the {@code .avsc} files, named by each record's full name.
     */
    private static Map<String, Object> avroChannelView(EventChannel channel, String stem, String topicKey,
                                                       AvroSchemas avro, String eventsPackage,
                                                       EventsWizardOptions opts) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("package", eventsPackage);
        view.put("stem", stem);
        view.put("addressJavadoc", SchemaRecordEmitter.escapeJavadoc(channel.address()));
        view.put("addressString", SchemaRecordEmitter.javaString(channel.address()));
        boolean described = channel.description() != null;
        view.put("hasDescription", described);
        view.put("descriptionJavadoc", described ? SchemaRecordEmitter.escapeJavadoc(channel.description()) : null);
        view.put("topicPlaceholder", "${app.events.topics." + topicKey + "}");
        view.put("topicProperty", "app.events.topics." + topicKey);
        view.put("groupId", opts.groupId() == null || opts.groupId().isBlank()
                ? "${spring.kafka.consumer.group-id}" : SchemaRecordEmitter.javaString(opts.groupId().trim()));

        record Sent(EventMessage message, AvroSchemas.AvroType type) {}
        List<Sent> sent = new ArrayList<>();
        for (EventMessage m : channel.messages()) sent.add(new Sent(m, avro.typeOf(m)));
        // A class is imported by its simple name unless two of the channel's share one.
        List<String> fullNames = sent.stream().map(s -> s.type().javaType()).distinct().toList();
        Set<String> simpleNames = new LinkedHashSet<>();
        boolean clash = false;
        for (String full : fullNames) clash |= !simpleNames.add(full.substring(full.lastIndexOf('.') + 1));
        boolean imported = !clash;
        java.util.function.Function<String, String> shown = full -> imported ? full.substring(full.lastIndexOf('.') + 1) : full;
        Set<String> typeImports = new TreeSet<>();
        if (imported) {
            for (String full : fullNames) {
                if (full.contains(".") && !isIn(full, eventsPackage)) typeImports.add(full);
            }
        }

        boolean single = sent.size() == 1;
        List<Map<String, Object>> sends = new ArrayList<>();
        Set<String> usedMethods = new LinkedHashSet<>();
        for (Sent s : sent) {
            Map<String, Object> mv = new LinkedHashMap<>();
            String method = single ? "send"
                    : "send" + SchemaRecordEmitter.toPascalCase(s.message().name().replaceAll("[^A-Za-z0-9]+", " ").trim());
            String candidate = method;
            for (int n = 2; !usedMethods.add(candidate); n++) candidate = method + n;
            mv.put("method", candidate);
            mv.put("type", shown.apply(s.type().javaType()));
            mv.put("hasTitle", s.message().title() != null);
            mv.put("titleJavadoc", s.message().title() == null ? null : SchemaRecordEmitter.escapeJavadoc(s.message().title()));
            mv.put("hasSample", s.type().sample() != null);
            mv.put("sample", s.type().sample());
            mv.put("testName", candidate + "WritesAvroToTheTopic");
            sends.add(mv);
        }
        view.put("sends", sends);
        view.put("hasAnySample", sent.stream().anyMatch(s -> s.type().sample() != null));

        // The listener: the one class, or SpecificRecord when several kinds share the channel.
        boolean several = fullNames.size() > 1;
        view.put("hasCandidates", several);
        view.put("listenerType", several ? "SpecificRecord" : shown.apply(fullNames.get(0)));
        view.put("candidates", fullNames.stream().map(f -> Map.of("type", shown.apply(f))).toList());
        view.put("candidatesText", String.join(", ", fullNames.stream().map(shown).toList()));
        String listenerSample = sent.stream().map(s -> s.type().sample()).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        view.put("hasListenerSample", listenerSample != null);
        view.put("listenerSample", listenerSample);

        Set<String> producerImports = new TreeSet<>(typeImports);
        producerImports.addAll(List.of(
                "java.io.IOException",
                "java.io.UncheckedIOException",
                "java.nio.ByteBuffer",
                "java.util.concurrent.CompletableFuture",
                "org.springframework.beans.factory.annotation.Qualifier",
                "org.springframework.beans.factory.annotation.Value",
                "org.springframework.kafka.core.KafkaTemplate",
                "org.springframework.kafka.support.SendResult",
                "org.springframework.stereotype.Component"));
        view.put("producerImports", producerImports);

        Set<String> listenerImports = new TreeSet<>(typeImports);
        if (several) {
            listenerImports.add("org.apache.avro.message.MissingSchemaException");
            listenerImports.add("org.apache.avro.specific.SpecificRecord");
        }
        listenerImports.addAll(List.of(
                "java.io.IOException",
                "org.apache.kafka.clients.consumer.ConsumerRecord",
                "org.slf4j.Logger",
                "org.slf4j.LoggerFactory",
                "org.springframework.kafka.annotation.KafkaListener",
                "org.springframework.stereotype.Component"));
        view.put("listenerImports", listenerImports);

        // The producer's test names the payload classes; the listener's makes a value by its full name.
        view.put("testImports", typeImports);
        return view;
    }

    private static boolean isIn(String imported, String pkg) {
        return imported.startsWith(pkg + ".") && imported.indexOf('.', pkg.length() + 1) < 0;
    }

    /** {@code mapper.readValue(json, X.class)}, or with a {@code TypeReference} for a generic type. */
    private static String readExpression(String mapper, String json, String type) {
        return type.indexOf('<') >= 0
                ? mapper + ".readValue(" + json + ", new TypeReference<" + type + ">() {})"
                : mapper + ".readValue(" + json + ", " + type + ".class)";
    }

    /** {@code app.events.topics.<channel>: <address>}, merged into the project's application.yaml. */
    private static String topicsYaml(Map<String, String> topics) {
        StringBuilder sb = new StringBuilder("app:\n  events:\n    topics:\n");
        topics.forEach((key, address) -> sb.append("      ").append(key).append(": \"")
                .append(address.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n"));
        return sb.toString();
    }
}
