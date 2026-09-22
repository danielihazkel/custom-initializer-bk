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
    private static final Set<String> PLANNED_TYPES = Set.of("master-detail", "record", "report", "wizard");

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
        boolean anyVisible = false;
        for (PageDefinitionDto p : raw) {
            String id = p.id().trim();
            PageDefinition.Type type = typeById.get(id);
            String title = checkLength(trimToNull(p.title()), MAX_TITLE, "Page '" + id + "' title");
            String description = checkLength(trimToNull(p.description()), MAX_DESCRIPTION,
                    "Page '" + id + "' description");
            boolean hidden = Boolean.TRUE.equals(p.hidden());
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
                + "' (expected entity-list, dashboard or tabs)");
    }

    /** A property that belongs to another page type is a mistake worth reporting, not ignoring. */
    private static void rejectForeignProps(String id, PageDefinition.Type type, PageDefinitionDto p) {
        String prefix = "Page '" + id + "' (" + type.wire() + ") ";
        if (type != PageDefinition.Type.ENTITY_LIST) {
            if (trimToNull(p.entity()) != null) throw new WizardArgumentException(prefix + "does not take 'entity'");
            if (p.presetFilter() != null && !p.presetFilter().isEmpty()) {
                throw new WizardArgumentException(prefix + "does not take 'presetFilter'");
            }
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
            String agg = trimToNull(w.agg());
            if (agg != null && !(kind == PageDefinition.WidgetKind.KPI && agg.equalsIgnoreCase("count"))) {
                throw new WizardArgumentException(prefix + ": agg '" + agg
                        + "' is not supported yet (a kpi widget counts records)");
            }
            String groupBy = null;
            if (kind == PageDefinition.WidgetKind.BAR) {
                groupBy = groupBy(prefix, entity, trimToNull(w.groupBy()));
            } else if (trimToNull(w.groupBy()) != null) {
                throw new WizardArgumentException(prefix + ": only a bar widget takes 'groupBy'");
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
            out.add(new PageDefinition.Widget(kind, entity.name(), title, groupBy, limit));
        }
        return out;
    }

    private static PageDefinition.WidgetKind parseKind(String prefix, String rawKind) {
        String k = trimToNull(rawKind);
        if (k == null) throw new WizardArgumentException(prefix + ": kind is required");
        for (PageDefinition.WidgetKind kind : PageDefinition.WidgetKind.values()) {
            if (kind.wire().equalsIgnoreCase(k)) return kind;
        }
        throw new WizardArgumentException(prefix + ": unknown widget kind '" + k + "' (expected kpi, bar or recent)");
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
            if (!seen.add(target)) throw new WizardArgumentException(prefix + ": page '" + target + "' is already a tab");
            out.add(new PageDefinition.Tab(checkLength(trimToNull(t.title()), MAX_TITLE, prefix + " title"), target));
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
