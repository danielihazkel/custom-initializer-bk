package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;
import com.menora.initializr.fullstack.FullstackStarterRequest.PageDefinitionDto;
import com.menora.initializr.fullstack.FullstackStarterRequest.TabDto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.menora.initializr.fullstack.FullstackPageValidator.*;
import static com.menora.initializr.fullstack.PageListRules.*;
import static com.menora.initializr.fullstack.PagePresets.*;
import static com.menora.initializr.fullstack.DashboardWidgetRules.*;
import static com.menora.initializr.fullstack.ReportChartRules.*;
import static com.menora.initializr.fullstack.PageChecks.*;

/** The page types with a structure of their own: tabs, wizard steps, record tabs and header stats, tree, master-detail, calendar, board, inbox, content and search. Part of {@link FullstackPageValidator}. */
final class PageStructureRules {

    private PageStructureRules() {}

    static List<PageDefinition.Tab> tabs(String id, List<TabDto> raw, Map<String, PageDefinition.Type> typeById) {
        if (raw == null || raw.size() < MIN_TABS || raw.size() > MAX_TABS) {
            throw new WizardArgumentException("Page '" + id + "' (tabs) needs between " + MIN_TABS + " and "
                    + MAX_TABS + " tabs");
        }
        Set<String> seen = new HashSet<>();
        List<PageDefinition.Tab> out = new ArrayList<>(raw.size());
        for (int ti = 0; ti < raw.size(); ti++) {
            try {
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
            } catch (WizardArgumentException problem) {
                throw problem.at("tabs[" + ti + "]");
            }
        }
        return out;
    }

    /**
     * A wizard's steps. Each names form fields: the entity's fields — all but a generated key —
     * and its relations by field name. Every field and relation the form requires has to be asked
     * for somewhere, and nothing twice. Omitted: the fields in declaration order, then the
     * relations, {@value #DEFAULT_STEP_SIZE} to a step.
     */
    static List<PageDefinition.Step> steps(String prefix, EntityDefinition entity,
                                           List<FullstackStarterRequest.StepDto> raw) {
        Map<String, String> askable = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        // A workflow's status starts in its initial state and moves only by transitions.
        String status = entity.workflow() == null ? null : entity.workflow().statusField();
        // …and its assignee only by Claim, Release and Reassign.
        String assignee = entity.workflow() == null ? null : entity.workflow().assigneeField();
        for (FieldDefinition f : entity.fields()) {
            if (f.primaryKey() && f.generated()) continue;
            if (f.name().equals(status) || f.name().equals(assignee)) continue;
            askable.put(f.name().toLowerCase(Locale.ROOT), f.name());
            if (f.required() || f.primaryKey()) required.add(f.name());
        }
        for (RelationDefinition r : entity.relations()) {
            if (r.type() != RelationType.MANY_TO_ONE) continue;
            askable.put(r.fieldName().toLowerCase(Locale.ROOT), r.fieldName());
            if (r.required()) required.add(r.fieldName());
        }
        if (askable.isEmpty()) {
            throw new WizardArgumentException(prefix + ": " + entity.name() + " has no field to ask for");
        }
        if ((raw == null || raw.isEmpty()) && !entity.formSections().isEmpty()) {
            // The form's own sections, one step each; whatever they leave out is the last step.
            List<PageDefinition.Step> out = new ArrayList<>();
            Set<String> placed = new HashSet<>();
            for (EntityDefinition.FormSection section : entity.formSections()) {
                List<String> asked = section.fields().stream().filter(f -> !f.equals(status) && !f.equals(assignee)).toList();
                if (!asked.isEmpty()) out.add(new PageDefinition.Step(section.title(), asked));
                section.fields().forEach(f -> placed.add(f.toLowerCase(Locale.ROOT)));
            }
            List<String> rest = askable.values().stream().filter(f -> !placed.contains(f.toLowerCase(Locale.ROOT))).toList();
            if (!rest.isEmpty()) out.add(new PageDefinition.Step(null, rest));
            return out;
        }
        if (raw == null || raw.isEmpty()) {
            List<String> all = new ArrayList<>(askable.values());
            List<PageDefinition.Step> out = new ArrayList<>();
            for (int i = 0; i < all.size(); i += DEFAULT_STEP_SIZE) {
                out.add(new PageDefinition.Step(null, all.subList(i, Math.min(all.size(), i + DEFAULT_STEP_SIZE))));
            }
            return out;
        }
        if (raw.size() > MAX_STEPS) {
            throw new WizardArgumentException(prefix + ": at most " + MAX_STEPS + " steps are allowed");
        }
        Set<String> seen = new HashSet<>();
        List<PageDefinition.Step> out = new ArrayList<>();
        for (int si = 0; si < raw.size(); si++) {
            try {
                FullstackStarterRequest.StepDto step = raw.get(si);
                String stepPrefix = prefix + " steps[" + si + "]";
                if (step == null || step.fields() == null || step.fields().isEmpty()) {
                    throw new WizardArgumentException(stepPrefix + ": a step needs at least one field");
                }
                List<String> fields = new ArrayList<>();
                for (String name : step.fields()) {
                    String canonical = name == null ? null : askable.get(name.trim().toLowerCase(Locale.ROOT));
                    if (canonical == null && status != null && name != null && status.equalsIgnoreCase(name.trim())) {
                        throw new WizardArgumentException(stepPrefix + ": '" + status + "' is set by the workflow, so a step cannot ask for it");
                    }
                    if (canonical == null && assignee != null && name != null && assignee.equalsIgnoreCase(name.trim())) {
                        throw new WizardArgumentException(stepPrefix + ": '" + assignee + "' is set by claiming and reassigning, so a step cannot ask for it");
                    }
                    if (canonical == null) {
                        throw new WizardArgumentException(stepPrefix + ": '" + name + "' is not a field of the "
                                + entity.name() + " form");
                    }
                    if (!seen.add(canonical)) {
                        throw new WizardArgumentException(stepPrefix + ": '" + canonical + "' is already asked for");
                    }
                    fields.add(canonical);
                }
                out.add(new PageDefinition.Step(checkLength(trimToNull(step.title()), MAX_TITLE, stepPrefix + " title"),
                        fields));
            } catch (WizardArgumentException problem) {
                throw problem.at("steps[" + si + "]");
            }
        }
        for (String name : required) {
            if (!seen.contains(name)) {
                throw new WizardArgumentException(prefix + ": the required field '" + name
                        + "' is not asked for in any step");
            }
        }
        return out;
    }

