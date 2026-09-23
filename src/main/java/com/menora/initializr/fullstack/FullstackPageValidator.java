package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;
import com.menora.initializr.fullstack.FullstackStarterRequest.PageDefinitionDto;
import com.menora.initializr.fullstack.FullstackStarterRequest.TabDto;
import com.menora.initializr.fullstack.FullstackStarterRequest.WidgetDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the optional {@code pages} of a fullstack request against its (already validated)
 * entities and converts them into {@link PageDefinition}s. Null/empty pages → an empty list, which
 * the generator treats as the classic layout (one dashboard + one list page per entity).
 *
 * <p>Throws {@link WizardArgumentException} (HTTP 400). Messages name the page by id.
 */
public final class FullstackPageValidator {

    static final Pattern PAGE_ID = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    static final int MAX_PAGES = 30;
    static final int MAX_WIDGETS = 24;
    static final int MIN_TABS = 2;
    static final int MAX_TABS = 6;
    static final int MAX_TITLE = 80;
    static final int MAX_DESCRIPTION = 300;
    static final int MAX_RECENT_LIMIT = 20;
    static final int DEFAULT_RECENT_LIMIT = 5;

    static final int MAX_GROUP = 40;
    static final int MAX_SPAN = 4;
    static final int MAX_CHARTS = 4;

    /** The lucide icons a page may put in the nav. The shell imports exactly the ones in use, so the
     *  list is a whitelist rather than "any lucide name": a typo would otherwise fail the build of the
     *  generated project instead of the request. */
    static final List<String> NAV_ICONS = List.of(
            "BarChart3", "Building2", "Calendar", "FileText", "Inbox", "Layers", "LayoutDashboard", "ListChecks",
            "Package", "PanelLeft", "Settings", "ShoppingCart", "Star", "Table2", "Tag", "Ticket", "Truck", "Users",
            "Wallet", "Wand2");

    /** Page types reserved for a later release: named so the error says "not yet", not "unknown". */
    private static final Set<String> PLANNED_TYPES = Set.of("wizard");

    private FullstackPageValidator() {}

