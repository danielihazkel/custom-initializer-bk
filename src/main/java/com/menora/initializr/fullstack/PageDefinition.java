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
        List<HeaderStat> headerStats) {

    public PageDefinition {
        presetFilter = presetFilter == null ? Map.of() : Map.copyOf(presetFilter);
        widgets = widgets == null ? List.of() : List.copyOf(widgets);
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
        childTabs = childTabs == null ? List.of() : List.copyOf(childTabs);
        charts = charts == null ? List.of() : List.copyOf(charts);
        steps = steps == null ? List.of() : List.copyOf(steps);
        headerStats = headerStats == null ? List.of() : List.copyOf(headerStats);
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
                parent, child, via, childTabs, charts, group, icon, dateRange, steps, headerStats);
    }

    /** The same dashboard with a period picker opening on {@code range}. */
    public PageDefinition withDateRange(DateRange range) {
        return new PageDefinition(id, type, title, description, hidden, entity, presetFilter, widgets, tabs,
                parent, child, via, childTabs, charts, group, icon, range, steps, headerStats);
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
        WIZARD("wizard");

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
        PROGRESS("progress");

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
                         String sortBy, String dateField, boolean compare, java.math.BigDecimal target) {

        public Widget {
            presetFilter = presetFilter == null ? Map.of() : Map.copyOf(presetFilter);
        }

        /** A widget with the default span of its kind and no filter, sort or date field. */
        public Widget(WidgetKind kind, String entity, String title, String groupBy, int limit,
                      Agg agg, String field, Bucket bucket) {
            this(kind, entity, title, groupBy, limit, agg, field, bucket, defaultSpan(kind), null, null, null,
                    false, null);
        }

        /** A number tile (plain or against a target) takes one grid column; charts and lists two. */
        public static int defaultSpan(WidgetKind kind) {
            return kind == WidgetKind.KPI || kind == WidgetKind.PROGRESS ? 1 : 2;
        }
    }

    /** @param page id of the embedded page (never a {@link Type#TABS} or {@link Type#RECORD} page) */
    public record Tab(String title, String page) {}

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
    public record ChildTab(String entity, String via) {}

    /**
     * A report's chart: rows grouped by {@code groupBy} — an enum/boolean field (a bar) or a
     * temporal one (a line, bucketed) — and reduced by {@code agg} over {@code field}.
     *
     * @param bucket set only when {@code groupBy} is temporal
     */
    public record Chart(String groupBy, Bucket bucket, Agg agg, String field) {}
}
