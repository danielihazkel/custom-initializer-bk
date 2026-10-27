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
    void frontend_offersTheMovesWhereTheRowIs() throws Exception {
        Map<String, String> files = generate(workflowLayout(true));
        String feature = FE + "src/features/expense-claim-form/";
        assertThat(files.get(feature + "model/workflow.ts"))
                .contains("  { name: 'submit', label: 'Submit', from: ['DRAFT'], to: 'SUBMITTED', roles: [], requires: [] },")
                .contains("  { name: 'reject', label: 'Send back', from: ['SUBMITTED'], to: 'REJECTED', roles: ['ADMIN'], requires: ['rejectionReason'] },")
                .contains("const OPEN: readonly string[] = ['DRAFT', 'SUBMITTED', 'REJECTED']")
                .contains("api.post<ExpenseClaim>(`${rowPath(row)}/transitions/${name}`, values)")
                .contains("claim: (row: ExpenseClaim) => api.post<ExpenseClaim>(`${rowPath(row)}/claim`, {}),");
        assertThat(files.get(feature + "ui/ExpenseClaimTransitions.tsx"))
                .contains("const moves = ExpenseClaimWorkflow.available(row, me.roles)")
                .contains("const canClaim = me.id != null && assignee == null && ExpenseClaimWorkflow.isOpen(row)")
                // reject asks for a reason: its drawer shows only that field.
                .contains("<ExpenseClaimForm value={draft} onChange={setDraft} errors={errors} isNew={false} only={asking.requires} />");
        assertThat(files.get(feature + "index.ts")).contains("export { ExpenseClaimTransitions } from './ui/ExpenseClaimTransitions'");
        // The status is the workflow's: never in the form, and the form takes `only` for a move's fields.
        assertThat(files.get(feature + "ui/ExpenseClaimForm.tsx"))
                .doesNotContain("set('status'")
                .doesNotContain("ExpenseClaimStatusTypeOptions")
                .contains("only?: readonly string[]");
        String page = files.get(FE + "src/pages/expense-claim/ui/ExpenseClaimPage.tsx");
        assertThat(page)
                .contains("const step = ExpenseClaimWorkflow.between(String(row.status), value, me.roles)")
                .contains("      await ExpenseClaimWorkflow.run(row, step.transition.name)\n")
                .doesNotContain("{ ...row, status: value }")
                .contains("<ExpenseClaimTransitions row={selectedRow} onDone={next => { setFetchedRow(next); void reload() }} />")
                .contains("<ExpenseClaimTransitions row={detailRow} onDone={next => { setDetailRow(next); void reload() }} />");
        assertThat(files.get(FE + "src/app/screens/ClaimScreen.tsx")).contains("<ExpenseClaimTransitions row={record} onDone={setRecord} />");
        assertThat(files.get(FE + "src/app/screens/BoardScreen.tsx"))
                .contains("const step = ExpenseClaimWorkflow.between(from, to, me.roles)")
                .contains("onNavigate('claim', String(row.id))")
                .doesNotContain("pathOf");
        assertThat(files).containsKey(FE + "src/shared/api/me.ts");
        assertThat(files.get(FE + "src/shared/i18n/strings.ts")).contains("claim: 'Claim',").contains("moveNotAllowed:");
        // ldap-auth-rest is on, so the dev header was sent already; it reads the same either way.
        assertThat(files.get(FE + "src/shared/api/client.ts")).contains("headers['userinfo'] = import.meta.env.VITE_DEV_USERINFO ?? 'dev-user'");
        // The cost center has no workflow: its frontend keeps its bytes.
        Map<String, Object> plain = workflowLayout(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> claim = ((List<Map<String, Object>>) plain.get("entities")).get(1);
        claim.remove("workflow");
        ((List<Map<String, Object>>) plain.get("pages")).removeIf(p -> "inbox".equals(p.get("type")));
        Map<String, String> without = generate(plain);
        for (String path : List.of("src/pages/cost-center/ui/CostCenterPage.tsx", "src/features/cost-center-form/ui/CostCenterForm.tsx",
                "src/features/cost-center-form/index.ts", "src/app/screens/CentersScreen.tsx")) {
            assertThat(files.get(FE + path)).as(path).isNotNull().isEqualTo(without.get(FE + path));
        }
        assertThat(without.get(FE + "src/app/screens/BoardScreen.tsx")).contains("await api.put<ExpenseClaim>(`${PATH}/${pathOf(row)}`, moved)");
    }

    @Test
    void frontend_sendsTheDevUserForAWorkflowWithoutLdap() throws Exception {
        Map<String, Object> body = claims(true);
        for (Map<String, Object> t : transitions(body)) t.remove("roles");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        assertThat(generate(body).get(FE + "src/shared/api/client.ts"))
                .contains("headers['userinfo'] = import.meta.env.VITE_DEV_USERINFO ?? 'dev-user'");
    }

    @Test
    void inboxPage_listsMyUnassignedAndOpenTasks() throws Exception {
        Map<String, String> files = generate(workflowLayout(true));
        String inbox = files.get(FE + "src/app/screens/TasksScreen.tsx");
        assertThat(inbox)
                .contains("type Queue = 'mine' | 'unassigned'")
                .contains("  { value: 'mine', label: t('myTasks') },\n  { value: 'unassigned', label: t('unassignedTasks') },\n")
                .contains("inbox: queue, page: String(page), size: String(size), sort: SORT")
                // Rows open their record page; their moves sit in the last column.
                .contains("onClick={() => onNavigate('claim', String(r.id))}")
                .contains("<ExpenseClaimTransitions row={r} onDone={changed} compact />")
                .contains("{ ExpenseClaimStatusTypeLabels[r.status] }")
                .doesNotContain("DetailDrawer");
        assertThat(files.get(FE + "src/app/App.tsx"))
                .contains("<TasksScreen query={route.query} onQueryChange={setQuery} onNavigate={goView} />")
                .contains("Inbox");
        assertThat(files.get(FE + "src/shared/i18n/strings.ts")).contains("myTasks: 'My tasks',").contains("overdue: 'Overdue',");
        // Without a record page a row opens in the quick look, which offers the moves too.
        String drawer = generate(workflowLayout(false)).get(FE + "src/app/screens/TasksScreen.tsx");
        assertThat(drawer)
                .contains("type Queue = 'open' | 'mine'")
                .contains("onClick={() => setDetail(r)}")
                .contains("<ExpenseClaimTransitions row={detail} onDone={next => { setDetail(next); changed() }} />")
                .contains("{ r.costCenterLabel ?? (r.costCenterId == null ? '—' : '#' + String(r.costCenterId)) }");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inboxPage_highlightsOverdueAndSortsByDue() throws Exception {
        String inbox = generate(workflowLayout(true)).get(FE + "src/app/screens/TasksScreen.tsx");
        assertThat(inbox)
                .contains("const SORT = 'dueOn,asc'")
                .contains("const late = r.dueOn != null && String(r.dueOn).slice(0, 10) < today")
                .contains("{late && <span className=\"ms-2 text-xs\">{t('overdue')}</span>}");
        // No due date: the newest first, and nothing is late.
        Map<String, Object> body = workflowLayout(true);
        Map<String, Object> claim = ((List<Map<String, Object>>) body.get("entities")).get(1);
        ((Map<String, Object>) claim.get("workflow")).remove("dueField");
        String undated = generate(body).get(FE + "src/app/screens/TasksScreen.tsx");
        assertThat(undated).contains("const SORT = 'id,desc'").doesNotContain("const late");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inboxPage_rejectsAnEntityWithoutAnAssignee() {
        Map<String, Object> body = workflowLayout(true);
        ((List<Map<String, Object>>) body.get("pages")).add(new LinkedHashMap<>(Map.of("id", "centers-inbox", "type", "inbox", "entity", "CostCenter")));
        assertBadRequest(body, "Page 'centers-inbox' (inbox): CostCenter has no workflow with an assignee");
        Map<String, Object> queues = workflowLayout(true);
        ((List<Map<String, Object>>) queues.get("pages")).get(3).put("queues", List.of("mine", "later"));
        assertBadRequest(queues, "unknown queue 'later' (expected mine, unassigned or open)");
        Map<String, Object> twice = workflowLayout(true);
        ((List<Map<String, Object>>) twice.get("pages")).get(3).put("queues", List.of("mine", "MINE"));
        assertBadRequest(twice, "queue 'mine' is listed twice");
        Map<String, Object> list = workflowLayout(true);
        ((List<Map<String, Object>>) list.get("pages")).get(0).put("queues", List.of("mine"));
        assertBadRequest(list, "(entity-list) does not take 'queues'");
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

    /**
     * The claims with a page layout that shows the workflow everywhere it reaches: a list with a
     * kanban over the status, a board, and — with {@code recordPage} — a record page and a list
     * opening rows in a side pane; without one, both open rows in the quick-look drawer.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> workflowLayout(boolean recordPage) {
        Map<String, Object> body = claims(true);
        Map<String, Object> claim = ((List<Map<String, Object>>) body.get("entities")).get(1);
        claim.put("listViews", List.of("table", "kanban"));
        List<Map<String, Object>> pages = new ArrayList<>();
        pages.add(new LinkedHashMap<>(recordPage
                ? Map.of("id", "claims", "type", "entity-list", "entity", "ExpenseClaim", "detail", "side")
                : Map.of("id", "claims", "type", "entity-list", "entity", "ExpenseClaim")));
        pages.add(new LinkedHashMap<>(Map.of("id", "board", "type", "board", "entity", "ExpenseClaim")));
        pages.add(new LinkedHashMap<>(Map.of("id", "centers", "type", "entity-list", "entity", "CostCenter")));
        pages.add(new LinkedHashMap<>(recordPage
                ? Map.of("id", "tasks", "type", "inbox", "entity", "ExpenseClaim")
                : Map.of("id", "tasks", "type", "inbox", "entity", "ExpenseClaim", "queues", List.of("open", "mine"),
                        "cardFields", List.of("title", "amount", "costCenter"))));
        if (recordPage) pages.add(new LinkedHashMap<>(Map.of("id", "claim", "type", "record", "entity", "ExpenseClaim")));
        body.put("pages", pages);
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
