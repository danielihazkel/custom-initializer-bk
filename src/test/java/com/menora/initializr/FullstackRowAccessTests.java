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
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row-level access (Round 14, phase 3): {@code access.owners} / {@code access.rows} on an entity
 * keep a user to their own rows. Generated text here; the behaviour over a real API is
 * {@code GeneratedBackendBuildSmokeTests.rowsBelongToTheirUsersOverTheRealApi}.
 */
@IntegrationTest
class FullstackRowAccessTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void aUserSeesAndChangesOnlyTheirOwnRows() throws Exception {
        Map<String, String> files = generate(FullstackExampleRequests.rowAccessBody("OWN"));
        String service = file(files, "service/VisitService.java");
        assertThat(service)
                .contains("private final com.menora.visits.web.RowAccess rowAccess;")
                // Whose a row is, on a loaded row and in a query.
                .contains("return me != null && (me.equals(row.getCreatedBy()));")
                .contains("me == null ? cb.disjunction() : cb.equal(root.get(\"createdBy\"), me);")
                // The list, "changed by me" and the statistics are over the caller's rows…
                .contains("Specification<Visit> spec = limited(specOf(q, filters));")
                .contains("org.springframework.data.jpa.domain.Specification<Visit> spec = limited(specOf(q, filters));")
                // …and somebody else's row is not there, for a read or for any change that starts with one.
                .contains("repository.findById(id).filter(this::isMine).orElseThrow(() ->")
                .contains("rows = rows.stream().filter(this::isMine).toList(); // somebody else's row is not there")
                // A deleted row is brought back by whose it is.
                .contains("requireOwnedDeleted(id);")
                .contains("select count(*) from visits t where t.id = :id and (t.created_by = :me)")
                // The creator owns: nothing to fill in on create, nothing to refuse.
                .doesNotContain("own(entity)").doesNotContain("requireMine(");
        // The audit columns the owner is read from came with it.
        assertThat(file(files, "entity/Visit.java")).contains("private String createdBy;");

        String controller = file(files, "controller/VisitController.java");
        assertThat(controller)
                // An export reads its rows on another thread: the caller travels with it.
                .contains("com.menora.visits.web.RowAccess.Caller caller = service.caller();")
                .contains("chunk = com.menora.visits.web.RowAccess.as(caller, () -> service.findAll(q, filters, author, part))")
                .contains("if (service.caller().limited()) service.findById(id); // somebody else's row is not there");
        assertThat(count(controller, "RowAccess.as(caller, () ->")).isEqualTo(2);

