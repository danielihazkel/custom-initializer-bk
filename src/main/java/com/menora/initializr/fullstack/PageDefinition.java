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
 * @param chart        {@link Type#REPORT} only — the single chart the report is built around
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
        Chart chart) {

    public PageDefinition {
        presetFilter = presetFilter == null ? Map.of() : Map.copyOf(presetFilter);
        widgets = widgets == null ? List.of() : List.copyOf(widgets);
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
        childTabs = childTabs == null ? List.of() : List.copyOf(childTabs);
    }

    /** Phase-1 page types (no master-detail/record/report props). */
    public PageDefinition(String id, Type type, String title, String description, boolean hidden, String entity,
                          Map<String, String> presetFilter, List<Widget> widgets, List<Tab> tabs) {
        this(id, type, title, description, hidden, entity, presetFilter, widgets, tabs, null, null, null, null, null);
    }

    /** A {@link Type#REPORT} page: one entity, the filters it opens with, and its chart. */
    public PageDefinition(String id, Type type, String title, String description, boolean hidden, String entity,
                          Map<String, String> presetFilter, Chart chart) {
        this(id, type, title, description, hidden, entity, presetFilter, null, null, null, null, null, null, chart);
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
        REPORT("report");

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
        RECENT("recent");

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

    /**
     * @param groupBy {@link WidgetKind#BAR} (enum/boolean) and {@link WidgetKind#LINE} (temporal)
     * @param bucket  {@link WidgetKind#LINE} only
     * @param field   the numeric column {@code agg} reduces; null when {@code agg} is COUNT
     * @param limit   {@link WidgetKind#RECENT} only
     */
    public record Widget(WidgetKind kind, String entity, String title, String groupBy, int limit,
                         Agg agg, String field, Bucket bucket) {}

    /** @param page id of the embedded page (never a {@link Type#TABS} or {@link Type#RECORD} page) */
    public record Tab(String title, String page) {}

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
