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
 * Round 10, phase 10: the dashboard widgets that read a process — {@code funnel}, {@code gauge}
 * and {@code timeInState}. They live in a file of their own ({@code widgets-flow.tsx}), shipped
 * only with a dashboard that draws one. Built for real by
 * {@code GeneratedFrontendBuildSmokeTests.fullstackFrontend*FlowWidgets*}.
 */
@IntegrationTest
class FullstackFlowWidgetsTests {

    private static final String FE = "support/frontend/src/";

    @Autowired
    private TestRestTemplate rest;

    /** Approvals (its workflow logging its moves) with a dashboard of just the given widgets. */
    @SafeVarargs
    @SuppressWarnings("unchecked")
    static Map<String, Object> dashboardOf(String frontendSet, boolean trackMoves, Map<String, Object>... widgets) {
        Map<String, Object> body = FullstackWorkflowMovesTests.approvals(frontendSet, trackMoves);
        List<Map<String, Object>> pages = new ArrayList<>();
        for (Map<String, Object> page : (List<Map<String, Object>>) body.get("pages")) {
            Map<String, Object> copy = new LinkedHashMap<>(page);
            if ("home".equals(copy.get("id"))) copy.put("widgets", List.of(widgets));
            pages.add(copy);
        }
        body.put("pages", pages);
        return body;
    }

    static Map<String, Object> widget(String kind, Object... more) {
        Map<String, Object> widget = new LinkedHashMap<>();
        widget.put("kind", kind);
        widget.put("entity", "ExpenseClaim");
        for (int i = 0; i < more.length; i += 2) widget.put((String) more[i], more[i + 1]);
        return widget;
    }

    private ResponseEntity<byte[]> post(Map<String, Object> body) {
        return FullstackExampleRequests.post(rest, body);
    }

    private Map<String, String> generate(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = post(body);
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Map<String, String> files = new LinkedHashMap<>();
        FullstackExampleRequests.unzip(response.getBody()).forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
        return files;
    }

    private String refusal(Map<String, Object> body) {
        ResponseEntity<byte[]> response = post(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    @Test
    void aFunnelReadsAnEnumAsStages() throws Exception {
        // No field named: the workflow's status is the pipeline.
        Map<String, String> files = generate(dashboardOf("react-tailwind-crud", true, widget("funnel")));
        assertThat(files).containsKey(FE + "shared/ui/widgets-flow.tsx");
        assertThat(files.get(FE + "app/screens/HomeScreen.tsx"))
                .contains("import { FunnelCard } from '@shared/ui/widgets-flow'")
                .contains("        <FunnelCard\n"
                        + "          title={ t('xByY', { x: ")
                .contains("          path=\"/api/expense-claims\"\n"
                        + "          field=\"status\"\n"
                        + "          labels={ ExpenseClaimStatusTypeLabels }\n");
        // Limited to some of the values, those are its stages: a state off the main line is left out.
        assertThat(generate(dashboardOf("react-tailwind-crud", true,
                widget("funnel", "presetFilter", Map.of("status", "paid, draft, SUBMITTED, approved"))))
                .get(FE + "app/screens/HomeScreen.tsx"))
                .contains("          stages={ ['DRAFT', 'SUBMITTED', 'APPROVED', 'PAID'] }\n");
        // A sum adds up across stages too; an average does not.
        assertThat(generate(dashboardOf("react-tailwind-crud", true, widget("funnel", "agg", "sum", "field", "amount")))
                .get(FE + "app/screens/HomeScreen.tsx")).contains("          agg=\"sum\"\n          valueField=\"amount\"\n");
        assertThat(refusal(dashboardOf("react-tailwind-crud", true, widget("funnel", "agg", "avg", "field", "amount"))))
                .contains("a funnel counts rows or sums a field").contains("widgets[0].agg");
        // Its stages are an enum's values: a text field has none.
        assertThat(refusal(dashboardOf("react-tailwind-crud", true, widget("funnel", "groupBy", "title")))).contains("widgets[0]");
    }

    @Test
    void aGaugeIsANumberAgainstAFullScale() throws Exception {
        Map<String, String> files = generate(dashboardOf("react-tailwind-crud", true, widget("gauge", "target", "50")));
        assertThat(files.get(FE + "app/screens/HomeScreen.tsx"))
                .contains("import { GaugeTile } from '@shared/ui/widgets-flow'")
                .contains("        <GaugeTile\n")
                .contains("          target={ 50 }\n");
        assertThat(refusal(dashboardOf("react-tailwind-crud", true, widget("gauge")))).contains("target");
        assertThat(refusal(dashboardOf("react-tailwind-crud", true, widget("kpi", "target", "50"))))
                .contains("only a progress or gauge widget takes 'target'");
    }

    @Test
    void timeInStateReadsTheMoveLog() throws Exception {
        // (Beside a count: the page's period picker needs a widget it can limit, and this is not one.)
        Map<String, String> files = generate(dashboardOf("react-tailwind-crud", true, widget("kpi"), widget("timeInState")));
        assertThat(files.get(FE + "app/screens/HomeScreen.tsx"))
                .contains("import { TimeInStateCard } from '@shared/ui/widgets-flow'")
                .contains("        <TimeInStateCard\n"
                        + "          title={ t('timeInStateOf', { x: ")
                .contains("          path=\"/api/expense-claims\"\n"
                        + "          labels={ ExpenseClaimStatusTypeLabels }\n");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).contains("  timeInStateOf: 'Time in each state — {x}',");
        // It needs the log: a workflow that keeps none, or an entity without a workflow, is refused.
        assertThat(refusal(dashboardOf("react-tailwind-crud", false, widget("kpi"), widget("timeInState"))))
                .contains("workflow does not keep one (trackMoves)").contains("widgets[1].entity");
        assertThat(refusal(dashboardOf("react-tailwind-crud", true, widget("kpi"), widget("timeInState", "entity", "CostCenter"))))
                .contains("CostCenter has no workflow");
        // It reads every row ever moved: nothing that filters a list applies.
        assertThat(refusal(dashboardOf("react-tailwind-crud", true, widget("kpi"), widget("timeInState", "agg", "sum", "field", "amount"))))
                .contains("takes only 'entity', 'title' and 'span'");
    }

    @Test
    void theFileShipsOnlyWithADashboardThatDrawsOne() throws Exception {
        Map<String, String> files = generate(dashboardOf("react-tailwind-crud", true, widget("kpi")));
        assertThat(files).doesNotContainKey(FE + "shared/ui/widgets-flow.tsx");
        assertThat(files.get(FE + "app/screens/HomeScreen.tsx")).doesNotContain("widgets-flow");
        // The Menora set borrows it.
        Map<String, String> menora = generate(dashboardOf("react-menora-digital-crud", true, widget("funnel"), widget("gauge", "target", "9"), widget("timeInState")));
        assertThat(menora.get(FE + "app/screens/HomeScreen.tsx"))
                .contains("import { FunnelCard, GaugeTile, TimeInStateCard } from '@shared/ui/widgets-flow'");
        assertThat(menora).containsKey(FE + "shared/ui/widgets-flow.tsx");
    }
}
