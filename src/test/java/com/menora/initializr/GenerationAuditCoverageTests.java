package com.menora.initializr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.GenerationEventEntity;
import com.menora.initializr.db.repository.GenerationEventRepository;
import com.menora.initializr.observability.GenerationAuditFilter;
import com.menora.initializr.observability.GenerationAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generation audit covers every endpoint: a JSON request (wizard, fullstack, patch) is recorded
 * with what it resolved to, a multi-module one with every module's dependencies; each row says its
 * kind and action; a failure says why; detection calls are not generations. And the admin side:
 * the period summary (generations apart from previews, per day, by kind, department and user,
 * failures by reason), the filtered events, the CSV, and retention.
 */
@IntegrationTest
class GenerationAuditCoverageTests {

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;
    @Autowired private GenerationEventRepository repo;
    @Autowired private GenerationAuditService audit;

    private String token;

    private HttpHeaders admin() throws Exception {
        if (token == null) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = rest.exchange("/admin/login", HttpMethod.POST,
                    new HttpEntity<>("{\"password\":\"test\"}", headers), String.class);
            token = json.readTree(response.getBody()).get("token").asText();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private JsonNode adminGet(String url) throws Exception {
        ResponseEntity<String> response = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(admin()), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return json.readTree(response.getBody());
    }

    /** The newest event of an artifact, through the admin API (which waits for the writer). */
    private JsonNode eventOf(String artifactId) throws Exception {
        JsonNode page = adminGet("/admin/activity/events?q=" + artifactId + "&size=10");
        for (JsonNode event : page.path("content")) {
            if (artifactId.equals(event.path("artifactId").asText())) return event;
        }
        throw new AssertionError("no event for " + artifactId + " in " + page);
    }

    private ResponseEntity<byte[]> post(String url, Object body, HttpHeaders extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (extra != null) headers.putAll(extra);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
    }

    private static Map<String, Object> wizardBody(String artifactId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", artifactId);
        body.put("packageName", "com.menora.audit");
        body.put("bootVersion", "3.2.1");
        body.put("javaVersion", "21");
        body.put("department", "lts");
        body.put("dependencies", List.of("web", "h2"));
        return body;
    }

    @Test
    void aWizardRequestIsRecordedAsItResolved() throws Exception {
        Map<String, Object> body = wizardBody("audit-wizard");
        body.put("sqlByDep", Map.of("h2", "CREATE TABLE things (id BIGINT PRIMARY KEY, name VARCHAR(40));"));
        HttpHeaders user = new HttpHeaders();
        user.set("userinfo", "auditor");
        assertThat(post("/starter-wizard.zip", body, user).getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode event = eventOf("audit-wizard");
        assertThat(event.path("endpoint").asText()).isEqualTo("starter-wizard.zip");
        assertThat(event.path("kind").asText()).isEqualTo("wizard");
        assertThat(event.path("action").asText()).isEqualTo("download");
        assertThat(event.path("wizards").asText()).isEqualTo("sql");
        assertThat(event.path("groupId").asText()).isEqualTo("com.menora");
        assertThat(event.path("bootVersion").asText()).isEqualTo("3.2.1");
        assertThat(event.path("department").asText()).isEqualTo("lts");
        assertThat(event.path("username").asText()).isEqualTo("auditor");
        // The dependency the wizard added (data-jpa for the entities) is there too.
        assertThat(event.path("dependencyIds").asText()).contains("web").contains("h2").contains("data-jpa");
    }

    @Test
    void theWizardEndpointWithoutADocumentIsAPlainBackend() throws Exception {
        assertThat(post("/starter-wizard.zip", wizardBody("audit-plain"), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode event = eventOf("audit-plain");
        assertThat(event.path("kind").asText()).isEqualTo("backend");
        assertThat(event.path("wizards").isNull()).isTrue();
    }

    @Test
    void aFullstackRequestSaysItsModel() throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("artifactId", "audit-fullstack");
        assertThat(FullstackExampleRequests.post(rest, body).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode event = eventOf("audit-fullstack");
        assertThat(event.path("kind").asText()).isEqualTo("fullstack");
        assertThat(event.path("entityCount").asInt()).isEqualTo(((List<?>) body.get("entities")).size());
        assertThat(event.path("dependencyIds").asText()).isNotBlank();
    }

    @Test
    void aMultiModuleProjectCountsEveryModulesDependencies() throws Exception {
        ResponseEntity<byte[]> response = rest.getForEntity("/starter-multimodule.zip?modules=api,core,persistence"
                + "&artifactId=audit-multi&groupId=com.menora&dependencies=kafka", byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode event = eventOf("audit-multi");
        assertThat(event.path("kind").asText()).isEqualTo("multimodule");
        // kafka on every module, and what the persistence module maps to.
        assertThat(event.path("dependencyIds").asText()).contains("kafka").contains("data-jpa");
    }

    @Test
    void anUpdateIsAPatchOfItsModelsKind() throws Exception {
        Map<String, Object> base = wizardBody("audit-patch");
        Map<String, Object> target = wizardBody("audit-patch");
        target.put("dependencies", List.of("web", "h2", "validation"));
        target.put("sqlByDep", Map.of("h2", "CREATE TABLE things (id BIGINT PRIMARY KEY);"));
        assertThat(post("/starter.patch", Map.of("base", Map.of("request", base), "target", Map.of("request", target)), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode event = eventOf("audit-patch");
        assertThat(event.path("action").asText()).isEqualTo("patch");
        assertThat(event.path("kind").asText()).isEqualTo("wizard");
        assertThat(event.path("dependencyIds").asText()).contains("validation");
    }

    @Test
    void aFailureSaysWhy() throws Exception {
        Map<String, Object> body = wizardBody("audit-broken");
        body.put("sqlByDep", Map.of("h2", "CREATE TABLE t (id BIGINT PRIMARY KEY);"));
        body.put("sqlOptions", Map.of("h2", Map.of("apiMode", "NOPE")));
        assertThat(post("/starter-wizard.zip", body, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode page = adminGet("/admin/activity/events?status=FAILURE&q=NOPE&size=5");
        assertThat(page.path("totalElements").asLong()).isPositive();
        JsonNode event = page.path("content").get(0);
        assertThat(event.path("errorMessage").asText()).startsWith("HTTP 400 — ").contains("NOPE");
        assertThat(event.path("status").asText()).isEqualTo("FAILURE");
    }

    @Test
    void aDetectionCallIsNotAGeneration() throws Exception {
        long before = repo.count();
        post("/starter-wizard.detect-paths", Map.of("spec", "openapi: 3.0.0\ninfo: {title: t, version: '1'}\npaths: {}"), null);
        audit.awaitIdle(java.time.Duration.ofSeconds(2));
        assertThat(repo.findAll()).noneMatch(e -> e.getEndpoint().contains("detect"));
        assertThat(repo.count()).isEqualTo(before);
    }

    @Test
    void theSummaryCountsGenerationsApartFromPreviews() throws Exception {
        rest.getForEntity("/starter.zip?dependencies=web&artifactId=audit-sum-zip&department=audit-dept", byte[].class);
        rest.getForEntity("/starter.preview?dependencies=web&artifactId=audit-sum-preview", String.class);
        JsonNode summary = adminGet("/admin/activity/summary?days=7");
        assertThat(summary.path("totalCount").asLong()).isPositive();
        assertThat(summary.path("previewCount").asLong()).isPositive();
        assertThat(summary.path("perDay")).hasSize(8);
        JsonNode today = summary.path("perDay").get(7);
        assertThat(today.path("generations").asLong()).isPositive();
        assertThat(today.path("byKind").path("backend").asLong()).isPositive();
        assertThat(summary.path("byKind").get(0).path("kind").asText()).isEqualTo("backend");
        assertThat(summary.path("byDepartment").toString()).contains("audit-dept");
        assertThat(summary.path("retentionDays").asInt()).isEqualTo(365);
        // Generations only: the preview is not in the totals.
        long generations = 0;
        for (JsonNode kind : summary.path("byKind")) generations += kind.path("generations").asLong();
        assertThat(generations).isEqualTo(summary.path("totalCount").asLong());
    }

    @Test
    void theEventsFilterAndExport() throws Exception {
        rest.getForEntity("/starter.zip?dependencies=web&artifactId=audit-filter-a", byte[].class);
        rest.getForEntity("/frontend/starter.zip?projectName=audit-filter-b", byte[].class);
        JsonNode frontend = adminGet("/admin/activity/events?kind=frontend&q=audit-filter&size=10");
        assertThat(frontend.path("content")).allMatch(e -> e.path("kind").asText().equals("frontend"));
        assertThat(frontend.path("content").toString()).contains("audit-filter-b").doesNotContain("audit-filter-a");
        JsonNode previews = adminGet("/admin/activity/events?action=preview&size=200");
        assertThat(previews.path("content")).allMatch(e -> e.path("action").asText().equals("preview"));
        JsonNode generations = adminGet("/admin/activity/events?action=generation&size=200");
        assertThat(generations.path("content")).noneMatch(e -> e.path("action").asText().equals("preview"));
        JsonNode paged = adminGet("/admin/activity/events?size=1&page=0");
        assertThat(paged.path("content")).hasSize(1);
        assertThat(paged.path("totalElements").asLong()).isGreaterThan(1);

        ResponseEntity<byte[]> csv = rest.exchange("/admin/activity/events.csv?q=audit-filter", HttpMethod.GET,
                new HttpEntity<>(admin()), byte[].class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("generation-activity.csv");
        String text = new String(csv.getBody(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿timestamp,endpoint,kind,action,status,").contains("audit-filter-a").contains("audit-filter-b");

        assertThat(rest.exchange("/admin/activity/events?from=yesterday", HttpMethod.GET, new HttpEntity<>(admin()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Behind the admin login.
        assertThat(rest.getForEntity("/admin/activity/events", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void retentionRemovesWhatIsOlderThanThePeriod() {
        GenerationEventEntity old = GenerationAuditService.newEvent("starter.zip",
                Instant.now().minus(400, ChronoUnit.DAYS), 5, GenerationEventEntity.Status.SUCCESS);
        old.setArtifactId("audit-old");
        GenerationEventEntity recent = GenerationAuditService.newEvent("starter.zip",
                Instant.now().minus(10, ChronoUnit.DAYS), 5, GenerationEventEntity.Status.SUCCESS);
        recent.setArtifactId("audit-recent");
        repo.save(old);
        repo.save(recent);
        assertThat(audit.purgeOlderThan(0)).isZero();
        assertThat(audit.purgeOlderThan(365)).isPositive();
        assertThat(repo.findAll()).noneMatch(e -> "audit-old".equals(e.getArtifactId()))
                .anyMatch(e -> "audit-recent".equals(e.getArtifactId()));
    }

    @Test
    void anErrorBodySaysItsMessage() {
        assertThat(GenerationAuditFilter.errorMessageOf(400, "{\"error\":\"Invalid request\",\"detail\":\"Unknown mode\"}".getBytes()))
                .isEqualTo("HTTP 400 — Unknown mode");
        assertThat(GenerationAuditFilter.errorMessageOf(400, "{\"side\":\"target\",\"messages\":[\"a\",\"b\"]}".getBytes()))
                .isEqualTo("HTTP 400 — target: a; b");
        assertThat(GenerationAuditFilter.errorMessageOf(500, "<html>oops</html>".getBytes())).isEqualTo("HTTP 500");
        assertThat(GenerationAuditFilter.errorMessageOf(404, new byte[0])).isEqualTo("HTTP 404");
        assertThat(GenerationAuditFilter.errorMessageOf(400, "plain words".getBytes())).isEqualTo("HTTP 400 — plain words");
    }

    @Test
    void aCsvCellCannotBeAFormula() {
        assertThat(com.menora.initializr.admin.ActivityController.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(com.menora.initializr.admin.ActivityController.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(com.menora.initializr.admin.ActivityController.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(com.menora.initializr.admin.ActivityController.cell(null)).isEmpty();
    }
}
