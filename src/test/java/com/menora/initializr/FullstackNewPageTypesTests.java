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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calendar, board, content, import and search page types, pinned on the Project planning
 * example ({@code projects}), and the {@code csvImport} scaffold opt they build on. The real
 * type-check/lint runs in {@code GeneratedFrontendBuildSmokeTests.fullstackFrontend*PlanningLayout*}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class FullstackNewPageTypesTests {

    private static final String FE = "support/frontend/";
    private static final String BE = "support/backend/src/main/java/com/menora/support/";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void calendarPage_fetchesItsPeriodAndOpensOrCreatesRows() throws Exception {
        Map<String, String> files = generate(planning("react-tailwind-crud"));
        assertThat(files.get(FE + "src/app/screens/ScheduleScreen.tsx"))
                .contains("const MODES: ScheduleMode[] = ['month', 'week', 'agenda', 'timeline']")
                // With an end date, a row shows while it overlaps the period.
                .contains("    startsOnTo: to,\n    dueOnFrom: from,\n")
                .contains("use Task({ page: 0, size: LIMIT, sort: SORT, q: '', filters })".replace("use Task", "useTask"))
                // Task has a record page: an event opens it. No wizard: a day creates in the drawer.
                .contains("onOpen={r => onNavigate('task', String(r.id))}")
                .contains("onCreate={createOn}")
                .contains("setEditing({ startsOn: day } as Partial<Task>)")
                .doesNotContain("DetailDrawer");
        assertThat(files).containsKeys(FE + "src/shared/ui/ScheduleView.tsx", FE + "src/shared/ui/schedule.ts");
        // Routed, its day and view ride in the URL.
        assertThat(files.get(FE + "src/app/App.tsx"))
                .contains("<ScheduleScreen query={route.query} onQueryChange={setQuery} onNavigate={goView} />");
    }

    @Test
    void boardPage_loadsEachLaneAndMovesCards() throws Exception {
        Map<String, String> files = generate(planning("react-tailwind-crud"));
        assertThat(files.get(FE + "src/app/screens/BoardScreen.tsx"))
                .contains("  { value: 'DOING', label: 'Doing', limit: 5 },")
                .contains("  { value: 'REVIEW', label: 'Review' },")
                .contains("const SORT: { field: string; direction: 'asc' | 'desc' } | null = { field: 'dueOn', direction: 'asc' }")
                .contains("new URLSearchParams({ status: value, page: String(page), size: String(LANE_SIZE) })")
                .contains("const moved = { ...row, status: to } as Task")
                .contains("toast.error(t('laneFull', { lane: target.label, n: target.limit }))")
                // Cards: the title as heading, then priority (its label), due date and assignee.
                .contains("<div className=\"font-medium text-fg\">{ r.title == null ? '—' : String(r.title) }</div>")
                .contains("{ r.priority == null ? '—' : TaskPriorityTypeLabels[r.priority] }")
                .contains("{ r.assigneeLabel ?? (r.assigneeId == null ? '—' : '#' + String(r.assigneeId)) }")
                .contains("import { TaskPriorityTypeLabels } from '@entities/task'");
        assertThat(files).containsKey(FE + "src/shared/ui/BoardView.tsx");
    }

    @Test
    void contentPage_isPlainJsxWithCheckedLinks() throws Exception {
        String help = generate(planning("react-tailwind-crud")).get(FE + "src/app/screens/HelpScreen.tsx");
        assertThat(help)
                .contains("<strong className=\"font-semibold\">{'Doing'}</strong>")
                .contains("<a href=\"#/board\" onClick={e => { e.preventDefault(); onNavigate('board') }}")
                .contains("<li>{'Stuck? Say so in the task\\'s '}<em>{'notes'}</em>{'.'}</li>")
                .contains("<aside role=\"note\"")
                .contains("<hr className=\"border-border\" />")
                // A mailto: link stays in the tab; nothing is injected as HTML.
                .contains("<a href={'mailto:planning@example.com'} className=")
                .doesNotContain("dangerouslySetInnerHTML");
    }

    @Test
    void searchPage_asksEveryEntityAndTheHeaderOpensIt() throws Exception {
        Map<String, String> files = generate(planning("react-tailwind-crud"));
        assertThat(files.get(FE + "src/app/screens/FindScreen.tsx"))
                .contains("const PER_ENTITY = 5")
                .contains("api.get<PageOf<Task>>(`/api/tasks?${params}`),")
                .contains("api.get<PageOf<Person>>(`/api/persons?${params}`),")
                .contains(".then(([g0, g1, g2, ]) => {")
                // Tasks open on their record page; projects and people in the quick-look drawer.
                .contains("onOpen={r => onNavigate('task', String(r.id))}")
                .contains("onOpen={row => setDetail({ group: 'g1', row })}")
                .contains("onSeeAll={() => onNavigate('projects', undefined, { _q: q.trim() })}")
                .contains("{detail?.group === 'g2' && <PersonDetail value={detail.row} />}");
        assertThat(files.get(FE + "src/app/App.tsx"))
                .contains("go('find', undefined, words ? { q: words } : {})")
                .contains("<FindScreen query={route.query} onQueryChange={setQuery} onNavigate={goView} />");
        assertThat(generate(planning("react-menora-digital-crud")).get(FE + "src/app/App.tsx"))
                .contains("import { DropdownMenu, NavLinks, SearchField, ThemeToggle, Footer, useMenoraTheme } from '@shared/ui/menora'")
                .contains("<SearchField")
                .contains("const [shellQ, setShellQ] = useState('')");
    }

    @Test
    void importPage_addsTheEndpointOnlyForItsEntity() throws Exception {
        Map<String, String> files = generate(planning("react-tailwind-crud"));
        assertThat(files.get(FE + "src/app/screens/ImportTasksScreen.tsx"))
                .contains("fields={ TaskImportFields }")
                .contains("onNavigate('tasks')");
        assertThat(files.get(FE + "src/features/task-form/model/importFields.ts"))
                .contains("{ name: 'status', label: 'Status', kind: 'enum', required: true, options: [{ value: 'TODO', label: 'To do' }")
                .contains("{ name: 'projectId', label: 'Project', kind: 'relation', required: true, also: ['project'], numeric: true },")
                .doesNotContain("name: 'id'");
        assertThat(files).doesNotContainKey(FE + "src/features/project-form/model/importFields.ts");
        // The list page's Import goes to the import page; elsewhere (a tab, a related list) a drawer.
        assertThat(files.get(FE + "src/pages/task/ui/TaskPage.tsx"))
                .contains("onClick={ onImport ?? (() => setImporting(true)) }")
                .contains("<CsvImport");
        assertThat(files.get(FE + "src/app/screens/TasksScreen.tsx")).contains("onImport={() => onNavigate('import-tasks')}");
        assertThat(files.get(FE + "src/pages/project/ui/ProjectPage.tsx")).doesNotContain("CsvImport");

        String service = files.get(BE + "service/TaskService.java");
        assertThat(service)
                .contains("public java.util.List<ImportError> importAll(java.util.List<TaskDto> rows) {")
                .contains("entityManager.find(com.menora.support.entity.Project.class, dto.projectId()) == null")
                .contains("if (errors.isEmpty()) repository.saveAll(entities);");
        assertThat(files.get(BE + "controller/TaskController.java"))
                .contains("@PostMapping(\"/import\")")
                .contains("return ResponseEntity.unprocessableEntity().body(java.util.Map.of(\"errors\", errors));");
        assertThat(files.get(BE + "controller/ProjectController.java")).doesNotContain("/import");
        assertThat(files.get(BE + "service/ProjectService.java")).doesNotContain("importAll");
    }

    @Test
    void csvImportOpt_addsImportToTheClassicShell() throws Exception {
        Map<String, Object> body = planning("react-tailwind-crud");
        body.put("pages", List.of());
        body.put("opts", Map.of("scaffold", List.of("csvImport")));
        // Person opts out.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) body.get("entities");
        Map<String, Object> person = new LinkedHashMap<>(entities.get(0));
        person.put("opts", Map.of("csvImport", false));
        entities.set(0, person);
        Map<String, String> files = generate(body);
        assertThat(files).containsKeys(FE + "src/shared/ui/csv.ts", FE + "src/shared/ui/CsvImport.tsx",
                FE + "src/features/project-form/model/importFields.ts");
        assertThat(files.get(FE + "src/pages/project/ui/ProjectPage.tsx"))
                .contains("onClick={ (() => setImporting(true)) }")
                .contains("toast.success(t('importDone', { n, x: 'Projects' }))");
        assertThat(files.get(FE + "src/pages/person/ui/PersonPage.tsx")).doesNotContain("CsvImport");
        assertThat(files.get(BE + "controller/PersonController.java")).doesNotContain("/import");
        assertThat(files.get(BE + "controller/ProjectController.java")).contains("@PostMapping(\"/import\")");
    }

    @Test
    void rejectsInvalidNewPages() {
        assertRejected(pages -> page(pages, "schedule").put("dateField", "title"), "dateField 'title' must be a filterable, non-key date field of Task");
        assertRejected(pages -> page(pages, "schedule").remove("endField"), "the timeline mode needs an endField");
        assertRejected(pages -> page(pages, "schedule").put("modes", List.of("month", "year")), "unknown mode 'year'");
        assertRejected(pages -> page(pages, "board").put("laneField", "title"), "laneField 'title' must be a filterable enum or boolean field of Task");
        assertRejected(pages -> page(pages, "board").put("wipLimits", Map.of("LATER", 3)), "wipLimits: 'LATER' is not one of its lanes");
        assertRejected(pages -> page(pages, "board").put("presetFilter", Map.of("status", "TODO")), "the lanes already split by it");
        assertRejected(pages -> page(pages, "board").put("cardFields", List.of("title", "nope")), "cardFields: 'nope' is not a field or relation of Task");
        assertRejected(pages -> page(pages, "board").put("dateField", "dueOn"), "(board) does not take 'dateField'");
        assertRejected(pages -> page(pages, "help").put("body", "Go [here](javascript:alert(1))."), "must be an http(s) or mailto address, or a page (#/page-id)");
        assertRejected(pages -> page(pages, "help").put("body", "See [the task](#/task)."), "cannot link to the record page 'task'");
        assertRejected(pages -> page(pages, "help").put("body", "See [nothing](#/nowhere)."), "links to no page 'nowhere'");
        assertRejected(pages -> page(pages, "help").remove("title"), "(content) needs a title");
        assertRejected(pages -> pages.add(new LinkedHashMap<>(Map.of("id", "import-again", "type", "import", "entity", "Task"))),
                "Task already has an import page ('import-tasks')");
        assertRejected(pages -> pages.add(new LinkedHashMap<>(Map.of("id", "find-again", "type", "search"))),
                "a layout has one search page ('find' already is)");
        assertRejected(pages -> { page(pages, "find").put("hidden", true); page(pages, "find").put("shellSearch", false); },
                "is hidden, so it needs shellSearch");
        assertRejected(pages -> page(pages, "find").put("perEntity", 50), "perEntity must be between 3 and 10");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> planning(String set) {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("projects", set);
        body.put("opts", Map.of("scaffold", List.of("csvExport")));
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> page(List<Map<String, Object>> pages, String id) {
        return pages.stream().filter(p -> id.equals(p.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private void assertRejected(Consumer<List<Map<String, Object>>> mutator, String expected) {
        Map<String, Object> body = planning("react-tailwind-crud");
        mutator.accept((List<Map<String, Object>>) body.get("pages"));
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
