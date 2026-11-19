package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;
import com.menora.initializr.fullstack.FullstackStarterRequest.WidgetDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.menora.initializr.fullstack.FullstackPageValidator.*;
import static com.menora.initializr.fullstack.PageListRules.*;
import static com.menora.initializr.fullstack.PagePresets.*;
import static com.menora.initializr.fullstack.ReportChartRules.*;
import static com.menora.initializr.fullstack.PageStructureRules.*;
import static com.menora.initializr.fullstack.PageChecks.*;

/** A dashboard's widgets, its filters and where a widget drills to. Part of {@link FullstackPageValidator}. */
final class DashboardWidgetRules {

    private DashboardWidgetRules() {}

    static List<PageDefinition.Widget> widgets(String id, List<WidgetDto> raw,
                                               Map<String, EntityDefinition> entitiesByLower,
                                               boolean hasDateRange,
                                               Map<String, PageDefinition.Type> typeById,
                                               Map<String, Boolean> hiddenById,
                                               Map<String, String> entityById,
                                               Set<String> scaffoldOpts) {
        if (raw == null || raw.isEmpty()) {
            throw new WizardArgumentException("Page '" + id + "' (dashboard) needs at least one widget");
        }
        if (raw.size() > MAX_WIDGETS) {
            throw new WizardArgumentException("Page '" + id + "' has more than " + MAX_WIDGETS + " widgets");
        }
        List<PageDefinition.Widget> out = new ArrayList<>(raw.size());
        for (int wi = 0; wi < raw.size(); wi++) {
            try {
                WidgetDto w = raw.get(wi);
                String prefix = "Page '" + id + "' widgets[" + wi + "]";
                if (w == null) throw new WizardArgumentException(prefix + " is null");
                PageDefinition.WidgetKind kind = parseKind(prefix, w.kind());
                if (kind == PageDefinition.WidgetKind.ACTIVITY) {
                    out.add(activityWidget(prefix, w, entitiesByLower, scaffoldOpts));
                    continue;
                }
                if (w.entities() != null && !w.entities().isEmpty()) {
                    throw new WizardArgumentException(prefix + ": only an activity widget takes 'entities'");
                }
                if (kind == PageDefinition.WidgetKind.TEXT) {
                    out.add(textWidget(prefix, w));
                    continue;
                }
                if (kind == PageDefinition.WidgetKind.LINKS) {
                    out.add(linksWidget(prefix, w, typeById, hiddenById));
                    continue;
                }
                if (kind == PageDefinition.WidgetKind.LIST) {
                    PageDefinition.Widget list = listWidget(prefix, w, entitiesByLower, scaffoldOpts);
                    out.add(withDrillTo(list, drillTo(prefix, w.drillTo(), list.entity(), typeById, hiddenById, entityById)));
                    continue;
                }
                if (trimToNull(w.text()) != null) {
                    throw new WizardArgumentException(prefix + ": only a text widget takes 'text'");
                }
                if (kind == PageDefinition.WidgetKind.TIME_IN_STATE) {
                    out.add(timeInStateWidget(prefix, w, entitiesByLower));
                    continue;
                }
                if (w.pages() != null && !w.pages().isEmpty()) {
                    throw new WizardArgumentException(prefix + ": only a links widget takes 'pages'");
                }
                if ((w.columns() != null && !w.columns().isEmpty()) || w.sort() != null) {
                    throw new WizardArgumentException(prefix + ": only a list widget takes 'columns' and 'sort'");
                }
                EntityDefinition entity = requireEntity(entitiesByLower, w.entity(), prefix);
                String title = checkLength(trimToNull(w.title()), MAX_TITLE, prefix + " title");
                boolean reduces = kind != PageDefinition.WidgetKind.RECENT;
                // A tile against a target: the same reduction as a kpi, and the target it fills up to.
                java.math.BigDecimal target = null;
                if (kind == PageDefinition.WidgetKind.PROGRESS || kind == PageDefinition.WidgetKind.GAUGE) {
                    target = progressTarget(prefix, trimToNull(w.target()));
                } else if (trimToNull(w.target()) != null) {
                    throw new WizardArgumentException(prefix + ": only a progress or gauge widget takes 'target'");
                }
                PageDefinition.Agg agg = null;
                String field = null;
                if (reduces) {
                    agg = parseAgg(prefix, w.agg());
                    field = aggField(prefix, entity, agg, trimToNull(w.field()));
                    // A stage of a funnel is that stage and every later one, added up: only a count
                    // or a sum adds up.
                    if (kind == PageDefinition.WidgetKind.FUNNEL && agg != null
                            && agg != PageDefinition.Agg.COUNT && agg != PageDefinition.Agg.SUM) {
                        throw new WizardArgumentException(prefix + ": a funnel counts rows or sums a field — '"
                                + agg.wire() + "' does not add up across stages").on("agg");
                    }
                } else {
                    if (trimToNull(w.agg()) != null) {
                        throw new WizardArgumentException(prefix + ": a recent widget lists rows, so it takes no 'agg'");
                    }
                    if (trimToNull(w.field()) != null) {
                        throw new WizardArgumentException(prefix + ": a recent widget lists rows, so it takes no 'field'");
                    }
                }
                String groupBy = null;
                String series = null;
                PageDefinition.Bucket bucket = null;
                switch (kind) {
                    case BAR, DONUT -> groupBy = rankBy(prefix, entity, trimToNull(w.groupBy()), "group by");
                    case STACKED -> {
                        groupBy = groupBy(prefix, entity, trimToNull(w.groupBy()));
                        series = series(prefix, entity, groupBy, trimToNull(w.series()));
                    }
                    case TOP -> groupBy = rankBy(prefix, entity, trimToNull(w.groupBy()), "rank by");
                    case FUNNEL -> groupBy = funnelStages(prefix, entity, trimToNull(w.groupBy()));
                    case PIVOT -> {
                        groupBy = rankBy(prefix, entity, trimToNull(w.groupBy()), "lay out in rows");
                        series = pivotColumns(prefix, entity, groupBy, trimToNull(w.series()));
                    }
                    case LINE -> {
                        groupBy = dateGroupBy(prefix, entity, trimToNull(w.groupBy()));
                        bucket = parseBucket(prefix, w.bucket());
                    }
                    default -> {
                        if (trimToNull(w.groupBy()) != null) {
                            throw new WizardArgumentException(prefix + ": only a bar, donut, stacked, pivot, funnel, line or top widget takes 'groupBy'");
                        }
                    }
                }
                if (kind != PageDefinition.WidgetKind.STACKED && kind != PageDefinition.WidgetKind.PIVOT
                        && trimToNull(w.series()) != null) {
                    throw new WizardArgumentException(prefix + ": only a stacked or pivot widget takes 'series'");
                }
                if (kind != PageDefinition.WidgetKind.LINE && trimToNull(w.bucket()) != null) {
                    throw new WizardArgumentException(prefix + ": only a line widget takes 'bucket'");
                }
                // A kpi's sparkline: kept in the bucket and groupBy a kpi has no other use for.
                String rawTrend = trimToNull(w.trend());
                if (rawTrend != null) {
                    if (kind != PageDefinition.WidgetKind.KPI) {
                        throw new WizardArgumentException(prefix + ": only a kpi widget takes 'trend'");
                    }
                    bucket = parseBucket(prefix, rawTrend, "trend");
                }
                int limit = 0;
                if (kind == PageDefinition.WidgetKind.RECENT || kind == PageDefinition.WidgetKind.TOP) {
                    limit = w.limit() == null ? DEFAULT_RECENT_LIMIT : w.limit();
                    if (limit < 1 || limit > MAX_RECENT_LIMIT) {
                        throw new WizardArgumentException(prefix + ": limit must be between 1 and " + MAX_RECENT_LIMIT);
                    }
                } else if (w.limit() != null) {
                    throw new WizardArgumentException(prefix + ": only a recent or top widget takes 'limit'");
                }
                int span = w.span() == null ? PageDefinition.Widget.defaultSpan(kind) : w.span();
                if (span < 1 || span > MAX_SPAN) {
                    throw new WizardArgumentException(prefix + ": span must be between 1 and " + MAX_SPAN);
                }
                String sortBy = null;
                if (kind == PageDefinition.WidgetKind.RECENT) {
                    sortBy = sortBy(prefix, entity, trimToNull(w.sortBy()), auditApplies(entity, scaffoldOpts));
                } else if (trimToNull(w.sortBy()) != null) {
                    throw new WizardArgumentException(prefix + ": only a recent widget takes 'sortBy'");
                }
                // Without a picker, a trend's dateField only names the date it plots over.
                boolean trendDate = bucket != null && kind == PageDefinition.WidgetKind.KPI && !hasDateRange;
                String dateField = trendDate ? null : dateField(prefix, entity, trimToNull(w.dateField()), hasDateRange);
                if (bucket != null && kind == PageDefinition.WidgetKind.KPI) {
                    groupBy = trendOn(prefix, entity, dateField, trendDate ? trimToNull(w.dateField()) : null);
                }
                boolean compare = Boolean.TRUE.equals(w.compare());
                if (compare) {
                    if (kind != PageDefinition.WidgetKind.KPI) {
                        throw new WizardArgumentException(prefix + ": only a kpi widget takes 'compare'");
                    }
                    // The previous period is the picker's, over the widget's date.
                    if (dateField == null) {
                        throw new WizardArgumentException(prefix + ": 'compare' needs the dashboard's dateRange and a"
                                + " filterable date field on " + entity.name() + " to compare periods by");
                    }
                }
                out.add(new PageDefinition.Widget(kind, entity.name(), title, groupBy, limit, agg, field, bucket, span,
                        presetFilter(prefix, entity, w.presetFilter(), entitiesByLower), sortBy, dateField, compare, target, series, null,
                        null, null, null, drillTo(prefix, w.drillTo(), entity.name(), typeById, hiddenById, entityById)));
            } catch (WizardArgumentException problem) {
                throw problem.at("widgets[" + wi + "]");
            }
        }
        return out;
    }