    public static List<PageDefinition> validateAndConvert(List<PageDefinitionDto> raw, List<EntityDefinition> entities) {
        if (raw == null || raw.isEmpty()) return List.of();
        if (raw.size() > MAX_PAGES) {
            throw new WizardArgumentException("At most " + MAX_PAGES + " pages are allowed");
        }
        Map<String, EntityDefinition> entitiesByLower = new HashMap<>();
        for (EntityDefinition e : entities) entitiesByLower.put(e.name().toLowerCase(Locale.ROOT), e);

        // Pass 1: ids + types, so tabs can reference pages declared after them.
        Map<String, PageDefinition.Type> typeById = new LinkedHashMap<>();
        for (int pi = 0; pi < raw.size(); pi++) {
            PageDefinitionDto p = raw.get(pi);
            if (p == null) throw new WizardArgumentException("pages[" + pi + "] is null");
            String id = trimToNull(p.id());
            if (id == null) throw new WizardArgumentException("pages[" + pi + "].id is required");
            if (id.length() > 40 || !PAGE_ID.matcher(id).matches()) {
                throw new WizardArgumentException("Page id '" + id
                        + "' must be lower-case letters and digits separated by single '-', starting with a letter (max 40)");
            }
            if (typeById.containsKey(id)) throw new WizardArgumentException("Duplicate page id '" + id + "'");
            typeById.put(id, parseType(id, p.type()));
        }

        List<PageDefinition> pages = new ArrayList<>(raw.size());
        Map<String, String> recordPageByEntity = new HashMap<>();
        boolean anyVisible = false;
        for (PageDefinitionDto p : raw) {
            String id = p.id().trim();
            PageDefinition.Type type = typeById.get(id);
            String title = checkLength(trimToNull(p.title()), MAX_TITLE, "Page '" + id + "' title");
            String description = checkLength(trimToNull(p.description()), MAX_DESCRIPTION,
                    "Page '" + id + "' description");
            boolean hidden = Boolean.TRUE.equals(p.hidden());
            if (type == PageDefinition.Type.RECORD) {
                // A record page opens with a record id, so there is nothing to show from the nav.
                if (Boolean.FALSE.equals(p.hidden())) {
                    throw new WizardArgumentException("Page '" + id
                            + "' (record) cannot be in the navigation: it opens from a row of its entity");
                }
                hidden = true;
            }
            anyVisible |= !hidden;
            rejectForeignProps(id, type, p);
            String group = navGroup(id, hidden, p.group());
            String icon = navIcon(id, hidden, p.icon());
            PageDefinition page = switch (type) {
                case ENTITY_LIST -> {
                    EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), "Page '" + id + "'");
                    yield new PageDefinition(id, type, title, description, hidden, entity.name(),
                            presetFilter("Page '" + id + "'", entity, p.presetFilter()), null, null);
                }
                case DASHBOARD -> {
                    PageDefinition.DateRange range = parseDateRange(id, p.dateRange());
                    List<PageDefinition.Widget> widgets = widgets(id, p.widgets(), entitiesByLower, range != null);
                    if (range != null && widgets.stream().allMatch(w -> w.dateField() == null)) {
                        throw new WizardArgumentException("Page '" + id + "' has a dateRange, but none of its widgets"
                                + " counts an entity with a filterable date field for it to limit");
                    }
                    yield new PageDefinition(id, type, title, description, hidden, null, null, widgets, null)
                            .withDateRange(range);
                }
                case TABS -> {
                    // No entity to borrow a name from, so the nav label has to be given.
                    if (title == null) throw new WizardArgumentException("Page '" + id + "' (tabs) needs a title");
                    yield new PageDefinition(id, type, title, description, hidden, null, null, null,
                            tabs(id, p.tabs(), typeById));
                }
                case MASTER_DETAIL -> {
                    String prefix = "Page '" + id + "' (master-detail)";
                    EntityDefinition parent = requireEntity(entitiesByLower, p.parent(), prefix + " parent");
                    EntityDefinition child = requireEntity(entitiesByLower, p.child(), prefix + " child");
                    requireSinglePk(prefix, parent);
                    String via = via(prefix, child, parent, trimToNull(p.via()));
                    yield new PageDefinition(id, type, title, description, hidden, null, null, null, null,
                            parent.name(), child.name(), via, null, null, null, null, null);
                }
                case REPORT -> {
                    String prefix = "Page '" + id + "' (report)";
                    EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                    yield new PageDefinition(id, type, title, description, hidden, entity.name(),
                            presetFilter("Page '" + id + "'", entity, p.presetFilter()), charts(prefix, entity, p));
                }
                case RECORD -> {
                    String prefix = "Page '" + id + "' (record)";
                    EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                    requireSinglePk(prefix, entity);
                    String previous = recordPageByEntity.putIfAbsent(entity.name(), id);
                    if (previous != null) {
                        throw new WizardArgumentException(prefix + ": " + entity.name()
                                + " already has a record page ('" + previous + "')");
                    }
                    yield new PageDefinition(id, type, title, description, true, entity.name(), null, null, null,
                            null, null, null, childTabs(prefix, entity, p.childTabs(), entities, entitiesByLower),
                            null, null, null, null);
                }
            };
            pages.add(page.withNav(group, icon));
        }
        if (!anyVisible) throw new WizardArgumentException("At least one page must be visible in the navigation");
        return pages;
    }

    private static PageDefinition.Type parseType(String id, String rawType) {
        String t = trimToNull(rawType);
        if (t == null) throw new WizardArgumentException("Page '" + id + "' type is required");
        String lower = t.toLowerCase(Locale.ROOT);
        for (PageDefinition.Type type : PageDefinition.Type.values()) {
            if (type.wire().equals(lower)) return type;
        }
        if (PLANNED_TYPES.contains(lower)) {
            throw new WizardArgumentException("Page '" + id + "': type '" + t + "' is not supported yet");
        }
        throw new WizardArgumentException("Page '" + id + "': unknown type '" + t
                + "' (expected entity-list, dashboard, tabs, master-detail, record or report)");
    }

    /** A nav section name. Only a page that is in the nav can sit in a section of it. */
    private static String navGroup(String id, boolean hidden, String raw) {
        String group = checkLength(trimToNull(raw), MAX_GROUP, "Page '" + id + "' group");
        if (group != null && hidden) {
            throw new WizardArgumentException("Page '" + id + "' is hidden, so it takes no nav 'group'");
        }
        return group;
    }

    /** A nav icon: one of {@link #NAV_ICONS}, matched ignoring case and returned as declared. */
    private static String navIcon(String id, boolean hidden, String raw) {
        String icon = trimToNull(raw);
        if (icon == null) return null;
        if (hidden) throw new WizardArgumentException("Page '" + id + "' is hidden, so it takes no nav 'icon'");
        return NAV_ICONS.stream().filter(i -> i.equalsIgnoreCase(icon)).findFirst()
                .orElseThrow(() -> new WizardArgumentException("Page '" + id + "': unknown icon '" + icon
                        + "' (expected one of " + String.join(", ", NAV_ICONS) + ")"));
    }

    /** A property that belongs to another page type is a mistake worth reporting, not ignoring. */
    private static void rejectForeignProps(String id, PageDefinition.Type type, PageDefinitionDto p) {
        String prefix = "Page '" + id + "' (" + type.wire() + ") ";
        if (type != PageDefinition.Type.ENTITY_LIST && type != PageDefinition.Type.RECORD
                && type != PageDefinition.Type.REPORT && trimToNull(p.entity()) != null) {
            throw new WizardArgumentException(prefix + "does not take 'entity'");
        }
        if (type != PageDefinition.Type.ENTITY_LIST && type != PageDefinition.Type.REPORT
                && p.presetFilter() != null && !p.presetFilter().isEmpty()) {
            throw new WizardArgumentException(prefix + "does not take 'presetFilter'");
        }
        if (type != PageDefinition.Type.REPORT && p.chart() != null) {
            throw new WizardArgumentException(prefix + "does not take 'chart'");
        }
        if (type != PageDefinition.Type.REPORT && p.charts() != null && !p.charts().isEmpty()) {
            throw new WizardArgumentException(prefix + "does not take 'charts'");
        }
        if (type != PageDefinition.Type.MASTER_DETAIL) {
            if (trimToNull(p.parent()) != null) throw new WizardArgumentException(prefix + "does not take 'parent'");
            if (trimToNull(p.child()) != null) throw new WizardArgumentException(prefix + "does not take 'child'");
            if (trimToNull(p.via()) != null) throw new WizardArgumentException(prefix + "does not take 'via'");
        }
        if (type != PageDefinition.Type.RECORD && p.childTabs() != null && !p.childTabs().isEmpty()) {
            throw new WizardArgumentException(prefix + "does not take 'childTabs'");
        }
        if (type != PageDefinition.Type.DASHBOARD && p.widgets() != null && !p.widgets().isEmpty()) {
            throw new WizardArgumentException(prefix + "does not take 'widgets'");
        }
        if (type != PageDefinition.Type.TABS && p.tabs() != null && !p.tabs().isEmpty()) {
            throw new WizardArgumentException(prefix + "does not take 'tabs'");
        }
        if (type != PageDefinition.Type.DASHBOARD && trimToNull(p.dateRange()) != null) {
            throw new WizardArgumentException(prefix + "does not take 'dateRange'");
        }
    }

    /** Preset filters are equality filters on non-PK, filterable enum/boolean fields. Enum values
     *  are canonicalized to the declared constant (case-insensitive match). {@code owner} names the
     *  page or widget in the error. */
    private static Map<String, String> presetFilter(String owner, EntityDefinition entity, Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> en : raw.entrySet()) {
            String prefix = owner + " presetFilter '" + en.getKey() + "'";
            FieldDefinition field = entity.fields().stream()
                    .filter(f -> f.name().equals(en.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": no such field on " + entity.name()));
            if (field.primaryKey() || !field.filterable() || !(field.type().isEnum() || field.type().isBoolean())) {
                throw new WizardArgumentException(prefix + ": only filterable enum or boolean fields can be preset");
            }
            String value = trimToNull(en.getValue());
            if (value == null) throw new WizardArgumentException(prefix + ": value is required");
            if (field.type().isBoolean()) {
                String lower = value.toLowerCase(Locale.ROOT);
                if (!lower.equals("true") && !lower.equals("false")) {
                    throw new WizardArgumentException(prefix + ": expected true or false, got '" + value + "'");
                }
                out.put(field.name(), lower);
            } else {
                String constant = field.enumValues().stream()
                        .filter(c -> c.equalsIgnoreCase(value))
                        .findFirst()
                        .orElseThrow(() -> new WizardArgumentException(prefix + ": '" + value
                                + "' is not one of " + field.enumValues()));
                out.put(field.name(), constant);
            }
        }
        return out;
    }

    /** A dashboard's period picker: absent (no picker), or the period it opens on. */
    private static PageDefinition.DateRange parseDateRange(String id, String raw) {
        String r = trimToNull(raw);
        if (r == null) return null;
        for (PageDefinition.DateRange range : PageDefinition.DateRange.values()) {
            if (range.wire().equalsIgnoreCase(r)) return range;
        }
        throw new WizardArgumentException("Page '" + id + "': unknown dateRange '" + r
                + "' (expected all, 7d, 30d, 90d, ytd or 12m)");
    }

    private static List<PageDefinition.Widget> widgets(String id, List<WidgetDto> raw,
                                                       Map<String, EntityDefinition> entitiesByLower,
                                                       boolean hasDateRange) {
        if (raw == null || raw.isEmpty()) {
            throw new WizardArgumentException("Page '" + id + "' (dashboard) needs at least one widget");
        }
        if (raw.size() > MAX_WIDGETS) {
            throw new WizardArgumentException("Page '" + id + "' has more than " + MAX_WIDGETS + " widgets");
        }
        List<PageDefinition.Widget> out = new ArrayList<>(raw.size());
        for (int wi = 0; wi < raw.size(); wi++) {
            WidgetDto w = raw.get(wi);
            String prefix = "Page '" + id + "' widgets[" + wi + "]";
            if (w == null) throw new WizardArgumentException(prefix + " is null");
            PageDefinition.WidgetKind kind = parseKind(prefix, w.kind());
            EntityDefinition entity = requireEntity(entitiesByLower, w.entity(), prefix);
            String title = checkLength(trimToNull(w.title()), MAX_TITLE, prefix + " title");
            boolean reduces = kind != PageDefinition.WidgetKind.RECENT;
            // A tile against a target: the same reduction as a kpi, and the target it fills up to.
            java.math.BigDecimal target = null;
            if (kind == PageDefinition.WidgetKind.PROGRESS) {
                target = progressTarget(prefix, trimToNull(w.target()));
            } else if (trimToNull(w.target()) != null) {
                throw new WizardArgumentException(prefix + ": only a progress widget takes 'target'");
            }
            PageDefinition.Agg agg = null;
            String field = null;
            if (reduces) {
                agg = parseAgg(prefix, w.agg());
                field = aggField(prefix, entity, agg, trimToNull(w.field()));
            } else {
                if (trimToNull(w.agg()) != null) {
                    throw new WizardArgumentException(prefix + ": a recent widget lists rows, so it takes no 'agg'");
                }
                if (trimToNull(w.field()) != null) {
                    throw new WizardArgumentException(prefix + ": a recent widget lists rows, so it takes no 'field'");
                }
            }
            String groupBy = null;
            PageDefinition.Bucket bucket = null;
            switch (kind) {
                case BAR -> groupBy = groupBy(prefix, entity, trimToNull(w.groupBy()));
                case TOP -> groupBy = rankBy(prefix, entity, trimToNull(w.groupBy()));
                case LINE -> {
                    groupBy = dateGroupBy(prefix, entity, trimToNull(w.groupBy()));
                    bucket = parseBucket(prefix, w.bucket());
                }
                default -> {
                    if (trimToNull(w.groupBy()) != null) {
                        throw new WizardArgumentException(prefix + ": only a bar, line or top widget takes 'groupBy'");
                    }
                }
            }
            if (kind != PageDefinition.WidgetKind.LINE && trimToNull(w.bucket()) != null) {
                throw new WizardArgumentException(prefix + ": only a line widget takes 'bucket'");
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
                sortBy = sortBy(prefix, entity, trimToNull(w.sortBy()));
            } else if (trimToNull(w.sortBy()) != null) {
                throw new WizardArgumentException(prefix + ": only a recent widget takes 'sortBy'");
            }
            String dateField = dateField(prefix, entity, trimToNull(w.dateField()), hasDateRange);
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
                    presetFilter(prefix, entity, w.presetFilter()), sortBy, dateField, compare, target));
        }
        return out;
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
    private static String rankBy(String prefix, EntityDefinition entity, String requested) {
        List<String> relations = entity.relations().stream()
                .filter(r -> r.type() == RelationType.MANY_TO_ONE).map(RelationDefinition::fieldName).toList();
        if (requested == null) {
            return entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isEnum()).findFirst()
                    .or(() -> entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isBoolean()).findFirst())
                    .map(FieldDefinition::name)
                    .or(() -> relations.stream().findFirst())
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " has no enum, boolean or relation to rank by"));
        }
        for (String relation : relations) {
            if (relation.equalsIgnoreCase(requested)) return relation;
        }
        return groupBy(prefix, entity, requested);
    }

    /** What a recent list orders by: any column of the entity (the list endpoint sorts by every
     *  scalar DTO column), newest first. Null keeps the primary key. */
    private static String sortBy(String prefix, EntityDefinition entity, String requested) {
        if (requested == null) return null;
        FieldDefinition field = entity.fields().stream()
                .filter(f -> f.name().equals(requested))
                .findFirst()
                .orElseThrow(() -> new WizardArgumentException(prefix + ": sortBy '" + requested
                        + "' is not a field of " + entity.name()));
        return field.primaryKey() ? null : field.name();
    }

    /**
     * The date a dashboard's period picker limits a widget by: the named field, else the entity's
     * first filterable date. Only the list's own date filters can narrow the rows (the picker sends
     * them as {@code <field>From}/{@code <field>To}), so the field must be filterable. Null when the
     * dashboard has no picker, or the entity has no such date — that widget then covers all rows.
     */
    private static String dateField(String prefix, EntityDefinition entity, String requested, boolean hasDateRange) {
        if (requested == null) {
            if (!hasDateRange) return null;
            return entity.fields().stream()
                    .filter(f -> !f.primaryKey() && f.filterable() && f.type().isTemporal())
                    .findFirst().map(FieldDefinition::name).orElse(null);
        }
        if (!hasDateRange) {
            throw new WizardArgumentException(prefix + ": 'dateField' applies to the dashboard's dateRange, which is not set");
        }
        FieldDefinition field = entity.fields().stream()
                .filter(f -> f.name().equals(requested))
                .findFirst()
                .orElseThrow(() -> new WizardArgumentException(prefix + ": dateField '" + requested
                        + "' is not a field of " + entity.name()));
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
                + "' (expected kpi, bar, line, recent, top or progress)");
    }

    /** How a tile or chart reduces its rows. Absent means {@code count}. */
    private static PageDefinition.Agg parseAgg(String prefix, String rawAgg) {
        String a = trimToNull(rawAgg);
        if (a == null) return PageDefinition.Agg.COUNT;
        for (PageDefinition.Agg agg : PageDefinition.Agg.values()) {
            if (agg.wire().equalsIgnoreCase(a)) return agg;
        }
        throw new WizardArgumentException(prefix + ": unknown agg '" + a + "' (expected count, sum, avg, min or max)");
    }

    /** The numeric column an agg reduces: required for everything but {@code count}, which takes none. */
    private static String aggField(String prefix, EntityDefinition entity, PageDefinition.Agg agg, String requested) {
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
        FieldDefinition field = entity.fields().stream()
                .filter(f -> f.name().equals(requested))
                .findFirst()
                .orElseThrow(() -> new WizardArgumentException(prefix + ": field '" + requested
                        + "' is not a field of " + entity.name()));
        if (field.primaryKey() || !field.type().isNumeric()) {
            throw new WizardArgumentException(prefix + ": field '" + requested
                    + "' must be a non-key numeric field to be aggregated");
        }
        return field.name();
    }

    /** A line's bucket granularity. Absent means {@code month}. */
    private static PageDefinition.Bucket parseBucket(String prefix, String rawBucket) {
        String b = trimToNull(rawBucket);
        if (b == null) return PageDefinition.Bucket.MONTH;
        for (PageDefinition.Bucket bucket : PageDefinition.Bucket.values()) {
            if (bucket.wire().equalsIgnoreCase(b)) return bucket;
        }
        throw new WizardArgumentException(prefix + ": unknown bucket '" + b + "' (expected day, month or year)");
    }

    /** A line plots a temporal field over time — the entity's first, when omitted. */
    private static String dateGroupBy(String prefix, EntityDefinition entity, String requested) {
        if (requested == null) {
            return entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isTemporal()).findFirst()
                    .map(FieldDefinition::name)
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " has no date field to plot over time"));
        }
        FieldDefinition field = entity.fields().stream()
                .filter(f -> f.name().equals(requested))
                .findFirst()
                .orElseThrow(() -> new WizardArgumentException(prefix + ": groupBy '" + requested
                        + "' is not a field of " + entity.name()));
        if (field.primaryKey() || !field.type().isTemporal()) {
            throw new WizardArgumentException(prefix + ": groupBy '" + requested
                    + "' must be a non-key date field to plot over time");
        }
        return field.name();
    }

    /** A report's charts: {@code charts} (1–4), or the one-chart spelling {@code chart}. */
    private static List<PageDefinition.Chart> charts(String prefix, EntityDefinition entity, PageDefinitionDto p) {
        boolean many = p.charts() != null && !p.charts().isEmpty();
        if (many && p.chart() != null) {
            throw new WizardArgumentException(prefix + ": give either 'chart' or 'charts', not both");
        }
        if (!many) return List.of(chart(prefix, entity, p.chart()));
        if (p.charts().size() > MAX_CHARTS) {
            throw new WizardArgumentException(prefix + ": at most " + MAX_CHARTS + " charts are allowed");
        }
        List<PageDefinition.Chart> out = new ArrayList<>();
        for (int i = 0; i < p.charts().size(); i++) {
            out.add(chart(prefix + " charts[" + i + "]", entity, p.charts().get(i)));
        }
        return out;
    }

    /**
     * A report's chart. {@code groupBy} decides its shape: an enum/boolean field draws a bar, a
     * temporal one draws a line bucketed by day/month/year. Omitted, it falls back to the entity's
     * first enum/boolean field, else its first date.
     */
    private static PageDefinition.Chart chart(String prefix, EntityDefinition entity,
                                              FullstackStarterRequest.ChartDto raw) {
        if (raw == null) {
            throw new WizardArgumentException(prefix + ": a report needs a 'chart'");
        }
        String requested = trimToNull(raw.groupBy());
        FieldDefinition field = null;
        if (requested != null) {
            field = entity.fields().stream()
                    .filter(f -> f.name().equals(requested))
                    .findFirst()
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": groupBy '" + requested
                            + "' is not a field of " + entity.name()));
            if (field.primaryKey()
                    || !(field.type().isEnum() || field.type().isBoolean() || field.type().isTemporal())) {
                throw new WizardArgumentException(prefix + ": groupBy '" + requested
                        + "' must be a non-key enum, boolean or date field");
            }
        } else {
            field = entity.fields().stream()
                    .filter(f -> !f.primaryKey() && (f.type().isEnum() || f.type().isBoolean()))
                    .findFirst()
                    .or(() -> entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isTemporal()).findFirst())
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " has no enum, boolean or date field to group by"));
        }
        boolean overTime = field.type().isTemporal();
        if (!overTime && trimToNull(raw.bucket()) != null) {
            throw new WizardArgumentException(prefix + ": 'bucket' applies to a date groupBy, and '"
                    + field.name() + "' is not one");
        }
        PageDefinition.Agg agg = parseAgg(prefix, raw.agg());
        return new PageDefinition.Chart(field.name(), overTime ? parseBucket(prefix, raw.bucket()) : null,
                agg, aggField(prefix, entity, agg, trimToNull(raw.field())));
    }

    /** A bar groups by a non-PK enum/boolean field — the first enum, else the first boolean, when omitted. */
    private static String groupBy(String prefix, EntityDefinition entity, String requested) {
        if (requested == null) {
            return entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isEnum()).findFirst()
                    .or(() -> entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isBoolean()).findFirst())
                    .map(FieldDefinition::name)
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " has no enum or boolean field to group by"));
        }
        FieldDefinition field = entity.fields().stream()
                .filter(f -> f.name().equals(requested))
                .findFirst()
                .orElseThrow(() -> new WizardArgumentException(prefix + ": groupBy '" + requested
                        + "' is not a field of " + entity.name()));
        if (field.primaryKey() || !(field.type().isEnum() || field.type().isBoolean())) {
            throw new WizardArgumentException(prefix + ": groupBy '" + requested + "' must be a non-key enum or boolean field");
        }
        return field.name();
    }

    private static List<PageDefinition.Tab> tabs(String id, List<TabDto> raw, Map<String, PageDefinition.Type> typeById) {
        if (raw == null || raw.size() < MIN_TABS || raw.size() > MAX_TABS) {
            throw new WizardArgumentException("Page '" + id + "' (tabs) needs between " + MIN_TABS + " and "
                    + MAX_TABS + " tabs");
        }
        Set<String> seen = new HashSet<>();
        List<PageDefinition.Tab> out = new ArrayList<>(raw.size());
        for (int ti = 0; ti < raw.size(); ti++) {
            TabDto t = raw.get(ti);
            String prefix = "Page '" + id + "' tabs[" + ti + "]";
            if (t == null) throw new WizardArgumentException(prefix + " is null");
            String target = trimToNull(t.page());
            if (target == null) throw new WizardArgumentException(prefix + ": page is required");
            PageDefinition.Type targetType = typeById.get(target);
            if (targetType == null) throw new WizardArgumentException(prefix + ": no page with id '" + target + "'");
            if (targetType == PageDefinition.Type.TABS) {
                throw new WizardArgumentException(prefix + ": a tab cannot embed another tabs page ('" + target + "')");
            }
            if (targetType == PageDefinition.Type.RECORD) {
                throw new WizardArgumentException(prefix + ": a tab cannot embed a record page ('" + target
                        + "') — it needs a record id");
            }
            if (!seen.add(target)) throw new WizardArgumentException(prefix + ": page '" + target + "' is already a tab");
            out.add(new PageDefinition.Tab(checkLength(trimToNull(t.title()), MAX_TITLE, prefix + " title"), target));
        }
        return out;
    }

    /** Master-detail parents and record pages address one row by a single id. */
    private static void requireSinglePk(String prefix, EntityDefinition entity) {
        long pks = entity.fields().stream().filter(FieldDefinition::primaryKey).count();
        if (pks != 1) {
            throw new WizardArgumentException(prefix + ": " + entity.name()
                    + " has a composite key; only single-key entities can be opened as one record");
        }
    }

    /** The child's MANY_TO_ONE fields that point at {@code parent}, in declaration order. */
    private static List<String> relationsTo(EntityDefinition child, EntityDefinition parent) {
        return child.relations().stream()
                .filter(r -> r.type() == RelationType.MANY_TO_ONE && r.targetEntity().equalsIgnoreCase(parent.name()))
                .map(RelationDefinition::fieldName)
                .toList();
    }

    /** The relation linking child to parent: the named one, else the only one. */
    private static String via(String prefix, EntityDefinition child, EntityDefinition parent, String requested) {
        List<String> candidates = relationsTo(child, parent);
        if (candidates.isEmpty()) {
            throw new WizardArgumentException(prefix + ": " + child.name() + " has no relation to " + parent.name());
        }
        if (requested != null) {
            return candidates.stream().filter(c -> c.equalsIgnoreCase(requested)).findFirst()
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": via '" + requested + "' is not a relation of "
                            + child.name() + " to " + parent.name() + " (expected one of " + candidates + ")"));
        }
        if (candidates.size() > 1) {
            throw new WizardArgumentException(prefix + ": " + child.name() + " has several relations to " + parent.name()
                    + " " + candidates + "; set 'via' to pick one");
        }
        return candidates.get(0);
    }

    /** A record page's related lists: the named entities, else every entity with a relation to it.
     *  Each links through its first relation to the record entity. */
    private static List<PageDefinition.ChildTab> childTabs(String prefix, EntityDefinition entity, List<String> raw,
                                                          List<EntityDefinition> entities,
                                                          Map<String, EntityDefinition> entitiesByLower) {
        List<PageDefinition.ChildTab> out = new ArrayList<>();
        if (raw == null) {
            for (EntityDefinition candidate : entities) {
                List<String> rels = relationsTo(candidate, entity);
                if (!rels.isEmpty()) out.add(new PageDefinition.ChildTab(candidate.name(), rels.get(0)));
                // The default takes the first related lists that fit, rather than failing.
                if (out.size() == MAX_TABS - 1) break;
            }
        } else {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < raw.size(); i++) {
                String itemPrefix = prefix + " childTabs[" + i + "]";
                EntityDefinition child = requireEntity(entitiesByLower, raw.get(i), itemPrefix);
                List<String> rels = relationsTo(child, entity);
                if (rels.isEmpty()) {
                    throw new WizardArgumentException(itemPrefix + ": " + child.name() + " has no relation to " + entity.name());
                }
                if (!seen.add(child.name())) {
                    throw new WizardArgumentException(itemPrefix + ": " + child.name() + " is already a tab");
                }
                out.add(new PageDefinition.ChildTab(child.name(), rels.get(0)));
            }
        }
        if (out.size() > MAX_TABS - 1) {
            throw new WizardArgumentException(prefix + ": at most " + (MAX_TABS - 1) + " related lists are allowed");
        }
        return out;
    }

    private static EntityDefinition requireEntity(Map<String, EntityDefinition> byLower, String raw, String prefix) {
        String name = trimToNull(raw);
        if (name == null) throw new WizardArgumentException(prefix + ": entity is required");
        EntityDefinition e = byLower.get(name.toLowerCase(Locale.ROOT));
        if (e == null) throw new WizardArgumentException(prefix + ": unknown entity '" + name + "'");
        return e;
    }

    private static String checkLength(String value, int max, String what) {
        if (value != null && value.length() > max) {
            throw new WizardArgumentException(what + " must be at most " + max + " characters");
        }
        return value;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
