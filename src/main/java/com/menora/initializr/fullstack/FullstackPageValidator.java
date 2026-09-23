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
            pages.add(switch (type) {
                case ENTITY_LIST -> {
                    EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), "Page '" + id + "'");
                    yield new PageDefinition(id, type, title, description, hidden, entity.name(),
                            presetFilter(id, entity, p.presetFilter()), null, null);
                }
                case DASHBOARD -> new PageDefinition(id, type, title, description, hidden, null, null,
                        widgets(id, p.widgets(), entitiesByLower), null);
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
                            parent.name(), child.name(), via, null, null);
                }
                case REPORT -> {
                    String prefix = "Page '" + id + "' (report)";
                    EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                    yield new PageDefinition(id, type, title, description, hidden, entity.name(),
                            presetFilter(id, entity, p.presetFilter()), chart(prefix, entity, p.chart()));
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
                            null);
                }
            });
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
    }

    /** Preset filters are equality filters on non-PK, filterable enum/boolean fields. Enum values
     *  are canonicalized to the declared constant (case-insensitive match). */
    private static Map<String, String> presetFilter(String id, EntityDefinition entity, Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> en : raw.entrySet()) {
            String prefix = "Page '" + id + "' presetFilter '" + en.getKey() + "'";
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

    private static List<PageDefinition.Widget> widgets(String id, List<WidgetDto> raw,
                                                       Map<String, EntityDefinition> entitiesByLower) {
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
                case LINE -> {
                    groupBy = dateGroupBy(prefix, entity, trimToNull(w.groupBy()));
                    bucket = parseBucket(prefix, w.bucket());
                }
                default -> {
                    if (trimToNull(w.groupBy()) != null) {
                        throw new WizardArgumentException(prefix + ": only a bar or line widget takes 'groupBy'");
                    }
                }
            }
            if (kind != PageDefinition.WidgetKind.LINE && trimToNull(w.bucket()) != null) {
                throw new WizardArgumentException(prefix + ": only a line widget takes 'bucket'");
            }
            int limit = 0;
            if (kind == PageDefinition.WidgetKind.RECENT) {
                limit = w.limit() == null ? DEFAULT_RECENT_LIMIT : w.limit();
                if (limit < 1 || limit > MAX_RECENT_LIMIT) {
                    throw new WizardArgumentException(prefix + ": limit must be between 1 and " + MAX_RECENT_LIMIT);
                }
            } else if (w.limit() != null) {
                throw new WizardArgumentException(prefix + ": only a recent widget takes 'limit'");
            }
            out.add(new PageDefinition.Widget(kind, entity.name(), title, groupBy, limit, agg, field, bucket));
        }
        return out;
    }

    private static PageDefinition.WidgetKind parseKind(String prefix, String rawKind) {
        String k = trimToNull(rawKind);
        if (k == null) throw new WizardArgumentException(prefix + ": kind is required");
        for (PageDefinition.WidgetKind kind : PageDefinition.WidgetKind.values()) {
            if (kind.wire().equalsIgnoreCase(k)) return kind;
        }
        throw new WizardArgumentException(prefix + ": unknown widget kind '" + k
                + "' (expected kpi, bar, line or recent)");
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