    private static PageDefinition.Widget withDrillTo(PageDefinition.Widget w, String drillTo) {
        return new PageDefinition.Widget(w.kind(), w.entity(), w.title(), w.groupBy(), w.limit(), w.agg(), w.field(),
                w.bucket(), w.span(), w.presetFilter(), w.sortBy(), w.dateField(), w.compare(), w.target(), w.series(),
                w.text(), w.pages(), w.columns(), w.sort(), drillTo);
    }

    /**
     * The page a widget's clicks and "View all" open instead of its entity's own list: a visible
     * entity-list or report page of the same entity — a page that shows those rows and takes their
     * filters from the route. Null when not given.
     */
    static String drillTo(String prefix, String raw, String entity,
                          Map<String, PageDefinition.Type> typeById, Map<String, Boolean> hiddenById,
                          Map<String, String> entityById) {
        String id = trimToNull(raw);
        if (id == null) return null;
        PageDefinition.Type type = typeById.get(id);
        if (type == null) throw new WizardArgumentException(prefix + ": drillTo names no page with id '" + id + "'");
        if (type != PageDefinition.Type.ENTITY_LIST && type != PageDefinition.Type.REPORT) {
            throw new WizardArgumentException(prefix + ": drillTo '" + id + "' must be an entity-list or report page");
        }
        if (Boolean.TRUE.equals(hiddenById.get(id))) {
            throw new WizardArgumentException(prefix + ": drillTo '" + id + "' is hidden, so it cannot be opened from a widget");
        }
        if (!entity.equals(entityById.get(id))) {
            throw new WizardArgumentException(prefix + ": drillTo '" + id + "' shows " + entityById.get(id)
                    + ", not " + entity);
        }
        return id;
    }

