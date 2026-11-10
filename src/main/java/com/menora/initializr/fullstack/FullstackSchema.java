package com.menora.initializr.fullstack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * What a fullstack request may say, as data: the field types and their aliases, the page types,
 * widget kinds and every other closed list, the limits, the name rules, the reserved names and
 * the scaffold options — read straight from the constants the validators enforce.
 *
 * <p>Served at {@code GET /metadata/fullstack/schema}. The editor used to keep its own copy of
 * each of these by hand (and they drifted: an option the API honoured but the editor never
 * offered, limits that disagreed); it now derives them from this document, a checked-in copy of
 * which is pinned on both sides ({@code FullstackSchemaTest} here, {@code fixturesInSync} in the
 * UI). Other clients — scripts, agents — can read the same thing instead of guessing.
 *
 * <p>Only add to it: a key the editor reads may not change meaning. {@link #VERSION} rises when
 * something is removed or reshaped.
 */
public final class FullstackSchema {

    public static final int VERSION = 1;

    /** The languages of the generated frontend's chrome strings ({@code i18n-strings.ts.mustache}). */
    public static final List<String> LOCALES = List.of("en", "he");

    /** The relation kinds a request may use today ({@link RelationType} declares more than it takes). */
    static final List<RelationType> RELATION_TYPES = List.of(RelationType.MANY_TO_ONE, RelationType.MANY_TO_MANY);

    private static final Map<String, Object> DOCUMENT = build();

    private FullstackSchema() {
    }

    /** The schema document; nested maps and lists, in a stable order. */
    public static Map<String, Object> document() {
        return DOCUMENT;
    }

    private static Map<String, Object> build() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", VERSION);

        Map<String, Object> names = new LinkedHashMap<>();
        names.put("identifier", FullstackRequestValidator.IDENTIFIER_RULE);
        names.put("reservedKeywords", new TreeSet<>(FullstackRequestValidator.RESERVED_JAVA_KEYWORDS));
        names.put("reservedEntityNames", ReservedNames.all());
        names.put("generatedClassSuffixes", ReservedNames.GENERATED_SUFFIXES);
        names.put("sqlName", FullstackRequestValidator.SQL_NAME.pattern());
        names.put("pageId", FullstackPageValidator.PAGE_ID.pattern());
        names.put("transitionName", FullstackRequestValidator.TRANSITION_NAME.pattern());
        doc.put("names", names);

        List<Map<String, Object>> fieldTypes = new ArrayList<>();
        for (FieldType type : FieldType.values()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("type", type.name());
            t.put("tsType", type.tsType());
            List<String> aliases = new ArrayList<>();
            FieldType.ALIASES.forEach((alias, target) -> {
                if (target == type) aliases.add(alias);
            });
            t.put("aliases", aliases);
            fieldTypes.add(t);
        }
        doc.put("fieldTypes", fieldTypes);
        doc.put("relationTypes", RELATION_TYPES.stream().map(Enum::name).toList());

        List<Map<String, Object>> options = new ArrayList<>();
        for (ScaffoldOption option : ScaffoldOption.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("key", option.key());
            o.put("perEntity", option.perEntity());
            o.put("backend", option.readBy(ScaffoldOption.Side.BACKEND));
            o.put("frontend", option.readBy(ScaffoldOption.Side.FRONTEND));
            options.add(o);
        }
        doc.put("scaffoldOptions", options);

        doc.put("locales", LOCALES);
        doc.put("roles", FullstackPageValidator.ROLES);
        doc.put("listViews", FullstackPageValidator.LIST_VIEWS);
        doc.put("pageTypes", wires(PageDefinition.Type.values(), PageDefinition.Type::wire));
        doc.put("widgetKinds", wires(PageDefinition.WidgetKind.values(), PageDefinition.WidgetKind::wire));
        doc.put("aggregates", wires(PageDefinition.Agg.values(), PageDefinition.Agg::wire));
        doc.put("buckets", wires(PageDefinition.Bucket.values(), PageDefinition.Bucket::wire));
        doc.put("dateRanges", wires(PageDefinition.DateRange.values(), PageDefinition.DateRange::wire));
        List<Map<String, Object>> chartKinds = new ArrayList<>();
        for (PageDefinition.ChartKind kind : PageDefinition.ChartKind.values()) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("kind", kind.wire());
            k.put("overGroups", kind.fits(false));
            k.put("overTime", kind.fits(true));
            chartKinds.add(k);
        }
        doc.put("chartKinds", chartKinds);
        doc.put("calendarModes", wires(PageDefinition.CalendarMode.values(), PageDefinition.CalendarMode::wire));
        doc.put("navStyles", wires(PageDefinition.NavStyle.values(), PageDefinition.NavStyle::wire));
        doc.put("listDetails", wires(PageDefinition.Detail.values(), PageDefinition.Detail::wire));
        doc.put("listActions", wires(PageDefinition.ListAction.values(), PageDefinition.ListAction::wire));
        doc.put("inboxQueues", wires(PageDefinition.InboxQueue.values(), PageDefinition.InboxQueue::wire));
        doc.put("navIcons", FullstackPageValidator.NAV_ICONS);

        Map<String, Object> choices = new LinkedHashMap<>();
        choices.put("listPageSizes", FullstackPageValidator.LIST_PAGE_SIZES);
        choices.put("refreshSeconds", FullstackPageValidator.REFRESH_SECONDS);
        choices.put("laneSizes", FullstackPageValidator.LANE_SIZES);
        choices.put("presetPeriods", new TreeSet<>(FullstackPageValidator.PRESET_PERIODS));
        doc.put("choices", choices);

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxPages", FullstackPageValidator.MAX_PAGES);
        limits.put("maxTitle", FullstackPageValidator.MAX_TITLE);
        limits.put("maxDescription", FullstackPageValidator.MAX_DESCRIPTION);
        limits.put("maxGroup", FullstackPageValidator.MAX_GROUP);
        limits.put("maxWidgets", FullstackPageValidator.MAX_WIDGETS);
        limits.put("maxSpan", FullstackPageValidator.MAX_SPAN);
        limits.put("maxText", FullstackPageValidator.MAX_TEXT);
        limits.put("maxLinks", FullstackPageValidator.MAX_LINKS);
        limits.put("defaultListLimit", FullstackPageValidator.DEFAULT_LIST_LIMIT);
        limits.put("maxRecentLimit", FullstackPageValidator.MAX_RECENT_LIMIT);
        limits.put("defaultRecentLimit", FullstackPageValidator.DEFAULT_RECENT_LIMIT);
        limits.put("defaultActivityLimit", FullstackPageValidator.DEFAULT_ACTIVITY_LIMIT);
        limits.put("minActivityLimit", FullstackPageValidator.MIN_ACTIVITY_LIMIT);
        limits.put("maxActivityEntities", FullstackPageValidator.MAX_ACTIVITY_ENTITIES);
        limits.put("maxDashboardFilters", FullstackPageValidator.MAX_DASHBOARD_FILTERS);
        limits.put("minTabs", FullstackPageValidator.MIN_TABS);
        limits.put("maxTabs", FullstackPageValidator.MAX_TABS);
        limits.put("maxCharts", FullstackPageValidator.MAX_CHARTS);
        limits.put("maxSteps", FullstackPageValidator.MAX_STEPS);
        limits.put("defaultStepSize", FullstackPageValidator.DEFAULT_STEP_SIZE);
        limits.put("maxHeaderStats", FullstackPageValidator.MAX_HEADER_STATS);
        limits.put("maxBody", FullstackPageValidator.MAX_BODY);
        limits.put("defaultLaneSize", FullstackPageValidator.DEFAULT_LANE_SIZE);
        limits.put("maxCardFields", FullstackPageValidator.MAX_CARD_FIELDS);
        limits.put("maxWipLimit", FullstackPageValidator.MAX_WIP_LIMIT);
        limits.put("maxSearchEntities", FullstackPageValidator.MAX_SEARCH_ENTITIES);
        limits.put("minPerEntity", FullstackPageValidator.MIN_PER_ENTITY);
        limits.put("maxPerEntity", FullstackPageValidator.MAX_PER_ENTITY);
        limits.put("defaultPerEntity", FullstackPageValidator.DEFAULT_PER_ENTITY);
        limits.put("maxFormSections", FullstackRequestValidator.MAX_FORM_SECTIONS);
        limits.put("maxSectionTitle", FullstackRequestValidator.MAX_SECTION_TITLE);
        limits.put("maxEnumLabel", FullstackRequestValidator.MAX_ENUM_LABEL_LENGTH);
        limits.put("maxTransitions", FullstackRequestValidator.MAX_TRANSITIONS);
        limits.put("maxTransitionLabel", FullstackRequestValidator.MAX_TRANSITION_LABEL);
        doc.put("limits", limits);
        return doc;
    }

    private static <E> List<String> wires(E[] values, Function<E, String> wire) {
        return Arrays.stream(values).map(wire).toList();
    }
}
