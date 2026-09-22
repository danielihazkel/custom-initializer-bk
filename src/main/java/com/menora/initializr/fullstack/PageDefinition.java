package com.menora.initializr.fullstack;

import java.util.List;
import java.util.Map;

/**
 * One validated page of the generated fullstack frontend (see {@link FullstackPageValidator}).
 * Entity references are canonicalized to the entity's declared name; which of the type-specific
 * properties are set depends on {@link #type()}.
 *
 * @param entity       {@link Type#ENTITY_LIST} and {@link Type#RECORD}
 * @param presetFilter {@link Type#ENTITY_LIST} only — filter param name -> value the page opens with
 * @param widgets      {@link Type#DASHBOARD} only
 * @param tabs         {@link Type#TABS} only
 * @param parent       {@link Type#MASTER_DETAIL} only — the entity listed on the left
 * @param child        {@link Type#MASTER_DETAIL} only — the entity listed for the selected parent
 * @param via          {@link Type#MASTER_DETAIL} only — the child's MANY_TO_ONE field to the parent
 * @param childTabs    {@link Type#RECORD} only — the related lists shown under the record
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
        List<ChildTab> childTabs) {

    public PageDefinition {
        presetFilter = presetFilter == null ? Map.of() : Map.copyOf(presetFilter);
        widgets = widgets == null ? List.of() : List.copyOf(widgets);
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
        childTabs = childTabs == null ? List.of() : List.copyOf(childTabs);
    }

    /** Phase-1 page types (no master-detail/record props). */
    public PageDefinition(String id, Type type, String title, String description, boolean hidden, String entity,
                          Map<String, String> presetFilter, List<Widget> widgets, List<Tab> tabs) {
        this(id, type, title, description, hidden, entity, presetFilter, widgets, tabs, null, null, null, null);
    }

    public enum Type {
        ENTITY_LIST("entity-list"),
        DASHBOARD("dashboard"),
        TABS("tabs"),
        /** A parent list beside the child rows of the selected parent. */
        MASTER_DETAIL("master-detail"),
        /** One record (opened by id, never in the navigation) with its related lists as tabs. */
        RECORD("record");

        private final String wire;

        Type(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    public enum WidgetKind {
        /** A record-count tile. */
        KPI("kpi"),
        /** Record count grouped by an enum/boolean field. */
        BAR("bar"),
        /** The latest rows, newest first. */
        RECENT("recent");

        private final String wire;

        WidgetKind(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** @param groupBy {@link WidgetKind#BAR} only; @param limit {@link WidgetKind#RECENT} only */
    public record Widget(WidgetKind kind, String entity, String title, String groupBy, int limit) {}

    /** @param page id of the embedded page (never a {@link Type#TABS} or {@link Type#RECORD} page) */
    public record Tab(String title, String page) {}

    /** A related list under a record page: {@code entity} rows whose {@code via} relation points at the record. */
    public record ChildTab(String entity, String via) {}
}