    /**
     * A dashboard's filters: each an enum, boolean or MANY_TO_ONE name (matched ignoring case) that
     * at least one of its data or list widgets' entities filters by — and wherever several entities
     * have it, the same kind of filter (the same enum constants, the same relation target), since
     * one control sets them all. Returns the declared spellings.
     */
    static List<String> dashboardFilters(String id, List<String> raw, List<PageDefinition.Widget> widgets,
                                         Map<String, EntityDefinition> entitiesByLower) {
        if (raw == null || raw.isEmpty()) return List.of();
        String prefix = "Page '" + id + "'";
        if (raw.size() > MAX_DASHBOARD_FILTERS) {
            throw new WizardArgumentException(prefix + " has more than " + MAX_DASHBOARD_FILTERS + " filters");
        }
        Set<String> entities = new LinkedHashSet<>();
        for (PageDefinition.Widget w : widgets) {
            if (w.entity() != null && w.kind() != PageDefinition.WidgetKind.LINKS && w.kind() != PageDefinition.WidgetKind.TEXT) {
                entities.add(w.entity());
            }
        }
        List<String> out = new ArrayList<>();
        for (String requested : raw) {
            String name = trimToNull(requested);
            if (name == null) throw new WizardArgumentException(prefix + ": a filter name is blank");
            String canonical = null;
            String shape = null;
            String shapeOwner = null;
            for (String entityName : entities) {
                EntityDefinition e = entitiesByLower.get(entityName.toLowerCase(Locale.ROOT));
                String found = null;
                String thisShape = null;
                for (FieldDefinition f : e.fields()) {
                    if (f.name().equalsIgnoreCase(name) && !f.primaryKey() && f.filterable()
                            && (f.type().isEnum() || f.type().isBoolean())) {
                        found = f.name();
                        thisShape = f.type().isBoolean() ? "a yes/no field"
                                : "the values " + String.join(", ", f.enumValues());
                    }
                }
                for (RelationDefinition r : e.relations()) {
                    if (r.fieldName().equalsIgnoreCase(name)) {
                        found = r.fieldName();
                        thisShape = "a link to " + r.targetEntity();
                    }
                }
                if (found == null) continue;
                if (canonical == null) {
                    canonical = found;
                    shape = thisShape;
                    shapeOwner = e.name();
                } else if (!shape.equals(thisShape)) {
                    throw new WizardArgumentException(prefix + ": filter '" + name + "' is " + shape + " on " + shapeOwner
                            + " but " + thisShape + " on " + e.name() + " — one control cannot set both");
                }
            }
            if (canonical == null) {
                throw new WizardArgumentException(prefix + ": filter '" + name + "' is not an enum, boolean or relation"
                        + " filter of any of its widgets' entities (" + String.join(", ", entities) + ")");
            }
            for (String seen : out) {
                if (seen.equalsIgnoreCase(canonical)) {
                    throw new WizardArgumentException(prefix + ": filter '" + canonical + "' is listed twice");
                }
            }
            out.add(canonical);
        }
        return out;
    }

