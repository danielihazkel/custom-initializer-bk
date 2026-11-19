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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 14's fix sweep over generated code (2026-11): one test per defect, on the bundled
 * examples. What each does over a real API is {@code GeneratedBackendBuildSmokeTests}.
 */
@IntegrationTest
class FullstackFixSweepTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void aChangeOfWhoHoldsARowRunsItsRulesAndADemoRowThatBreaksOneIsLeftOut() throws Exception {
        Map<String, String> files = generate("approvals", body -> body.put("opts", Map.of("scaffold", List.of("audit", "seedData"))));
        String service = file(files, "service/ExpenseClaimService.java");
        // claim, release and assign: each a save like any other.
        assertThat(count(service, "        checkRules(existing);\n")).isEqualTo(3);
        assertThat(service).contains("private void checkRules(ExpenseClaim row) {")
                .contains("        withLinkedRows(row);\n        com.menora.support.rules.ExpenseClaimRules.check(row);");
        assertThat(file(files, "config/DemoDataLoader.java"))
                .contains("if (!com.menora.support.rules.ExpenseClaimRules.apply(row).isEmpty()) continue;");
    }

    @Test
    void aVersionPutBackFromTheHistoryIsCheckedAndSaysWhatItCouldNotPutBack() throws Exception {
        Map<String, String> files = generate("projects", body -> body.put("opts", Map.of("scaffold", List.of("audit", "history"))));
        assertThat(file(files, "history/ChangeHistory.java"))
                .contains("java.util.function.Consumer<Object> check) {")
                .contains("if (check != null) check.accept(row);")
                .contains("return gone;");
        assertThat(file(files, "controller/TaskController.java"))
                .contains("service::checkRestored);")
                .contains("public record Restored(TaskDto row, java.util.List<String> skipped) {}");
        assertThat(file(files, "service/TaskService.java"))
                .contains("public void checkRestored(Object restored) {")
                .contains("validator.validate(com.menora.support.dto.TaskDto.from(row))")
                .contains("com.menora.support.rules.TaskRules.check(row);");
        assertThat(file(files, "shared/ui/ChangeHistory.tsx")).contains("t('versionRestoredWithout', { fields: skipped.join(', ') })");
        assertThat(file(files, "shared/i18n/strings.ts")).contains("versionRestoredWithout:");
    }

    @Test
    void aFieldTheFormHidesIsEmptiedSoItsOldValueIsNotSent() throws Exception {
        Map<String, String> files = generate("projects", null);
        assertThat(file(files, "task-form/ui/TaskForm.tsx"))
                .contains("export function TaskForm({ value, onChange: report, errors, isNew")
                .contains("    if (row.estimate != null && !rule.applies(")
                .contains("    report(row)\n");
        // A form without such a field is what it was.
        assertThat(file(files, "project-form/ui/ProjectForm.tsx")).contains("export function ProjectForm({ value, onChange, errors, isNew");
    }

    @Test
    void aComputedFieldOverTheClockIsAnsweredAsItStandsNow() throws Exception {
        Map<String, String> files = generate("projects", body ->
                field(entity(body, "Project"), "durationDays").put("formula", "daysBetween(startsOn, today())"));
        assertThat(file(files, "rules/ProjectRules.java"))
                .contains("public static Integer durationDaysNow(Project row) {");
        assertThat(file(files, "dto/ProjectDto.java"))
                .contains("com.menora.support.rules.ProjectRules.durationDaysNow(entity),")
                .doesNotContain("entity.getDurationDays()");
        // A formula over the row alone is read as it was saved.
        Map<String, String> plain = generate("projects", null);
        assertThat(file(plain, "dto/ProjectDto.java")).contains("entity.getDurationDays(),");
        assertThat(file(plain, "rules/ProjectRules.java")).doesNotContain("Now(Project row)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aChartOverMoneyIsWrittenInItsCurrency() throws Exception {
        Map<String, String> files = generate("approvals", body -> {
            body.put("opts", Map.of("scaffold", List.of("audit"))); // its activity feed needs it
            Map<String, Object> dashboard = ((List<Map<String, Object>>) body.get("pages")).stream()
                    .filter(p -> "dashboard".equals(p.get("type"))).findFirst().orElseThrow();
            List<Object> widgets = new ArrayList<>((List<Object>) dashboard.get("widgets"));
            widgets.add(Map.of("kind", "bar", "entity", "ExpenseClaim", "groupBy", "status", "agg", "sum", "field", "amount"));
            widgets.add(Map.of("kind", "bar", "entity", "ExpenseClaim", "groupBy", "status"));
            dashboard.put("widgets", widgets);
        });
        String screen = files.entrySet().stream().filter(e -> e.getKey().contains("/app/screens/") && e.getValue().contains("<BreakdownCard"))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
        List<String> cards = new ArrayList<>();
        for (int at = screen.indexOf("<BreakdownCard"); at >= 0; at = screen.indexOf("<BreakdownCard", at + 1)) {
            cards.add(screen.substring(at, screen.indexOf("/>", at)));
        }
        assertThat(cards).filteredOn(card -> card.contains("valueField=\"amount\"")).isNotEmpty()
                .allSatisfy(card -> assertThat(card).contains("currency=\"ILS\""));
        // A count is a number of rows, not money.
        assertThat(cards).filteredOn(card -> !card.contains("agg=")).isNotEmpty()
                .allSatisfy(card -> assertThat(card).doesNotContain("currency="));
        assertThat(file(files, "shared/ui/widgets.tsx"))
                .contains("formatStat(d.value, currency)")
                .contains("<DonutChart currency={currency} data={data} onSelect={select} />")
                .contains("formatStat(stats.total, currency)");
    }

    @Test
    void theExportsHoldWhatTheListShows() throws Exception {
        Map<String, String> files = generate("projects", body ->
                body.put("opts", Map.of("scaffold", List.of("audit", "csvExport", "xlsxExport"))));
        String controller = file(files, "controller/TaskController.java");
        // The list, the CSV and the workbook all take "changed by".
        assertThat(count(controller, "@RequestParam(required = false) String changedBy,")).isEqualTo(3);
        assertThat(count(controller, "author, PageRequest.of(pageNo++")).isEqualTo(2);
        assertThat(controller).contains("if (sheet.full()) break;").contains("} while (chunk.hasNext() && !sheet.full());");
        assertThat(file(files, "web/XlsxWriter.java"))
                .contains("public boolean full() {")
                .contains("numFmtId=\\\"14\\\"")
                .contains("value instanceof java.time.LocalDate date")
                .contains("<cols>");
        // The workbook is asked for with the list's own filters: its scope, and "changed by me".
        assertThat(file(files, "pages/task/model/useTaskList.tsx"))
                .contains("for (const [key, value] of Object.entries(withMine(scopedFilters) ?? {})) {");
    }

    @Test
    void aValueADeletedRowHoldsIsSaidToBeSo() throws Exception {
        Map<String, String> files = generate("projects", body -> body.put("opts", Map.of("scaffold", List.of("softDelete"))));
        assertThat(file(files, "service/TaskService.java"))
                .contains("private void requireNoDeletedHolder(Task row) {")
                .contains("where deleted = true and project_id = :v0 and title = :v1")
                .contains(".setFlushMode(jakarta.persistence.FlushModeType.COMMIT)")
                .contains("new com.menora.support.web.DeletedHolderException(\"A deleted Task has the same Project and Title\",")
                .contains("        requireNoDeletedHolder(entity);\n")
                .contains("        requireNoDeletedHolder(existing);\n");
        assertThat(file(files, "web/DeletedHolderException.java")).contains("public Object deletedId()");
        assertThat(file(files, "web/ApiExceptionHandler.java"))
                .contains("public ProblemDetail handleDeletedHolder(DeletedHolderException ex) {")
                .contains("pd.setProperty(\"deleted\", Map.of(\"id\", ex.deletedId()));");
        assertThat(file(files, "pages/task/model/useTaskList.tsx")).contains("label: t('restoreIt'),").contains("restore(held)");
        assertThat(file(files, "shared/i18n/strings.ts")).contains("restoreIt: 'Restore it',");

        // Without soft delete nothing holds a value it does not show.
        Map<String, String> plain = generate("projects", null);
        assertThat(file(plain, "web/DeletedHolderException.java")).isNull();
        assertThat(file(plain, "service/TaskService.java")).doesNotContain("requireNoDeletedHolder");
        assertThat(file(plain, "shared/i18n/strings.ts")).doesNotContain("restoreIt");
    }

    @Test
    void anEntityNamedLikeASelectedDependencysSampleClassIsRefused() {
        Consumer<Map<String, Object>> users = body -> {
            body.put("entities", List.of(Map.of("name", "User", "fields", List.of(
                    Map.of("name", "id", "type", "Long", "primaryKey", true, "generated", true),
                    Map.of("name", "name", "type", "String")))));
            body.put("pages", List.of());
            body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        };
        // Without the sample, a User is an entity like any other.
        assertThat(post("projects", users).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> refused = post("projects", users.andThen(body ->
                body.put("opts", Map.of("data-jpa", List.of("sample-entity")))));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody())
                .contains("\"path\":\"entities[0].name\"")
                .contains("Entity 'User' would be generated as User")
                .contains("the 'data-jpa' dependency (its 'sample-entity' option)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNameThatIsAKeywordIsWrittenQuotedInTheCaseItsDatabaseFoldsTo() throws Exception {
        // H2, the default: bare names fold to upper case, so that is how a keyword is quoted.
        Map<String, String> h2 = unzip(FullstackExampleRequests.keywordNamesBody());
        String setting = file(h2, "entity/Setting.java");
        assertThat(setting)
                .contains("@Table(name = \"\\\"VALUES\\\"\"")
                .contains("@Column(name = \"\\\"KEY\\\"\"")
                .contains("@Column(name = \"\\\"ORDER\\\"\"")
                .contains("@Column(name = \"\\\"USER\\\"\"")
                .contains("@Column(name = \"\\\"VALUE\\\"\"")
                // Keywords elsewhere, names on H2: left as they were.
                .contains("@Column(name = \"comment\"").contains("@Column(name = \"level\"").contains("@Column(name = \"date\"")
                // The SQL the mapping carries names the table the same way.
                .contains("@SQLDelete(sql = \"UPDATE \\\"VALUES\\\" SET deleted = true WHERE id = ?");
        assertThat(file(h2, "entity/Batch.java")).contains("@Table(name = \"\\\"USER\\\"\"");
        // A quoted name reaches the database as written only through this strategy.
        assertThat(file(h2, "config/QuotedNamesNamingStrategy.java")).isNotNull();

        // A link's label is read in SQL written into the mapping, which cannot name a quoted table:
        // a link to Setting shows its id.
        assertThat(file(h2, "entity/Batch.java")).doesNotContain("settingLabel").doesNotContain("@Formula");

        // PostgreSQL folds to lower case, and reserves fewer of these. What it takes bare stays bare —
        // and the project's own tests, on H2, are told those words are no keywords there.
        Map<String, Object> onPostgres = FullstackExampleRequests.keywordNamesBody();
        ((List<String>) onPostgres.get("dependencies")).replaceAll(d -> d.equals("h2") ? "postgresql" : d);
        ((List<String>) onPostgres.get("dependencies")).add("flyway");
        Map<String, String> pg = unzip(onPostgres);
        assertThat(file(pg, "entity/Setting.java"))
                .contains("@Table(name = \"\\\"values\\\"\"")
                .contains("@Column(name = \"\\\"order\\\"\"")
                .contains("@Column(name = \"\\\"user\\\"\"")
                .contains("@Column(name = \"value\"")
                .contains("@Column(name = \"key\"");
        assertThat(file(pg, "db/migration/V1__init.sql"))
                .contains("create table \"values\" (")
                .contains("\"order\" integer")
                .contains("\"user\" varchar(255)")
                .contains("create table \"user\" (");
        assertThat(file(pg, "src/test/resources/application-test.yaml")).contains(";NON_KEYWORDS=KEY,VALUE");
        // On H2 itself nothing needs freeing: such a name is quoted.
        assertThat(file(h2, "src/test/resources/application-test.yaml")).doesNotContain("NON_KEYWORDS");

        // Nothing changes for a model without such names.
        assertThat(file(generate("projects", null), "config/QuotedNamesNamingStrategy.java")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aColumnEveryTableOfTheDatabaseAlreadyHasIsRefused() {
        Map<String, Object> body = FullstackExampleRequests.keywordNamesBody();
        ((List<String>) body.get("dependencies")).replaceAll(d -> d.equals("h2") ? "postgresql" : d);
        Map<String, Object> setting = ((List<Map<String, Object>>) body.get("entities")).get(0);
        List<Map<String, Object>> fields = new ArrayList<>((List<Map<String, Object>>) setting.get("fields"));
        fields.add(Map.of("name", "xmin", "type", "INTEGER"));
        setting.put("fields", fields);
        ResponseEntity<byte[]> refused = FullstackExampleRequests.post(rest, body);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new String(refused.getBody(), StandardCharsets.UTF_8))
                .contains("\"path\":\"entities[0].fields[8].name\"")
                .contains("would be the column xmin, which every table on PostgreSQL already has");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLinksLabelIsTheFirstTextFieldItsSqlCanName() throws Exception {
        // Setting in a table of an ordinary name: its first text field, key, is a keyword on H2, so
        // the label a Batch reads is the next one a formula can write — comment.
        Map<String, Object> body = FullstackExampleRequests.keywordNamesBody();
        ((List<Map<String, Object>>) body.get("entities")).get(0).put("tableName", "settings");
        Map<String, String> files = unzip(body);
        assertThat(file(files, "entity/Batch.java"))
                .contains("(select t.comment from settings t where t.id = setting_id)")
                .doesNotContain("KEY");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aChildWhoseTableHasToBeQuotedCannotBeCountedUnderItsParent() {
        // Batch's table is "USER"; with inverse collections a Setting would count its batches in SQL
        // that cannot name it.
        Map<String, Object> body = FullstackExampleRequests.keywordNamesBody();
        body.put("opts", Map.of("scaffold", List.of("inverseCollections")));
        ResponseEntity<byte[]> refused = FullstackExampleRequests.post(rest, body);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new String(refused.getBody(), StandardCharsets.UTF_8))
                .contains("\"path\":\"entities[1].tableName\"")
                .contains("give Batch a tableName that needs no quotes");

        // Named something a formula can write, it is counted like any other.
        ((List<Map<String, Object>>) body.get("entities")).get(1).put("tableName", "batches");
        assertThat(FullstackExampleRequests.post(rest, body).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Map<String, String> unzip(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, body);
        assertThat(response.getStatusCode()).as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Map<String, String> files = new TreeMap<>();
        FullstackExampleRequests.unzip(response.getBody()).forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
        return files;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<String> post(String example, Consumer<Map<String, Object>> change) {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody(example, "react-tailwind-crud");
        if (change != null) change.accept(body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/starter-fullstack.zip", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private Map<String, String> generate(String example, Consumer<Map<String, Object>> change) throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody(example, "react-tailwind-crud");
        if (change != null) change.accept(body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = rest.exchange("/starter-fullstack.zip", HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);
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

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) n++;
        return n;
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
    private static Map<String, Object> field(Map<String, Object> entity, String name) {
        for (Object f : (List<?>) entity.get("fields")) {
            Map<String, Object> field = (Map<String, Object>) f;
            if (name.equals(field.get("name"))) return field;
        }
        throw new IllegalArgumentException(name);
    }
}
