package com.menora.initializr.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.events.AsyncApiReader.AsyncApiDocument;
import com.menora.initializr.events.AsyncApiReader.AsyncApiParseException;
import com.menora.initializr.events.EventsCodeGenerator.DetectedChannel;
import com.menora.initializr.events.EventsCodeGenerator.GeneratedEventFile;
import com.menora.initializr.events.EventsCodeGenerator.ProjectFacts;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Avro payloads in the events wizard: the document's Avro schemas become {@code .avsc} files the
 * avro-maven-plugin builds classes from, and their channels producers and listeners that send and
 * read the single-object encoding over {@code KafkaTemplate<String, byte[]>} (no registry).
 */
class EventsAvroTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PKG = "com.menora.demo";
    private static final String MODEL = PKG + ".events.model";

    private final EventsCodeGenerator generator = new EventsCodeGenerator();

    private static String fixture() throws IOException {
        try (var in = new ClassPathResource("wizard-fixtures/orders-avro-asyncapi.yaml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private Map<String, String> generate(String document, EventsWizardOptions options) {
        return generator.generate(document, PKG, options, ProjectFacts.NONE).stream()
                .collect(Collectors.toMap(GeneratedEventFile::relativePath, GeneratedEventFile::content, (a, b) -> a,
                        java.util.LinkedHashMap::new));
    }

    private static String java(String path) {
        return "src/main/java/{{packagePath}}/" + path;
    }

    @Test
    void avroMessagesAreReadAndTheDrawerIsToldWhichChannelsAreAvro() throws IOException {
        AsyncApiDocument doc = AsyncApiReader.read(fixture());
        assertThat(doc.channel("orderPlaced").avro()).isTrue();
        assertThat(doc.channel("customerEvents").avro()).isTrue();
        assertThat(doc.channel("audit").avro()).isFalse();
        // A schema given as its JSON text is read as the schema.
        assertThat(doc.channel("customerEvents").messages().get(1).payload().path("name").asText()).isEqualTo("CustomerDeleted");

        assertThat(generator.detectChannels(fixture())).extracting(DetectedChannel::name, DetectedChannel::format)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("orderPlaced", "avro"),
                        org.assertj.core.groups.Tuple.tuple("customerEvents", "avro"),
                        org.assertj.core.groups.Tuple.tuple("audit", "json"));
    }

    @Test
    void eachAvroSchemaIsAFileAndTheSharedOnesAreReadFirst() throws IOException {
        Map<String, String> files = generate(fixture(), null);
        assertThat(files.keySet().stream().filter(p -> p.startsWith("src/main/avro/")).toList()).containsExactly(
                "src/main/avro/com.acme.common.Address.avsc",
                "src/main/avro/com.acme.orders.OrderPlaced.avsc",
                "src/main/avro/" + MODEL + ".CustomerCreated.avsc",
                "src/main/avro/" + MODEL + ".CustomerDeleted.avsc");
        // A schema without a namespace takes the payload package, so its class has one.
        JsonNode created = JSON.readTree(files.get("src/main/avro/" + MODEL + ".CustomerCreated.avsc"));
        assertThat(created.path("namespace").asText()).isEqualTo(MODEL);
        assertThat(created.path("fields").get(1).path("type").asText()).isEqualTo("com.acme.common.Address");
        JsonNode placed = JSON.readTree(files.get("src/main/avro/com.acme.orders.OrderPlaced.avsc"));
        assertThat(placed.path("fields").get(2).path("type").path("logicalType").asText()).isEqualTo("decimal");

        // Address is used by two files, CustomerCreated defines the Tier CustomerDeleted uses.
        assertThat(generator.avroImports(fixture(), PKG, null))
                .containsExactly("com.acme.common.Address.avsc", MODEL + ".CustomerCreated.avsc");
        // A document without Avro asks for no plugin.
        assertThat(generator.avroImports("asyncapi: 3.0.0\nchannels: {a: {address: t.a}}", PKG, null)).isNull();
    }

    @Test
    void anAvroChannelSendsAndReadsBytesThroughItsOwnTemplate() throws IOException {
        Map<String, String> files = generate(fixture(), new EventsWizardOptions(null, null, null, List.of(), true));

        String producer = files.get(java("events/OrderPlacedProducer.java"));
        assertThat(producer)
                .contains("import com.acme.orders.OrderPlaced;")
                .contains("@Qualifier(AvroKafkaConfig.TEMPLATE) KafkaTemplate<String, byte[]> kafkaTemplate")
                .contains("public CompletableFuture<SendResult<String, byte[]>> send(String key, OrderPlaced payload)")
                .contains("bytes(payload.toByteBuffer())")
                .contains("/** An order was placed */")
                .doesNotContain("ObjectMapper");
        String listener = files.get(java("events/OrderPlacedListener.java"));
        assertThat(listener)
                .contains("containerFactory = AvroKafkaConfig.LISTENER_FACTORY")
                .contains("OrderPlaced payload = OrderPlaced.getDecoder().decode(record.value());")
                .contains("void handle(String key, OrderPlaced payload)");

        // Two kinds of message on one channel: told apart by their schemas' fingerprints.
        String customers = files.get(java("events/CustomerEventsListener.java"));
        assertThat(customers)
                .contains("import " + MODEL + ".CustomerCreated;")
                .contains("return CustomerCreated.getDecoder().decode(value);")
                .contains("return CustomerDeleted.getDecoder().decode(value);")
                .contains("catch (MissingSchemaException notThisOne)")
                .contains("void handle(String key, SpecificRecord payload)");

        assertThat(files.get(java("events/AvroKafkaConfig.java")))
                .contains("package " + PKG + ".events;")
                .contains("ByteArraySerializer.class").contains("ByteArrayDeserializer.class");

        // The tests make a value through the record's constructor, logical types included.
        assertThat(files.get("src/test/java/{{packagePath}}/events/OrderPlacedProducerTest.java"))
                .contains("OrderPlaced payload = new com.acme.orders.OrderPlaced(1L, java.time.Instant.ofEpochSecond(0), "
                        + "java.math.BigDecimal.ONE.setScale(2), com.acme.orders.OrderStatus.values()[0], java.util.List.of(), null, null);")
                .contains("assertThat(OrderPlaced.getDecoder().decode(sent.getValue())).isEqualTo(payload);");
        assertThat(files.get("src/test/java/{{packagePath}}/events/CustomerEventsListenerTest.java"))
                .contains("new " + MODEL + ".CustomerCreated(\"text\", new com.acme.common.Address(\"text\", \"text\"), "
                        + MODEL + ".Tier.values()[0]).toByteBuffer()")
                .contains(".isInstanceOf(AvroRuntimeException.class)");

        // The JSON channel beside them is as before, and the Avro schemas are no JSON payload types.
        assertThat(files.get(java("events/AuditProducer.java"))).contains("KafkaTemplate<String, String>");
        assertThat(files).containsKey(java("events/model/AuditEntry.java"));
        assertThat(files.keySet()).noneMatch(p -> p.endsWith("model/OrderPlaced.java") || p.endsWith("model/Address.java"));
    }

    @Test
    void aDocumentWithoutAvroGetsNoAvroConfig() throws IOException {
        try (var in = new ClassPathResource("wizard-fixtures/orders-asyncapi-v3.yaml").getInputStream()) {
            String v3 = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(generate(v3, null).keySet()).noneMatch(p -> p.contains("Avro") || p.startsWith("src/main/avro"));
        }
    }

    @Test
    void oneDefinitionWrittenTwiceTheSameWayIsOneFile() {
        String record = "{type: record, name: Money, namespace: com.acme, fields: [{name: cents, type: long}]}";
        Map<String, String> files = generate("""
                asyncapi: 2.6.0
                channels:
                  a:
                    subscribe:
                      message:
                        name: A
                        schemaFormat: application/vnd.apache.avro;version=1.9.0
                        payload: {type: record, name: A, namespace: com.acme, fields: [{name: price, type: %s}]}
                  b:
                    subscribe:
                      message:
                        name: B
                        schemaFormat: application/vnd.apache.avro;version=1.9.0
                        payload: %s
                """.formatted(record, record), null);
        assertThat(files.keySet().stream().filter(p -> p.endsWith(".avsc")).toList())
                .containsExactly("src/main/avro/com.acme.A.avsc");
        assertThat(files.get(java("events/BProducer.java"))).contains("import com.acme.Money;");
    }

    @Test
    void whatWouldNotBuildIsRefusedWithItsReason() {
        String avro = "schemaFormat: application/vnd.apache.avro;version=1.9.0";
        assertThatThrownBy(() -> generate("""
                asyncapi: 2.6.0
                channels:
                  a:
                    subscribe:
                      message: {name: Primitive, %1$s, payload: string}
                  b:
                    subscribe:
                      message: {name: Unknown, %1$s, payload: {type: record, name: U, fields: [{name: x, type: Nowhere}]}}
                  c:
                    subscribe:
                      message: {name: BadName, %1$s, payload: {type: record, name: B, fields: [{name: shipped-at, type: long}]}}
                  d:
                    subscribe:
                      message: {name: Twice1, %1$s, payload: {type: record, name: T, namespace: x, fields: [{name: a, type: int}]}}
                  e:
                    subscribe:
                      message: {name: Twice2, %1$s, payload: {type: record, name: T, namespace: x, fields: [{name: b, type: int}]}}
                """.formatted(avro), null))
                .isInstanceOfSatisfying(AsyncApiParseException.class, e -> assertThat(e.messages())
                        .anySatisfy(m -> assertThat(m).contains("message 'Primitive' is a string").contains("records only"))
                        .anySatisfy(m -> assertThat(m).contains("'" + MODEL + ".Nowhere', which no schema in the document defines"))
                        .anySatisfy(m -> assertThat(m).contains("field named 'shipped-at'"))
                        .anySatisfy(m -> assertThat(m).contains("defines 'x.T' differently")));
        // One topic is read one way.
        assertThat(AsyncApiReader.read("""
                asyncapi: 2.6.0
                channels:
                  mixed:
                    subscribe:
                      message:
                        oneOf:
                          - {name: J, payload: {type: object}}
                          - {name: A, %s, payload: {type: record, name: A, fields: []}}
                """.formatted(avro)).channel("mixed").problem()).contains("Avro and JSON messages together");
        assertThatThrownBy(() -> AsyncApiReader.read("""
                asyncapi: 2.6.0
                channels:
                  a:
                    subscribe:
                      message: {name: Empty, %s}
                """.formatted(avro))).hasMessageContaining("Message 'Empty' says its payload is Avro but gives no schema");
    }
}