    /** A text widget: its content and optional title and width — nothing that queries an entity. */
    /**
     * A funnel's stages: an enum field, whose values in their own order are the pipeline — named,
     * else the workflow's status, else the entity's first enum.
     */
    private static String funnelStages(String prefix, EntityDefinition entity, String raw) {
        String wanted = raw != null ? raw : entity.workflow() != null ? entity.workflow().statusField() : null;
        String resolved = groupBy(prefix, entity, wanted);
        boolean isEnum = entity.fields().stream().anyMatch(f -> f.name().equals(resolved) && f.type() == FieldType.ENUM);
        if (!isEnum) {
            throw new WizardArgumentException(prefix + ": a funnel's stages are an enum field's values, in their order — '"
                    + resolved + "' is not an enum").on("groupBy");
        }
        return resolved;
    }

    /**
     * A time-in-state widget: an entity whose workflow keeps a log of its moves, a title and a
     * span. It reads the log — every row ever moved — so nothing that filters a list applies.
     */
    private static PageDefinition.Widget timeInStateWidget(String prefix, WidgetDto w,
                                                           Map<String, EntityDefinition> entitiesByLower) {
        EntityDefinition entity = requireEntity(entitiesByLower, w.entity(), prefix);
        if (entity.workflow() == null || !entity.workflow().trackMoves()) {
            throw new WizardArgumentException(prefix + ": a timeInState widget reads the workflow's move log — "
                    + entity.name() + (entity.workflow() == null ? " has no workflow" : "'s workflow does not keep one (trackMoves)"))
                    .on("entity");
        }
        if (trimToNull(w.agg()) != null || trimToNull(w.field()) != null || trimToNull(w.groupBy()) != null
                || trimToNull(w.bucket()) != null || w.limit() != null
                || (w.presetFilter() != null && !w.presetFilter().isEmpty()) || trimToNull(w.sortBy()) != null
                || trimToNull(w.dateField()) != null || Boolean.TRUE.equals(w.compare()) || trimToNull(w.target()) != null
                || trimToNull(w.series()) != null || (w.pages() != null && !w.pages().isEmpty())
                || (w.columns() != null && !w.columns().isEmpty()) || w.sort() != null || trimToNull(w.drillTo()) != null
                || trimToNull(w.trend()) != null) {
            throw new WizardArgumentException(prefix + ": a timeInState widget takes only 'entity', 'title' and 'span'");
        }
        int span = w.span() == null ? PageDefinition.Widget.defaultSpan(PageDefinition.WidgetKind.TIME_IN_STATE) : w.span();
        if (span < 1 || span > MAX_SPAN) {
            throw new WizardArgumentException(prefix + ": span must be between 1 and " + MAX_SPAN);
        }
        return new PageDefinition.Widget(PageDefinition.WidgetKind.TIME_IN_STATE, entity.name(),
                checkLength(trimToNull(w.title()), MAX_TITLE, prefix + " title"), null, 0, null, null, null, span,
                null, null, null, false, null);
    }

    private static PageDefinition.Widget textWidget(String prefix, WidgetDto w) {
        String text = trimToNull(w.text());
        if (text == null) throw new WizardArgumentException(prefix + ": a text widget needs 'text'");
        text = checkLength(text.replace("\r\n", "\n"), MAX_TEXT, prefix + " text");
        if (trimToNull(w.entity()) != null || trimToNull(w.agg()) != null || trimToNull(w.field()) != null
                || trimToNull(w.groupBy()) != null || trimToNull(w.bucket()) != null || w.limit() != null
                || (w.presetFilter() != null && !w.presetFilter().isEmpty()) || trimToNull(w.sortBy()) != null
                || trimToNull(w.dateField()) != null || Boolean.TRUE.equals(w.compare()) || trimToNull(w.target()) != null
                || trimToNull(w.series()) != null || (w.pages() != null && !w.pages().isEmpty())
                || (w.columns() != null && !w.columns().isEmpty()) || w.sort() != null || trimToNull(w.drillTo()) != null || trimToNull(w.trend()) != null) {
            throw new WizardArgumentException(prefix + ": a text widget takes only 'text', 'title' and 'span'");
        }
        int span = w.span() == null ? PageDefinition.Widget.defaultSpan(PageDefinition.WidgetKind.TEXT) : w.span();
        if (span < 1 || span > MAX_SPAN) {
            throw new WizardArgumentException(prefix + ": span must be between 1 and " + MAX_SPAN);
        }
        return new PageDefinition.Widget(PageDefinition.WidgetKind.TEXT, null,
                checkLength(trimToNull(w.title()), MAX_TITLE, prefix + " title"), null, 0, null, null, null, span,
                null, null, null, false, null, null, text);
    }

