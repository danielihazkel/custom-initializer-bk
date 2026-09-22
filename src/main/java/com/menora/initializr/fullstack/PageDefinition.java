package com.menora.initializr.fullstack;

import java.util.List;
import java.util.Map;

/**
 * One validated page of the generated fullstack frontend (see {@link FullstackPageValidator}).
 * Entity references are canonicalized to the entity's declared name; which of the type-specific
 * properties are set depends on {@link #type()}.
 *
 * @param entity       {@link Type#ENTITY_LIST} only
 * @param presetFilter {@link Type#ENTITY_LIST} only — filter param name -> value the page opens with
 * @param widgets      {@link Type#DASHBOARD} only
 * @param tabs         {@link Type#TABS} only
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
        List<Tab> tabs) {

    public PageDefinition {
        presetFilter = presetFilter == null ? Map.of() : Map.copyOf(presetFilter);
        widgets = widgets == null ? List.of() : List.copyOf(widgets);
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
    }

    public enum Type {
        ENTITY_LIST("entity-list"),
        DASHBOARD("dashboard"),
        TABS("tabs");

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

    /** @param page id of the embedded page (never itself a {@link Type#TABS} page) */
    public record Tab(String title, String page) {}
}