        assertThat(file(files, "web/RowAccess.java")).contains("public record Caller(String id, boolean limited) {}")
                .contains("!currentUser.roles(id).contains(\"ADMIN\")");
        assertThat(file(files, "web/RowAccessException.java")).isNotNull();
        assertThat(file(files, "web/ApiExceptionHandler.java")).contains("@ExceptionHandler(RowAccessException.class)")
                .contains("return problem(HttpStatus.FORBIDDEN, ex.getMessage());");
        assertThat(file(files, "web/CurrentUser.java")).isNotNull();
    }

    @Test
    void anOwnerFieldIsFilledWithWhoeverCreatesTheRow() throws Exception {
        Map<String, String> files = generate(FullstackExampleRequests.rowAccessBody("OWN"));
        assertThat(file(files, "service/ClientService.java"))
                .contains("return me != null && (me.equals(row.getAccountManager()));")
                .contains("        own(entity);\n")
                .contains("        if (me != null && (row.getAccountManager() == null || row.getAccountManager().isBlank())) {\n"
                        + "            row.setAccountManager(me);\n")
                .contains("A Client you create is yours: leave accountManager empty, or put your own user id there")
                // An imported row is owned the same way, its refusal that row's error.
                .contains("                own(entity); // a row a user imports is theirs, like one they create\n")
                .contains("select count(*) from clients t where t.id = :id and (t.account_manager = :me)");
        // A third of the demo rows are the dev user's.
        assertThat(file(files, "config/DemoDataLoader.java")).contains("row.setAccountManager(i % 3 == 1 ? \"dev-user\" : \"user\" + i);");
    }

    @Test
    void aUserSeesEveryRowAndChangesOnlyTheirOwn() throws Exception {
        Map<String, String> files = generate(FullstackExampleRequests.rowAccessBody("OWN_WRITES"));
        String service = file(files, "service/VisitService.java");
        assertThat(service)
                .contains("if (!isMine(row)) throw new com.menora.visits.web.RowAccessException(\"This Visit is not yours to change\");")
                .contains("        Visit existing = requireMine(findById(id));\n")
                .contains("        rows.forEach(this::requireMine);\n")
                // Reading is not limited.
                .doesNotContain("spec = limited(").doesNotContain(".filter(this::isMine)")
                .contains("Specification<Visit> spec = specOf(q, filters);");
        // update, delete; the two bulk calls.
        assertThat(count(service, "requireMine(findById(id))")).isEqualTo(2);
        assertThat(count(service, "rows.forEach(this::requireMine);")).isEqualTo(2);
        assertThat(file(files, "controller/VisitController.java")).doesNotContain("RowAccess");
    }

    @Test
    void theRowsOfAComposedListFollowTheirParent() throws Exception {
        Map<String, String> files = generate(FullstackExampleRequests.rowAccessBody("OWN"));
        assertThat(file(files, "service/VisitItemService.java"))
                .contains("A VisitItem belongs to the Visit it belongs to: whoever created it.")
                .contains("return me != null && (row.getVisit() != null && (me.equals(row.getVisit().getCreatedBy())));")
                .contains("cb.equal(root.get(\"visit\").get(\"createdBy\"), me)")
                .contains("        requireParentMine(entity.getVisit());\n")
                .contains("        requireParentMine(updated.getVisit());\n")
                .contains("throw new IllegalArgumentException(\"visitId: no Visit \" + stub.getId());");
        // Under OWN_WRITES the rows are read by all and changed by the visit's owner.
        Map<String, String> writes = generate(FullstackExampleRequests.rowAccessBody("OWN_WRITES"));
        assertThat(file(writes, "service/VisitItemService.java"))
                .contains("throw new com.menora.visits.web.RowAccessException(\"This Visit is not yours to change\");")
                .contains("VisitItem existing = requireMine(findById(id));");
    }

    @Test
    void aLinkToARowTheCallerMayNotSeeIsALinkToNoRow() throws Exception {
        Map<String, String> files = generate(FullstackExampleRequests.rowAccessBody("OWN_WRITES"));
        assertThat(file(files, "service/VisitService.java"))
                .contains("        requireVisibleLinks(entity, null);\n")
                .contains("        requireVisibleLinks(updated, existing);\n")
                .contains("\"select count(t) from Client t where t.id = :id and (t.accountManager = :me)\", Long.class)")
                .contains("throw new IllegalArgumentException(\"clientId: no Client \" + id);");
    }

    @Test
    void filesAndLiveChangesFollowTheRows() throws Exception {
        Map<String, Object> body = FullstackExampleRequests.rowAccessBody("OWN");
        List<Map<String, Object>> fields = new ArrayList<>(fieldsOf(body, "Client"));
        fields.add(Map.of("name", "contract", "type", "FILE"));
        fields.add(Map.of("name", "photos", "type", "IMAGE", "maxFiles", 3));
        entity(body, "Client").put("fields", fields);
        body.put("opts", Map.of("scaffold", List.of("softDelete", "events", "tests")));
        Map<String, String> files = generate(body);
        assertThat(file(files, "attachment/Attachments.java"))
                .contains("String countSql, String mineSql) {")
                .contains("\"select count(*) from clients t where t.contract = :id and (t.account_manager = :me)\")")
                .contains("f where f.attachment_id = :id and exists (select 1 from clients t where t.id = f.owner_id and (t.account_manager = :me))\")")
                .contains("if (holds(user, owner.readLevel()) && seesRow(owner, id, user)) return;")
                .contains("if (owner.mineSql() == null || holds(user, \"ADMIN\")) return true;");
        assertThat(file(files, "events/EventRecorder.java"))
                .contains("new Tracked(\"client\", true, null, \"USER\", true)")
                .contains("new Tracked(\"visit-item\", true, null, \"USER\", true)");
        assertThat(file(files, "events/LiveEvents.java"))
                .contains("if (tracked.ownRows() && entities.contains(tracked.name())) unnamed.add(tracked.name());")
                .contains("listener.unnamed().contains(entity) ? anonymous : change");
    }

    @Test
    void theGeneratedTestsTryARowAsItsOwnerAnotherUserAndAnAdmin() throws Exception {
        Map<String, String> files = generate(FullstackExampleRequests.rowAccessBody("OWN"));
        assertThat(file(files, "application-test.yaml")).contains("      api-test-other: USER\n");
        assertThat(file(files, "ApiTestSupport.java")).contains("protected static final String OTHER = \"api-test-other\";");
        assertThat(file(files, "controller/ClientApiTest.java"))
                .contains("void aUsersRowIsTheirOwn() throws Exception {")
                .contains("        row.remove(\"accountManager\"); // left empty, it is whoever makes the row\n")
                .contains("        send(get(path), OTHER, null, 404);\n")
                .contains("        send(put(path), ADMIN, mine, 200);\n");
        // A visit may link to a client only its user sees, and an item is its visit's: left to the smoke build.
        assertThat(file(files, "controller/VisitItemApiTest.java")).doesNotContain("aUsersRowIsTheirOwn");
        Map<String, String> writes = generate(FullstackExampleRequests.rowAccessBody("OWN_WRITES"));
        assertThat(file(writes, "controller/VisitApiTest.java")).doesNotContain("aUsersRowIsTheirOwn");
    }

    @Test
    void theBaselineIndexesWhoseARowIs() throws Exception {
        Map<String, Object> body = FullstackExampleRequests.rowAccessBody("OWN");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "flyway", "ldap-auth-rest"));
        Map<String, String> files = generate(body);
        assertThat(file(files, "db/migration/V1__init.sql"))
                .contains("create index ix_clients_account_manager on clients (account_manager);")
                .contains("create index ix_visits_created_by on visits (created_by);");
    }

    @Test
    void rowAccessNeedsRolesToTellAnAdminFromAUser() {
        Map<String, Object> body = FullstackExampleRequests.rowAccessBody("OWN");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        ResponseEntity<byte[]> answer = FullstackExampleRequests.post(rest, body);
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text(answer)).contains("ldap-auth").contains("entities[0].access");
    }

    @Test
    void whatCannotOwnARowIsRefusedByName() {
        refused(visit -> access(visit).put("owners", List.of("createdBy", "nobody")),
                "owner 'nobody' is not a field of 'Visit'", "entities[1].access.owners[1]");
        refused(visit -> access(visit).put("owners", List.of("visitedOn")),
                "owner 'visitedOn' is LOCAL_DATE: a user id is kept in a STRING field", "entities[1].access.owners[0]");
        refused(visit -> access(visit).put("owners", List.of("createdBy", "CREATEDBY")),
                "owner 'createdBy' is named twice", "entities[1].access.owners[1]");
        refused(visit -> access(visit).put("owners", List.of("a", "b", "c", "d", "e")), "at most 4 owners", "entities[1].access.owners");
        refused(visit -> access(visit).put("rows", "MINE"), "rows 'MINE' is not one of OWN, OWN_WRITES", "entities[1].access.rows");
        refused(visit -> {
            access(visit).remove("owners");
            access(visit).put("rows", "OWN");
        }, "rows OWN needs owners", "entities[1].access.rows");
        refused(visit -> {
            access(visit).put("read", "ADMIN");
            access(visit).put("write", "ADMIN");
        }, "admins are never limited", "entities[1].access.owners");
        refused(visit -> {
            access(visit).put("rows", "OWN_WRITES");
            access(visit).put("write", "ADMIN");
        }, "only admins change 'Visit'", "entities[1].access.rows");
        refused(visit -> visit.put("opts", Map.of("audit", false)),
                "createdBy as an owner needs the audit columns", "entities[1].access.owners");
        refused(visit -> {
            visit.put("readOnly", true);
            visit.put("relations", List.of());
        }, "is read-only", "entities[1].access.owners");
        // A composed child follows its parent.
        Map<String, Object> body = FullstackExampleRequests.rowAccessBody("OWN");
        entity(body, "VisitItem").put("access", Map.of("read", "USER", "write", "USER", "owners", List.of("note")));
        ResponseEntity<byte[]> answer = FullstackExampleRequests.post(rest, body);
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text(answer)).contains("its rows are part of 'Visit' (a composition) and follow that entity's owners")
                .contains("entities[2].access.owners");
    }

    @Test
    void aWorkflowsAssigneeAloneCannotOwnARowNobodyCouldCreate() {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("approvals", "react-tailwind-crud");
        Map<String, Object> claim = new LinkedHashMap<>(entity(body, "ExpenseClaim"));
        claim.put("access", Map.of("read", "USER", "write", "USER", "owners", List.of("assignee")));
        replace(body, claim);
        ResponseEntity<byte[]> answer = FullstackExampleRequests.post(rest, body);
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text(answer)).contains("the only owner is the workflow's assignee ('assignee')");
        // With the creator beside it the model generates, and a new row is its creator's.
        claim.put("access", Map.of("read", "USER", "write", "USER", "owners", List.of("createdBy", "assignee")));
        assertThat(FullstackExampleRequests.post(rest, body).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theApprovalsExampleKeepsAUserToTheirOwnClaims() throws Exception {
        Map<String, String> files = generate(FullstackPagesIntegrationTests.exampleBody("approvals", "react-tailwind-crud"));
        assertThat(file(files, "service/ExpenseClaimService.java"))
                .contains("A ExpenseClaim belongs to whoever created it or its assignee.")
                .contains("return me != null && (me.equals(row.getCreatedBy()) || me.equals(row.getAssignee()));")
                .contains("cb.or(cb.equal(root.get(\"createdBy\"), me), cb.equal(root.get(\"assignee\"), me))")
                // The inbox's queues are over the caller's rows like every other list.
                .contains("Specification<ExpenseClaim> spec = limited(specOf(q, filters));");
        assertThat(file(files, "controller/ExpenseClaimController.java"))
                // How long claims stay in a state is over everybody's: an admin's number.
                .contains("    @RequiresPermission(Constants.ADMIN)\n    @GetMapping(\"/moves/time-in-state\")");
        assertThat(file(files, "screens/HomeScreen.tsx")).contains("const isAdmin = useCallerMay('ADMIN')")
                .contains("        {isAdmin && (\n        <TimeInStateCard");
        // Users write their own claims now, so the list asks for the USER role.
        assertThat(file(files, "model/useExpenseClaimList.tsx")).contains("const mayWrite = useCallerMay('USER')");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private void refused(Consumer<Map<String, Object>> change, String message, String path) {
        Map<String, Object> body = FullstackExampleRequests.rowAccessBody("OWN");
        change.accept(entity(body, "Visit"));
        ResponseEntity<byte[]> answer = FullstackExampleRequests.post(rest, body);
        assertThat(answer.getStatusCode()).as(message).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text(answer)).contains(message).contains("\"" + path + "\"");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> access(Map<String, Object> entity) {
        return (Map<String, Object>) entity.get("access");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fieldsOf(Map<String, Object> body, String name) {
        return (List<Map<String, Object>>) entity(body, name).get("fields");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entity(Map<String, Object> body, String name) {
        for (Object e : (List<?>) body.get("entities")) {
            Map<String, Object> entity = (Map<String, Object>) e;
            if (name.equals(entity.get("name"))) return entity;
        }
        throw new IllegalArgumentException(name);
    }

    @SuppressWarnings("unchecked")
    private static void replace(Map<String, Object> body, Map<String, Object> entity) {
        List<Map<String, Object>> entities = (List<Map<String, Object>>) body.get("entities");
        for (int i = 0; i < entities.size(); i++) {
            if (entity.get("name").equals(entities.get(i).get("name"))) entities.set(i, entity);
        }
    }

    private Map<String, String> generate(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, body);
        assertThat(response.getStatusCode()).as(text(response)).isEqualTo(HttpStatus.OK);
        Map<String, String> files = new TreeMap<>();
        FullstackExampleRequests.unzip(response.getBody())
                .forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
        return files;
    }

    private static String text(ResponseEntity<byte[]> response) {
        return response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8);
    }

    private static String file(Map<String, String> files, String suffix) {
        return files.entrySet().stream().filter(e -> e.getKey().endsWith(suffix)).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) n++;
        return n;
    }
}