    /** A links widget: the pages its tiles open — each a page of the layout that can be opened
     *  from a link (not a record page, which needs a row; not a hidden page, unless it is a wizard,
     *  which is a route of its own) — plus an optional title and width. */
    private static PageDefinition.Widget linksWidget(String prefix, WidgetDto w,
                                                     Map<String, PageDefinition.Type> typeById,
                                                     Map<String, Boolean> hiddenById) {
        if (w.pages() == null || w.pages().isEmpty()) {
            throw new WizardArgumentException(prefix + ": a links widget needs 'pages' (1–" + MAX_LINKS + " page ids)");
        }
        if (w.pages().size() > MAX_LINKS) {
            throw new WizardArgumentException(prefix + ": at most " + MAX_LINKS + " pages are allowed");
        }
        List<String> pages = new ArrayList<>();
        for (String raw : w.pages()) {
            String id = trimToNull(raw);
            if (id == null) throw new WizardArgumentException(prefix + ": a page id is blank");
            PageDefinition.Type type = typeById.get(id);
            if (type == null) throw new WizardArgumentException(prefix + ": no page with id '" + id + "'");
            if (type == PageDefinition.Type.RECORD) {
                throw new WizardArgumentException(prefix + ": a links widget cannot link to a record page ('" + id
                        + "') — it opens from a row");
            }
            // A hidden wizard or form is still a route (New opens it), and so is a hidden search page
            // (the header's search box opens it — a hidden one must have that box).
            if (Boolean.TRUE.equals(hiddenById.get(id)) && type != PageDefinition.Type.WIZARD
                    && type != PageDefinition.Type.FORM && type != PageDefinition.Type.SEARCH) {
                throw new WizardArgumentException(prefix + ": page '" + id + "' is hidden and not a wizard, so nothing can open it");
            }
            if (pages.contains(id)) throw new WizardArgumentException(prefix + ": page '" + id + "' is listed twice");
            pages.add(id);
        }
        if (trimToNull(w.entity()) != null || trimToNull(w.agg()) != null || trimToNull(w.field()) != null
                || trimToNull(w.groupBy()) != null || trimToNull(w.bucket()) != null || w.limit() != null
                || (w.presetFilter() != null && !w.presetFilter().isEmpty()) || trimToNull(w.sortBy()) != null
                || trimToNull(w.dateField()) != null || Boolean.TRUE.equals(w.compare()) || trimToNull(w.target()) != null
                || trimToNull(w.series()) != null || trimToNull(w.text()) != null
                || (w.columns() != null && !w.columns().isEmpty()) || w.sort() != null || trimToNull(w.drillTo()) != null || trimToNull(w.trend()) != null) {
            throw new WizardArgumentException(prefix + ": a links widget takes only 'pages', 'title' and 'span'");
        }
        int span = w.span() == null ? PageDefinition.Widget.defaultSpan(PageDefinition.WidgetKind.LINKS) : w.span();
        if (span < 1 || span > MAX_SPAN) {
            throw new WizardArgumentException(prefix + ": span must be between 1 and " + MAX_SPAN);
        }
        return new PageDefinition.Widget(PageDefinition.WidgetKind.LINKS, null,
                checkLength(trimToNull(w.title()), MAX_TITLE, prefix + " title"), null, 0, null, null, null, span,
                null, null, null, false, null, null, null, pages, null, null);
    }

    /** A list widget: an entity's list page in a card, opening with the same columns, sort and
     *  filter an entity-list page can name, and a page size of 10 or 20 rows (the pager's sizes, so
     *  the embedded pager stays consistent). */
    private static PageDefinition.Widget listWidget(String prefix, WidgetDto w,
                                                    Map<String, EntityDefinition> entitiesByLower,
                                                    Set<String> scaffoldOpts) {
        EntityDefinition entity = requireEntity(entitiesByLower, w.entity(), prefix);
        if (trimToNull(w.agg()) != null || trimToNull(w.field()) != null || trimToNull(w.groupBy()) != null
                || trimToNull(w.bucket()) != null || trimToNull(w.sortBy()) != null || trimToNull(w.dateField()) != null
                || Boolean.TRUE.equals(w.compare()) || trimToNull(w.target()) != null || trimToNull(w.series()) != null
                || trimToNull(w.text()) != null || (w.pages() != null && !w.pages().isEmpty())
                || trimToNull(w.trend()) != null) {
            throw new WizardArgumentException(prefix
                    + ": a list widget takes only 'entity', 'columns', 'sort', 'presetFilter', 'limit', 'title' and 'span'");
        }
        boolean audit = auditApplies(entity, scaffoldOpts);
        List<String> columns = columns(prefix, entity, w.columns(), audit);
        PageDefinition.ListSort sort = sort(prefix, entity, w.sort(), audit);
        int limit = w.limit() == null ? DEFAULT_LIST_LIMIT : w.limit();
        if (limit != 10 && limit != 20) {
            throw new WizardArgumentException(prefix + ": limit must be 10 or 20 (the list pager's sizes)");
        }
        int span = w.span() == null ? PageDefinition.Widget.defaultSpan(PageDefinition.WidgetKind.LIST) : w.span();
        if (span < 1 || span > MAX_SPAN) {
            throw new WizardArgumentException(prefix + ": span must be between 1 and " + MAX_SPAN);
        }
        return new PageDefinition.Widget(PageDefinition.WidgetKind.LIST, entity.name(),
                checkLength(trimToNull(w.title()), MAX_TITLE, prefix + " title"), null, limit, null, null, null, span,
                presetFilter(prefix, entity, w.presetFilter(), entitiesByLower), null, null, false, null, null, null, null, columns, sort);
    }

