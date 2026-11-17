package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 10, phase 10: a workflow's move log ({@code workflow.trackMoves}) — every step of a row
 * written down, a record page's history read from it. Off unless asked for: a workflow without it
 * generates what it did. Driven for real by
 * {@code GeneratedBackendBuildSmokeTests.liveApiOfAWorkflowBackendBehaves}.
 */
@IntegrationTest
class FullstackWorkflowMovesTests {

    private static final String BE = "support/backend/src/main/java/com/menora/support/";
    private static final String FE = "support/frontend/src/";

    @Autowired
    private TestRestTemplate rest;

    /** The Approvals example with its own options, its workflow logging its moves or not. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> approvals(String frontendSet, boolean trackMoves) {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("approvals", frontendSet);
        body.put("opts", Map.of("scaffold", List.of("audit", "optimisticLocking", "csvExport", "seedData")));
        List<Map<String, Object>> entities = new ArrayList<>();
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            Map<String, Object> copy = new LinkedHashMap<>(entity);
            if (copy.get("workflow") instanceof Map<?, ?> workflow) {
                Map<String, Object> changed = new LinkedHashMap<>((Map<String, Object>) workflow);
                if (trackMoves) changed.put("trackMoves", true);
                else changed.remove("trackMoves");
                copy.put("workflow", changed);
            }
            entities.add(copy);
        }
        body.put("entities", entities);
        if (!trackMoves) {
            // The example's dashboard has a time-in-state card, which reads the log: without the
            // log it goes too (the validator would refuse it).
            List<Map<String, Object>> pages = new ArrayList<>();
            for (Map<String, Object> page : (List<Map<String, Object>>) body.get("pages")) {
                Map<String, Object> copy = new LinkedHashMap<>(page);
                if (copy.get("widgets") instanceof List<?> widgets) {
                    copy.put("widgets", widgets.stream()
                            .filter(w -> !"timeInState".equals(((Map<String, Object>) w).get("kind"))).toList());
                }
                pages.add(copy);
            }
            body.put("pages", pages);
        }
        return body;
    }

    private Map<String, String> generate(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, body);
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Map<String, String> files = new LinkedHashMap<>();
        FullstackExampleRequests.unzip(response.getBody()).forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
        return files;
    }

    @Test
    void everyStepOfARowIsWrittenDown() throws Exception {
        Map<String, String> files = generate(approvals("react-tailwind-crud", true));
        assertThat(files).containsKeys(BE + "workflow/WorkflowMove.java", BE + "workflow/WorkflowMoveRepository.java",
                BE + "workflow/WorkflowMoveLog.java");
        assertThat(files.get(BE + "workflow/WorkflowMove.java")).contains("@Table(name = \"app_workflow_move\",");
        String service = files.get(BE + "service/ExpenseClaimService.java");
        assertThat(service)
                // A new row's first step...
                .contains("        ExpenseClaim saved = repository.save(entity);\n"
                        + "        moves.created(\"expense-claim\", saved.getId(), saved.getStatus());\n"
                        + "        return saved;")
                // ...and each move, in the transaction that makes it.
                .contains("        ExpenseClaim.ExpenseClaimStatusType before = existing.getStatus();")
                .contains("        moves.moved(\"expense-claim\", existing.getId(), before, existing.getStatus(), name);\n"
                        + "        return repository.save(existing);");
        assertThat(files.get(BE + "controller/ExpenseClaimController.java"))
                .contains("    @GetMapping(\"/{id}/moves\")")
                .contains("    @GetMapping(\"/moves/time-in-state\")");
        // The demo rows get a history, so the page has something to show on a fresh start.
        assertThat(files.get(BE + "config/DemoDataLoader.java"))
                .contains("moves.seed(\"expense-claim\", row.getId(), ExpenseClaim.ExpenseClaimStatusType.DRAFT, row.getStatus(), i);");
        // The entity without a workflow is untouched.
        assertThat(files.get(BE + "service/CostCenterService.java")).doesNotContain("moves");
    }

    @Test
    void theRecordPageShowsTheRowsHistory() throws Exception {
        Map<String, String> files = generate(approvals("react-tailwind-crud", true));
        assertThat(files).containsKey(FE + "shared/ui/MovesList.tsx");
        assertThat(files.get(FE + "app/screens/ClaimScreen.tsx"))
                .contains("import { ExpenseClaimStatusTypeLabels as MOVE_STATES } from '@entities/expense-claim'")
                .contains("import { MovesList } from '@shared/ui/MovesList'")
                .contains("          path={`/api/expense-claims/${encodeURIComponent(recordId)}`}\n"
                        + "          labels={MOVE_STATES}\n"
                        + "          transitions={ { submit: ")
                // Read again whenever the row changes: a move made on the page shows at once.
                .contains("          version={record}");
        assertThat(files.get(FE + "app/screens/CostCenterScreen.tsx")).doesNotContain("MovesList");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).contains("  history: 'History',").contains("  history: 'היסטוריה',");
        // The Menora set borrows the list and the record screen.
        Map<String, String> menora = generate(approvals("react-menora-digital-crud", true));
        assertThat(menora.get(FE + "shared/ui/MovesList.tsx")).isEqualTo(files.get(FE + "shared/ui/MovesList.tsx"));
    }

    @Test
    void aWorkflowThatDoesNotAskKeepsNoLog() throws Exception {
        Map<String, String> files = generate(approvals("react-tailwind-crud", false));
        assertThat(files.keySet()).noneMatch(path -> path.contains("/workflow/") || path.endsWith("MovesList.tsx"));
        assertThat(files.get(BE + "service/ExpenseClaimService.java")).doesNotContain("moves").contains("return repository.save(entity);");
        assertThat(files.get(BE + "controller/ExpenseClaimController.java")).doesNotContain("/moves");
        assertThat(files.get(BE + "config/DemoDataLoader.java")).doesNotContain("moves");
        assertThat(files.get(FE + "app/screens/ClaimScreen.tsx")).doesNotContain("MovesList");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).doesNotContain("historyMoved");
    }

    @Test
    void aHandWrittenDatasourceConfigScansTheLogsPackage() throws Exception {
        Map<String, Object> body = approvals("react-tailwind-crud", true);
        body.put("dependencies", List.of("web", "data-jpa", "postgresql", "validation", "ldap-auth-rest"));
        Map<String, String> files = generate(body);
        String config = files.entrySet().stream()
                .filter(e -> e.getKey().startsWith("support/backend/") && e.getKey().endsWith("Config.java") && e.getValue().contains("EnableJpaRepositories"))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
        assertThat(config).contains("com.menora.support.workflow");
    }
}
