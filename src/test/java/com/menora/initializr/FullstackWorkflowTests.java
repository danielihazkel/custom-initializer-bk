package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entity workflows ("human tasks"): a status moved only by named transitions, limited to roles and
 * asking for fields, with claim/release and inbox queues over an assignee. Pinned on an expense
 * claim with a cost center; entities without a workflow keep their bytes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class FullstackWorkflowTests {

    private static final String BE = "claims/backend/src/main/java/com/menora/claims/";
    private static final String FE = "claims/frontend/";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void workflowEntity_movesOnlyByItsTransitions() throws Exception {
        Map<String, String> files = generate(claims(true));
        String service = files.get(BE + "service/ExpenseClaimService.java");
        assertThat(service)
                // A new row starts where the workflow begins, whatever the client sent.
                .contains("entity.setStatus(ExpenseClaim.ExpenseClaimStatusType.DRAFT); // the workflow decides where a new row starts")
                // An update leaves the status alone; transitions are the only way it changes.
                .doesNotContain("existing.setStatus(updated.getStatus());")
                .contains("public ExpenseClaim transition(Long id, String name, ExpenseClaim values, String user) {")
                .contains("            case \"submit\" -> {\n                requireState(existing, \"submit\", ExpenseClaim.ExpenseClaimStatusType.DRAFT);\n"
                        + "                existing.setStatus(ExpenseClaim.ExpenseClaimStatusType.SUBMITTED);\n            }\n")
                .contains("                requireRole(user, \"approve\", \"ADMIN\");\n")
                // reject asks for a reason, and says which field is missing.
                .contains("                if (blank(given.getRejectionReason())) missing.put(\"rejectionReason\", \"is required\");\n"
                        + "                if (!missing.isEmpty()) throw WorkflowException.missing(missing);\n"
                        + "                existing.setRejectionReason(given.getRejectionReason());\n")
                .contains("java.util.EnumSet.of(ExpenseClaim.ExpenseClaimStatusType.DRAFT, ExpenseClaim.ExpenseClaimStatusType.SUBMITTED, ExpenseClaim.ExpenseClaimStatusType.REJECTED)")
                .contains("public ExpenseClaim claim(Long id, String user) {")
                .contains("throw WorkflowException.wrongState(\"Already assigned to \" + holder);")
                .contains("public ExpenseClaim release(Long id, String user) {")
                .contains("case \"unassigned\" -> (root, query, cb) -> cb.and(")
                .contains("            String inbox, String me,\n")
                .contains("if (filters.inbox() != null && !filters.inbox().isBlank()) specs.add(inboxOf(filters.inbox(), filters.me()));")
                .contains("private final CurrentUser currentUser;");
        assertThat(files.get(BE + "controller/ExpenseClaimController.java"))
                .contains("@PostMapping(\"/{id}/transitions/{name}\")")
                .contains("service.transition(id, name, values == null ? null : values.toEntity(), CurrentUser.idOf(user))")
                .contains("@PostMapping(\"/{id}/claim\")")
                .contains("@PostMapping(\"/{id}/release\")")
                .contains("            @RequestParam(required = false) String inbox,\n"
                        + "            @RequestHeader(value = \"userinfo\", required = false) String workflowUser,\n            Pageable pageable) {")
                .contains("new ExpenseClaimService.Filters(inbox, CurrentUser.idOf(workflowUser), ");
        assertThat(files.get(BE + "web/ApiExceptionHandler.java"))
                .contains("@ExceptionHandler(WorkflowException.class)")
                .contains("if (!ex.errors().isEmpty()) pd.setProperty(\"errors\", ex.errors());");
        assertThat(files.get(BE + "web/WorkflowException.java")).contains("public static WorkflowException wrongState(String message) {");
        // The default backend set brings ldap-auth-rest: roles come from the user's LDAP groups.
        assertThat(files.get(BE + "web/CurrentUser.java"))
                .contains("List<String> groups = permissionService.getUserGroups(user);")
                .contains("public static String idOf(String header) {");
        assertThat(files.get(BE + "web/CurrentUserController.java")).contains("public Me me(@RequestHeader(value = \"userinfo\", required = false) String header) {");
        // The cost center has no workflow, so it keeps its bytes.
        Map<String, String> without = generate(claims(false));
        for (String path : List.of("service/CostCenterService.java", "controller/CostCenterController.java", "entity/CostCenter.java")) {
            assertThat(files.get(BE + path)).as(path).isNotNull().isEqualTo(without.get(BE + path));
        }
        assertThat(without).doesNotContainKeys(BE + "web/WorkflowException.java", BE + "web/CurrentUser.java", BE + "web/CurrentUserController.java");
    }

    @Test
    void workflowWithoutRoles_needsNoDirectory() throws Exception {
        Map<String, Object> body = claims(true);
        // No roles anywhere, and no ldap-auth: the service takes no CurrentUser, which knows no roles.
        for (Map<String, Object> t : transitions(body)) t.remove("roles");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        Map<String, String> files = generate(body);
        assertThat(files.get(BE + "service/ExpenseClaimService.java"))
                .doesNotContain("CurrentUser")
                .doesNotContain("requireRole");
        assertThat(files.get(BE + "web/CurrentUser.java"))
                .doesNotContain("PermissionService")
                .contains("public Set<String> roles(String user) {");
    }

    @Test
    void demoData_givesEveryInboxQueueRows() throws Exception {
        Map<String, Object> body = claims(true);
        body.put("opts", Map.of("scaffold", List.of("seedData")));
        assertThat(generate(body).get(BE + "config/DemoDataLoader.java"))
                .contains("row.setAssignee(i % 3 == 1 ? \"dev-user\" : i % 3 == 2 ? null : \"user\" + i);");
    }

    @Test
    void rejectsRolesWithoutADirectory() {
        Map<String, Object> body = claims(true);
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        assertBadRequest(body, "Entity 'ExpenseClaim' workflow: transition 'approve' has roles, which need the ldap-auth or ldap-auth-rest dependency");
        // A wizard cannot ask for the status: the workflow sets it.
        Map<String, Object> wizard = claims(true);
        wizard.put("pages", List.of(
                Map.of("id", "claims", "type", "entity-list", "entity", "ExpenseClaim"),
                Map.of("id", "new-claim", "type", "wizard", "entity", "ExpenseClaim",
                        "steps", List.of(Map.of("fields", List.of("title", "status"))))));
        assertBadRequest(wizard, "'status' is set by the workflow, so a step cannot ask for it");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Expense claims and the cost centers they are charged to; with or without the claim workflow. */
    static Map<String, Object> claims(boolean workflow) {
        Map<String, Object> costCenter = new LinkedHashMap<>(Map.of("name", "CostCenter", "fields", List.of(
                Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "name", "type", "STRING", "required", true))));
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("name", "ExpenseClaim");
        claim.put("fields", List.of(
                Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "title", "type", "STRING", "required", true),
                Map.of("name", "amount", "type", "BIG_DECIMAL"),
                Map.of("name", "status", "type", "ENUM", "enumValues", List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED")),
                Map.of("name", "assignee", "type", "STRING"),
                Map.of("name", "dueOn", "type", "LOCAL_DATE"),
                Map.of("name", "rejectionReason", "type", "TEXT")));
        claim.put("relations", List.of(Map.of("type", "MANY_TO_ONE", "fieldName", "costCenter", "targetEntity", "CostCenter")));
        if (workflow) {
            List<Map<String, Object>> transitions = new ArrayList<>();
            transitions.add(new LinkedHashMap<>(Map.of("name", "submit", "from", List.of("DRAFT"), "to", "SUBMITTED")));
            transitions.add(new LinkedHashMap<>(Map.of("name", "approve", "from", List.of("SUBMITTED"), "to", "APPROVED", "roles", List.of("ADMIN"))));
            transitions.add(new LinkedHashMap<>(Map.of("name", "reject", "label", "Send back", "from", List.of("SUBMITTED"), "to", "REJECTED",
                    "roles", List.of("ADMIN"), "requires", List.of("rejectionReason"))));
            transitions.add(new LinkedHashMap<>(Map.of("name", "reopen", "from", List.of("REJECTED"), "to", "DRAFT")));
            claim.put("workflow", new LinkedHashMap<>(Map.of("statusField", "status", "assigneeField", "assignee", "dueField", "dueOn",
                    "transitions", transitions)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "claims");
        body.put("packageName", "com.menora.claims");
        body.put("bootVersion", "3.2.1");
        body.put("entities", new ArrayList<>(List.of(costCenter, claim)));
        return body;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> transitions(Map<String, Object> body) {
        Map<String, Object> claim = ((List<Map<String, Object>>) body.get("entities")).get(1);
        return (List<Map<String, Object>>) ((Map<String, Object>) claim.get("workflow")).get("transitions");
    }

    private void assertBadRequest(Map<String, Object> body, String expected) {
        ResponseEntity<String> response = restTemplate.exchange("/starter-fullstack.zip", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(response.getStatusCode()).as(expected).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains(expected);
    }

    private Map<String, String> generate(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = restTemplate.exchange("/starter-fullstack.zip", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), byte[].class);
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Map<String, String> result = new TreeMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zin.transferTo(out);
                result.put(entry.getName(), out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
        return result;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