    /** What a stacked bar splits each group by: another enum/boolean field — the entity's next one
     *  after {@code groupBy}, when omitted. */
    static String series(String prefix, EntityDefinition entity, String groupBy, String requested) {
        if (requested == null) {
            return entity.fields().stream()
                    .filter(f -> !f.primaryKey() && !f.name().equals(groupBy) && (f.type().isEnum() || f.type().isBoolean()))
                    .findFirst().map(FieldDefinition::name)
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " needs a second enum or boolean field to split the bars by"));
        }
        String field = groupBy(prefix, entity, requested);
        if (field.equals(groupBy)) {
            throw new WizardArgumentException(prefix + ": series '" + requested + "' must be an enum or boolean field other than groupBy");
        }
        return field;
    }

    /** A progress widget's target: a positive number, kept as written (it lands in the screen as a
     *  literal). */
    private static java.math.BigDecimal progressTarget(String prefix, String raw) {
        if (raw == null) throw new WizardArgumentException(prefix + ": a progress widget needs a 'target'");
        java.math.BigDecimal value;
        try {
            value = new java.math.BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new WizardArgumentException(prefix + ": target '" + raw + "' is not a number");
        }
        if (value.signum() <= 0) throw new WizardArgumentException(prefix + ": target must be greater than 0");
        return value.stripTrailingZeros();
    }

    /**
     * What a top list ranks: the groups of an enum/boolean field, or the rows a MANY_TO_ONE points
     * at (the relation's field name). Omitted: the first enum, else the first boolean, else the
     * first relation.
     */
    private static String rankBy(String prefix, EntityDefinition entity, String requested, String verb) {
        List<String> relations = entity.relations().stream()
                .filter(r -> r.type() == RelationType.MANY_TO_ONE).map(RelationDefinition::fieldName).toList();
        if (requested == null) {
            return entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isEnum()).findFirst()
                    .or(() -> entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isBoolean()).findFirst())
                    .map(FieldDefinition::name)
                    .or(() -> relations.stream().findFirst())
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " has no enum, boolean or relation to " + verb));
        }
        for (String relation : relations) {
            if (relation.equalsIgnoreCase(requested)) return relation;
        }
        return groupBy(prefix, entity, requested);
    }

    /** What a recent list orders by: any column of the entity (the list endpoint sorts by every
     *  scalar DTO column), or — when the audit opt applies to it — {@code createdAt}/{@code updatedAt},
     *  newest first. Null keeps the primary key. */
    private static String sortBy(String prefix, EntityDefinition entity, String requested, boolean audit) {
        if (requested == null) return null;
        if (audit) {
            for (String column : List.of("createdAt", "updatedAt")) {
                if (column.equalsIgnoreCase(requested) && entity.fields().stream().noneMatch(f -> f.name().equalsIgnoreCase(column))) {
                    return column;
                }
            }
        }
        FieldDefinition field = fieldOf(prefix, entity, requested, "sortBy");
        if (!field.type().sortable()) {
            throw new WizardArgumentException(prefix + ": sortBy '" + field.name() + "' is long text or a file, which a list cannot be sorted by");
        }
        return field.primaryKey() ? null : field.name();
    }

    /** A pivot's columns: an enum/boolean field or a relation other than its rows — the next one
     *  the entity has, when omitted. */
    private static String pivotColumns(String prefix, EntityDefinition entity, String rows, String requested) {
        if (requested == null) {
            List<String> candidates = new ArrayList<>();
            entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isEnum()).forEach(f -> candidates.add(f.name()));
            entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isBoolean()).forEach(f -> candidates.add(f.name()));
            entity.relations().stream().filter(r -> r.type() == RelationType.MANY_TO_ONE).forEach(r -> candidates.add(r.fieldName()));
            return candidates.stream().filter(c -> !c.equals(rows)).findFirst()
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " needs a second enum, boolean or relation to lay out in columns"));
        }
        String columns = rankBy(prefix, entity, requested, "lay out in columns");
        if (columns.equals(rows)) {
            throw new WizardArgumentException(prefix + ": series '" + requested + "' must differ from groupBy — the rows already split by it");
        }
        return columns;
    }

    /**
     * An activity feed: the latest changes to the named entities (every one the audit opt applies
     * to, when omitted — at most {@value #MAX_ACTIVITY_ENTITIES}), merged newest first. It reads
     * each list sorted by {@code updatedAt}, which only an audited entity has; it is not a filter, so
     * the feed takes no preset, period, drill target or aggregate.
     */
    private static PageDefinition.Widget activityWidget(String prefix, WidgetDto w, Map<String, EntityDefinition> entitiesByLower,
                                                        Set<String> scaffoldOpts) {
        if (trimToNull(w.entity()) != null) {
            throw new WizardArgumentException(prefix + ": an activity widget lists 'entities', not one 'entity'");
        }
        if (trimToNull(w.agg()) != null || trimToNull(w.field()) != null || trimToNull(w.groupBy()) != null
                || trimToNull(w.bucket()) != null || (w.presetFilter() != null && !w.presetFilter().isEmpty())
                || trimToNull(w.sortBy()) != null || trimToNull(w.dateField()) != null || Boolean.TRUE.equals(w.compare())
                || trimToNull(w.target()) != null || trimToNull(w.series()) != null || trimToNull(w.text()) != null
                || (w.pages() != null && !w.pages().isEmpty()) || (w.columns() != null && !w.columns().isEmpty())
                || w.sort() != null || trimToNull(w.drillTo()) != null || trimToNull(w.trend()) != null) {
            throw new WizardArgumentException(prefix + ": an activity widget takes only 'entities', 'limit', 'title' and 'span'");
        }
        List<String> names = new ArrayList<>();
        if (w.entities() == null || w.entities().isEmpty()) {
            // In the request's own order (the map is a LinkedHashMap).
            for (EntityDefinition e : entitiesByLower.values()) {
                if (auditApplies(e, scaffoldOpts)) names.add(e.name());
            }
            if (names.isEmpty()) {
                throw new WizardArgumentException(prefix + ": an activity widget needs the 'audit' opt — no entity records when it changed");
            }
            if (names.size() > MAX_ACTIVITY_ENTITIES) names = new ArrayList<>(names.subList(0, MAX_ACTIVITY_ENTITIES));
        } else {
            if (w.entities().size() > MAX_ACTIVITY_ENTITIES) {
                throw new WizardArgumentException(prefix + ": an activity widget lists at most " + MAX_ACTIVITY_ENTITIES + " entities");
            }
            for (String raw : w.entities()) {
                EntityDefinition e = requireEntity(entitiesByLower, raw, prefix);
                if (!auditApplies(e, scaffoldOpts)) {
                    throw new WizardArgumentException(prefix + ": " + e.name()
                            + " does not record when it changed — turn the 'audit' opt on for it");
                }
                if (names.contains(e.name())) {
                    throw new WizardArgumentException(prefix + ": " + e.name() + " is listed twice");
                }
                names.add(e.name());
            }
        }
        int limit = w.limit() == null ? DEFAULT_ACTIVITY_LIMIT : w.limit();
        if (limit < MIN_ACTIVITY_LIMIT || limit > MAX_RECENT_LIMIT) {
            throw new WizardArgumentException(prefix + ": limit must be between " + MIN_ACTIVITY_LIMIT + " and " + MAX_RECENT_LIMIT);
        }
        int span = w.span() == null ? PageDefinition.Widget.defaultSpan(PageDefinition.WidgetKind.ACTIVITY) : w.span();
        if (span < 1 || span > MAX_SPAN) {
            throw new WizardArgumentException(prefix + ": span must be between 1 and " + MAX_SPAN);
        }
        return new PageDefinition.Widget(PageDefinition.WidgetKind.ACTIVITY, null,
                checkLength(trimToNull(w.title()), MAX_TITLE, prefix + " title"), null, limit, null, null, null, span,
                null, null, null, false, null, null, null, null, null, null, null, names);
    }

    /**
     * The date a dashboard's period picker limits a widget by: the named field, else the entity's
     * first filterable date. Only the list's own date filters can narrow the rows (the picker sends
     * them as {@code <field>From}/{@code <field>To}), so the field must be filterable. Null when the
     * dashboard has no picker, or the entity has no such date — that widget then covers all rows.
     */
    static String dateField(String prefix, EntityDefinition entity, String requested, boolean hasDateRange) {
        if (requested == null) {
            if (!hasDateRange) return null;
            return entity.fields().stream()
                    .filter(f -> !f.primaryKey() && f.filterable() && f.type().isTemporal())
                    .findFirst().map(FieldDefinition::name).orElse(null);
        }
        if (!hasDateRange) {
            throw new WizardArgumentException(prefix + ": 'dateField' applies to the dashboard's dateRange, which is not set");
        }
        FieldDefinition field = fieldOf(prefix, entity, requested, "dateField");
        if (field.primaryKey() || !field.filterable() || !field.type().isTemporal()) {
            throw new WizardArgumentException(prefix + ": dateField '" + requested
                    + "' must be a filterable, non-key date field");
        }
        return field.name();
    }

    private static PageDefinition.WidgetKind parseKind(String prefix, String rawKind) {
        String k = trimToNull(rawKind);
        if (k == null) throw new WizardArgumentException(prefix + ": kind is required");
        for (PageDefinition.WidgetKind kind : PageDefinition.WidgetKind.values()) {
            if (kind.wire().equalsIgnoreCase(k)) return kind;
        }
        throw new WizardArgumentException(prefix + ": unknown widget kind '" + k
                + "' (expected kpi, bar, donut, stacked, pivot, line, recent, top, progress, activity, text, links or list)");
    }

    /** How a tile or chart reduces its rows. Absent means {@code count}. */
    static PageDefinition.Agg parseAgg(String prefix, String rawAgg) {
        String a = trimToNull(rawAgg);
        if (a == null) return PageDefinition.Agg.COUNT;
        for (PageDefinition.Agg agg : PageDefinition.Agg.values()) {
            if (agg.wire().equalsIgnoreCase(a)) return agg;
        }
        throw new WizardArgumentException(prefix + ": unknown agg '" + a + "' (expected count, sum, avg, min or max)");
    }

    /** The numeric column an agg reduces: required for everything but {@code count}, which takes none. */
    static String aggField(String prefix, EntityDefinition entity, PageDefinition.Agg agg, String requested) {
        if (agg == PageDefinition.Agg.COUNT) {
            if (requested != null) {
                throw new WizardArgumentException(prefix + ": agg 'count' counts records, so it takes no 'field'");
            }
            return null;
        }
        if (requested == null) {
            throw new WizardArgumentException(prefix + ": agg '" + agg.wire()
                    + "' needs a numeric 'field' of " + entity.name() + " to reduce");
        }
        FieldDefinition field = fieldOf(prefix, entity, requested, "field");
        if (field.primaryKey() || !field.type().isNumeric()) {
            throw new WizardArgumentException(prefix + ": field '" + requested
                    + "' must be a non-key numeric field to be aggregated");
        }
        return field.name();
    }

    /** A line's bucket granularity. Absent means {@code month}. */
    static PageDefinition.Bucket parseBucket(String prefix, String rawBucket) {
        return parseBucket(prefix, rawBucket, "bucket");
    }

    static PageDefinition.Bucket parseBucket(String prefix, String rawBucket, String prop) {
        String b = trimToNull(rawBucket);
        if (b == null) return PageDefinition.Bucket.MONTH;
        for (PageDefinition.Bucket bucket : PageDefinition.Bucket.values()) {
            if (bucket.wire().equalsIgnoreCase(b)) return bucket;
        }
        throw new WizardArgumentException(prefix + ": unknown " + prop + " '" + b + "' (expected day, month or year)");
    }

    /**
     * The date a kpi's sparkline plots over: the date the dashboard's picker limits it by (so the
     * line covers the period on screen), else the {@code dateField} it names, else the entity's
     * first non-key date.
     */
    private static String trendOn(String prefix, EntityDefinition entity, String pickerDate, String requested) {
        if (pickerDate != null) return pickerDate;
        if (requested != null) {
            FieldDefinition field = fieldOf(prefix, entity, requested, "dateField");
            if (field.primaryKey() || !field.type().isTemporal()) {
                throw new WizardArgumentException(prefix + ": dateField '" + requested
                        + "' must be a non-key date field to plot the trend over");
            }
            return field.name();
        }
        return entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isTemporal()).findFirst()
                .map(FieldDefinition::name)
                .orElseThrow(() -> new WizardArgumentException(prefix + ": 'trend' needs a date field on "
                        + entity.name() + " to plot over time"));
    }

    /** A line plots a temporal field over time — the entity's first, when omitted. */
    private static String dateGroupBy(String prefix, EntityDefinition entity, String requested) {
        if (requested == null) {
            return entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isTemporal()).findFirst()
                    .map(FieldDefinition::name)
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " has no date field to plot over time"));
        }
        FieldDefinition field = fieldOf(prefix, entity, requested, "groupBy");
        if (field.primaryKey() || !field.type().isTemporal()) {
            throw new WizardArgumentException(prefix + ": groupBy '" + requested
                    + "' must be a non-key date field to plot over time");
        }
        return field.name();
    }
}
