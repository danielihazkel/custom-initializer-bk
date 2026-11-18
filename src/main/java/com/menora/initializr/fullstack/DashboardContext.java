package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dashboard and report pages' view-model: widgets (counts, charts, lists, links, activity,
 * time in state, text), their filter keys and ranges, and drill-through links.
 * Part of {@link EntityScaffoldContext}, split out by area.
 */
final class DashboardContext {

    private DashboardContext() {}

    @SuppressWarnings("unchecked")
    static void putDashboard(Map<String, Object> pv, PageDefinition p,
                             Map<String, Map<String, Object>> entityByPascal,
                             Map<String, Map<String, Object>> summaries,
                             PageContext.PageLinks links) {
        List<Map<String, Object>> widgetViews = new ArrayList<>();
        // The `<Enum>Labels` consts the bar charts read, grouped into one import per entity module
        // (deduped, declaration order).
        Map<String, Set<String>> labelRefsByModule = new LinkedHashMap<>();
        boolean needsNavigate = false;
        boolean usesRange = false;
        boolean usesStatsQuery = false;
        boolean usesQueryOf = false;
        boolean usesBucketRange = false;
        // Dashboard filters: one bar over the grid; each widget takes the ones its entity filters by.
        List<String> dashFilters = p.dashboard() == null ? List.of() : p.dashboard().filters();
        for (int i = 0; i < p.widgets().size(); i++) {
            PageDefinition.Widget w = p.widgets().get(i);
            if (w.kind() == PageDefinition.WidgetKind.TIME_IN_STATE) {
                widgetViews.add(timeInStateView(w, i, entityByPascal.get(Naming.toPascalCase(w.entity())), labelRefsByModule));
                continue;
            }
            if (w.kind() == PageDefinition.WidgetKind.TEXT) {
                widgetViews.add(textWidgetView(w, i));
                continue;
            }
            if (w.kind() == PageDefinition.WidgetKind.LINKS) {
                widgetViews.add(linksWidgetView(w, i));
                continue;
            }
            if (w.kind() == PageDefinition.WidgetKind.ACTIVITY) {
                Map<String, Object> av = activityWidgetView(w, i, entityByPascal, summaries, links);
                needsNavigate |= Boolean.TRUE.equals(av.get("needsNavigate"));
                widgetViews.add(av);
                continue;
            }
            if (w.kind() == PageDefinition.WidgetKind.LIST) {
                Map<String, Object> lv = listWidgetView(w, i, entityByPascal, summaries, links, dashFilters);
                needsNavigate |= Boolean.TRUE.equals(lv.get("needsNavigate"));
                // A period preset on the embedded list: the screen spreads a rangeParams() call in.
                usesQueryOf |= PageContext.presetHasPeriod(w.presetFilter());
                usesRange |= PageContext.presetHasPeriod(w.presetFilter());
                widgetViews.add(lv);
                continue;
            }
            Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(w.entity()));
            Map<String, Object> summary = summaries.get(w.entity().toLowerCase(Locale.ROOT));
            String entityLabels = TemplateText.tsString((String) ev.get("entityLabelPlural"));
            Map<String, Object> wv = new LinkedHashMap<>();
            wv.put("widgetKey", "w" + i);
            wv.put("widgetIsKpi", w.kind() == PageDefinition.WidgetKind.KPI);
            // A donut is a breakdown drawn as a ring: the same card, with `donut`.
            wv.put("widgetIsBar", w.kind() == PageDefinition.WidgetKind.BAR || w.kind() == PageDefinition.WidgetKind.DONUT);
            wv.put("isDonut", w.kind() == PageDefinition.WidgetKind.DONUT);
            wv.put("widgetIsStacked", w.kind() == PageDefinition.WidgetKind.STACKED);
            wv.put("widgetIsPivot", w.kind() == PageDefinition.WidgetKind.PIVOT);
            wv.put("widgetIsActivity", false);
            wv.put("widgetIsText", false);
            wv.put("widgetIsLine", w.kind() == PageDefinition.WidgetKind.LINE);
            wv.put("widgetIsRecent", w.kind() == PageDefinition.WidgetKind.RECENT);
            wv.put("widgetIsTop", w.kind() == PageDefinition.WidgetKind.TOP);
            wv.put("widgetIsProgress", w.kind() == PageDefinition.WidgetKind.PROGRESS);
            wv.put("widgetIsFunnel", w.kind() == PageDefinition.WidgetKind.FUNNEL);
            wv.put("widgetIsGauge", w.kind() == PageDefinition.WidgetKind.GAUGE);
            wv.put("widgetIsTimeInState", false);
            // `count` is the default everywhere, so only a real reduction reaches the props.
            boolean reduces = w.agg() != null && w.agg() != PageDefinition.Agg.COUNT;
            wv.put("hasAgg", reduces);
            wv.put("agg", w.agg() == null ? null : w.agg().wire());
            wv.put("aggField", w.field());
            putAggCurrency(wv, ev, reduces ? w.field() : null);
            wv.put("bucket", w.bucket() == null ? null : w.bucket().wire());
            wv.put("path", "/api/" + ev.get("entityNamePluralKebab"));
            String spanClass = SPAN_CLASSES.get(w.span() - 1);
            wv.put("hasSpanClass", !spanClass.isEmpty());
            wv.put("spanClass", spanClass);
            // The filter params the widget's queries carry: its fixed preset, then the dashboard
            // period over its date field (a TS expression, since the period is screen state).
            String preset = PageContext.presetQueryExpr(w.presetFilter(), ev);
            String dash = dashParamsExpr(dashKeys(ev, dashFilters, w.presetFilter()));
            String paramsExpr = params(preset, rangeExpr(ev, w.dateField(), false), dash);
            usesRange |= w.dateField() != null || PageContext.presetHasPeriod(w.presetFilter());
            usesStatsQuery |= paramsExpr != null && paramsExpr.contains("statsQuery(");
            wv.put("hasParams", paramsExpr != null);
            wv.put("paramsExpr", paramsExpr);
            // A kpi's change against the previous period: the same params, one period back. The
            // picker's "all time" has no previous period, so the tile then shows no change.
            wv.put("hasCompare", w.compare());
            // A kpi's sparkline: its bucket over the date kept in groupBy, with the tile's params.
            boolean trend = w.kind() == PageDefinition.WidgetKind.KPI && w.bucket() != null;
            wv.put("hasTrend", trend);
            wv.put("trendOn", trend ? w.groupBy() : null);
            wv.put("compareParamsExpr", w.compare() ? params(preset, rangeExpr(ev, w.dateField(), true), dash) : null);
            wv.put("target", w.target() == null ? null : w.target().toPlainString());
            // A widget's drillTo (a list or report page of its entity) replaces the entity's home.
            String target = w.drillTo() != null ? w.drillTo() : links.homeOf(w.entity());
            wv.put("hasTarget", target != null);
            wv.put("targetPageId", target);
            // "View all" on a filtered widget opens the list with the same filters, when its home is
            // a list page that takes them from the route (a drillTo page always does).
            boolean openWithQuery = paramsExpr != null && target != null
                    && (w.drillTo() != null || target.equals(links.listPageOf(w.entity())))
                    && Boolean.TRUE.equals(ev.get("hasFilters"));
            wv.put("openWithQuery", openWithQuery);
            usesQueryOf |= openWithQuery;
            needsNavigate |= target != null;
            String defaultTitle;
            switch (w.kind()) {
                // A reducing tile is titled by what it reduces ("Total Amount"), a counting one by
                // what it counts.
                case KPI, PROGRESS, GAUGE -> defaultTitle = reduces ? aggTitle(w, ev) : entityLabels;
                case LINE -> {
                    wv.put("groupBy", w.groupBy());
                    Map<String, Object> fv = PageContext.fieldOf(ev, w.groupBy());
                    wv.put("groupByIsDateTime", Boolean.TRUE.equals(fv.get("isDateTime")));
                    // A point opens the list over its day/month/year.
                    boolean drill = putDrill(wv, ev, w.entity(), links, w.groupBy(), paramsExpr, w.drillTo());
                    usesBucketRange |= drill;
                    usesQueryOf |= drill && paramsExpr != null;
                    needsNavigate |= drill;
                    defaultTitle = "t('xOverTime', { x: "
                            + (reduces ? aggTitle(w, ev) : entityLabels) + " })";
                }
                case STACKED -> {
                    Map<String, Object> rank = rankView(ev, w.groupBy());
                    wv.putAll(rank);
                    Map<String, Object> split = rankView(ev, w.series());
                    wv.put("seriesField", w.series());
                    wv.put("hasSeriesLabels", split.get("hasLabels"));
                    wv.put("seriesLabelsRef", split.get("labelsRef"));
                    for (Object ref : new Object[] {rank.get("labelsRef"), split.get("labelsRef")}) {
                        if (ref != null) {
                            labelRefsByModule.computeIfAbsent((String) ev.get("entityNameKebab"), k -> new LinkedHashSet<>())
                                    .add((String) ref);
                        }
                    }
                    boolean drill = putDrill(wv, ev, w.entity(), links, (String) rank.get("drillKey"), paramsExpr, w.drillTo());
                    usesQueryOf |= drill && paramsExpr != null;
                    needsNavigate |= drill;
                    defaultTitle = "t('xByYAndZ', { x: " + (reduces ? aggTitle(w, ev) : entityLabels)
                            + ", y: " + rank.get("groupLabelExpr") + ", z: " + split.get("groupLabelExpr") + " })";
                }
                case PIVOT -> {
                    Map<String, Object> rank = rankView(ev, w.groupBy());
                    wv.putAll(rank);
                    Map<String, Object> cols = rankView(ev, w.series());
                    wv.put("seriesField", w.series());
                    wv.put("hasSeriesLabels", cols.get("hasLabels"));
                    wv.put("seriesLabelsRef", cols.get("labelsRef"));
                    wv.put("seriesIsRelation", cols.get("isRelationRank"));
                    wv.put("seriesOptionsPath", cols.get("optionsPath"));
                    wv.put("seriesOptionValue", cols.get("optionValue"));
                    wv.put("seriesHasOptionLabel", Boolean.TRUE.equals(cols.get("hasOptionLabel")));
                    wv.put("seriesOptionLabel", cols.get("optionLabel"));
                    for (Object ref : new Object[] {rank.get("labelsRef"), cols.get("labelsRef")}) {
                        if (ref != null) {
                            labelRefsByModule.computeIfAbsent((String) ev.get("entityNameKebab"), k -> new LinkedHashSet<>())
                                    .add((String) ref);
                        }
                    }
                    // A cell opens the list filtered to both of its groups — when the list filters by both.
                    boolean drill = putDrill(wv, ev, w.entity(), links, (String) rank.get("drillKey"), paramsExpr, w.drillTo())
                            && ((List<Map<String, Object>>) ev.getOrDefault("filterFields", List.of())).stream()
                                    .anyMatch(f -> cols.get("drillKey").equals(f.get("name")));
                    wv.put("hasDrill", drill);
                    wv.put("seriesDrillKey", cols.get("drillKey"));
                    usesQueryOf |= drill && paramsExpr != null;
                    needsNavigate |= drill;
                    defaultTitle = "t('xByYAndZ', { x: " + (reduces ? aggTitle(w, ev) : entityLabels)
                            + ", y: " + rank.get("groupLabelExpr") + ", z: " + cols.get("groupLabelExpr") + " })";
                }
                case BAR, TOP, DONUT, FUNNEL -> {
                    Map<String, Object> rank = rankView(ev, w.groupBy());
                    wv.putAll(rank);
                    String labelsRef = (String) rank.get("labelsRef");
                    if (labelsRef != null) {
                        labelRefsByModule.computeIfAbsent((String) ev.get("entityNameKebab"), k -> new LinkedHashSet<>())
                                .add(labelsRef);
                    }
                    // A bar or a ranked row opens the list filtered to that group.
                    boolean drill = putDrill(wv, ev, w.entity(), links, (String) rank.get("drillKey"), paramsExpr, w.drillTo());
                    usesQueryOf |= drill && paramsExpr != null;
                    needsNavigate |= drill;
                    if (w.kind() == PageDefinition.WidgetKind.TOP) wv.put("limit", w.limit());
                    // A funnel limited to some of its enum's values draws those as its stages, in
                    // the enum's order: a state off the main line (REJECTED) is left out that way.
                    String stages = w.kind() == PageDefinition.WidgetKind.FUNNEL ? w.presetFilter().get(w.groupBy()) : null;
                    wv.put("hasStages", stages != null);
                    if (stages != null) {
                        wv.put("stagesTs", "[" + String.join(", ", java.util.Arrays.stream(stages.split(","))
                                .map(s -> TemplateText.tsString(s.trim())).toList()) + "]");
                    }
                    String measured = reduces ? aggTitle(w, ev) : entityLabels;
                    defaultTitle = "t('" + (w.kind() == PageDefinition.WidgetKind.TOP ? "topXByY" : "xByY")
                            + "', { x: " + measured + ", y: " + rank.get("groupLabelExpr") + " })";
                }
                default -> {
                    wv.put("limit", w.limit());
                    // Newest first by the chosen column; the key column reads as "#12".
                    wv.put("sortField", w.sortBy() != null ? w.sortBy() : summary.get("pkName"));
                    wv.put("hasKeyField", w.sortBy() != null);
                    wv.put("keyField", summary.get("pkName"));
                    Object labelField = summary.get("labelField");
                    wv.put("displayField", labelField != null ? labelField : summary.get("pkName"));
                    // A recent row opens its record page, when the entity has one.
                    PageContext.putRecordLink(wv, "", w.entity(), links, summaries);
                    needsNavigate |= Boolean.TRUE.equals(wv.get("hasRecordPage"));
                    defaultTitle = "t('recentX', { x: " + entityLabels + " })";
                }
            }
            wv.put("titleExpr", w.title() != null ? TemplateText.tsString(w.title()) : defaultTitle);
            widgetViews.add(wv);
        }
        pv.put("widgets", widgetViews);
        for (String kind : List.of("Kpi", "Bar", "Line", "Recent", "Top", "Progress", "Stacked", "Pivot", "Funnel", "Gauge", "TimeInState", "Activity", "Text", "Links", "List")) {
            pv.put("uses" + kind, widgetViews.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("widgetIs" + kind))));
        }
        // The process widgets live in a file of their own: only what the page draws is imported.
        List<String> flow = new ArrayList<>();
        if (Boolean.TRUE.equals(pv.get("usesFunnel"))) flow.add("FunnelCard");
        if (Boolean.TRUE.equals(pv.get("usesGauge"))) flow.add("GaugeTile");
        if (Boolean.TRUE.equals(pv.get("usesTimeInState"))) flow.add("TimeInStateCard");
        pv.put("usesFlow", !flow.isEmpty());
        pv.put("flowImports", String.join(", ", flow));
        // The entity pages the list widgets embed: one import per entity module.
        Map<String, Map<String, Object>> listModules = new LinkedHashMap<>();
        for (Map<String, Object> v : widgetViews) {
            if (Boolean.TRUE.equals(v.get("widgetIsList"))) {
                listModules.putIfAbsent((String) v.get("entityNameKebab"),
                        Map.of("EntityName", v.get("EntityName"), "entityNameKebab", v.get("entityNameKebab")));
            }
        }
        pv.put("listImports", new ArrayList<>(listModules.values()));
        usesStatsQuery |= widgetViews.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("hasCompare"))
                && ((String) v.get("compareParamsExpr")).startsWith("statsQuery("));
        List<Map<String, Object>> labelImports = new ArrayList<>();
        labelRefsByModule.forEach((kebab, refs) ->
                labelImports.add(Map.of("entityNameKebab", kebab, "labelsRefs", String.join(", ", refs))));
        pv.put("labelImports", labelImports);
        pv.put("needsNavigate", needsNavigate);
        pv.put("hasDateRange", p.dateRange() != null);
        pv.put("dateRangeDefault", p.dateRange() == null ? null : p.dateRange().wire());
        // A dashboard with data to show has a Refresh button, and reloads on a timer when it says
        // how often; its data widgets read the reload count from RefreshTick.
        boolean hasRefresh = p.widgets().stream().anyMatch(w -> w.kind() != PageDefinition.WidgetKind.TEXT
                && w.kind() != PageDefinition.WidgetKind.LINKS && w.kind() != PageDefinition.WidgetKind.LIST);
        pv.put("hasRefresh", hasRefresh);
        pv.put("hasAutoRefresh", hasRefresh && p.refreshSeconds() != null);
        pv.put("refreshMs", p.refreshSeconds() == null ? null : p.refreshSeconds() * 1000);
        pv.put("usesDashboardState", hasRefresh || p.dateRange() != null || !dashFilters.isEmpty());
        pv.put("hasHeaderTools", hasRefresh || p.dateRange() != null);
        // The filter bar's controls: each filter as the first widget entity that has it describes it.
        List<Map<String, Object>> dashFilterFields = new ArrayList<>();
        for (String name : dashFilters) {
            p.widgets().stream().filter(w -> w.entity() != null)
                    .map(w -> entityByPascal.get(Naming.toPascalCase(w.entity())))
                    .map(ev -> dashFilterField(ev, name))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .ifPresent(dashFilterFields::add);
        }
        pv.put("hasDashFilters", !dashFilterFields.isEmpty());
        pv.put("dashFilterFields", dashFilterFields);
        // stats.ts helpers the screen imports.
        pv.put("usesRangeParams", usesRange);
        pv.put("usesStatsQuery", usesStatsQuery);
        pv.put("usesQueryOf", usesQueryOf);
        pv.put("usesBucketRange", usesBucketRange);
        pv.put("usesStatsHelpers", p.dateRange() != null || usesStatsQuery || usesQueryOf || usesBucketRange || usesRange
                || p.widgets().stream().anyMatch(w -> w.kind() != PageDefinition.WidgetKind.TEXT
                        && w.kind() != PageDefinition.WidgetKind.LINKS && w.kind() != PageDefinition.WidgetKind.LIST
                        && w.kind() != PageDefinition.WidgetKind.TIME_IN_STATE));
    }

    /** The widget's filter params as a TS expression: its preset (already an expression), the
     *  period and the dashboard filters it takes, in that order — one of them alone, several joined
     *  by statsQuery; null for none. */
    private static String params(String... parts) {
        List<String> present = java.util.Arrays.stream(parts).filter(java.util.Objects::nonNull).toList();
        if (present.isEmpty()) return null;
        if (present.size() == 1) return present.get(0);
        return "statsQuery(" + String.join(", ", present) + ")";
    }

    /** The entity's filter entry a dashboard filter names (an enum/boolean field, or a relation by
     *  its field name), or null when the entity has no such filter. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> dashFilterField(Map<String, Object> ev, String name) {
        for (Map<String, Object> ff : (List<Map<String, Object>>) ev.getOrDefault("filterFields", List.of())) {
            boolean relation = Boolean.TRUE.equals(ff.get("isRelationFilter"));
            if (relation ? name.equals(ff.get("relationField"))
                    : name.equals(ff.get("name")) && (Boolean.TRUE.equals(ff.get("isEnumFilter")) || Boolean.TRUE.equals(ff.get("isBooleanFilter")))) {
                return ff;
            }
        }
        return null;
    }

    /** The query params of the dashboard filters a widget's entity takes — not one its own preset
     *  already fixes. */
    private static List<String> dashKeys(Map<String, Object> ev, List<String> dashFilters, Map<String, String> preset) {
        List<String> keys = new ArrayList<>();
        for (String name : dashFilters) {
            Map<String, Object> ff = dashFilterField(ev, name);
            if (ff == null) continue;
            String key = (String) ff.get("name");
            if (preset.containsKey(name) || preset.containsKey(key)) continue;
            keys.add(key);
        }
        return keys;
    }

    /** {@code dashParams(['status', 'customerId'])}, the screen's helper over its filter bar; null for no keys. */
    private static String dashParamsExpr(List<String> keys) {
        return keys.isEmpty() ? null : "dashParams(" + dashKeysTs(keys) + ")";
    }

    private static String dashKeysTs(List<String> keys) {
        return "[" + String.join(", ", keys.stream().map(TemplateText::tsString).toList()) + "]";
    }

    /** {@code rangeParams('soldOn', false, period)} (the previous period with {@code previous}), or
     *  null when the widget has no period date. */
    private static String rangeExpr(Map<String, Object> ev, String dateField, boolean previous) {
        if (dateField == null) return null;
        Map<String, Object> df = PageContext.fieldOf(ev, dateField);
        return "rangeParams('" + dateField + "', " + Boolean.TRUE.equals(df.get("isDateTime")) + ", period"
                + (previous ? ", true" : "") + ")";
    }

    /**
     * What a bar chart or top list groups by, for its screen: the {@code /stats} key, how a group is
     * labelled (an enum's labels, or — for a relation — the target's rows by id), the heading of the
     * column, and the list filter a group drills into.
     */
    /** A list widget's view: the entity page it embeds and the literal props it hands it (the same
     *  ones an entity-list page's screen passes), a record link for its rows and its home for
     *  "View all". */
    private static Map<String, Object> listWidgetView(PageDefinition.Widget w, int index,
                                                      Map<String, Map<String, Object>> entityByPascal,
                                                      Map<String, Map<String, Object>> summaries, PageContext.PageLinks links,
                                                      List<String> dashFilters) {
        Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(w.entity()));
        Map<String, Object> wv = new LinkedHashMap<>();
        wv.put("widgetKey", "w" + index);
        for (String kind : List.of("Kpi", "Bar", "Line", "Recent", "Top", "Progress", "Stacked", "Pivot", "Funnel", "Gauge", "TimeInState", "Activity", "Text", "Links")) {
            wv.put("widgetIs" + kind, false);
        }
        wv.put("widgetIsList", true);
        wv.put("EntityName", ev.get("EntityName"));
        wv.put("entityNameKebab", ev.get("entityNameKebab"));
        wv.put("titleExpr", w.title() != null ? TemplateText.tsString(w.title()) : TemplateText.tsString((String) ev.get("entityLabelPlural")));
        wv.put("limit", w.limit());
        String presetTs = PageContext.presetFilterTs(w.presetFilter(), ev);
        wv.put("hasPresetFilter", presetTs != null);
        wv.put("presetFilterTs", presetTs);
        wv.put("hasColumns", !w.columns().isEmpty());
        wv.put("columnsTs", w.columns().isEmpty() ? null
                : "[" + String.join(", ", w.columns().stream().map(TemplateText::tsString).toList()) + "]");
        wv.put("hasSort", w.sort() != null);
        wv.put("sortTs", w.sort() == null ? null
                : "{ field: " + TemplateText.tsString(w.sort().field()) + ", direction: '" + (w.sort().desc() ? "desc" : "asc") + "' }");
        String spanClass = SPAN_CLASSES.get(w.span() - 1);
        wv.put("hasSpanClass", !spanClass.isEmpty());
        wv.put("spanClass", spanClass);
        PageContext.putRecordLink(wv, "", w.entity(), links, summaries);
        String target = w.drillTo() != null ? w.drillTo() : links.homeOf(w.entity());
        wv.put("hasTarget", target != null);
        wv.put("targetPageId", target);
        wv.put("needsNavigate", target != null || Boolean.TRUE.equals(wv.get("hasRecordPage")));
        // The dashboard filters the embedded list takes: its filters open with them, and a change
        // remounts it (`key`), since a list reads its opening filters once.
        List<String> keys = dashKeys(ev, dashFilters, w.presetFilter());
        wv.put("hasDashKeys", !keys.isEmpty());
        wv.put("dashKeysTs", keys.isEmpty() ? null : dashKeysTs(keys));
        wv.put("hasParams", false);
        wv.put("hasCompare", false);
        return wv;
    }

    /** A links widget's view before its tiles are resolved (a target may be declared later): the
     *  page ids, and the title and width like a text card. */
    private static Map<String, Object> linksWidgetView(PageDefinition.Widget w, int index) {
        Map<String, Object> wv = new LinkedHashMap<>();
        wv.put("widgetKey", "w" + index);
        for (String kind : List.of("Kpi", "Bar", "Line", "Recent", "Top", "Progress", "Stacked", "Pivot", "Funnel", "Gauge", "TimeInState", "Activity", "Text", "List")) {
            wv.put("widgetIs" + kind, false);
        }
        wv.put("widgetIsLinks", true);
        wv.put("hasTitle", w.title() != null);
        wv.put("titleExpr", w.title() == null ? null : TemplateText.tsString(w.title()));
        wv.put("linkIds", w.pages());
        String spanClass = SPAN_CLASSES.get(w.span() - 1);
        wv.put("hasSpanClass", !spanClass.isEmpty());
        wv.put("spanClass", spanClass);
        wv.put("hasParams", false);
        wv.put("hasCompare", false);
        return wv;
    }

    /** A text widget's view: its title (if any) and paragraphs as TS string literals. */
    /** An activity feed: one source per audited entity — its list endpoint, its name, the column a
     *  row reads by, and the record page a row opens (when there is one). */
    private static Map<String, Object> activityWidgetView(PageDefinition.Widget w, int index,
                                                          Map<String, Map<String, Object>> entityByPascal,
                                                          Map<String, Map<String, Object>> summaries, PageContext.PageLinks links) {
        Map<String, Object> wv = new LinkedHashMap<>();
        wv.put("widgetKey", "w" + index);
        for (String kind : List.of("Kpi", "Bar", "Line", "Recent", "Top", "Progress", "Stacked", "Pivot", "Funnel", "Gauge", "TimeInState", "Text", "Links", "List")) {
            wv.put("widgetIs" + kind, false);
        }
        wv.put("widgetIsActivity", true);
        wv.put("titleExpr", w.title() != null ? TemplateText.tsString(w.title()) : "t('recentActivity')");
        wv.put("limit", w.limit());
        boolean needsNavigate = false;
        List<Map<String, Object>> sources = new ArrayList<>();
        for (String name : w.entities()) {
            Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(name));
            Map<String, Object> summary = summaries.get(name.toLowerCase(Locale.ROOT));
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("path", "/api/" + ev.get("entityNamePluralKebab"));
            sv.put("labelExpr", TemplateText.tsString((String) ev.get("entityLabel")));
            Object labelField = summary.get("labelField");
            sv.put("displayField", labelField != null ? labelField : summary.get("pkName"));
            PageContext.putRecordLink(sv, "", name, links, summaries);
            needsNavigate |= Boolean.TRUE.equals(sv.get("hasRecordPage"));
            sources.add(sv);
        }
        wv.put("activitySources", sources);
        wv.put("needsNavigate", needsNavigate);
        String spanClass = SPAN_CLASSES.get(w.span() - 1);
        wv.put("hasSpanClass", !spanClass.isEmpty());
        wv.put("spanClass", spanClass);
        wv.put("hasParams", false);
        wv.put("hasCompare", false);
        return wv;
    }

    /** A time-in-state widget: the entity's move log, read through its status labels. */
    private static Map<String, Object> timeInStateView(PageDefinition.Widget w, int index, Map<String, Object> ev,
                                                       Map<String, Set<String>> labelRefsByModule) {
        Map<String, Object> wv = new LinkedHashMap<>();
        wv.put("widgetKey", "w" + index);
        for (String kind : List.of("Kpi", "Bar", "Line", "Recent", "Top", "Progress", "Stacked", "Pivot", "Funnel", "Gauge",
                "Activity", "Text", "Links", "List")) {
            wv.put("widgetIs" + kind, false);
        }
        wv.put("widgetIsTimeInState", true);
        wv.put("path", "/api/" + ev.get("entityNamePluralKebab"));
        String labelsRef = PageContext.fieldOf(ev, (String) ev.get("workflowStatusField")).get("enumTypeName") + "Labels";
        wv.put("labelsRef", labelsRef);
        labelRefsByModule.computeIfAbsent((String) ev.get("entityNameKebab"), k -> new LinkedHashSet<>()).add(labelsRef);
        wv.put("titleExpr", w.title() != null ? TemplateText.tsString(w.title())
                : "t('timeInStateOf', { x: " + TemplateText.tsString((String) ev.get("entityLabelPlural")) + " })");
        String spanClass = SPAN_CLASSES.get(w.span() - 1);
        wv.put("hasSpanClass", !spanClass.isEmpty());
        wv.put("spanClass", spanClass);
        wv.put("hasParams", false);
        wv.put("hasCompare", false);
        return wv;
    }

    private static Map<String, Object> textWidgetView(PageDefinition.Widget w, int index) {
        Map<String, Object> wv = new LinkedHashMap<>();
        wv.put("widgetKey", "w" + index);
        for (String kind : List.of("Kpi", "Bar", "Line", "Recent", "Top", "Progress", "Stacked", "Pivot", "Funnel", "Gauge", "TimeInState", "Activity", "Links", "List")) {
            wv.put("widgetIs" + kind, false);
        }
        wv.put("widgetIsText", true);
        wv.put("hasTitle", w.title() != null);
        wv.put("titleExpr", w.title() == null ? null : TemplateText.tsString(w.title()));
        List<Map<String, Object>> paragraphs = new ArrayList<>();
        for (String para : w.text().split("\\n\\s*\\n")) {
            String trimmed = para.strip();
            if (!trimmed.isEmpty()) paragraphs.add(Map.of("textExpr", TemplateText.tsString(trimmed)));
        }
        wv.put("paragraphs", paragraphs);
        String spanClass = SPAN_CLASSES.get(w.span() - 1);
        wv.put("hasSpanClass", !spanClass.isEmpty());
        wv.put("spanClass", spanClass);
        wv.put("hasParams", false);
        wv.put("hasCompare", false);
        return wv;
    }

    private static Map<String, Object> rankView(Map<String, Object> ev, String groupBy) {
        Map<String, Object> out = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> rel = ((List<Map<String, Object>>) ev.get("relations")).stream()
                .filter(r -> groupBy.equalsIgnoreCase((String) r.get("fieldName"))).findFirst().orElse(null);
        if (rel != null) {
            String name = (String) rel.get("fieldName");
            out.put("groupBy", name);
            out.put("hasLabels", false);
            out.put("labelsRef", null);
            out.put("isRelationRank", true);
            out.put("optionsPath", "/api/" + rel.get("targetEntityKebabPlural"));
            out.put("optionValue", rel.get("targetPkName"));
            out.put("hasOptionLabel", rel.get("targetLabelField") != null);
            out.put("optionLabel", rel.get("targetLabelField"));
            out.put("groupLabelExpr", TemplateText.tsString((String) rel.get("label")));
            out.put("drillKey", rel.get("fkFieldName"));
            return out;
        }
        Map<String, Object> fv = PageContext.fieldOf(ev, groupBy);
        Object enumType = Boolean.TRUE.equals(fv.get("isEnum")) ? fv.get("enumTypeName") : null;
        out.put("groupBy", groupBy);
        out.put("hasLabels", enumType != null);
        out.put("labelsRef", enumType == null ? null : enumType + "Labels");
        out.put("isRelationRank", false);
        out.put("groupLabelExpr", TemplateText.tsString((String) fv.get("label")));
        out.put("drillKey", groupBy);
        return out;
    }

    /**
     * Drill-down: a click on a group (a bar, a ranked row, a point in time) opens the entity's list
     * page filtered to it. Only when the entity has a visible list page and the list can filter by
     * {@code filterKey} — a filter it does not have would be silently dropped, showing every row.
     * Sets {@code hasDrill}/{@code drillPageId}/{@code drillKey}.
     */
    @SuppressWarnings("unchecked")
    private static boolean putDrill(Map<String, Object> view, Map<String, Object> ev, String entity, PageContext.PageLinks links,
                                    String filterKey, String paramsExpr) {
        return putDrill(view, ev, entity, links, filterKey, paramsExpr, null);
    }

    /** As above, opening {@code drillTo} (a list or report page of the entity) when given. */
    @SuppressWarnings("unchecked")
    private static boolean putDrill(Map<String, Object> view, Map<String, Object> ev, String entity, PageContext.PageLinks links,
                                    String filterKey, String paramsExpr, String drillTo) {
        String listPage = drillTo != null ? drillTo : links.listPageOf(entity);
        boolean filterable = ((List<Map<String, Object>>) ev.getOrDefault("filterFields", List.of())).stream()
                .anyMatch(f -> filterKey.equals(f.get("name")));
        boolean drill = listPage != null && filterable;
        view.put("hasDrill", drill);
        view.put("drillPageId", drill ? listPage : null);
        view.put("drillKey", filterKey);
        view.put("drillBase", drill && paramsExpr != null ? "...queryOf(" + paramsExpr + "), " : "");
        return drill;
    }

    /** A tile that sums (averages…) a money field shows the number in the field's currency:
     *  {@code aggCurrency}, or nothing for a count or a plain number. */
    static void putAggCurrency(Map<String, Object> view, Map<String, Object> entityView, String field) {
        Map<String, Object> fv = field == null ? null : PageContext.fieldOf(entityView, field);
        boolean money = fv != null && Boolean.TRUE.equals(fv.get("isMoney"));
        view.put("hasAggCurrency", money);
        view.put("aggCurrency", money ? fv.get("currency") : null);
    }

    /** Grid classes per widget span (1–4 columns of the dashboard's sm:2 / lg:4 grid). Literal
     *  strings in the generated screen, so Tailwind's scanner sees them. */
    private static final List<String> SPAN_CLASSES = List.of(
            "", "sm:col-span-2", "sm:col-span-2 lg:col-span-3", "sm:col-span-2 lg:col-span-4");

    /**
     * A report's charts, as its screen's view-model: for each, which component draws it, the fixed
     * half of the {@code /stats} query it asks for (the filter bar appends its values), the headings
     * of its values, and where a click on a group drills to. The first chart also gets the totals
     * table.
     */
    static void putCharts(Map<String, Object> pv, PageDefinition p, Map<String, Object> ev, PageContext.PageLinks links) {
        List<Map<String, Object>> charts = new ArrayList<>();
        Set<String> labelRefs = new LinkedHashSet<>();
        boolean drills = false;
        boolean usesBucketRange = false;
        for (int i = 0; i < p.charts().size(); i++) {
            PageDefinition.Chart chart = p.charts().get(i);
            Map<String, Object> cv = new LinkedHashMap<>();
            // The grouping as a top widget sees it: an enum's labels, or a relation's target list
            // (bars named from it, the drill key being the FK filter param).
            Map<String, Object> rank = rankView(ev, chart.groupBy());
            boolean relation = Boolean.TRUE.equals(rank.get("isRelationRank"));
            Map<String, Object> fv = relation ? null : PageContext.fieldOf(ev, chart.groupBy());
            boolean overTime = chart.bucket() != null;
            cv.put("chartIsLine", overTime);
            cv.put("chartIsBar", !overTime);
            // A kind other than the default bar/line: the shape ReportChart draws instead.
            cv.put("chartHasShape", chart.kind() != null);
            cv.put("chartShape", chart.kind() == null ? "" : chart.kind().wire());
            Map<String, Object> seriesRank = chart.series() == null ? null : rankView(ev, chart.series());
            String seriesLabelsRef = seriesRank == null ? null : (String) seriesRank.get("labelsRef");
            cv.put("chartHasSeriesLabels", seriesLabelsRef != null);
            cv.put("chartSeriesLabelsRef", seriesLabelsRef);
            if (seriesLabelsRef != null) labelRefs.add(seriesLabelsRef);
            cv.put("chartGroupBy", chart.groupBy());
            cv.put("chartIsRelation", relation);
            if (relation) {
                cv.put("optionsPath", rank.get("optionsPath"));
                cv.put("optionValue", rank.get("optionValue"));
                cv.put("hasOptionLabel", rank.get("hasOptionLabel"));
                cv.put("optionLabel", rank.get("optionLabel"));
            }
            String groupLabelExpr = (String) rank.get("groupLabelExpr");
            cv.put("chartGroupLabelExpr", groupLabelExpr);
            String labelsRef = (String) rank.get("labelsRef");
            cv.put("chartHasLabels", labelsRef != null);
            cv.put("chartLabelsRef", labelsRef);
            if (labelsRef != null) labelRefs.add(labelsRef);
            boolean reduces = chart.agg() != PageDefinition.Agg.COUNT;
            String measure = reduces ? PageContext.aggTitleExpr(chart.agg(), (String) PageContext.fieldOf(ev, chart.field()).get("label")) : null;
            cv.put("chartValueHeaderExpr", reduces ? measure : "t('rows')");
            cv.put("chartTitleExpr", "t('" + (overTime ? "xOverTime" : "xByY") + "', { x: "
                    + (reduces ? measure : TemplateText.tsString((String) ev.get("entityLabelPlural")))
                    + (overTime ? "" : ", y: " + groupLabelExpr) + " })");
            cv.put("chartIsFirst", i == 0);
            // The totals table: under the first chart unless it says otherwise, under another when asked.
            cv.put("chartHasTable", chart.table() == null ? i == 0 : chart.table());
            StringBuilder query = new StringBuilder("groupBy=").append(chart.groupBy());
            if (overTime) query.append("&bucket=").append(chart.bucket().wire());
            if (chart.series() != null) query.append("&series=").append(chart.series());
            if (reduces) query.append("&agg=").append(chart.agg().wire()).append("&field=").append(chart.field());
            cv.put("rollupQuery", query.toString());
            // A bar opens the list on its value (a relation's on its id); a point in time on its day/month/year.
            boolean drill = putDrill(cv, ev, p.entity(), links, (String) rank.get("drillKey"), null);
            cv.put("drillExpr", overTime
                    ? "...bucketRange('" + chart.groupBy() + "', key, " + Boolean.TRUE.equals(fv.get("isDateTime")) + ")"
                    : rank.get("drillKey") + ": key");
            drills |= drill;
            usesBucketRange |= drill && overTime;
            charts.add(cv);
        }
        pv.put("charts", charts);
        pv.put("hasManyCharts", charts.size() > 1);
        pv.put("firstChart", charts.subList(0, 1));
        pv.put("moreCharts", charts.subList(1, charts.size()));
        pv.put("chartLabelsRefs", String.join(", ", labelRefs));
        pv.put("hasChartLabels", !labelRefs.isEmpty());
        pv.put("usesBucketRange", usesBucketRange);
        pv.put("needsNavigate", drills);
    }

    /** {@code t('aggSum', { x: 'Amount' })} — what a reducing tile or chart is called when the
     *  request gave it no title of its own. */
    private static String aggTitle(PageDefinition.Widget w, Map<String, Object> entityView) {
        return PageContext.aggTitleExpr(w.agg(), (String) PageContext.fieldOf(entityView, w.field()).get("label"));
    }
}
