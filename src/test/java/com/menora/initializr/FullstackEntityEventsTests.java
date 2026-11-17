package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entity events and notifications (2026-11): the {@code events} option — an outbox sent to Kafka
 * and webhooks, and a live stream the frontend refreshes from — and a workflow transition's
 * {@code notify} e-mails, on the Approvals example. Their behaviour over a real API is
 * {@code GeneratedBackendBuildSmokeTests} ({@code LIVE_EVENTS_TESTS}).
 */
@IntegrationTest
class FullstackEntityEventsTests {

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<byte[]> post(List<String> scaffold, List<String> deps, Consumer<Map<String, Object>> claim) {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("approvals", "react-tailwind-crud");
        body.put("opts", Map.of("scaffold", scaffold));
        // The example's activity feed needs audit: without it, the classic shell.
        if (!scaffold.contains("audit")) body.put("pages", List.of());
        if (deps != null) body.put("dependencies", deps);
        if (claim != null) {
            for (Object e : (List<?>) body.get("entities")) {
                @SuppressWarnings("unchecked") Map<String, Object> entity = (Map<String, Object>) e;
                if ("ExpenseClaim".equals(entity.get("name"))) claim.accept(entity);
            }
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/starter-fullstack.zip", HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
    }

    private Map<String, String> generate(List<String> scaffold, List<String> deps, Consumer<Map<String, Object>> claim) throws Exception {
        ResponseEntity<byte[]> response = post(scaffold, deps, claim);
        assertThat(response.getStatusCode()).as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Map<String, String> files = new TreeMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.isDirectory()) files.put(entry.getName(), new String(zin.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static String file(Map<String, String> files, String suffix) {
        return files.entrySet().stream().filter(e -> e.getKey().endsWith(suffix)).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    /** The approve move e-mails {@code recipients}. */
    @SuppressWarnings("unchecked")
    private static Consumer<Map<String, Object>> approveNotifies(List<String> recipients) {
        return claim -> {
            Map<String, Object> workflow = (Map<String, Object>) claim.get("workflow");
            for (Object t : (List<?>) workflow.get("transitions")) {
                Map<String, Object> transition = (Map<String, Object>) t;
                if ("approve".equals(transition.get("name"))) transition.put("notify", recipients);
            }
        };
    }

    @Test
    void eventsGoThroughAnOutboxToWebhooksAndTheBrowser() throws Exception {
        Map<String, String> files = generate(List.of("audit", "events"), null, null);
        String recorder = file(files, "events/EventRecorder.java");
        assertThat(recorder)
                .contains("Map.entry(com.menora.support.entity.ExpenseClaim.class, new Tracked(\"expense-claim\", true, \"status\", \"USER\"))")
                .contains("Map.entry(com.menora.support.entity.CostCenter.class, new Tracked(\"cost-center\", true, null, \"USER\"))")
                .contains("registry.appendListeners(EventType.POST_UPDATE, this);")
                .doesNotContain("NOTIFY")
                .doesNotContain("\"kafka\"");
        assertThat(file(files, "events/OutboxMessage.java")).contains("@Table(name = \"app_outbox\"");
        assertThat(file(files, "events/OutboxRelay.java")).contains("@EnableScheduling");
        assertThat(file(files, "events/WebhookSender.java")).contains("X-Event-Signature");
        String live = file(files, "events/LiveEvents.java");
        assertThat(live).contains("@GetMapping(path = \"/api/events/stream\"")
                // Approvals' backend set has ldap-auth: a page hears only of what its user may read.
                .contains("return permissions.holds(user.trim(), level);");
        // Without the kafka dependency and without a move that e-mails: neither channel.
        assertThat(files.keySet()).noneMatch(p -> p.endsWith("KafkaEventSender.java") || p.endsWith("MailNotifier.java"));
        // A move names its transition in the event.
        assertThat(file(files, "service/ExpenseClaimService.java"))
                .contains("com.menora.support.events.EventRecorder.transition(\"expense-claim\", id, name);");
        // Frontend: one live connection at the root, and the dashboards add it to their own refresh.
        assertThat(file(files, "frontend/src/main.tsx"))
                .contains("import { LiveUpdates } from './shared/api/LiveUpdates'")
                .contains("    <LiveUpdates>\n      <App />\n    </LiveUpdates>\n");
        assertThat(file(files, "shared/api/LiveUpdates.tsx")).contains("/api/events/stream");
        assertThat(files.entrySet().stream().filter(e -> e.getKey().contains("app/screens/")
                && e.getValue().contains("<RefreshTick.Provider value={tick + live}>"))).isNotEmpty();
        assertThat(file(files, "README.md")).contains("## Events and e-mails").contains("**Webhooks**").doesNotContain("**Kafka**");
    }

    @Test
    void withKafkaEachEntityHasATopic() throws Exception {
        Map<String, String> files = generate(List.of("events", "tests"),
                List.of("web", "data-jpa", "h2", "validation", "kafka", "ldap-auth-rest"), null);
        assertThat(file(files, "events/KafkaEventSender.java")).contains("kafka.send(properties.getKafka().topicOf(message.getEntityName())");
        assertThat(file(files, "events/EventRecorder.java")).contains("messages.add(new String[] {\"kafka\", payload});");
        // The generated tests have no broker.
        assertThat(file(files, "application-test.yaml")).contains("  events:\n    # No broker in the tests: the changes are not queued for Kafka.\n    kafka:\n      enabled: false\n");
        assertThat(file(files, "README.md")).contains("**Kafka**");
    }

    @Test
    void aTransitionEmailsItsRecipientsWithoutTheOption() throws Exception {
        Map<String, String> files = generate(List.of("audit"),
                List.of("web", "data-jpa", "h2", "validation", "flyway", "ldap-auth-rest"),
                approveNotifies(List.of("creator", "admin")));
        String recorder = file(files, "events/EventRecorder.java");
        assertThat(recorder)
                // Watched for its moves only: its changes are not published.
                .contains("new Tracked(\"expense-claim\", false, \"status\", \"USER\")")
                .doesNotContain("CostCenter.class")
                .contains("Map.entry(\"expense-claim:approve\", new Notify(null, true, Set.of(\"ADMIN\"), \"ExpenseClaim\", \"Approve\"))")
                // The states as the application words them.
                .contains("Map.entry(\"SUBMITTED\", \"Waiting for approval\")")
                .contains("Map.entry(\"APPROVED\", \"Approved\")");
        assertThat(file(files, "events/MailNotifier.java")).contains("implements OutboxSender");
        // The LDAP REST service is asked for an address.
        assertThat(file(files, "events/MailAddresses.java")).contains("@Value(\"${menora.ldap-rest.base-url}\") String directoryUrl");
        assertThat(file(files, "backend/pom.xml")).contains("<artifactId>spring-boot-starter-mail</artifactId>");
        // No events option: no webhooks, no live stream, no frontend change.
        assertThat(files.keySet()).noneMatch(p -> p.endsWith("WebhookSender.java") || p.endsWith("LiveEvents.java")
                || p.endsWith("LiveUpdates.tsx"));
        assertThat(file(files, "frontend/src/main.tsx")).doesNotContain("LiveUpdates");
        assertThat(file(files, "db/migration/V1__init.sql")).contains("create table app_outbox");
        assertThat(file(files, "README.md")).contains("**E-mail**").doesNotContain("**Webhooks**");
    }

    @Test
    void rejectsRecipientsTheModelCannotName() {
        // Nobody keeps who created a claim without audit.
        ResponseEntity<byte[]> creator = post(List.of(), null, approveNotifies(List.of("creator")));
        assertThat(creator.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new String(creator.getBody(), StandardCharsets.UTF_8)).contains("notifies the creator, which needs the audit option");
        ResponseEntity<byte[]> unknown = post(List.of("audit"), null, approveNotifies(List.of("manager")));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new String(unknown.getBody(), StandardCharsets.UTF_8))
                .contains("notifies 'manager' (expected assignee, creator, ADMIN or USER)");
    }

    @Test
    void withoutEitherNothingChanges() throws Exception {
        Map<String, String> files = generate(List.of("csvExport"), null, null);
        assertThat(files.keySet()).noneMatch(p -> p.contains("/events/") || p.endsWith("LiveUpdates.tsx"));
        assertThat(file(files, "service/ExpenseClaimService.java")).doesNotContain("EventRecorder");
        assertThat(file(files, "README.md")).doesNotContain("## Events and e-mails");
    }
}
