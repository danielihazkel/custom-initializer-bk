package com.menora.initializr.fullstack;

import java.util.List;
import java.util.Map;

/**
 * One validated page of the generated fullstack frontend (see {@link FullstackPageValidator}).
 * Entity references are canonicalized to the entity's declared name; which of the type-specific
 * properties are set depends on {@link #type()}.
 *
 * @param entity       {@link Type#ENTITY_LIST}, {@link Type#RECORD} and {@link Type#REPORT}
 * @param presetFilter {@link Type#ENTITY_LIST} and {@link Type#REPORT} — filter param name -> value
 *                     the page opens with
 * @param widgets      {@link Type#DASHBOARD} only
 * @param tabs         {@link Type#TABS} only
 * @param parent       {@link Type#MASTER_DETAIL} only — the entity listed on the left
 * @param child        {@link Type#MASTER_DETAIL} only — the entity listed for the selected parent
 * @param via          {@link Type#MASTER_DETAIL} only — the child's MANY_TO_ONE field to the parent
 * @param childTabs    {@link Type#RECORD} only — the related lists shown under the record
 * @param charts       {@link Type#REPORT} only — its charts (1–4); the first also gets the totals table
 * @param group        visible pages only — the nav section the page is listed under (null: ungrouped)
 * @param icon         visible pages only — the lucide icon the nav shows (null: the type's default)
 * @param dateRange    {@link Type#DASHBOARD} only — the period its picker opens on (null: no picker)
 * @param steps        {@link Type#WIZARD} only — the create form, step by step
 * @param headerStats  {@link Type#RECORD} only — the number tiles above its tabs
 * @param roles        the roles (any of) that may open the page; empty: everyone
 * @param columns      {@link Type#ENTITY_LIST} only — the columns the list shows, in order (empty:
 *                     every column)
 * @param sort         {@link Type#ENTITY_LIST} only — the column the list opens sorted by (null: the key)
 * @param view         {@link Type#ENTITY_LIST} only — the list view it opens in, one of the entity's
 *                     emitted views (null: the entity's first)
 * @param pageSize     {@link Type#ENTITY_LIST} only — rows per page it opens with (null: the default 20)
 * @param refreshSeconds {@link Type#DASHBOARD} only — how often its widgets reload (null: on request only)
 */