    /**
     * A record page's header tiles: the named aggregates over related lists, else one row count per
     * related list tab. Each goes through the child's first relation to the record entity.
     */
    static List<PageDefinition.HeaderStat> headerStats(String prefix, EntityDefinition entity,
                                                       List<FullstackStarterRequest.HeaderStatDto> raw,
                                                       List<PageDefinition.ChildTab> childTabs,
                                                       Map<String, EntityDefinition> entitiesByLower) {
        List<PageDefinition.HeaderStat> out = new ArrayList<>();
        if (raw == null) {
            for (PageDefinition.ChildTab tab : childTabs) {
                if (out.size() == MAX_HEADER_STATS) break;
                out.add(new PageDefinition.HeaderStat(tab.entity(), tab.via(), PageDefinition.Agg.COUNT, null, null));
            }
            return out;
        }
        if (raw.size() > MAX_HEADER_STATS) {
            throw new WizardArgumentException(prefix + ": at most " + MAX_HEADER_STATS + " header stats are allowed");
        }
        for (int i = 0; i < raw.size(); i++) {
            try {
                FullstackStarterRequest.HeaderStatDto s = raw.get(i);
                String statPrefix = prefix + " headerStats[" + i + "]";
                if (s == null) throw new WizardArgumentException(statPrefix + " is null");
                EntityDefinition child = requireEntity(entitiesByLower, s.child(), statPrefix);
                // An unnamed link follows the child's tab, so a tile counts exactly what its tab lists.
                String requested = trimToNull(s.via());
                String viaRel = requested != null ? linkVia(statPrefix, child, entity, requested)
                        : childTabs.stream().filter(t -> t.entity().equals(child.name())).map(PageDefinition.ChildTab::via)
                            .findFirst().orElseGet(() -> linkVia(statPrefix, child, entity, null));
                PageDefinition.Agg agg = parseAgg(statPrefix, s.agg());
                out.add(new PageDefinition.HeaderStat(child.name(), viaRel, agg,
                        aggField(statPrefix, child, agg, trimToNull(s.field())),
                        checkLength(trimToNull(s.title()), MAX_TITLE, statPrefix + " title")));
            } catch (WizardArgumentException problem) {
                throw problem.at("headerStats[" + i + "]");
            }
        }
        return out;
    }

