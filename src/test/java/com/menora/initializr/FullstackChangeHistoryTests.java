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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who changed what (2026-11): the {@code audit} option's created-by/updated-by columns and the
 * {@code history} option's change log, restore and History tab — on the Approvals example. Their
 * behaviour over a real API is {@code GeneratedBackendBuildSmokeTests} ({@code LIVE_HISTORY_TESTS}).
 */
@IntegrationTest
class FullstackChangeHistoryTests {

    @Autowired
    private TestRestTemplate rest;

    private Map<String, String> generate(List<String> scaffold, Map<String, Object> claimOpts, List<String> deps) throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("approvals", "react-tailwind-crud");
        body.put("opts", Map.of("scaffold", scaffold));
        // The example's activity feed needs audit: without it, the classic shell.
        if (!scaffold.contains("audit")) body.put("pages", List.of());
        if (deps != null) body.put("dependencies", deps);
        if (claimOpts != null) {
            for (Object e : (List<?>) body.get("entities")) {
                @SuppressWarnings("unchecked") Map<String, Object> entity = (Map<String, Object>) e;
                if ("ExpenseClaim".equals(entity.get("name"))) entity.put("opts", claimOpts);
            }
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = rest.exchange("/starter-fullstack.zip", HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
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

    @Test
    void auditNamesWhoCreatedAndLastChangedARow() throws Exception {
        Map<String, String> files = generate(List.of("audit"), null, null);
        assertThat(file(files, "entity/ExpenseClaim.java"))
                .contains("@CreatedBy\n    @Column(name = \"created_by\", updatable = false)\n    private String createdBy;")
                .contains("@LastModifiedBy\n    @Column(name = \"updated_by\")\n    private String updatedBy;");
        assertThat(file(files, "config/JpaAuditingConfig.java"))
                .contains("@EnableJpaAuditing(auditorAwareRef = \"auditor\")")
                .contains("return () -> Optional.of(RequestUser.current());");
        assertThat(file(files, "web/RequestUser.java")).contains("request.getHeader(\"userinfo\")").contains("SYSTEM = \"system\"");
        assertThat(file(files, "dto/ExpenseClaimDto.java")).contains("String createdBy,").contains("entity.getUpdatedBy()");
        String controller = file(files, "controller/ExpenseClaimController.java");
        assertThat(controller)
                .contains("@RequestParam(required = false) String changedBy,")
                .contains("String author = \"me\".equals(changedBy) ? com.menora.support.web.RequestUser.current() : changedBy;")
                .contains("            \"createdBy\",\n            \"updatedBy\",\n    });");
        assertThat(file(files, "service/ExpenseClaimService.java"))
                .contains("cb.equal(root.get(\"createdBy\"), changedBy), cb.equal(root.get(\"updatedBy\"), changedBy)");
        // The frontend shows them in the details, offers them as (hidden) columns and filters by them.
        assertThat(file(files, "entities/expense-claim/model/types.ts")).contains("createdBy?: string | null");
        assertThat(file(files, "features/expense-claim-form/ui/ExpenseClaimDetail.tsx")).contains("<Row label={t('createdBy')}>");
        assertThat(ListPages.of(files, "pages/expense-claim/ui/ExpenseClaimPage.tsx"))
                .contains("const WHO_HIDDEN = ['createdBy', 'updatedBy']")
                .contains("filters: withMine(")
                .contains("{t('changedByMe')}");
        // No history without the option.
        assertThat(files.keySet()).noneMatch(p -> p.contains("/history/") || p.endsWith("History.tsx"));
    }

    @Test
    void historyKeepsEveryChangeAndAnAdminRestoresAVersion() throws Exception {
        Map<String, String> files = generate(List.of("audit", "history"), null, null);
        String recorder = file(files, "history/ChangeLogRecorder.java");
        assertThat(recorder)
                // Each tracked class with the fields a restore leaves alone: the workflow's and the file.
                .contains("Map.entry(com.menora.support.entity.ExpenseClaim.class, new Tracked(\"expense-claim\", Set.of(\"status\", \"assignee\", \"receipt\")))")
                .contains("Map.entry(com.menora.support.entity.CostCenter.class, new Tracked(\"cost-center\", Set.of()))")
                .contains("registry.appendListeners(EventType.POST_UPDATE, this);");
        assertThat(file(files, "history/ChangeHistory.java")).contains("throw new ResourceConflictException(newest.getChangedBy() + \" changed it since\");");
        assertThat(file(files, "history/ChangeLogEntry.java")).contains("@Table(name = \"app_change_log\"");
        String controller = file(files, "controller/ExpenseClaimController.java");
        assertThat(controller)
                .contains("@GetMapping(\"/{id}/history\")")
                .contains("@PostMapping(\"/{id}/history/{changeId}/restore\")")
                .contains("history.restore(\"expense-claim\", id, changeId,");
        // The approvals example's backend set has ldap-auth: a restore is an admin's.
        assertThat(controller).contains("@RequiresPermission(Constants.ADMIN)\n    @PostMapping(\"/{id}/history/{changeId}/restore\")");
        // Frontend: the shared panel, one per entity, in the drawer, the side pane and the record page.
        assertThat(file(files, "shared/ui/ChangeHistory.tsx")).contains("export function ChangeHistory(");
        assertThat(file(files, "features/expense-claim-form/ui/ExpenseClaimHistory.tsx"))
                .contains("const mayRestore = useCallerMay('ADMIN')")
                .contains("'status': { label: 'Status', values: {");
        assertThat(ListPages.of(files, "pages/expense-claim/ui/ExpenseClaimPage.tsx")).contains("<ExpenseClaimHistory id={String(list.rowKey(list.detailRow))}");
        assertThat(files.entrySet().stream().filter(e -> e.getKey().contains("app/screens/") && e.getValue().contains("ExpenseClaimHistory")))
                .isNotEmpty();
        assertThat(file(files, "shared/i18n/strings.ts")).contains("changeHistory: 'Change history',").contains("restoreVersion:");
    }

    @Test
    void anEntityMayOptOutAndTheBaselineHasTheLog() throws Exception {
        Map<String, String> files = generate(List.of("audit", "history"), Map.of("history", false),
                List.of("web", "data-jpa", "h2", "validation", "flyway", "ldap-auth-rest"));
        assertThat(file(files, "history/ChangeLogRecorder.java")).doesNotContain("ExpenseClaim.class");
        assertThat(file(files, "controller/ExpenseClaimController.java")).doesNotContain("/history");
        assertThat(files.keySet()).noneMatch(p -> p.endsWith("ExpenseClaimHistory.tsx"));
        String baseline = file(files, "db/migration/V1__init.sql");
        assertThat(baseline).contains("create table app_change_log").contains("created_by varchar(255)");
    }

    @Test
    void withoutEitherOptionNothingChanges() throws Exception {
        Map<String, String> files = generate(List.of("csvExport"), null, null);
        assertThat(files.keySet()).noneMatch(p -> p.endsWith("RequestUser.java") || p.contains("/history/"));
        assertThat(file(files, "controller/ExpenseClaimController.java")).doesNotContain("changedBy");
    }
}