public record PageDefinition(
        String id,
        Type type,
        String title,
        String description,
        boolean hidden,
        String entity,
        Map<String, String> presetFilter,
        List<Widget> widgets,
        List<Tab> tabs,
        String parent,
        String child,
        String via,
        List<ChildTab> childTabs,
        List<Chart> charts,
        String group,
        String icon,
        DateRange dateRange,
        List<Step> steps,
        List<HeaderStat> headerStats,
        List<String> roles,
        List<String> columns,
        ListSort sort,
        String view,
        Integer pageSize,
        Detail detail,
        boolean showParent,
        Integer refreshSeconds,
        Spec spec) {

    public PageDefinition {
        roles = roles == null ? List.of() : List.copyOf(roles);
        columns = columns == null ? List.of() : List.copyOf(columns);
        presetFilter = presetFilter == null ? Map.of() : Map.copyOf(presetFilter);
        widgets = widgets == null ? List.of() : List.copyOf(widgets);
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
        childTabs = childTabs == null ? List.of() : List.copyOf(childTabs);
        charts = charts == null ? List.of() : List.copyOf(charts);
        steps = steps == null ? List.of() : List.copyOf(steps);
        headerStats = headerStats == null ? List.of() : List.copyOf(headerStats);
    }

    /** Every page property but the type-specific {@link Spec} (the page types before calendar,
     *  board, content, import and search). */
    public PageDefinition(String id, Type type, String title, String description, boolean hidden, String entity,
                          Map<String, String> presetFilter, List<Widget> widgets, List<Tab> tabs, String parent,
                          String child, String via, List<ChildTab> childTabs, List<Chart> charts, String group,
                          String icon, DateRange dateRange, List<Step> steps, List<HeaderStat> headerStats,
                          List<String> roles, List<String> columns, ListSort sort, String view, Integer pageSize,
                          Detail detail, boolean showParent, Integer refreshSeconds) {
        this(id, type, title, description, hidden, entity, presetFilter, widgets, tabs, parent, child, via, childTabs,
                charts, group, icon, dateRange, steps, headerStats, roles, columns, sort, view, pageSize, detail,
                showParent, refreshSeconds, null);
    }

    /** Every page property but the list presentation (every column, default sort, view and page size). */
    public PageDefinition(String id, Type type, String title, String description, boolean hidden, String entity,
                          Map<String, String> presetFilter, List<Widget> widgets, List<Tab> tabs, String parent,
                          String child, String via, List<ChildTab> childTabs, List<Chart> charts, String group,
                          String icon, DateRange dateRange, List<Step> steps, List<HeaderStat> headerStats,
                          List<String> roles) {
        this(id, type, title, description, hidden, entity, presetFilter, widgets, tabs, parent, child, via, childTabs,
                charts, group, icon, dateRange, steps, headerStats, roles, null, null, null, null, null, false, null);
    }

    /** Every page property but {@code roles} (open to everyone). */
    public PageDefinition(String id, Type type, String title, String description, boolean hidden, String entity,
                          Map<String, String> presetFilter, List<Widget> widgets, List<Tab> tabs, String parent,
                          String child, String via, List<ChildTab> childTabs, List<Chart> charts, String group,
                          String icon, DateRange dateRange, List<Step> steps, List<HeaderStat> headerStats) {
        this(id, type, title, description, hidden, entity, presetFilter, widgets, tabs, parent, child, via, childTabs,
                charts, group, icon, dateRange, steps, headerStats, null);
    }

    /** A report's first chart — the one its totals table follows; null for any other page. */
    public Chart chart() {
        return charts.isEmpty() ? null : charts.get(0);
    }

    /** Phase-1 page types (no master-detail/record/report props). */
    public PageDefinition(String id, Type type, String title, String description, boolean hidden, String entity,
                          Map<String, String> presetFilter, List<Widget> widgets, List<Tab> tabs) {
        this(id, type, title, description, hidden, entity, presetFilter, widgets, tabs, null, null, null, null, null,
                null, null, null, null, null);
    }

    /** A {@link Type#REPORT} page: one entity, the filters it opens with, and its charts. */
    public PageDefinition(String id, Type type, String title, String description, boolean hidden, String entity,
                          Map<String, String> presetFilter, List<Chart> charts) {
        this(id, type, title, description, hidden, entity, presetFilter, null, null, null, null, null, null, charts,
                null, null, null, null, null);
    }

    /** The same page placed in a nav section and given an icon. */
    public PageDefinition withNav(String group, String icon) {
        return new PageDefinition(id, type, title, description, hidden, entity, presetFilter, widgets, tabs,
                parent, child, via, childTabs, charts, group, icon, dateRange, steps, headerStats, roles,
                columns, sort, view, pageSize, detail, showParent, refreshSeconds, spec);
    }

    /** The same page, open only to users holding one of {@code roles}. */
    public PageDefinition withRoles(List<String> roles) {
        return new PageDefinition(id, type, title, description, hidden, entity, presetFilter, widgets, tabs,
                parent, child, via, childTabs, charts, group, icon, dateRange, steps, headerStats, roles,
                columns, sort, view, pageSize, detail, showParent, refreshSeconds, spec);
    }

    /** The same dashboard with a period picker opening on {@code range}. */
    public PageDefinition withDateRange(DateRange range) {
        return new PageDefinition(id, type, title, description, hidden, entity, presetFilter, widgets, tabs,
                parent, child, via, childTabs, charts, group, icon, range, steps, headerStats, roles,
                columns, sort, view, pageSize, detail, showParent, refreshSeconds, spec);
    }

    /** The same list page opening with these columns, sort, view and page size (each null/empty: the default). */
    public PageDefinition withListPresentation(List<String> columns, ListSort sort, String view, Integer pageSize) {
        return new PageDefinition(id, type, title, description, hidden, entity, presetFilter, widgets, tabs,
                parent, child, via, childTabs, charts, group, icon, dateRange, steps, headerStats, roles,
                columns, sort, view, pageSize, detail, showParent, refreshSeconds, spec);
    }

    /** The same list page opening its rows as {@code detail} says (null: the default). */
    public PageDefinition withDetail(Detail detail) {
        return new PageDefinition(id, type, title, description, hidden, entity, presetFilter, widgets, tabs,
                parent, child, via, childTabs, charts, group, icon, dateRange, steps, headerStats, roles,
                columns, sort, view, pageSize, detail, showParent, refreshSeconds, spec);
    }

    /** The same master-detail page, with (or without) the selected parent's own details above its rows. */
    public PageDefinition withShowParent(boolean showParent) {
        return new PageDefinition(id, type, title, description, hidden, entity, presetFilter, widgets, tabs,
                parent, child, via, childTabs, charts, group, icon, dateRange, steps, headerStats, roles,
                columns, sort, view, pageSize, detail, showParent, refreshSeconds, spec);
    }

    /** The same dashboard, reloading its widgets every {@code seconds} (null: on request only). */
    public PageDefinition withRefreshSeconds(Integer seconds) {
        return new PageDefinition(id, type, title, description, hidden, entity, presetFilter, widgets, tabs,
                parent, child, via, childTabs, charts, group, icon, dateRange, steps, headerStats, roles,
                columns, sort, view, pageSize, detail, showParent, seconds, spec);
    }

    /** The same page carrying its type's own settings. */
    public PageDefinition withSpec(Spec spec) {
        return new PageDefinition(id, type, title, description, hidden, entity, presetFilter, widgets, tabs,
                parent, child, via, childTabs, charts, group, icon, dateRange, steps, headerStats, roles,
                columns, sort, view, pageSize, detail, showParent, refreshSeconds, spec);
    }

    /** A {@link Type#CALENDAR} page's settings; null on any other page. */
    public CalendarSpec calendar() { return spec instanceof CalendarSpec c ? c : null; }

    /** A {@link Type#BOARD} page's settings; null on any other page. */
    public BoardSpec board() { return spec instanceof BoardSpec b ? b : null; }

    /** A {@link Type#CONTENT} page's text; null on any other page. */
    public ContentSpec content() { return spec instanceof ContentSpec c ? c : null; }

    /** A {@link Type#SEARCH} page's settings; null on any other page. */
    public SearchSpec search() { return spec instanceof SearchSpec s ? s : null; }

    /** The settings only one page type has, kept apart so the record does not grow a field per type. */
    public sealed interface Spec permits CalendarSpec, BoardSpec, ContentSpec, SearchSpec {}

    /**
     * @param dateField the date column rows are placed by (and the visible window is fetched through)
     * @param endField  the date column a row ends on (null: rows are one day long); needed by the timeline
     * @param modes     the views the page offers, the first one opening (month, week, agenda, timeline)
     */
    public record CalendarSpec(String dateField, String endField, List<CalendarMode> modes) implements Spec {
        public CalendarSpec {
            modes = List.copyOf(modes);
        }
    }

    public enum CalendarMode {
        MONTH("month"), WEEK("week"), AGENDA("agenda"), TIMELINE("timeline");

        private final String wire;

        CalendarMode(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /**
     * @param laneField  the enum/boolean column the lanes split by
     * @param lanes      the lanes, in order — the field's values
     * @param cardFields what a card shows: field and relation names, the first as its heading
     * @param wipLimits  lane value -> the most cards it may hold (absent: no limit)
     * @param laneSize   the cards a lane loads at a time
     */
    public record BoardSpec(String laneField, List<String> lanes, List<String> cardFields,
                            Map<String, Integer> wipLimits, int laneSize) implements Spec {
        public BoardSpec {
            lanes = List.copyOf(lanes);
            cardFields = List.copyOf(cardFields);
            wipLimits = wipLimits == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(wipLimits));
        }
    }

    /** @param body the page's text in the Markdown subset {@link ContentMarkdown} reads */
    public record ContentSpec(String body) implements Spec {}

    /**
     * @param entities    the entities searched, in the order their results are listed
     * @param perEntity   the matches shown per entity
     * @param shellSearch whether the shell's header has a search box opening this page
     */
    public record SearchSpec(List<String> entities, int perEntity, boolean shellSearch) implements Spec {
        public SearchSpec {
            entities = List.copyOf(entities);
        }
    }

    /** The generated shell's navigation, for a layout: its style and whether sections fold. */
    public record Nav(NavStyle style, boolean collapsibleGroups) {}

    public enum NavStyle {
        SIDEBAR("sidebar"), TOPBAR("topbar");

        private final String wire;

        NavStyle(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** Where an entity-list page opens a row. */
    public enum Detail {
        /** The quick-look drawer over the list. */
        DRAWER("drawer"),
        /** A pane beside the rows, the open row in the route. */
        SIDE("side"),
        /** The entity's record page. */
        RECORD("record");

        private final String wire;

        Detail(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** Whether any of the list presentation properties is set. */
    public boolean hasListPresentation() {
        return !columns.isEmpty() || sort != null || view != null || pageSize != null;
    }

    /** A {@link Type#WIZARD} page: the entity it creates, step by step. */
    public static PageDefinition wizard(String id, String title, String description, boolean hidden, String entity,
                                        List<Step> steps) {
        return new PageDefinition(id, Type.WIZARD, title, description, hidden, entity, null, null, null, null, null,
                null, null, null, null, null, null, steps, null);
    }

    public enum Type {
        ENTITY_LIST("entity-list"),
        DASHBOARD("dashboard"),
        TABS("tabs"),
        /** A parent list beside the child rows of the selected parent. */
        MASTER_DETAIL("master-detail"),
        /** One record (opened by id, never in the navigation) with its related lists as tabs. */
        RECORD("record"),
        /** One entity's filter bar, chart and grouped totals, with a CSV export. */
        REPORT("report"),
        /** A create form for one entity, split into steps, with a review before saving. */
        WIZARD("wizard"),
        /** One entity's rows on a month / week / agenda / timeline by a date field. */
        CALENDAR("calendar"),
        /** One entity's rows as cards in lanes of an enum/boolean field, moved by dragging. */
        BOARD("board"),
        /** Static text — headings, lists, links to other pages — with no entity. */
        CONTENT("content"),
        /** A CSV upload that creates rows of one entity, checked before anything is saved. */
        IMPORT("import"),
        /** One search box over several entities, the matches grouped by entity. */
        SEARCH("search");

        private final String wire;

        Type(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    public enum WidgetKind {
        /** A single-number tile: a record count, or an aggregate of one numeric column. */
        KPI("kpi"),
        /** Records grouped by an enum/boolean field. */
        BAR("bar"),
        /** A time series over a temporal field, bucketed by day/month/year. */
        LINE("line"),
        /** The latest rows, newest first. */
        RECENT("recent"),
        /** The largest groups of an enum/boolean field or a relation, ranked. */
        TOP("top"),
        /** One number against a target, as a bar. */
        PROGRESS("progress"),
        /** Records grouped by an enum/boolean field, as shares of a ring. */
        DONUT("donut"),
        /** Records grouped by an enum/boolean field, each bar split by a second one ({@code series}). */
        STACKED("stacked"),
        /** Static text: a note, a how-to, links spelled out — no entity, no query. */
        TEXT("text"),
        /** Tiles that open other pages of the app — a launcher. No entity, no query. */
        LINKS("links"),
        /** An entity's rows in a card: its list page embedded, with chosen columns, a sort and a filter. */
        LIST("list");

        private final String wire;

        WidgetKind(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** How a chart or tile reduces the rows it covers. {@code COUNT} needs no field; the rest take
     *  a numeric one. */
    public enum Agg {
        COUNT("count"), SUM("sum"), AVG("avg"), MIN("min"), MAX("max");

        private final String wire;

        Agg(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** The granularity a {@link WidgetKind#LINE} (or a date-grouped report) buckets its temporal
     *  field into. Deliberately no week: Hibernate does not register {@code week()} on every
     *  dialect the catalog can generate for. */
    public enum Bucket {
        DAY("day"), MONTH("month"), YEAR("year");

        private final String wire;

        Bucket(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** The periods a dashboard's picker offers, ending today. */
    public enum DateRange {
        ALL("all"), LAST_7_DAYS("7d"), LAST_30_DAYS("30d"), LAST_90_DAYS("90d"), YEAR_TO_DATE("ytd"),
        LAST_12_MONTHS("12m");

        private final String wire;

        DateRange(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /**
     * @param groupBy      {@link WidgetKind#BAR} (enum/boolean) and {@link WidgetKind#LINE} (temporal)
     * @param bucket       {@link WidgetKind#LINE} only
     * @param field        the numeric column {@code agg} reduces; null when {@code agg} is COUNT
     * @param limit        {@link WidgetKind#RECENT} only
     * @param span         the dashboard grid columns the widget takes (1–4)
     * @param presetFilter enum/boolean field -> value the widget is limited to
     * @param sortBy       {@link WidgetKind#RECENT} only — the column it orders by, newest first
     *                     (null: the primary key)
     * @param dateField    the date column the dashboard's period picker limits (null: the widget
     *                     is not limited, because the dashboard has no picker or the entity no date)
     * @param compare      {@link WidgetKind#KPI} only — also show the change against the previous period
     * @param target       {@link WidgetKind#PROGRESS} only — the value the bar fills up to
     */
    public record Widget(WidgetKind kind, String entity, String title, String groupBy, int limit,
                         Agg agg, String field, Bucket bucket, int span, Map<String, String> presetFilter,
                         String sortBy, String dateField, boolean compare, java.math.BigDecimal target,
                         String series, String text, List<String> pages, List<String> columns, ListSort sort) {

        public Widget {
            presetFilter = presetFilter == null ? Map.of() : Map.copyOf(presetFilter);
            pages = pages == null ? List.of() : List.copyOf(pages);
            columns = columns == null ? List.of() : List.copyOf(columns);
        }

        /** A widget of the kinds before links and lists (no page ids, columns or sort). */
        public Widget(WidgetKind kind, String entity, String title, String groupBy, int limit,
                      Agg agg, String field, Bucket bucket, int span, Map<String, String> presetFilter,
                      String sortBy, String dateField, boolean compare, java.math.BigDecimal target,
                      String series, String text) {
            this(kind, entity, title, groupBy, limit, agg, field, bucket, span, presetFilter, sortBy, dateField,
                    compare, target, series, text, null, null, null);
        }

        /** A widget of the kinds before donut/stacked/text (no series, no text). */
        public Widget(WidgetKind kind, String entity, String title, String groupBy, int limit,
                      Agg agg, String field, Bucket bucket, int span, Map<String, String> presetFilter,
                      String sortBy, String dateField, boolean compare, java.math.BigDecimal target) {
            this(kind, entity, title, groupBy, limit, agg, field, bucket, span, presetFilter, sortBy, dateField,
                    compare, target, null, null);
        }

        /** A widget with the default span of its kind and no filter, sort or date field. */
        public Widget(WidgetKind kind, String entity, String title, String groupBy, int limit,
                      Agg agg, String field, Bucket bucket) {
            this(kind, entity, title, groupBy, limit, agg, field, bucket, defaultSpan(kind), null, null, null,
                    false, null);
        }

        /** A number tile (plain or against a target) takes one grid column; charts and lists two;
         *  a launcher the whole row. */
        public static int defaultSpan(WidgetKind kind) {
            return kind == WidgetKind.KPI || kind == WidgetKind.PROGRESS ? 1
                    : kind == WidgetKind.LINKS || kind == WidgetKind.LIST ? 4 : 2;
        }
    }

    /** @param page id of the embedded page (never a {@link Type#TABS} or {@link Type#RECORD} page) */
    public record Tab(String title, String page) {}

    /** The column a list page opens sorted by, and whether descending. */
    public record ListSort(String field, boolean desc) {}

    /**
     * One step of a wizard.
     *
     * @param fields the form fields it asks for — field names, and relation field names for the
     *               relation pickers — in the order the form lays them out
     */
    public record Step(String title, List<String> fields) {
        public Step {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /** A number tile above a record page's tabs: {@code agg} (over {@code field}) of the
     *  {@code child} rows whose {@code via} relation points at the record. */
    public record HeaderStat(String child, String via, Agg agg, String field, String title) {}

    /** A related list under a record page: {@code entity} rows whose {@code via} relation points at the record. */
    /** @param columns the related list's columns, in order (empty: every column); @param sort its opening sort */
    public record ChildTab(String entity, String via, List<String> columns, ListSort sort) {

        public ChildTab {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }

        public ChildTab(String entity, String via) {
            this(entity, via, null, null);
        }

        /** Whether the list opens other than by default (so its entity page takes the props). */
        public boolean hasListPresentation() {
            return !columns.isEmpty() || sort != null;
        }
    }

    /**
     * A report's chart: rows grouped by {@code groupBy} — an enum/boolean field (a bar) or a
     * temporal one (a line, bucketed) — and reduced by {@code agg} over {@code field}.
     *
     * @param bucket set only when {@code groupBy} is temporal
     */
    /** @param table the grouped totals table under the chart; null: under a report's first chart only */
    public record Chart(String groupBy, Bucket bucket, Agg agg, String field, Boolean table) {

        public Chart(String groupBy, Bucket bucket, Agg agg, String field) {
            this(groupBy, bucket, agg, field, null);
        }
    }
}