    /** Master-detail parents and record pages address one row by a single id. */
    /**
     * The link a tree page is drawn by: a MANY_TO_ONE of the entity to itself — named, or the only
     * one it has. Without one there is no tree; with several the page has to say which.
     */
    static String treeLink(String prefix, EntityDefinition entity, String raw) {
        List<RelationDefinition> own = entity.relations().stream()
                .filter(r -> r.targetEntity().equalsIgnoreCase(entity.name())).toList();
        if (own.isEmpty()) {
            throw new WizardArgumentException(prefix + ": " + entity.name()
                    + " has no relation to itself — a tree needs one (a parent)").on("entity");
        }
        if (raw != null) {
            return own.stream().filter(r -> r.fieldName().equalsIgnoreCase(raw)).findFirst()
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": via '" + raw + "' is not a relation of "
                            + entity.name() + " to itself (" + String.join(", ", own.stream().map(RelationDefinition::fieldName).toList())
                            + ")").on("via"))
                    .fieldName();
        }
        if (own.size() > 1) {
            throw new WizardArgumentException(prefix + ": " + entity.name() + " points at itself through "
                    + String.join(" and ", own.stream().map(RelationDefinition::fieldName).toList())
                    + " — say which with 'via'").on("via");
        }
        return own.get(0).fieldName();
    }

    static void requireSinglePk(String prefix, EntityDefinition entity) {
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
    static String via(String prefix, EntityDefinition child, EntityDefinition parent, String requested) {
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

    /** A record page's link to a related entity: the named relation, else the child's first one. */
    private static String linkVia(String prefix, EntityDefinition child, EntityDefinition parent, String requested) {
        List<String> rels = relationsTo(child, parent);
        if (rels.isEmpty()) {
            throw new WizardArgumentException(prefix + ": " + child.name() + " has no relation to " + parent.name());
        }
        return requested == null ? rels.get(0) : via(prefix, child, parent, requested);
    }

    /** A record page's related lists: the named entities, else every entity with a relation to it.
     *  Each links through its named relation, else its first one to the record entity. */
    static List<PageDefinition.ChildTab> childTabs(String prefix, EntityDefinition entity,
                                                  List<FullstackStarterRequest.ChildTabDto> raw,
                                                  List<EntityDefinition> entities,
                                                  Map<String, EntityDefinition> entitiesByLower,
                                                  Set<String> scaffoldOpts) {
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
                try {
                    String itemPrefix = prefix + " childTabs[" + i + "]";
                    FullstackStarterRequest.ChildTabDto item = raw.get(i);
                    if (item == null) throw new WizardArgumentException(itemPrefix + " is null");
                    EntityDefinition child = requireEntity(entitiesByLower, item.entity(), itemPrefix);
                    String viaRel = linkVia(itemPrefix, child, entity, trimToNull(item.via()));
                    if (!seen.add(child.name())) {
                        throw new WizardArgumentException(itemPrefix + ": " + child.name() + " is already a tab");
                    }
                    // How the related list opens: the columns and sort of an entity-list page.
                    boolean audit = auditApplies(child, scaffoldOpts);
                    out.add(new PageDefinition.ChildTab(child.name(), viaRel,
                            columns(itemPrefix, child, item.columns(), audit), sort(itemPrefix, child, item.sort(), audit)));
                } catch (WizardArgumentException problem) {
                    throw problem.at("childTabs[" + i + "]");
                }
            }
        }
        if (out.size() > MAX_TABS - 1) {
            throw new WizardArgumentException(prefix + ": at most " + (MAX_TABS - 1) + " related lists are allowed");
        }
        return out;
    }

    // ── Calendar, board, content and search pages ───────────────────────────

    /** A calendar page's date field(s) and views. Both dates must be filterable: the visible window
     *  is fetched through the list's own {@code <field>From}/{@code <field>To} params. */
    static PageDefinition.CalendarSpec calendar(String prefix, EntityDefinition entity, PageDefinitionDto p) {
        String requested = trimToNull(p.dateField());
        FieldDefinition date;
        if (requested == null) {
            date = entity.fields().stream()
                    .filter(f -> !f.primaryKey() && f.filterable() && f.type().isTemporal())
                    .findFirst()
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " has no filterable date field to place its rows by"));
        } else {
            date = calendarDate(prefix, entity, requested, "dateField");
        }
        String rawEnd = trimToNull(p.endField());
        FieldDefinition end = rawEnd == null ? null : calendarDate(prefix, entity, rawEnd, "endField");
        if (end != null && end.name().equals(date.name())) {
            throw new WizardArgumentException(prefix + ": endField must differ from dateField ('" + date.name() + "')");
        }
        List<PageDefinition.CalendarMode> modes = new ArrayList<>();
        if (p.modes() != null) {
            if (p.modes().isEmpty()) throw new WizardArgumentException(prefix + ": 'modes' needs at least one view when given");
            for (String raw : p.modes()) {
                String m = trimToNull(raw);
                PageDefinition.CalendarMode mode = null;
                for (PageDefinition.CalendarMode candidate : PageDefinition.CalendarMode.values()) {
                    if (candidate.wire().equalsIgnoreCase(m == null ? "" : m)) mode = candidate;
                }
                if (mode == null) {
                    throw new WizardArgumentException(prefix + ": unknown mode '" + raw + "' (expected month, week, agenda or timeline)");
                }
                if (modes.contains(mode)) throw new WizardArgumentException(prefix + ": mode '" + mode.wire() + "' is listed twice");
                modes.add(mode);
            }
        } else {
            modes.add(PageDefinition.CalendarMode.MONTH);
        }
        if (modes.contains(PageDefinition.CalendarMode.TIMELINE) && end == null) {
            throw new WizardArgumentException(prefix + ": the timeline mode needs an endField — a bar runs from dateField to it");
        }
        return new PageDefinition.CalendarSpec(date.name(), end == null ? null : end.name(), modes);
    }

    private static FieldDefinition calendarDate(String prefix, EntityDefinition entity, String requested, String what) {
        FieldDefinition f = fieldOf(prefix, entity, requested, what);
        if (f.primaryKey() || !f.filterable() || !f.type().isTemporal()) {
            throw new WizardArgumentException(prefix + ": " + what + " '" + f.name()
                    + "' must be a filterable, non-key date field of " + entity.name());
        }
        return f;
    }

    /** A board page's lanes and cards. The lane field must be filterable: each lane is its own
     *  list call, filtered to the lane's value. */
    static PageDefinition.BoardSpec board(String prefix, EntityDefinition entity, PageDefinitionDto p) {
        String requested = trimToNull(p.laneField());
        FieldDefinition lane;
        if (requested == null) {
            List<FieldDefinition> candidates = entity.fields().stream()
                    .filter(f -> !f.primaryKey() && f.filterable() && (f.type().isEnum() || f.type().isBoolean()))
                    .toList();
            lane = candidates.stream().filter(f -> f.type().isEnum()).findFirst()
                    .or(() -> candidates.stream().findFirst())
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " has no filterable enum or boolean field to split into lanes"));
        } else {
            lane = fieldOf(prefix, entity, requested, "laneField");
            if (lane.primaryKey() || !lane.filterable() || !(lane.type().isEnum() || lane.type().isBoolean())) {
                throw new WizardArgumentException(prefix + ": laneField '" + lane.name()
                        + "' must be a filterable enum or boolean field of " + entity.name());
            }
        }
        List<String> values = lane.type().isBoolean() ? List.of("true", "false") : lane.enumValues();
        List<String> lanes = new ArrayList<>();
        if (p.lanes() != null) {
            if (p.lanes().isEmpty()) throw new WizardArgumentException(prefix + ": 'lanes' needs at least one lane when given");
            for (String raw : p.lanes()) {
                String v = trimToNull(raw);
                String canonical = v == null ? null : values.stream().filter(c -> c.equalsIgnoreCase(v)).findFirst().orElse(null);
                if (canonical == null) {
                    throw new WizardArgumentException(prefix + ": lane '" + raw + "' is not one of " + values);
                }
                if (lanes.contains(canonical)) throw new WizardArgumentException(prefix + ": lane '" + canonical + "' is listed twice");
                lanes.add(canonical);
            }
        } else {
            lanes.addAll(values);
        }
        List<String> cards = cardFields(prefix, entity, p.cardFields(), defaultCardFields(entity, lane.name()));
        Map<String, Integer> limits = new LinkedHashMap<>();
        if (p.wipLimits() != null) {
            for (Map.Entry<String, Integer> en : p.wipLimits().entrySet()) {
                String canonical = lanes.stream().filter(l -> l.equalsIgnoreCase(en.getKey() == null ? "" : en.getKey().trim()))
                        .findFirst()
                        .orElseThrow(() -> new WizardArgumentException(prefix + " wipLimits: '" + en.getKey()
                                + "' is not one of its lanes " + lanes));
                Integer limit = en.getValue();
                if (limit == null || limit < 1 || limit > MAX_WIP_LIMIT) {
                    throw new WizardArgumentException(prefix + " wipLimits: lane '" + canonical + "' must hold 1 to "
                            + MAX_WIP_LIMIT + " cards, got " + limit);
                }
                limits.put(canonical, limit);
            }
        }
        Integer size = p.laneSize();
        if (size != null && !LANE_SIZES.contains(size)) {
            throw new WizardArgumentException(prefix + ": laneSize must be 10, 20 or 50, got " + size);
        }
        return new PageDefinition.BoardSpec(lane.name(), lanes, cards, limits, size == null ? DEFAULT_LANE_SIZE : size);
    }

    /** What a card shows when the page names nothing: a heading (the first text field, else the
     *  key), then the next two fields other than the lane field and the first relation, up to four. */
    /** What a board card or an inbox row shows: 1–4 field or relation names (the first naming the row), else the defaults. */
    static List<String> cardFields(String prefix, EntityDefinition entity, List<String> raw, List<String> defaults) {
        if (raw == null) return defaults;
        Map<String, String> known = new LinkedHashMap<>();
        for (FieldDefinition f : entity.fields()) known.put(f.name().toLowerCase(Locale.ROOT), f.name());
        for (RelationDefinition r : entity.relations()) {
            if (r.type() == RelationType.MANY_TO_ONE) known.put(r.fieldName().toLowerCase(Locale.ROOT), r.fieldName());
        }
        if (raw.isEmpty() || raw.size() > MAX_CARD_FIELDS) {
            throw new WizardArgumentException(prefix + ": 'cardFields' takes 1 to " + MAX_CARD_FIELDS + " fields");
        }
        List<String> cards = new ArrayList<>();
        for (String name : raw) {
            String canonical = trimToNull(name) == null ? null : known.get(name.trim().toLowerCase(Locale.ROOT));
            if (canonical == null) {
                throw new WizardArgumentException(prefix + " cardFields: '" + name + "' is not a field or relation of "
                        + entity.name() + " (expected one of " + new ArrayList<>(known.values()) + ")");
            }
            if (cards.contains(canonical)) throw new WizardArgumentException(prefix + " cardFields: '" + canonical + "' is listed twice");
            cards.add(canonical);
        }
        return cards;
    }

    /** An inbox's queues: 1–3 of mine, unassigned and open, each once. Absent: mine, then unassigned. */
    static List<PageDefinition.InboxQueue> inboxQueues(String prefix, List<String> raw) {
        if (raw == null) return List.of(PageDefinition.InboxQueue.MINE, PageDefinition.InboxQueue.UNASSIGNED);
        if (raw.isEmpty()) throw new WizardArgumentException(prefix + ": 'queues' needs at least one queue when given");
        List<PageDefinition.InboxQueue> out = new ArrayList<>();
        for (String q : raw) {
            String v = trimToNull(q);
            PageDefinition.InboxQueue queue = java.util.Arrays.stream(PageDefinition.InboxQueue.values())
                    .filter(x -> x.wire().equalsIgnoreCase(v == null ? "" : v)).findFirst()
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": unknown queue '" + q + "' (expected mine, unassigned or open)"));
            if (out.contains(queue)) throw new WizardArgumentException(prefix + ": queue '" + queue.wire() + "' is listed twice");
            out.add(queue);
        }
        return out;
    }

    static List<String> defaultCardFields(EntityDefinition entity, String laneField) {
        List<String> out = new ArrayList<>();
        FieldDefinition heading = entity.fields().stream()
                .filter(f -> !f.primaryKey() && f.type().isTextual())
                .findFirst()
                .orElseGet(() -> entity.fields().stream().filter(FieldDefinition::primaryKey).findFirst().orElse(entity.fields().get(0)));
        out.add(heading.name());
        for (FieldDefinition f : entity.fields()) {
            if (out.size() >= 3) break;
            if (f.primaryKey() || f.name().equals(laneField) || out.contains(f.name()) || f.type().isText()) continue;
            out.add(f.name());
        }
        entity.relations().stream().filter(r -> r.type() == RelationType.MANY_TO_ONE).findFirst()
                .ifPresent(r -> out.add(r.fieldName()));
        return out.size() > MAX_CARD_FIELDS ? out.subList(0, MAX_CARD_FIELDS) : out;
    }

    /** A content page's text: its length, and every link either another page anyone can open or an
     *  http(s) / mailto address. */
    static String body(String prefix, String raw, Map<String, PageDefinition.Type> typeById,
                       Map<String, Boolean> hiddenById) {
        String body = raw == null ? null : raw.strip();
        if (body == null || body.isEmpty()) throw new WizardArgumentException(prefix + " needs a body");
        if (body.length() > MAX_BODY) {
            throw new WizardArgumentException(prefix + " body must be at most " + MAX_BODY + " characters");
        }
        for (ContentMarkdown.Block block : ContentMarkdown.parse(body)) {
            for (List<ContentMarkdown.Run> item : block.items()) {
                for (ContentMarkdown.Run run : item) {
                    if (run.kind() == ContentMarkdown.RunKind.LINK && !ContentMarkdown.isExternal(run.target())) {
                        throw new WizardArgumentException(prefix + ": link '" + run.target()
                                + "' must be an http(s) or mailto address, or a page (#/page-id)");
                    }
                    if (run.kind() != ContentMarkdown.RunKind.PAGE_LINK) continue;
                    PageDefinition.Type type = typeById.get(run.target());
                    if (type == null) throw new WizardArgumentException(prefix + ": links to no page '" + run.target() + "'");
                    if (type == PageDefinition.Type.RECORD) {
                        throw new WizardArgumentException(prefix + ": cannot link to the record page '" + run.target()
                                + "' — it opens from a row");
                    }
                    if (Boolean.TRUE.equals(hiddenById.get(run.target())) && type != PageDefinition.Type.WIZARD
                            && type != PageDefinition.Type.FORM && type != PageDefinition.Type.SEARCH) {
                        throw new WizardArgumentException(prefix + ": page '" + run.target()
                                + "' is hidden and not a wizard, so a link cannot open it");
                    }
                }
            }
        }
        return body;
    }

    /** A search page's entities (each with text the list endpoint can search), matches per entity
     *  and whether the header gets a search box. */
    static PageDefinition.SearchSpec search(String prefix, PageDefinitionDto p, List<EntityDefinition> entities,
                                            Map<String, EntityDefinition> entitiesByLower) {
        List<String> names = new ArrayList<>();
        if (p.entities() != null) {
            if (p.entities().isEmpty() || p.entities().size() > MAX_SEARCH_ENTITIES) {
                throw new WizardArgumentException(prefix + ": 'entities' takes 1 to " + MAX_SEARCH_ENTITIES + " entities");
            }
            for (String raw : p.entities()) {
                EntityDefinition e = requireEntity(entitiesByLower, raw, prefix);
                if (!searchable(e)) {
                    throw new WizardArgumentException(prefix + ": " + e.name()
                            + " has no searchable text field, so a search cannot find its rows");
                }
                if (names.contains(e.name())) throw new WizardArgumentException(prefix + ": " + e.name() + " is listed twice");
                names.add(e.name());
            }
        } else {
            entities.stream().filter(PageStructureRules::searchable).limit(MAX_SEARCH_ENTITIES).forEach(e -> names.add(e.name()));
            if (names.isEmpty()) {
                throw new WizardArgumentException(prefix + ": no entity has a searchable text field to search");
            }
        }
        Integer per = p.perEntity();
        if (per != null && (per < MIN_PER_ENTITY || per > MAX_PER_ENTITY)) {
            throw new WizardArgumentException(prefix + ": perEntity must be between " + MIN_PER_ENTITY + " and "
                    + MAX_PER_ENTITY + ", got " + per);
        }
        return new PageDefinition.SearchSpec(names, per == null ? DEFAULT_PER_ENTITY : per, Boolean.TRUE.equals(p.shellSearch()));
    }

    /** Whether the entity's list endpoint searches text ({@code ?q=}): some searchable STRING/TEXT field. */
    static boolean searchable(EntityDefinition e) {
        return e.fields().stream().anyMatch(f -> f.searchable() && f.type().isTextual());
    }
}
