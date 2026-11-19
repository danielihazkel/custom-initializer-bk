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
 * The deeper field model, phase 1 (2026-11): a field's {@code help} (a hint under its input) and
 * an entity's {@code uniqueKeys} (no two rows share the values of several fields and links) — on
 * the Projects example, whose tasks are unique per project and title. Their behaviour over a real
 * API is {@code GeneratedBackendBuildSmokeTests} ({@code FIELD_MODEL_LIVE_TESTS}).
 */
@IntegrationTest
class FullstackFieldModelTests {

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<String> post(Consumer<Map<String, Object>> change) {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("projects", "react-tailwind-crud");
        if (change != null) change.accept(body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/starter-fullstack.zip", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private Map<String, String> generate(Consumer<Map<String, Object>> change) throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("projects", "react-tailwind-crud");
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

    private static Consumer<Map<String, Object>> taskKeys(List<?>... keys) {
        return body -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (List<?> key : keys) list.add(Map.of("fields", key));
            entity(body, "Task").put("uniqueKeys", list);
        };
    }

    @Test
    void aUniqueKeyIsAConstraintA409NamingItsFieldsAndAFormThatShowsIt() throws Exception {
        Map<String, String> files = generate(body -> {
            body.put("dependencies", List.of("flyway"));
            body.put("opts", Map.of("scaffold", List.of("seedData")));
        });
        assertThat(file(files, "entity/Task.java")).contains("@Table(name = \"tasks\", uniqueConstraints = "
                + "{@UniqueConstraint(name = \"uk_tasks_project_id_title\", columnNames = {\"project_id\", \"title\"})})");
        assertThat(file(files, "db/migration/V1__init.sql"))
                .contains("constraint uk_tasks_project_id_title unique (project_id, title)");
        // The clash names the key's fields as the client knows them, a link by its id.
        assertThat(file(files, "web/ApiExceptionHandler.java"))
                .contains("new UniqueKey(\"uk_tasks_project_id_title\", java.util.List.of(\"projectId\", \"title\"), "
                        + "\"Another Task has the same Project and Title\")")
                .contains("pd.setProperty(\"errors\", errors);");
        // …and the client hands a 409's field errors to the form.
        assertThat(file(files, "shared/api/client.ts")).contains("if ((res.status === 400 || res.status === 409) && errors) {");
        // Demo rows that would repeat a key are left out.
        assertThat(file(files, "config/DemoDataLoader.java"))
                .contains("java.util.Set<List<Object>> taskKey0 = new java.util.HashSet<>();")
                .contains("if (!taskKey0.add(java.util.Arrays.<Object>asList(row.getProject(), row.getTitle()))) continue;");
        // Without a key nothing changes: another entity's table has no constraint list.
        assertThat(file(files, "entity/Person.java")).contains("@Table(name = \"persons\")");
    }

    @Test
    void aFieldsHelpIsAHintUnderItsInputEscapedForJsx() throws Exception {
        Map<String, String> files = generate(body ->
                field(entity(body, "Task"), "notes").put("help", "Say \"why\" & <how>"));
        assertThat(file(files, "task-form/ui/TaskForm.tsx"))
                .contains("hint=\"Hours of work, roughly. Leave empty if nobody has looked yet.\"")
                // A quote becomes a character reference; an & that starts none stays as it is.
                .contains("<Field label=\"Notes\" hint=\"Say &quot;why&quot; & <how>\" error={errors?.notes}>");
    }

    @Test
    void whatAKeyCannotBeIsRefusedWhereItIs() {
        assertRefused(taskKeys(List.of("title")), "entities[2].uniqueKeys[0]", "a key needs two or more");
        assertRefused(taskKeys(List.of("title", "nope")), "entities[2].uniqueKeys[0]", "'nope', which is not one of its fields or many-to-one links");
        assertRefused(taskKeys(List.of("id", "title")), "entities[2].uniqueKeys[0]", "includes the primary key 'id'");
        assertRefused(taskKeys(List.of("notes", "title")), "entities[2].uniqueKeys[0]", "a long text");
        assertRefused(taskKeys(List.of("title", "Title")), "entities[2].uniqueKeys[0]", "names 'title' twice");
        assertRefused(taskKeys(List.of("project", "title"), List.of("title", "project")), "entities[2].uniqueKeys[1]",
                "over the same fields as another key");
        assertRefused(taskKeys(List.of("status", "priority")), "entities[2].uniqueKeys[0]",
                "over enums, yes/no fields and patterned texts only");
        assertRefused(body -> field(entity(body, "Task"), "estimate").put("help", "x".repeat(301)),
                "entities[2].fields[6]", "help of field 'estimate' on entity 'Task' is over 300 characters");
    }

    @Test
    void anEntityWithAnImportPageKeepsItsAccessLevels() throws Exception {
        // EntityDefinition.withOpt (an import page switches csvImport on) used to drop `access`.
        // The example has an import page on Task ('import-tasks').
        Map<String, String> files = generate(body ->
                entity(body, "Task").put("access", Map.of("read", "SIGNED_IN", "write", "USER")));
        String controller = file(files, "controller/TaskController.java");
        assertThat(controller).contains("@PostMapping(\"/import\")")
                .contains("@RequiresPermission(Constants.SIGNED_IN)")
                .contains("@RequiresPermission(Constants.USER)")
                .doesNotContain("@RequiresPermission(Constants.ADMIN)");
    }

    @Test
    void rulesAreCheckedByTheServiceAndTheFormAlike() throws Exception {
        Map<String, String> files = generate(body -> body.put("opts", Map.of("scaffold", List.of("tests", "seedData"))));
        assertThat(file(files, "rules/ProjectRules.java"))
                .contains("if (Expr.fails(Expr.ge(row.getEndsOn(), row.getStartsOn()))) {")
                .contains("errors.putIfAbsent(\"endsOn\", \"A project ends on or after the day it starts\");");
        assertThat(file(files, "rules/TaskRules.java"))
                .contains("// when status == 'DONE': not isEmpty(estimate)")
                .contains("Expr.applies(Expr.eq(Expr.name(row.getStatus()), \"DONE\")) && Expr.fails(Expr.not(Expr.isEmpty(Expr.num(row.getEstimate()))))");
        // Before every save: a create, an update, an import's rows.
        assertThat(file(files, "service/TaskService.java"))
                .contains("rules.TaskRules.check(entity);")
                .contains("rules.TaskRules.check(existing);")
                .contains("rules.TaskRules.apply(entity)");
        assertThat(file(files, "web/ApiExceptionHandler.java")).contains("public ProblemDetail handleRules(RuleViolationException ex) {");
        assertThat(file(files, "task-form/model/validate.ts"))
                .contains("import * as rule from '@shared/ui/rules'")
                .contains("if (!errors.dueOn && rule.fails(rule.ge(rule.date(value.dueOn), rule.date(value.startsOn)))) errors.dueOn = 'Due on or after the day it starts'");
        assertThat(file(files, "frontend/src/shared/ui/rules.ts")).isNotNull();
        assertThat(file(files, "rules/Expr.java")).isNotNull();

        // The rows the generator makes pass the rules: the dates the rules read are counted from today.
        String support = file(files, "ApiTestSupport.java");
        assertThat(support).contains("row.put(\"startsOn\", java.time.LocalDate.now().plusDays(0).toString());")
                .contains("row.put(\"endsOn\", java.time.LocalDate.now().plusDays(0).toString());");
        // Demo rows keep their spread of dates, shifted as the test rows' are.
        assertThat(file(files, "config/DemoDataLoader.java")).contains("row.setEndsOn(java.time.LocalDate.now().minusDays(i).plusDays(0));");
        assertThat(file(files, "task-form/model/validate.test.ts")).contains("const day = (k: number): string =>")
                .contains("dueOn: day(0)");
    }

    @Test
    void aRuleNoTestRowCanPassLeavesItsEntitysTestsToYou() throws Exception {
        Map<String, String> files = generate(body -> {
            body.put("opts", Map.of("scaffold", List.of("tests")));
            // The title is the row's own (it is in a unique key), and never this long.
            entity(body, "Task").put("rules", List.of(Map.of("check", "length(title) > 500", "message", "Too short")));
        });
        assertThat(file(files, "ApiTestSupport.java"))
                .contains("No Task can be made here: no row the tests can make passes its rule 1 ('length(title) > 500')");
    }

    @Test
    void whatARuleCannotBeIsRefusedWhereItIs() {
        Consumer<Map<String, Object>> badCheck = body -> entity(body, "Project").put("rules",
                List.of(Map.of("check", "endsOn >= 3", "message", "x")));
        assertRefused(badCheck, "entities[1].rules[0]", "Cannot compare a date with a number (at character 8)");
        assertRefused(body -> entity(body, "Project").put("rules", List.of(Map.of("check", "endsOn >= startsOn"))),
                "entities[1].rules[0]", "has no message");
        assertRefused(body -> entity(body, "Project").put("rules", List.of(Map.of("check", "today() > date('2020-01-01')", "message", "x"))),
                "entities[1].rules[0]", "reads no field");
        assertRefused(body -> entity(body, "Project").put("rules",
                        List.of(Map.of("when", "status", "check", "endsOn >= startsOn", "message", "x"))),
                "entities[1].rules[0]", "(its when): This must be a yes/no condition, but it is an enum value");
        assertRefused(body -> entity(body, "Project").put("rules",
                        List.of(Map.of("check", "endsOn >= startsOn", "message", "x", "field", "nope"))),
                "entities[1].rules[0]", "shows its message on 'nope'");
        assertRefused(body -> {
            entity(body, "Person").put("readOnly", true);
            entity(body, "Person").put("rules", List.of(Map.of("check", "length(fullName) > 1", "message", "x")));
        }, "entities[0]", "read-only: nothing saves a row");
        // …nor computed fields (Project's days).
        assertRefused(body -> entity(body, "Project").put("readOnly", true),
                "entities[1].fields[5]", "a read-only entity is never saved, so it has no computed fields");
    }

    @Test
    void aFieldShownOnAConditionIsClearedWhileHiddenAndAComputedOneIsComputed() throws Exception {
        // The estimate is required while it is shown: a rule of its own, in the form's language.
        Map<String, String> files = generate(body -> {
            body.put("opts", Map.of("scaffold", List.of("tests", "seedData")));
            field(entity(body, "Task"), "estimate").put("required", true);
        });
        assertThat(file(files, "rules/TaskRules.java"))
                .contains("if (!Expr.applies(Expr.ne(Expr.name(row.getStatus()), \"TODO\"))) row.setEstimate(null);")
                .contains("// when status != 'TODO': not isEmpty(estimate)")
                .contains("errors.putIfAbsent(\"estimate\", \"must not be empty\");");
        assertThat(file(files, "rules/ProjectRules.java")).contains(
                "row.setDurationDays(Expr.toInteger(Expr.add(Expr.daysBetween(row.getStartsOn(), row.getEndsOn()), new java.math.BigDecimal(\"1\"))));");
        // Required only while shown: no NOT NULL, no @NotNull, no "asks for" case of its own.
        assertThat(file(files, "entity/Task.java")).doesNotContain("name = \"estimate\", nullable = false");
        assertThat(file(files, "task-form/model/validate.ts"))
                .contains("value = { ...value, estimate: rule.applies(rule.ne(rule.text(value.status), 'TODO')) ? value.estimate : null }")
                .contains("errors.estimate = t('required')")
                .doesNotContain("if (blank(value.estimate))");
        assertThat(file(files, "task-form/ui/TaskForm.tsx"))
                .contains("{rule.applies(rule.ne(rule.text(value.status), 'TODO')) && (<Field label=\"Estimate\"");
        // The computed field: shown, read-only, as it will come out; never in a bulk change or a PUT.
        assertThat(file(files, "project-form/ui/ProjectForm.tsx"))
                .contains("value={rule.show(rule.add(rule.daysBetweenDates(rule.date(value.startsOn), rule.date(value.endsOn)), 1))} readOnly");
        assertThat(file(files, "project-form/model/validate.ts"))
                .contains("value = { ...value, durationDays: rule.add(rule.daysBetweenDates(rule.date(value.startsOn), rule.date(value.endsOn)), 1) }");
        assertThat(file(files, "service/ProjectService.java")).doesNotContain("existing.setDurationDays(updated.getDurationDays());");
        // Demo rows are derived too.
        assertThat(file(files, "config/DemoDataLoader.java")).contains("rules.ProjectRules.derive(row);");
    }

    @Test
    void whatAShowWhenOrAFormulaCannotBeIsRefusedOnItsField() {
        assertRefused(body -> field(entity(body, "Project"), "durationDays").put("formula", "endsOn"),
                "entities[1].fields[5]", "(its formula): This must be a number, but it is a date");
        assertRefused(body -> field(entity(body, "Project"), "durationDays").put("required", true),
                "entities[1].fields[5]", "never required");
        assertRefused(body -> field(entity(body, "Project"), "status").put("formula", "'ACTIVE'"),
                "entities[1].fields[2]", "cannot be computed");
        assertRefused(body -> field(entity(body, "Project"), "endsOn").put("formula", "startsOn + durationDays"),
                "entities[1].fields[4]", "reads 'durationDays', which is computed too");
        assertRefused(body -> field(entity(body, "Task"), "estimate").put("showWhen", "estimate > 1"),
                "entities[2].fields[6]", "reads the field itself");
        assertRefused(body -> field(entity(body, "Task"), "estimate").put("showWhen", "status"),
                "entities[2].fields[6]", "(its show-when): This must be a yes/no condition");
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Map<String, Object>> taskDueByItsProject(List<String> scaffold) {
        return body -> {
            body.put("opts", Map.of("scaffold", scaffold));
            Map<String, Object> task = entity(body, "Task");
            List<Object> rules = new java.util.ArrayList<>((List<Object>) task.getOrDefault("rules", List.of()));
            rules.add(Map.of("check", "dueOn <= project.endsOn", "message", "Due by the end of its project", "field", "dueOn"));
            task.put("rules", rules);
        };
    }

    @Test
    void aRuleReadsTheRowALinkPointsAt() throws Exception {
        Map<String, String> files = generate(taskDueByItsProject(List.of("tests", "seedData")));
        assertThat(file(files, "rules/TaskRules.java"))
                .contains("Expr.fails(Expr.le(row.getDueOn(), (row.getProject() == null ? null : row.getProject().getEndsOn())))");
        // The service reads the linked row, not the id the request carried, before the rules run.
        String service = file(files, "service/TaskService.java");
        assertThat(service)
                .contains("    private void withLinkedRows(Task row) {\n"
                        + "        if (row.getProject() != null && row.getProject().getId() != null) {\n"
                        + "            com.menora.support.entity.Project linked = entityManager.find(com.menora.support.entity.Project.class, row.getProject().getId());")
                .contains("        withLinkedRows(entity);\n        com.menora.support.rules.TaskRules.check(entity);")
                .contains("        withLinkedRows(existing);\n        com.menora.support.rules.TaskRules.check(existing);")
                .contains("            withLinkedRows(entity);\n            com.menora.support.rules.TaskRules.apply(entity)");
        // The form fetches the project as it is picked; its checks read it from there.
        assertThat(file(files, "task-form/model/validate.ts"))
                .contains("import * as linked from '@shared/api/linked'")
                .contains("rule.le(rule.date(value.dueOn), rule.date(linked.value('/api/projects', value.projectId, 'endsOn')))");
        assertThat(file(files, "task-form/ui/TaskForm.tsx"))
                .contains("import * as linked from '@shared/api/linked'")
                .contains("  linked.useLinkedRows([['/api/projects', value.projectId]])");
        assertThat(file(files, "frontend/src/shared/api/linked.ts")).contains("export function useLinkedRows(");
        // The test rows' project is made with an end the rules accept.
        assertThat(file(files, "ApiTestSupport.java"))
                .contains("row.set(\"projectId\", create(\"Project\", parent -> { parent.put(\"endsOn\", java.time.LocalDate.now().plusDays(")
                .contains("protected JsonNode create(String entity, java.util.function.Consumer<ObjectNode> values) throws Exception {");
        // Another entity's form, and the project without such a rule, are left as they were.
        assertThat(file(files, "project-form/ui/ProjectForm.tsx")).doesNotContain("linked");
        Map<String, String> plain = generate(body -> body.put("opts", Map.of("scaffold", List.of("tests"))));
        assertThat(plain.keySet()).noneMatch(p -> p.endsWith("shared/api/linked.ts"));
        assertThat(file(plain, "ApiTestSupport.java")).doesNotContain("Consumer<ObjectNode>");
    }

    @Test
    void whatALinkedReadCannotBeIsRefusedWhereItIs() {
        assertRefused(body -> entity(body, "Task").put("rules", List.of(
                        Map.of("check", "dueOn <= project.EndsOn", "message", "x"))),
                "entities[2].rules[0]", "'EndsOn' is not a field of Project (did you mean 'endsOn'?)");
        assertRefused(body -> entity(body, "Task").put("rules", List.of(
                        Map.of("check", "project.owner.name == 'x'", "message", "x", "field", "title"))),
                "entities[2].rules[0]", "A link is followed one step only");
        assertRefused(body -> entity(body, "Task").put("rules", List.of(
                        Map.of("check", "status.name == 'x'", "message", "x", "field", "title"))),
                "entities[2].rules[0]", "'status' is not a link of this entity");
    }

    private void assertRefused(Consumer<Map<String, Object>> change, String path, String message) {
        ResponseEntity<String> response = post(change);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains(path).contains(message);
    }
}
