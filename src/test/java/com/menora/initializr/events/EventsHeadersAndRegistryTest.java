package com.menora.initializr.events;

import com.menora.initializr.events.AsyncApiReader.AsyncApiDocument;
import com.menora.initializr.events.AsyncApiReader.EventMessage;
import com.menora.initializr.events.EventsCodeGenerator.GeneratedEventFile;
import com.menora.initializr.events.EventsCodeGenerator.ProjectFacts;
import io.spring.initializr.generator.version.Version;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keys, headers, correlation ids and traits in the events wizard — for JSON and Avro channels — and
 * Avro through Confluent's schema registry ({@code eventOptions.<dep>.schemaRegistry: confluent}).
 */
class EventsHeadersAndRegistryTest {

    private static final String PKG = "com.menora.demo";

    private final EventsCodeGenerator generator = new EventsCodeGenerator();

    private static String fixture() throws IOException {
        try (var in = new ClassPathResource("wizard-fixtures/orders-avro-registry-asyncapi.yaml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private Map<String, String> generate(EventsWizardOptions options) throws IOException {
        return generator.generate(fixture(), PKG, options, ProjectFacts.NONE).stream()
                .collect(Collectors.toMap(GeneratedEventFile::relativePath, GeneratedEventFile::content, (a, b) -> a,
                        java.util.LinkedHashMap::new));
    }

    private static String java(String path) {
        return "src/main/java/{{packagePath}}/" + path;
    }

    private static final EventsWizardOptions PLAIN = new EventsWizardOptions(null, null, null, List.of(), true);
    private static final EventsWizardOptions CONFLUENT = new EventsWizardOptions(null, null, null, List.of(), true, "Confluent");

    @Test
    void theReaderMergesTraitsAndFindsTheCorrelationIdAndTheKey() throws IOException {
        AsyncApiDocument doc = AsyncApiReader.read(fixture());
        EventMessage placed = doc.channel("orderPlaced").messages().get(0);
        // The message's own headers, then the trait's it does not name.
        assertThat(placed.headers().path("properties").fieldNames()).toIterable().containsExactly("source", "priority", "tenant");
        assertThat(placed.headers().path("properties").path("source").has("description")).isFalse();
        assertThat(placed.correlationHeader()).isEqualTo("x-correlation-id");
        assertThat(placed.key().path("format").asText()).isEqualTo("uuid");
        EventMessage refunded = doc.channel("refunds").messages().get(0);
        assertThat(refunded.hasHeaders()).isFalse();
        assertThat(refunded.key()).isNull();
    }

    @Test
    void headersAreARecordOfTheirOwnSentAsTextWithANewCorrelationId() throws IOException {
        Map<String, String> files = generate(PLAIN);
        assertThat(files.get(java("events/model/OrderPlacedHeaders.java")))
                .contains("public record OrderPlacedHeaders(\n        String source,\n        Integer priority,\n"
                        + "        String tenant,\n        String xCorrelationId) {\n}");
        // A UUID key; the two-argument send is kept and sends no headers but the correlation id.
        String producer = files.get(java("events/OrderPlacedProducer.java"));
        assertThat(producer)
                .contains("public CompletableFuture<SendResult<String, byte[]>> send(java.util.UUID key, OrderPlaced payload) {\n"
                        + "        return send(key, payload, null);")
                .contains("public CompletableFuture<SendResult<String, byte[]>> send(java.util.UUID key, OrderPlaced payload, OrderPlacedHeaders headers) {")
                .contains("record = new ProducerRecord<>(topic, key == null ? null : key.toString(), bytes(payload.toByteBuffer()));")
                .contains("if (headers != null && headers.priority() != null) record.headers().add(\"priority\", "
                        + "String.valueOf(headers.priority()).getBytes(StandardCharsets.UTF_8));")
                .contains("String correlation = headers == null || headers.xCorrelationId() == null ? "
                        + "java.util.UUID.randomUUID().toString() : headers.xCorrelationId();")
                .contains("import com.menora.demo.events.model.OrderPlacedHeaders;")
                .contains("import java.nio.charset.StandardCharsets;");
        // The listener hands the key, the payload and the headers over.
        String listener = files.get(java("events/OrderPlacedListener.java"));
        assertThat(listener)
                .contains("handle(record.key() == null ? null : java.util.UUID.fromString(record.key()), payload, headersOf(record));")
                .contains("parse(header(record, \"priority\"), Integer::valueOf)")
                .contains("void handle(java.util.UUID key, OrderPlaced payload, OrderPlacedHeaders headers) {");
        // A JSON channel too: its correlation id is one of its headers.
        String shipped = files.get(java("events/ShipmentsProducer.java"));
        assertThat(shipped)
                .contains("ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, toJson(payload));")
                .contains("String correlation = headers == null || headers.traceId() == null ? java.util.UUID.randomUUID().toString() : headers.traceId();")
                .contains("record.headers().add(\"trace-id\", String.valueOf(correlation).getBytes(StandardCharsets.UTF_8));");
        assertThat(files.get("src/test/java/{{packagePath}}/events/ShipmentsProducerTest.java"))
                .contains("Mockito.verify(kafkaTemplate).send(sent.capture());")
                .contains("assertThat(sent.getValue().headers().lastHeader(\"trace-id\")).isNotNull();");
        // A channel with neither keeps its code as it was.
        assertThat(files.get(java("events/RefundsListener.java")))
                .contains("handle(record.key(), payload);")
                .contains("void handle(String key, OrderRefunded payload) {")
                .doesNotContain("headersOf");
        assertThat(files.get(java("events/AvroKafkaConfig.java"))).contains("ByteArraySerializer").doesNotContain("Confluent");
    }

    @Test
    void withTheRegistryTheRecordsGoThroughConfluentsSerializer() throws IOException {
        Map<String, String> files = generate(CONFLUENT);
        assertThat(files.get(java("events/AvroKafkaConfig.java")))
                .contains("props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);")
                .contains("props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);")
                .contains("public KafkaTemplate<String, SpecificRecord> avroKafkaTemplate(");
        assertThat(files.get(java("events/OrderPlacedProducer.java")))
                .contains("ProducerRecord<String, SpecificRecord> record = new ProducerRecord<>(topic, key == null ? null : key.toString(), payload);")
                .doesNotContain("ByteBuffer");
        assertThat(files.get(java("events/RefundsListener.java")))
                .contains("public void onMessage(ConsumerRecord<String, OrderRefunded> record) {")
                .contains("handle(record.key(), record.value());");
        assertThat(files.get("src/test/java/{{packagePath}}/events/RefundsListenerTest.java"))
                .contains("\"schema.registry.url\", \"mock://RefundsListenerTest\"");
        assertThat(files.get("src/main/resources/application.yaml"))
                .startsWith("app:\n  events:\n    topics:\n")
                .contains("    schema-registry:\n      url: \"${SCHEMA_REGISTRY_URL:http://localhost:8081}\"\n"
                        + "      auto-register: \"${SCHEMA_REGISTRY_AUTO_REGISTER:false}\"\n");
        // The serializers get them from the configuration itself.
        assertThat(files.get(java("events/AvroKafkaConfig.java")))
                .contains("registry.put(\"schema.registry.url\", url);")
                .contains("        props.putAll(registry);\n        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);");
        // The JSON channel is the same either way.
        assertThat(files.get(java("events/ShipmentsProducer.java"))).isEqualTo(generate(PLAIN).get(java("events/ShipmentsProducer.java")));
        assertThat(generator.usesRegistry(fixture(), PKG, CONFLUENT)).isTrue();
        assertThat(generator.usesRegistry(fixture(), PKG, PLAIN)).isFalse();
    }

    @Test
    void theSerializerFollowsTheBootLinesKafkaClient() {
        assertThat(EventsCodeGenerator.confluentVersion(Version.parse("3.2.1"))).isEqualTo("7.6.1");
        assertThat(EventsCodeGenerator.confluentVersion(Version.parse("3.4.2"))).isEqualTo("7.8.0");
        assertThat(EventsCodeGenerator.confluentVersion(Version.parse("3.5.16"))).isEqualTo("7.9.0");
    }
}
