package com.menora.initializr.events;

import com.menora.initializr.config.WizardArgumentException;
import com.menora.initializr.events.AsyncApiReader.AsyncApiDocument;
import com.menora.initializr.events.AsyncApiReader.AsyncApiParseException;
import com.menora.initializr.events.AsyncApiReader.EventChannel;
import com.menora.initializr.events.EventsCodeGenerator.GeneratedEventFile;
import com.menora.initializr.events.EventsCodeGenerator.ProjectFacts;
import com.menora.initializr.events.EventsWizardOptions.ChannelChoice;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The events wizard: what {@link AsyncApiReader} makes of an AsyncAPI document, 2.x and 3.x, and
 * what {@link EventsCodeGenerator} writes from it. The two fixtures describe the same events.
 */
class EventsCodeGeneratorTest {

    private final EventsCodeGenerator generator = new EventsCodeGenerator();

    private static String v3() throws IOException {
        return fixture("orders-asyncapi-v3.yaml");
    }

    private static String v2() throws IOException {
        return fixture("orders-asyncapi-v2.yaml");
    }

    /** With LF line endings whatever the checkout made of the file. */
    private static String fixture(String name) throws IOException {
        try (var in = new ClassPathResource("wizard-fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    @Test
    void anAsyncApi3DocumentIsReadByItsOperations() throws IOException {
        AsyncApiDocument doc = AsyncApiReader.read(v3());
        assertThat(doc.version()).isEqualTo("3.0.0");
        assertThat(doc.channels()).extracting(EventChannel::name)
                .containsExactly("orderCreated", "orderEvents", "stockLevels", "heartbeat", "userInbox");
        EventChannel created = doc.channel("orderCreated");
        assertThat(created.address()).isEqualTo("orders.created");
        // An operation's action is the application's own: send, receive.
        assertThat(created.sends()).isTrue();
        assertThat(created.receives()).isTrue();
        assertThat(created.messages()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("OrderCreated");
            assertThat(m.title()).isEqualTo("An order was created");
            assertThat(m.payload().path("$ref").asText()).isEqualTo("#/components/schemas/Order");
        });
        assertThat(doc.channel("orderEvents").sends()).isFalse();
        assertThat(doc.channel("orderEvents").receives()).isTrue();
        assertThat(doc.channel("orderEvents").messages()).extracting(AsyncApiReader.EventMessage::name)
                .containsExactly("OrderShipped", "OrderCancelled");
        // payload: { schemaFormat, schema } is the schema.
        assertThat(doc.channel("orderEvents").messages().get(1).payload().path("properties").has("reason")).isTrue();
        assertThat(doc.channel("heartbeat").messages()).singleElement().satisfies(m -> assertThat(m.payload()).isNull());
        // A Kafka topic is a fixed name.
        assertThat(doc.channel("userInbox").problem()).contains("users.{userId}.inbox");
        assertThat(created.problem()).isNull();
    }

    @Test
    void anAsyncApi2DocumentIsReadTheOtherWayRound() throws IOException {
        // 2.x speaks as a client: "subscribe" is what clients subscribe to — the application sends it.
        AsyncApiDocument doc = AsyncApiReader.read(v2());
        assertThat(doc.channels()).extracting(EventChannel::name)
                .containsExactly("orders.created", "orders.events", "inventory.stock-levels", "ops.heartbeat", "users.{userId}.inbox");
        assertThat(doc.channel("orders.created").sends()).isTrue();
        assertThat(doc.channel("orders.created").receives()).isTrue();
        assertThat(doc.channel("orders.events").sends()).isFalse();
        assertThat(doc.channel("orders.events").receives()).isTrue();
        assertThat(doc.channel("orders.events").messages()).extracting(AsyncApiReader.EventMessage::name)
                .containsExactly("OrderShipped", "OrderCancelled");
        assertThat(doc.channel("inventory.stock-levels").sends()).isTrue();
        assertThat(doc.channel("inventory.stock-levels").receives()).isFalse();
        // The same directions as the 3.x document says in its own words.
        AsyncApiDocument three = AsyncApiReader.read(v3());
        for (int i = 0; i < doc.channels().size(); i++) {
            assertThat(doc.channels().get(i).address()).isEqualTo(three.channels().get(i).address());
            assertThat(doc.channels().get(i).sends()).isEqualTo(three.channels().get(i).sends());
            assertThat(doc.channels().get(i).receives()).isEqualTo(three.channels().get(i).receives());
        }
    }

    @Test
    void aDocumentThatCannotBeUsedSaysWhy() {
        assertThatThrownBy(() -> AsyncApiReader.read("{not: [yaml")).isInstanceOf(AsyncApiParseException.class)
                .hasMessageContaining("neither YAML nor JSON");
        assertThatThrownBy(() -> AsyncApiReader.read("openapi: 3.0.0\npaths: {}"))
                .hasMessageContaining("no 'asyncapi' version field");
        assertThatThrownBy(() -> AsyncApiReader.read("asyncapi: 1.2.0\nchannels: {a: {}}"))
                .hasMessageContaining("AsyncAPI 1.2.0 is not supported");
        assertThatThrownBy(() -> AsyncApiReader.read("asyncapi: 3.0.0\ninfo: {title: t, version: '1'}"))
                .hasMessageContaining("declares no channels");
        // A payload that is not JSON Schema, a reference out of the document, one into nothing.
        assertThatThrownBy(() -> AsyncApiReader.read("""
                asyncapi: 2.6.0
                channels:
                  a:
                    publish:
                      message:
                        name: Avro
                        schemaFormat: application/vnd.apache.avro;version=1.9.0
                        payload: { type: record }
                  b:
                    publish:
                      message: { $ref: './messages.yaml#/Order' }
                  c:
                    publish:
                      message: { $ref: '#/components/messages/Missing' }
                """))
                .isInstanceOfSatisfying(AsyncApiParseException.class, e -> assertThat(e.messages()).hasSize(3)
                        .anySatisfy(m -> assertThat(m).contains("Message 'Avro' has schemaFormat 'application/vnd.apache.avro;version=1.9.0'"))
                        .anySatisfy(m -> assertThat(m).contains("'./messages.yaml#/Order'").contains("self-contained"))
                        .anySatisfy(m -> assertThat(m).contains("'#/components/messages/Missing'").contains("does not contain")));
        // JSON is read too.
        assertThat(AsyncApiReader.read("{\"asyncapi\":\"3.0.0\",\"channels\":{\"a\":{\"address\":\"t.a\"}}}").channel("a").address())
                .isEqualTo("t.a");
    }

    // ── Generating ───────────────────────────────────────────────────────────

    @Test
    void byDefaultTheProjectIsTheApplicationTheDocumentDescribes() throws IOException {
        List<GeneratedEventFile> files = generator.generate(v3(), "com.menora.demo", null, ProjectFacts.NONE);
        assertThat(files).extracting(GeneratedEventFile::relativePath).containsExactlyInAnyOrder(
                // The payload types: the schemas the document names, and each payload written in place.
                "src/main/java/{{packagePath}}/events/model/Order.java",
                "src/main/java/{{packagePath}}/events/model/OrderLine.java",
                "src/main/java/{{packagePath}}/events/model/OrderStatus.java",
                "src/main/java/{{packagePath}}/events/model/StockLevel.java",
                "src/main/java/{{packagePath}}/events/model/OrderShipped.java",
                "src/main/java/{{packagePath}}/events/model/OrderShippedCarrier.java",
                "src/main/java/{{packagePath}}/events/model/OrderCancelled.java",
                // A producer where it sends, a listener where it receives. No code for the parameterized address.
                "src/main/java/{{packagePath}}/events/OrderCreatedProducer.java",
                "src/main/java/{{packagePath}}/events/OrderCreatedListener.java",
                "src/main/java/{{packagePath}}/events/OrderEventsListener.java",
                "src/main/java/{{packagePath}}/events/StockLevelsProducer.java",
                "src/main/java/{{packagePath}}/events/HeartbeatProducer.java",
                "src/main/resources/application.yaml");

        assertThat(source(files, "events/OrderCreatedProducer.java"))
                .contains("package com.menora.demo.events;")
                .contains("import com.menora.demo.events.model.Order;")
                .contains(" * Sends to {@code orders.created}: A new order *\\/ was placed.")
                .contains("@Value(\"${app.events.topics.order-created}\") String topic) {")
                .contains("    /** An order was created */\n"
                        + "    public CompletableFuture<SendResult<String, String>> send(String key, Order payload) {\n"
                        + "        return kafkaTemplate.send(topic, key, toJson(payload));");
        assertThat(source(files, "events/OrderCreatedListener.java"))
                .contains("    @KafkaListener(topics = \"${app.events.topics.order-created}\", groupId = \"${spring.kafka.consumer.group-id}\")\n"
                        + "    public void onMessage(ConsumerRecord<String, String> record) throws JsonProcessingException {\n"
                        + "        Order payload = objectMapper.readValue(record.value(), Order.class);\n"
                        + "        handle(record.key(), payload);")
                .contains("    void handle(String key, Order payload) {");
        // Several kinds of message on one channel: nothing in a record says which, so the tree is handed over.
        assertThat(source(files, "events/OrderEventsListener.java"))
                .contains("several kinds of message (OrderShipped, OrderCancelled)")
                .contains("        JsonNode payload = objectMapper.readTree(record.value());")
                .contains("    void handle(String key, JsonNode payload) {")
                .contains("import com.fasterxml.jackson.databind.JsonNode;");
        // A payload that is a list; one that declares nothing.
        assertThat(source(files, "events/StockLevelsProducer.java"))
                .contains("send(String key, List<StockLevel> payload)").contains("import java.util.List;");
        assertThat(source(files, "events/HeartbeatProducer.java")).contains("send(String key, Object payload)");
        // A topic's name is a property, set to the channel's address.
        assertThat(source(files, "application.yaml")).isEqualTo("""
                app:
                  events:
                    topics:
                      order-created: "orders.created"
                      order-events: "orders.events"
                      stock-levels: "inventory.stock-levels"
                      heartbeat: "ops.heartbeat"
                """);
        // The payload types are the OpenAPI wizard's: an enum keeps its wire values, a name its spelling.
        assertThat(source(files, "events/model/OrderStatus.java")).contains("    NEW(\"new\"),\n    PAID(\"paid\"),\n    IN_DELIVERY(\"in-delivery\");");
        assertThat(source(files, "events/model/OrderShipped.java"))
                .contains("        @JsonProperty(\"order_id\") Long orderId,\n        OrderShippedCarrier carrier,\n"
                        + "        @JsonProperty(\"shipped-at\") OffsetDateTime shippedAt\n");
        assertThat(source(files, "events/model/Order.java")).doesNotContain("jakarta.validation");
    }

    @Test
    void theOptionsChooseChannelsDirectionsPackagesAndTests() throws IOException {
        // The other party: it consumes what the described application sends, and sends what that receives.
        EventsWizardOptions options = new EventsWizardOptions("messaging", "messaging.dto", "billing-service", List.of(
                new ChannelChoice("orders.created", false, true),
                new ChannelChoice("orders.events", true, false),
                new ChannelChoice("ops.heartbeat", false, false)), true);
        List<GeneratedEventFile> files = generator.generate(v2(), "com.menora.demo", options, new ProjectFacts(true));
        assertThat(files).extracting(GeneratedEventFile::relativePath)
                .contains("src/main/java/{{packagePath}}/messaging/OrdersCreatedListener.java",
                        "src/main/java/{{packagePath}}/messaging/OrdersEventsProducer.java",
                        "src/test/java/{{packagePath}}/messaging/OrdersCreatedListenerTest.java",
                        "src/test/java/{{packagePath}}/messaging/OrdersEventsProducerTest.java",
                        "src/main/java/{{packagePath}}/messaging/dto/Order.java")
                .noneMatch(p -> p.contains("OrdersCreatedProducer") || p.contains("Heartbeat") || p.contains("StockLevels"));
        assertThat(source(files, "messaging/OrdersCreatedListener.java"))
                .contains("groupId = \"billing-service\")")
                .contains("import com.menora.demo.messaging.dto.Order;");
        // Several kinds of message: a method each.
        assertThat(source(files, "messaging/OrdersEventsProducer.java"))
                .contains("sendOrderShipped(String key, OrderShipped payload)")
                .contains("sendOrderCancelled(String key, OrderCancelled payload)");
        assertThat(source(files, "messaging/OrdersEventsProducerTest.java"))
                .contains("        OrderShipped payload = new OrderShipped(null, null, null);\n\n        producer.sendOrderShipped(\"key-1\", payload);")
                .contains("assertThat(mapper.readValue(sent.getValue(), OrderShipped.class)).isEqualTo(payload);")
                .contains("void sendOrderCancelledWritesJsonToTheTopic()");
        assertThat(source(files, "messaging/OrdersCreatedListenerTest.java"))
                .contains("String json = mapper.writeValueAsString(new Order(null, null, null, null, null));");
        // With the validation starter the payloads carry the schemas' constraints.
        assertThat(source(files, "messaging/dto/Order.java"))
                .contains("        @NotNull Long id,\n        @NotNull OrderStatus status,\n")
                .contains("@JsonProperty(\"customer_email\") @Email String customerEmail,\n")
                .contains("@NotNull @Size(min = 1) @Valid List<OrderLine> lines\n");
        assertThat(source(files, "application.yaml")).contains("orders-created: \"orders.created\"").contains("orders-events: \"orders.events\"")
                .doesNotContain("heartbeat");
    }

    @Test
    void aChoiceTheDocumentCannotMeetIsRefused() throws IOException {
        String doc = v3();
        assertThatThrownBy(() -> generator.generate(doc, "p", options(new ChannelChoice("nope", true, false)), ProjectFacts.NONE))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("Unknown channel 'nope'. The document has: orderCreated, orderEvents, stockLevels, heartbeat, userInbox");
        assertThatThrownBy(() -> generator.generate(doc, "p", options(new ChannelChoice("userInbox", false, true)), ProjectFacts.NONE))
                .hasMessageContaining("No code can be written for channel 'userInbox'").hasMessageContaining("a Kafka topic cannot have");
        assertThatThrownBy(() -> generator.generate(doc, "p",
                options(new ChannelChoice("heartbeat", true, false), new ChannelChoice("heartbeat", false, true)), ProjectFacts.NONE))
                .hasMessageContaining("listed twice");
        assertThatThrownBy(() -> generator.generate(doc, "p", options(new ChannelChoice("heartbeat", false, false)), ProjectFacts.NONE))
                .hasMessageContaining("No channel to generate");
        // Channels and no operations: nothing says what the application does — it has to be chosen.
        String channelsOnly = "asyncapi: 3.0.0\nchannels:\n  a: { address: t.a }\n";
        assertThatThrownBy(() -> generator.generate(channelsOnly, "p", null, ProjectFacts.NONE)).hasMessageContaining("No channel to generate");
        assertThat(generator.generate(channelsOnly, "p", options(new ChannelChoice("a", true, true)), ProjectFacts.NONE))
                .extracting(GeneratedEventFile::relativePath)
                .contains("src/main/java/{{packagePath}}/events/AProducer.java", "src/main/java/{{packagePath}}/events/AListener.java");
        assertThat(generator.generate("  ", "p", null, ProjectFacts.NONE)).isEmpty();
    }

    @Test
    void theDrawerIsToldWhatTheDocumentHas() throws IOException {
        assertThat(generator.detectChannels(v3())).extracting(c -> c.name() + " " + c.address() + " " + c.sends() + "/" + c.receives()
                        + " " + c.messages() + (c.problem() == null ? "" : " !"))
                .containsExactly(
                        "orderCreated orders.created true/true [OrderCreated]",
                        "orderEvents orders.events false/true [OrderShipped, OrderCancelled]",
                        "stockLevels inventory.stock-levels true/false [StockLevels]",
                        "heartbeat ops.heartbeat true/false [beat]",
                        "userInbox users.{userId}.inbox false/true [note] !");
        assertThat(generator.detectChannels("")).isEmpty();
    }

    private static EventsWizardOptions options(ChannelChoice... channels) {
        return new EventsWizardOptions(null, null, null, List.of(channels), false);
    }

    private static String source(List<GeneratedEventFile> files, String suffix) {
        return files.stream()
                .filter(f -> f.relativePath().endsWith(suffix))
                .map(GeneratedEventFile::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No file ending with " + suffix + " in: "
                        + files.stream().map(GeneratedEventFile::relativePath).toList()));
    }
}
