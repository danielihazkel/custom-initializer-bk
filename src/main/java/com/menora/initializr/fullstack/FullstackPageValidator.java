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
    /** The pages one links widget can open. */
    static final int MAX_LINKS = 8;
    /** A list widget's rows per page when it names none. */
    static final int DEFAULT_LIST_LIMIT = 10;
    static final int MIN_TABS = 2;
    static final int MAX_TABS = 6;
    /** A text widget's content. */
    static final int MAX_TEXT = 2000;
    /** The logical roles the ldap-auth dependency's security package defines (its Constants). */
    static final List<String> ROLES = List.of("ADMIN", "USER");
    static final int MAX_TITLE = 80;
    static final int MAX_DESCRIPTION = 300;
    static final int MAX_RECENT_LIMIT = 20;
    static final int DEFAULT_RECENT_LIMIT = 5;

    static final int MAX_GROUP = 40;
    static final int MAX_SPAN = 4;
    static final int MAX_CHARTS = 4;
    static final int MAX_STEPS = 8;
    static final int DEFAULT_STEP_SIZE = 4;
    static final int MAX_HEADER_STATS = 4;
    /** The rows-per-page choices the generated list's pager offers — a list page may open on one. */
    static final List<Integer> LIST_PAGE_SIZES = List.of(10, 20, 50, 100);
    /** How often a dashboard may reload its widgets, in seconds — the editor's choices. */
    static final List<Integer> REFRESH_SECONDS = List.of(30, 60, 300, 900);
    /** The list views an entity can enable (EntityDefinition.listViews). */
    static final List<String> LIST_VIEWS = List.of("table", "cards", "kanban", "calendar");

    /** The lucide icons a page may put in the nav. The shell imports exactly the ones in use, so the
     *  list is a whitelist rather than "any lucide name": a typo would otherwise fail the build of the
     *  generated project instead of the request. */
    static final List<String> NAV_ICONS = List.of(
            "BarChart3", "Building2", "Calendar", "Columns3", "FileText", "Inbox", "Layers", "LayoutDashboard",
            "ListChecks", "Package", "PanelLeft", "Search", "Settings", "ShoppingCart", "Star", "Table2", "Tag",
            "Ticket", "Truck", "Upload", "Users", "Wallet", "Wand2");
    /** A content page's text. */
    static final int MAX_BODY = 8000;
    /** The cards a board lane may load at a time. */
    static final List<Integer> LANE_SIZES = List.of(10, 20, 50);
    static final int DEFAULT_LANE_SIZE = 20;
    static final int MAX_CARD_FIELDS = 4;
    static final int MAX_WIP_LIMIT = 999;
    /** The entities one search page fans out to. */
    static final int MAX_SEARCH_ENTITIES = 8;
    static final int MIN_PER_ENTITY = 3;
    static final int MAX_PER_ENTITY = 10;
    static final int DEFAULT_PER_ENTITY = 5;

    private FullstackPageValidator() {}

    /** As {@link #validateAndConvert(List, List, Set)} with no scaffold opts (no audit columns). */
    public static List<PageDefinition> validateAndConvert(List<PageDefinitionDto> raw, List<EntityDefinition> entities) {
        return validateAndConvert(raw, entities, Set.of());
    }

    /**
     * @param scaffoldOpts the request's {@code opts.scaffold} names — a list page may only show or
     *                     sort by {@code createdAt}/{@code updatedAt} when {@code audit} is among
     *                     them (or overridden on the entity)
     */
    public static List<PageDefinition> validateAndConvert(List<PageDefinitionDto> raw, List<EntityDefinition> entities,
                                                          Set<String> scaffoldOpts) {
        if (raw == null || raw.isEmpty()) return List.of();
        if (raw.size() > MAX_PAGES) {
            throw new WizardArgumentException("At most " + MAX_PAGES + " pages are allowed");
        }
        Map<String, EntityDefinition> entitiesByLower = new HashMap<>();
        for (EntityDefinition e : entities) entitiesByLower.put(e.name().toLowerCase(Locale.ROOT), e);

        // Pass 1: ids + types, so tabs (and links widgets) can reference pages declared after them.
        Map<String, PageDefinition.Type> typeById = new LinkedHashMap<>();
        Map<String, Boolean> hiddenById = new LinkedHashMap<>();
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
            hiddenById.put(id, Boolean.TRUE.equals(p.hidden()) || typeById.get(id) == PageDefinition.Type.RECORD);
        }
        // Decided here, before any page is checked in depth: a layout with nothing in the nav gets
        // this message rather than one about a widget that happens to point at a hidden page.
        if (hiddenById.values().stream().allMatch(Boolean::booleanValue)) {
            throw new WizardArgumentException("At least one page must be visible in the navigation");
        }

        List<PageDefinition> pages = new ArrayList<>(raw.size());
        Map<String, String> recordPageByEntity = new HashMap<>();
        Map<String, String> wizardPageByEntity = new HashMap<>();
        Map<String, String> importPageByEntity = new HashMap<>();
        String searchPage = null;
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
            String group = navGroup(id, type, hidden, p.group());
            String icon = navIcon(id, type, hidden, p.icon());
            PageDefinition page = switch (type) {
                case ENTITY_LIST -> {
                    String prefix = "Page '" + id + "'";
                    EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                    boolean audit = auditApplies(entity, scaffoldOpts);
                    PageDefinition.Detail detail = detail(prefix, p.detail());
                    // A side pane addresses its row by one key in the route.
                    if (detail == PageDefinition.Detail.SIDE) requireSinglePk(prefix, entity);
                    yield new PageDefinition(id, type, title, description, hidden, entity.name(),
                            presetFilter(prefix, entity, p.presetFilter()), null, null)
                            .withListPresentation(columns(prefix, entity, p.columns(), audit),
                                    sort(prefix, entity, p.sort(), audit), view(prefix, entity, p.view()),
                                    pageSize(prefix, p.pageSize()))
                            .withDetail(detail);
                }
                case DASHBOARD -> {
                    PageDefinition.DateRange range = parseDateRange(id, p.dateRange());
                    List<PageDefinition.Widget> widgets = widgets(id, p.widgets(), entitiesByLower, range != null,
                            typeById, hiddenById, scaffoldOpts);
                    if (range != null && widgets.stream().allMatch(w -> w.dateField() == null)) {
                        throw new WizardArgumentException("Page '" + id + "' has a dateRange, but none of its widgets"
                                + " counts an entity with a filterable date field for it to limit");
                    }
                    Integer refresh = p.refreshSeconds();
                    if (refresh != null && !REFRESH_SECONDS.contains(refresh)) {
                        throw new WizardArgumentException("Page '" + id + "': refreshSeconds must be one of 30, 60, 300 or 900, got " + refresh);
                    }
                    if (refresh != null && widgets.stream().noneMatch(w -> w.kind() != PageDefinition.WidgetKind.TEXT
                            && w.kind() != PageDefinition.WidgetKind.LINKS && w.kind() != PageDefinition.WidgetKind.LIST)) {
                        throw new WizardArgumentException("Page '" + id + "' refreshes every " + refresh
                                + " seconds, but none of its widgets shows data to reload");
                    }
                    yield new PageDefinition(id, type, title, description, hidden, null, null, widgets, null)
                            .withDateRange(range)
                            .withRefreshSeconds(refresh);
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
                    // The child list may open with its own columns and sort, like an entity-list page.
                    boolean childAudit = auditApplies(child, scaffoldOpts);
                    yield new PageDefinition(id, type, title, description, hidden, null, null, null, null,
                            parent.name(), child.name(), via, null, null, null, null, null, null, null)
                            .withShowParent(Boolean.TRUE.equals(p.showParent()))
                            .withListPresentation(columns(prefix + " child", child, p.columns(), childAudit),
                                    sort(prefix + " child", child, p.sort(), childAudit), null, null);
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
                    List<PageDefinition.ChildTab> childTabs = childTabs(prefix, entity, p.childTabs(), entities, entitiesByLower, scaffoldOpts);
                    yield new PageDefinition(id, type, title, description, true, entity.name(), null, null, null,
                            null, null, null, childTabs, null, null, null, null, null,
                            headerStats(prefix, entity, p.headerStats(), childTabs, entitiesByLower));
                }
                case WIZARD -> {
                    String prefix = "Page '" + id + "' (wizard)";
                    EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                    if (entity.readOnly()) {
                        throw new WizardArgumentException(prefix + ": " + entity.name()
                                + " is read-only, so there is nothing to create");
                    }
                    String previous = wizardPageByEntity.putIfAbsent(entity.name(), id);
                    if (previous != null) {
                        throw new WizardArgumentException(prefix + ": " + entity.name()
                                + " already has a wizard page ('" + previous + "')");
                    }
                    yield PageDefinition.wizard(id, title, description, hidden, entity.name(),
                            steps(prefix, entity, p.steps()));
                }
                case CALENDAR -> {
                    String prefix = "Page '" + id + "' (calendar)";
                    EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                    PageDefinition.CalendarSpec spec = calendar(prefix, entity, p);
                    Map<String, String> preset = presetFilter("Page '" + id + "'", entity, p.presetFilter());
                    for (String field : java.util.stream.Stream.of(spec.dateField(), spec.endField()).filter(java.util.Objects::nonNull).toList()) {
                        if (preset.keySet().stream().anyMatch(k -> k.equals(field) || k.equals(field + "From") || k.equals(field + "To"))) {
                            throw new WizardArgumentException(prefix + ": presetFilter cannot name '" + field
                                    + "' — the calendar's own range sets it");
                        }
                    }
                    yield new PageDefinition(id, type, title, description, hidden, entity.name(), preset, null, null)
                            .withSpec(spec);
                }
                case BOARD -> {
                    String prefix = "Page '" + id + "' (board)";
                    EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                    PageDefinition.BoardSpec spec = board(prefix, entity, p);
                    Map<String, String> preset = presetFilter("Page '" + id + "'", entity, p.presetFilter());
                    if (preset.containsKey(spec.laneField())) {
                        throw new WizardArgumentException(prefix + ": presetFilter cannot name '" + spec.laneField()
                                + "' — the lanes already split by it");
                    }
                    PageDefinition.ListSort sort = sort(prefix, entity, p.sort(), auditApplies(entity, scaffoldOpts));
                    yield new PageDefinition(id, type, title, description, hidden, entity.name(), preset, null, null)
                            .withListPresentation(null, sort, null, null)
                            .withSpec(spec);
                }
                case CONTENT -> {
                    String prefix = "Page '" + id + "' (content)";
                    // No entity to borrow a name from, so the nav label has to be given.
                    if (title == null) throw new WizardArgumentException(prefix + " needs a title");
                    yield new PageDefinition(id, type, title, description, hidden, null, null, null, null)
                            .withSpec(new PageDefinition.ContentSpec(body(prefix, p.body(), typeById, hiddenById)));
                }
                case IMPORT -> {
                    String prefix = "Page '" + id + "' (import)";
                    EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                    if (entity.readOnly()) {
                        throw new WizardArgumentException(prefix + ": " + entity.name()
                                + " is read-only, so there is nothing to import");
                    }
                    if (Boolean.FALSE.equals(entity.opts().get("csvImport"))) {
                        throw new WizardArgumentException(prefix + " imports " + entity.name()
                                + ", whose csvImport option is off");
                    }
                    String previous = importPageByEntity.putIfAbsent(entity.name(), id);
                    if (previous != null) {
                        throw new WizardArgumentException(prefix + ": " + entity.name()
                                + " already has an import page ('" + previous + "')");
                    }
                    yield new PageDefinition(id, type, title, description, hidden, entity.name(), null, null, null);
                }
                case SEARCH -> {
                    String prefix = "Page '" + id + "' (search)";
                    if (searchPage != null) {
                        throw new WizardArgumentException(prefix + ": a layout has one search page ('" + searchPage
                                + "' already is) — the header search box opens it");
                    }
                    searchPage = id;
                    PageDefinition.SearchSpec spec = search(prefix, p, entities, entitiesByLower);
                    if (hidden && !spec.shellSearch()) {
                        throw new WizardArgumentException(prefix + " is hidden, so it needs shellSearch: nothing else opens it");
                    }
                    yield new PageDefinition(id, type, title, description, hidden, null, null, null, null).withSpec(spec);
                }
            };
            pages.add(page.withNav(group, icon).withRoles(roles(id, p.roles())));
        }
        if (!anyVisible) throw new WizardArgumentException("At least one page must be visible in the navigation");
        // A list that opens rows on the record page needs one for its entity.
        for (PageDefinition page : pages) {
            if (page.detail() == PageDefinition.Detail.RECORD && !recordPageByEntity.containsKey(page.entity())) {
                throw new WizardArgumentException("Page '" + page.id() + "' opens rows on a record page, but "
                        + page.entity() + " has none");
            }
        }
        // The app opens on the first nav page, so everyone must be allowed there.
        pages.stream().filter(q -> !q.hidden()).findFirst().filter(q -> !q.roles().isEmpty()).ifPresent(q -> {
            throw new WizardArgumentException("Page '" + q.id() + "' is the start page, so it takes no 'roles'"
                    + " — everyone opens the app there");
        });
        // A tab shows whatever it embeds, so the restriction belongs on the tabs page.
        for (PageDefinition page : pages) {
            for (PageDefinition.Tab tab : page.tabs()) {
                pages.stream().filter(q -> q.id().equals(tab.page()) && !q.roles().isEmpty()).findFirst().ifPresent(q -> {
                    throw new WizardArgumentException("Page '" + q.id() + "' is a tab of '" + page.id()
                            + "', so it takes no 'roles' — restrict '" + page.id() + "' instead");
                });
            }
        }
        return pages;
    }

    /**
     * The entities as the pages need them: an entity with an import page gets the {@code csvImport}
     * scaffold option switched on, so its backend grows the import endpoint and its frontend the
     * import screen. Runs before either render path, which then see the same entity model.
     */
    public static List<EntityDefinition> applyPageImplications(List<EntityDefinition> entities, List<PageDefinition> pages) {
        Set<String> imported = new HashSet<>();
        for (PageDefinition p : pages) {
            if (p.type() == PageDefinition.Type.IMPORT) imported.add(p.entity());
        }
        if (imported.isEmpty()) return entities;
        return entities.stream().map(e -> imported.contains(e.name()) ? e.withOpt("csvImport", true) : e).toList();
    }

    /** The roles a page is restricted to: the generated security's ADMIN / USER, upper-cased. */
    private static List<String> roles(String id, List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String r : raw) {
            String role = trimToNull(r) == null ? null : r.trim().toUpperCase(Locale.ROOT);
            if (!ROLES.contains(role)) {
                throw new WizardArgumentException("Page '" + id + "': unknown role '" + r + "' (expected "
                        + String.join(" or ", ROLES) + ")");
            }
            out.add(role);
        }
        return List.copyOf(out);
    }

    private static PageDefinition.Type parseType(String id, String rawType) {
        String t = trimToNull(rawType);
        if (t == null) throw new WizardArgumentException("Page '" + id + "' type is required");
        String lower = t.toLowerCase(Locale.ROOT);
        for (PageDefinition.Type type : PageDefinition.Type.values()) {
            if (type.wire().equals(lower)) return type;
        }
        throw new WizardArgumentException("Page '" + id + "': unknown type '" + t
                + "' (expected entity-list, dashboard, tabs, master-detail, record, report, wizard, calendar,"
                + " board, content, import or search)");
    }

    /** A nav section name. Only a page that is in the nav can sit in a section of it. */
    private static String navGroup(String id, PageDefinition.Type type, boolean hidden, String raw) {
        String group = checkLength(trimToNull(raw), MAX_GROUP, "Page '" + id + "' group");
        if (group != null && hidden) throw new WizardArgumentException(offNav(id, type) + "'group'");
        return group;
    }

    /** Why a page takes no nav group/icon: a record page is never in the nav, any other hidden page
     *  was hidden on purpose. */
    private static String offNav(String id, PageDefinition.Type type) {
        return type == PageDefinition.Type.RECORD
                ? "Page '" + id + "' (record) opens from a row of its entity, so it takes no nav "
                : "Page '" + id + "' is hidden, so it takes no nav ";
    }

    /** A nav icon: one of {@link #NAV_ICONS}, matched ignoring case and returned as declared. */
    private static String navIcon(String id, PageDefinition.Type type, boolean hidden, String raw) {
        String icon = trimToNull(raw);
        if (icon == null) return null;
        if (hidden) throw new WizardArgumentException(offNav(id, type) + "'icon'");
        return NAV_ICONS.stream().filter(i -> i.equalsIgnoreCase(icon)).findFirst()
                .orElseThrow(() -> new WizardArgumentException("Page '" + id + "': unknown icon '" + icon
                        + "' (expected one of " + String.join(", ", NAV_ICONS) + ")"));
    }

    /** A property that belongs to another page type is a mistake worth reporting, not ignoring. */
    private static void rejectForeignProps(String id, PageDefinition.Type type, PageDefinitionDto p) {
        String prefix = "Page '" + id + "' (" + type.wire() + ") ";
        if (type != PageDefinition.Type.ENTITY_LIST && type != PageDefinition.Type.RECORD
                && type != PageDefinition.Type.REPORT && type != PageDefinition.Type.WIZARD
                && type != PageDefinition.Type.CALENDAR && type != PageDefinition.Type.BOARD
                && type != PageDefinition.Type.IMPORT
                && trimToNull(p.entity()) != null) {
            throw new WizardArgumentException(prefix + "does not take 'entity'");
        }
        if (type != PageDefinition.Type.ENTITY_LIST && type != PageDefinition.Type.REPORT
                && type != PageDefinition.Type.CALENDAR && type != PageDefinition.Type.BOARD
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
            if (p.showParent() != null) throw new WizardArgumentException(prefix + "does not take 'showParent'");
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
        if (type != PageDefinition.Type.DASHBOARD && p.refreshSeconds() != null) {
            throw new WizardArgumentException(prefix + "does not take 'refreshSeconds'");
        }
        if (type != PageDefinition.Type.WIZARD && p.steps() != null && !p.steps().isEmpty()) {
            throw new WizardArgumentException(prefix + "does not take 'steps'");
        }
        if (type != PageDefinition.Type.RECORD && p.headerStats() != null) {
            throw new WizardArgumentException(prefix + "does not take 'headerStats'");
        }
        if (type != PageDefinition.Type.ENTITY_LIST && type != PageDefinition.Type.MASTER_DETAIL) {
            if (p.columns() != null) throw new WizardArgumentException(prefix + "does not take 'columns'");
            if (p.sort() != null && type != PageDefinition.Type.BOARD) throw new WizardArgumentException(prefix + "does not take 'sort'");
        }
        if (type != PageDefinition.Type.CALENDAR) {
            if (trimToNull(p.dateField()) != null) throw new WizardArgumentException(prefix + "does not take 'dateField'");
            if (trimToNull(p.endField()) != null) throw new WizardArgumentException(prefix + "does not take 'endField'");
            if (p.modes() != null) throw new WizardArgumentException(prefix + "does not take 'modes'");
        }
        if (type != PageDefinition.Type.BOARD) {
            if (trimToNull(p.laneField()) != null) throw new WizardArgumentException(prefix + "does not take 'laneField'");
            if (p.lanes() != null) throw new WizardArgumentException(prefix + "does not take 'lanes'");
            if (p.cardFields() != null) throw new WizardArgumentException(prefix + "does not take 'cardFields'");
            if (p.wipLimits() != null) throw new WizardArgumentException(prefix + "does not take 'wipLimits'");
            if (p.laneSize() != null) throw new WizardArgumentException(prefix + "does not take 'laneSize'");
        }
        if (type != PageDefinition.Type.CONTENT && p.body() != null) {
            throw new WizardArgumentException(prefix + "does not take 'body'");
        }
        if (type != PageDefinition.Type.SEARCH) {
            if (p.entities() != null) throw new WizardArgumentException(prefix + "does not take 'entities'");
            if (p.perEntity() != null) throw new WizardArgumentException(prefix + "does not take 'perEntity'");
            if (p.shellSearch() != null) throw new WizardArgumentException(prefix + "does not take 'shellSearch'");
        }
        if (type != PageDefinition.Type.ENTITY_LIST) {
            if (trimToNull(p.view()) != null) throw new WizardArgumentException(prefix + "does not take 'view'");
            if (p.pageSize() != null) throw new WizardArgumentException(prefix + "does not take 'pageSize'");
            if (trimToNull(p.detail()) != null) throw new WizardArgumentException(prefix + "does not take 'detail'");
        }
    }

    // ── List presentation (entity-list pages) ───────────────────────────────

    /**
     * Whether the generated list of {@code entity} carries the audit columns: the entity's own
     * {@code audit} override, else the project's {@code audit} scaffold opt, and only for a writable
     * entity (EntityScaffoldContext.auditApplicable).
     */
    /** The request's {@code nav}, checked against the layout: absent is null (the default shell);
     *  given, it needs a page layout, a known style (sidebar by default) and a boolean flag. */
    public static PageDefinition.Nav validateNav(FullstackStarterRequest.NavDto raw, List<PageDefinition> pages) {
        if (raw == null) return null;
        if (pages == null || pages.isEmpty()) {
            throw new WizardArgumentException("'nav' needs a page layout (pages) — the classic shell has its own navigation");
        }
        String style = trimToNull(raw.style());
        PageDefinition.NavStyle navStyle = PageDefinition.NavStyle.SIDEBAR;
        if (style != null) {
            navStyle = null;
            for (PageDefinition.NavStyle s : PageDefinition.NavStyle.values()) {
                if (s.wire().equalsIgnoreCase(style)) navStyle = s;
            }
            if (navStyle == null) throw new WizardArgumentException("nav.style must be sidebar or topbar, got '" + style + "'");
        }
        return new PageDefinition.Nav(navStyle, Boolean.TRUE.equals(raw.collapsibleGroups()));
    }

    static boolean auditApplies(EntityDefinition entity, Set<String> scaffoldOpts) {
        if (entity.readOnly()) return false;
        Boolean override = entity.opts().get("audit");
        return override != null ? override : scaffoldOpts.contains("audit");
    }

    /** The columns a list can show — lower-cased → as declared: every field, every MANY_TO_ONE
     *  relation (by field name) and, with audit, {@code createdAt}/{@code updatedAt}. */
    private static Map<String, String> listColumns(EntityDefinition entity, boolean audit) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldDefinition f : entity.fields()) out.put(f.name().toLowerCase(Locale.ROOT), f.name());
        for (RelationDefinition r : entity.relations()) {
            if (r.type() == RelationType.MANY_TO_ONE) out.put(r.fieldName().toLowerCase(Locale.ROOT), r.fieldName());
        }
        if (audit) {
            out.put("createdat", "createdAt");
            out.put("updatedat", "updatedAt");
        }
        return out;
    }

    /** The columns the generated list endpoint sorts by (its SORTABLE whitelist): every field and,
     *  with audit, the audit pair. Relations are shown but never sorted. */
    private static Map<String, String> sortableColumns(EntityDefinition entity, boolean audit) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldDefinition f : entity.fields()) out.put(f.name().toLowerCase(Locale.ROOT), f.name());
        if (audit) {
            out.put("createdat", "createdAt");
            out.put("updatedat", "updatedAt");
        }
        return out;
    }

    private static boolean isAuditColumn(String name) {
        return name.equalsIgnoreCase("createdAt") || name.equalsIgnoreCase("updatedAt");
    }

    /** The ordered subset of columns a list page shows: each a known column, none twice, canonicalized. */
    private static List<String> columns(String prefix, EntityDefinition entity, List<String> raw, boolean audit) {
        if (raw == null) return List.of();
        if (raw.isEmpty()) throw new WizardArgumentException(prefix + ": 'columns' needs at least one column when given");
        Map<String, String> known = listColumns(entity, audit);
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>(raw.size());
        for (String name : raw) {
            String canonical = trimToNull(name) == null ? null : known.get(name.trim().toLowerCase(Locale.ROOT));
            if (canonical == null) {
                String hint = name != null && isAuditColumn(name.trim()) && !audit
                        ? " — the audit scaffold option is off, so " + entity.name() + " has no audit columns"
                        : " (expected one of " + new ArrayList<>(known.values()) + ")";
                throw new WizardArgumentException(prefix + " columns: '" + name + "' is not a column of "
                        + entity.name() + hint);
            }
            if (!seen.add(canonical)) {
                throw new WizardArgumentException(prefix + " columns: '" + canonical + "' is listed twice");
            }
            out.add(canonical);
        }
        return out;
    }

    /** The column a list page opens sorted by: a sortable one, {@code asc} (default) or {@code desc}. */
    private static PageDefinition.ListSort sort(String prefix, EntityDefinition entity,
                                                FullstackStarterRequest.SortDto raw, boolean audit) {
        if (raw == null) return null;
        String field = trimToNull(raw.field());
        if (field == null) throw new WizardArgumentException(prefix + " sort: 'field' is required");
        Map<String, String> sortable = sortableColumns(entity, audit);
        String canonical = sortable.get(field.toLowerCase(Locale.ROOT));
        if (canonical == null) {
            String hint = isAuditColumn(field) && !audit
                    ? " — the audit scaffold option is off, so " + entity.name() + " has no audit columns"
                    : " (sortable: " + new ArrayList<>(sortable.values()) + ")";
            throw new WizardArgumentException(prefix + " sort: '" + field + "' is not sortable on " + entity.name() + hint);
        }
        String dir = trimToNull(raw.dir());
        boolean desc;
        if (dir == null || dir.equalsIgnoreCase("asc")) desc = false;
        else if (dir.equalsIgnoreCase("desc")) desc = true;
        else throw new WizardArgumentException(prefix + " sort: dir must be asc or desc, got '" + dir + "'");
        return new PageDefinition.ListSort(canonical, desc);
    }

    /** Where a list page opens a row: absent keeps the default (the record page when there is one,
     *  else the quick-look drawer). */
    private static PageDefinition.Detail detail(String prefix, String raw) {
        String d = trimToNull(raw);
        if (d == null) return null;
        for (PageDefinition.Detail detail : PageDefinition.Detail.values()) {
            if (detail.wire().equalsIgnoreCase(d)) return detail;
        }
        throw new WizardArgumentException(prefix + ": unknown detail '" + d + "' (expected drawer, side or record)");
    }

    /** The view a list page opens in: one the entity's list actually offers (its enabled listViews,
     *  minus the ones its fields cannot support — see EntityScaffoldContext.emittedListViews). */
    private static String view(String prefix, EntityDefinition entity, String raw) {
        String v = trimToNull(raw);
        if (v == null) return null;
        String lower = v.toLowerCase(Locale.ROOT);
        if (!LIST_VIEWS.contains(lower)) {
            throw new WizardArgumentException(prefix + ": unknown view '" + v + "' (expected table, cards, kanban or calendar)");
        }
        List<String> emitted = EntityScaffoldContext.emittedListViews(entity);
        if (!emitted.contains(lower)) {
            throw new WizardArgumentException(prefix + ": view '" + lower + "' is not enabled on " + entity.name()
                    + " (its list views are " + emitted + "; enable it on the entity — kanban needs an enum or"
                    + " boolean field on a writable entity, calendar a date field)");
        }
        return lower;
    }

    /** The rows per page a list opens with: one of the pager's choices. */
    private static Integer pageSize(String prefix, Integer raw) {
        if (raw == null) return null;
        if (!LIST_PAGE_SIZES.contains(raw)) {
            throw new WizardArgumentException(prefix + ": pageSize must be one of 10, 20, 50 or 100, got " + raw);
        }
        return raw;
    }

    /**
     * Preset filters: equality on a filterable enum/boolean field (the enum constant canonicalized,
     * matched ignoring case), a range on a filterable date or number field ({@code 2026-01-01..2026-03-31},
     * {@code 100..500}, either side optional) or a period ending on the day the app is opened on a
     * date field ({@code last:7d}, {@code last:30d}, {@code last:90d}, {@code ytd}, {@code 12m}).
     * The result maps list params to literals — a range becomes {@code <field>From}/{@code <field>To}
     * or {@code <field>Min}/{@code <field>Max} — except a period, kept under the field's own name as
     * {@code @<period>} for the renderer to turn into a {@code rangeParams(...)} call (it depends on
     * today's date). {@code owner} names the page or widget in the error.
     */
    private static Map<String, String> presetFilter(String owner, EntityDefinition entity, Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> en : raw.entrySet()) {
            String prefix = owner + " presetFilter '" + en.getKey() + "'";
            FieldDefinition field = entity.fields().stream()
                    .filter(f -> f.name().equalsIgnoreCase(en.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": no such field on " + entity.name()));
            FieldType type = field.type();
            if (field.primaryKey() || !field.filterable()
                    || !(type.isEnum() || type.isBoolean() || type.isTemporal() || type.isNumeric())) {
                throw new WizardArgumentException(prefix + ": only filterable enum, boolean, date or number fields can be preset");
            }
            String value = trimToNull(en.getValue());
            if (value == null) throw new WizardArgumentException(prefix + ": value is required");
            if (type.isBoolean()) {
                String lower = value.toLowerCase(Locale.ROOT);
                if (!lower.equals("true") && !lower.equals("false")) {
                    throw new WizardArgumentException(prefix + ": expected true or false, got '" + value + "'");
                }
                out.put(field.name(), lower);
            } else if (type.isEnum()) {
                String constant = field.enumValues().stream()
                        .filter(c -> c.equalsIgnoreCase(value))
                        .findFirst()
                        .orElseThrow(() -> new WizardArgumentException(prefix + ": '" + value
                                + "' is not one of " + field.enumValues()));
                out.put(field.name(), constant);
            } else if (type.isTemporal()) {
                String period = presetPeriod(value);
                if (period != null) {
                    out.put(field.name(), "@" + period);
                    continue;
                }
                String[] range = presetRange(prefix, value,
                        "a date range (2026-01-01..2026-03-31, either side optional) or a period (last:7d, last:30d, last:90d, ytd, 12m)");
                boolean dateTime = type == FieldType.LOCAL_DATE_TIME;
                String from = range[0] == null ? null : isoDate(prefix, range[0], dateTime, false);
                String to = range[1] == null ? null : isoDate(prefix, range[1], dateTime, true);
                if (from != null && to != null && from.compareTo(to) > 0) {
                    throw new WizardArgumentException(prefix + ": from '" + range[0] + "' is after to '" + range[1] + "'");
                }
                if (from != null) out.put(field.name() + "From", from);
                if (to != null) out.put(field.name() + "To", to);
            } else {
                String[] range = presetRange(prefix, value, "a number range (100..500, either side optional)");
                java.math.BigDecimal min = range[0] == null ? null : number(prefix, range[0], "min");
                java.math.BigDecimal max = range[1] == null ? null : number(prefix, range[1], "max");
                if (min != null && max != null && min.compareTo(max) > 0) {
                    throw new WizardArgumentException(prefix + ": min " + min.toPlainString() + " is above max " + max.toPlainString());
                }
                if (min != null) out.put(field.name() + "Min", min.toPlainString());
                if (max != null) out.put(field.name() + "Max", max.toPlainString());
            }
        }
        return out;
    }

    private static final Set<String> PRESET_PERIODS = Set.of("7d", "30d", "90d", "ytd", "12m");

    /** The wire id of a period preset ({@code last:30d} → {@code 30d}, {@code ytd}), or null for anything else. */
    private static String presetPeriod(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        if (v.startsWith("last:")) v = v.substring(5);
        return PRESET_PERIODS.contains(v) ? v : null;
    }

    /** The two halves of {@code a..b}, either blank (null); anything without {@code ..} is rejected. */
    private static String[] presetRange(String prefix, String value, String expected) {
        int at = value.indexOf("..");
        if (at < 0) throw new WizardArgumentException(prefix + ": expected " + expected + ", got '" + value + "'");
        String from = trimToNull(value.substring(0, at));
        String to = trimToNull(value.substring(at + 2));
        if (from == null && to == null) throw new WizardArgumentException(prefix + ": a range needs a from or a to");
        return new String[] {from, to};
    }

    /** An ISO day, or a date-time on a date-time column; a day on one is widened to its start or end,
     *  as the generated period picker does. */
    private static String isoDate(String prefix, String raw, boolean dateTime, boolean end) {
        try {
            if (raw.length() == 10) {
                java.time.LocalDate.parse(raw);
                return dateTime ? raw + (end ? "T23:59:59" : "T00:00:00") : raw;
            }
            if (!dateTime) throw new WizardArgumentException(prefix + ": expected a day (yyyy-MM-dd), got '" + raw + "'");
            java.time.LocalDateTime.parse(raw);
            return raw;
        } catch (java.time.format.DateTimeParseException e) {
            throw new WizardArgumentException(prefix + ": '" + raw + "' is not an ISO date"
                    + (dateTime ? "-time (yyyy-MM-dd or yyyy-MM-ddTHH:mm:ss)" : " (yyyy-MM-dd)"));
        }
    }

    private static java.math.BigDecimal number(String prefix, String raw, String what) {
        try {
            return new java.math.BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new WizardArgumentException(prefix + ": " + what + " '" + raw + "' is not a number");
        }
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
                                                       boolean hasDateRange,
                                                       Map<String, PageDefinition.Type> typeById,
                                                       Map<String, Boolean> hiddenById,
                                                       Set<String> scaffoldOpts) {
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
            if (kind == PageDefinition.WidgetKind.TEXT) {
                out.add(textWidget(prefix, w));
                continue;
            }
            if (kind == PageDefinition.WidgetKind.LINKS) {
                out.add(linksWidget(prefix, w, typeById, hiddenById));
                continue;
            }
            if (kind == PageDefinition.WidgetKind.LIST) {
                out.add(listWidget(prefix, w, entitiesByLower, scaffoldOpts));
                continue;
            }
            if (trimToNull(w.text()) != null) {
                throw new WizardArgumentException(prefix + ": only a text widget takes 'text'");
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
            String series = null;
            PageDefinition.Bucket bucket = null;
            switch (kind) {
                case BAR, DONUT -> groupBy = rankBy(prefix, entity, trimToNull(w.groupBy()), "group by");
                case STACKED -> {
                    groupBy = groupBy(prefix, entity, trimToNull(w.groupBy()));
                    series = series(prefix, entity, groupBy, trimToNull(w.series()));
                }
                case TOP -> groupBy = rankBy(prefix, entity, trimToNull(w.groupBy()), "rank by");
                case LINE -> {
                    groupBy = dateGroupBy(prefix, entity, trimToNull(w.groupBy()));
                    bucket = parseBucket(prefix, w.bucket());
                }
                default -> {
                    if (trimToNull(w.groupBy()) != null) {
                        throw new WizardArgumentException(prefix + ": only a bar, donut, stacked, line or top widget takes 'groupBy'");
                    }
                }
            }
            if (kind != PageDefinition.WidgetKind.STACKED && trimToNull(w.series()) != null) {
                throw new WizardArgumentException(prefix + ": only a stacked widget takes 'series'");
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
                    presetFilter(prefix, entity, w.presetFilter()), sortBy, dateField, compare, target, series, null));
        }
        return out;
    }

    /** A text widget: its content and optional title and width — nothing that queries an entity. */
    private static PageDefinition.Widget textWidget(String prefix, WidgetDto w) {
        String text = trimToNull(w.text());
        if (text == null) throw new WizardArgumentException(prefix + ": a text widget needs 'text'");
        text = checkLength(text.replace("\r\n", "\n"), MAX_TEXT, prefix + " text");
        if (trimToNull(w.entity()) != null || trimToNull(w.agg()) != null || trimToNull(w.field()) != null
                || trimToNull(w.groupBy()) != null || trimToNull(w.bucket()) != null || w.limit() != null
                || (w.presetFilter() != null && !w.presetFilter().isEmpty()) || trimToNull(w.sortBy()) != null
                || trimToNull(w.dateField()) != null || Boolean.TRUE.equals(w.compare()) || trimToNull(w.target()) != null
                || trimToNull(w.series()) != null || (w.pages() != null && !w.pages().isEmpty())
                || (w.columns() != null && !w.columns().isEmpty()) || w.sort() != null) {
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
            // A hidden wizard is still a route (New opens it), and so is a hidden search page (the
            // header's search box opens it — a hidden one must have that box).
            if (Boolean.TRUE.equals(hiddenById.get(id)) && type != PageDefinition.Type.WIZARD
                    && type != PageDefinition.Type.SEARCH) {
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
                || (w.columns() != null && !w.columns().isEmpty()) || w.sort() != null) {
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
                || trimToNull(w.text()) != null || (w.pages() != null && !w.pages().isEmpty())) {
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
                presetFilter(prefix, entity, w.presetFilter()), null, null, false, null, null, null, null, columns, sort);
    }

    /** What a stacked bar splits each group by: another enum/boolean field — the entity's next one
     *  after {@code groupBy}, when omitted. */
    private static String series(String prefix, EntityDefinition entity, String groupBy, String requested) {
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
     *  scalar DTO column), newest first. Null keeps the primary key. */
    private static String sortBy(String prefix, EntityDefinition entity, String requested) {
        if (requested == null) return null;
        FieldDefinition field = fieldOf(prefix, entity, requested, "sortBy");
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
                + "' (expected kpi, bar, donut, stacked, line, recent, top, progress, text, links or list)");
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
        FieldDefinition field = fieldOf(prefix, entity, requested, "field");
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
        FieldDefinition field = fieldOf(prefix, entity, requested, "groupBy");
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
            // A MANY_TO_ONE relation groups by the id it points at, like a top widget; bars are
            // named from the target's list. It has no bucket.
            for (RelationDefinition r : entity.relations()) {
                if (r.type() == RelationType.MANY_TO_ONE && r.fieldName().equalsIgnoreCase(requested)) {
                    if (trimToNull(raw.bucket()) != null) {
                        throw new WizardArgumentException(prefix + ": 'bucket' applies to a date groupBy, and '"
                                + r.fieldName() + "' is not one");
                    }
                    PageDefinition.Agg agg = parseAgg(prefix, raw.agg());
                    return new PageDefinition.Chart(r.fieldName(), null, agg,
                            aggField(prefix, entity, agg, trimToNull(raw.field())), raw.table());
                }
            }
            field = fieldOf(prefix, entity, requested, "groupBy");
            if (field.primaryKey()
                    || !(field.type().isEnum() || field.type().isBoolean() || field.type().isTemporal())) {
                throw new WizardArgumentException(prefix + ": groupBy '" + requested
                        + "' must be a non-key enum, boolean or date field, or a relation");
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
                agg, aggField(prefix, entity, agg, trimToNull(raw.field())), raw.table());
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
        FieldDefinition field = fieldOf(prefix, entity, requested, "groupBy");
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

    /**
     * A wizard's steps. Each names form fields: the entity's fields — all but a generated key —
     * and its relations by field name. Every field and relation the form requires has to be asked
     * for somewhere, and nothing twice. Omitted: the fields in declaration order, then the
     * relations, {@value #DEFAULT_STEP_SIZE} to a step.
     */
    private static List<PageDefinition.Step> steps(String prefix, EntityDefinition entity,
                                                   List<FullstackStarterRequest.StepDto> raw) {
        Map<String, String> askable = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (FieldDefinition f : entity.fields()) {
            if (f.primaryKey() && f.generated()) continue;
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
                out.add(new PageDefinition.Step(section.title(), section.fields()));
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
            FullstackStarterRequest.StepDto step = raw.get(si);
            String stepPrefix = prefix + " steps[" + si + "]";
            if (step == null || step.fields() == null || step.fields().isEmpty()) {
                throw new WizardArgumentException(stepPrefix + ": a step needs at least one field");
            }
            List<String> fields = new ArrayList<>();
            for (String name : step.fields()) {
                String canonical = name == null ? null : askable.get(name.trim().toLowerCase(Locale.ROOT));
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
    private static List<PageDefinition.HeaderStat> headerStats(String prefix, EntityDefinition entity,
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
    private static List<PageDefinition.ChildTab> childTabs(String prefix, EntityDefinition entity,
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
    private static PageDefinition.CalendarSpec calendar(String prefix, EntityDefinition entity, PageDefinitionDto p) {
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
    private static PageDefinition.BoardSpec board(String prefix, EntityDefinition entity, PageDefinitionDto p) {
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
        Map<String, String> known = new LinkedHashMap<>();
        for (FieldDefinition f : entity.fields()) known.put(f.name().toLowerCase(Locale.ROOT), f.name());
        for (RelationDefinition r : entity.relations()) {
            if (r.type() == RelationType.MANY_TO_ONE) known.put(r.fieldName().toLowerCase(Locale.ROOT), r.fieldName());
        }
        List<String> cards = new ArrayList<>();
        if (p.cardFields() != null) {
            if (p.cardFields().isEmpty() || p.cardFields().size() > MAX_CARD_FIELDS) {
                throw new WizardArgumentException(prefix + ": 'cardFields' takes 1 to " + MAX_CARD_FIELDS + " fields");
            }
            for (String raw : p.cardFields()) {
                String canonical = trimToNull(raw) == null ? null : known.get(raw.trim().toLowerCase(Locale.ROOT));
                if (canonical == null) {
                    throw new WizardArgumentException(prefix + " cardFields: '" + raw + "' is not a field or relation of "
                            + entity.name() + " (expected one of " + new ArrayList<>(known.values()) + ")");
                }
                if (cards.contains(canonical)) throw new WizardArgumentException(prefix + " cardFields: '" + canonical + "' is listed twice");
                cards.add(canonical);
            }
        } else {
            cards.addAll(defaultCardFields(entity, lane.name()));
        }
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
    static List<String> defaultCardFields(EntityDefinition entity, String laneField) {
        List<String> out = new ArrayList<>();
        FieldDefinition heading = entity.fields().stream()
                .filter(f -> !f.primaryKey() && (f.type() == FieldType.STRING || f.type() == FieldType.TEXT))
                .findFirst()
                .orElseGet(() -> entity.fields().stream().filter(FieldDefinition::primaryKey).findFirst().orElse(entity.fields().get(0)));
        out.add(heading.name());
        for (FieldDefinition f : entity.fields()) {
            if (out.size() >= 3) break;
            if (f.primaryKey() || f.name().equals(laneField) || out.contains(f.name()) || f.type() == FieldType.TEXT) continue;
            out.add(f.name());
        }
        entity.relations().stream().filter(r -> r.type() == RelationType.MANY_TO_ONE).findFirst()
                .ifPresent(r -> out.add(r.fieldName()));
        return out.size() > MAX_CARD_FIELDS ? out.subList(0, MAX_CARD_FIELDS) : out;
    }

    /** A content page's text: its length, and every link either another page anyone can open or an
     *  http(s) / mailto address. */
    private static String body(String prefix, String raw, Map<String, PageDefinition.Type> typeById,
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
                            && type != PageDefinition.Type.SEARCH) {
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
    private static PageDefinition.SearchSpec search(String prefix, PageDefinitionDto p, List<EntityDefinition> entities,
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
            entities.stream().filter(FullstackPageValidator::searchable).limit(MAX_SEARCH_ENTITIES).forEach(e -> names.add(e.name()));
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
        return e.fields().stream().anyMatch(f -> f.searchable() && (f.type() == FieldType.STRING || f.type() == FieldType.TEXT));
    }

    /** The entity's field named {@code requested}, matched ignoring case (so a hand-typed
     *  {@code "Status"} finds {@code status}); the returned field carries the declared spelling. */
    private static FieldDefinition fieldOf(String prefix, EntityDefinition entity, String requested, String what) {
        return entity.fields().stream()
                .filter(f -> f.name().equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new WizardArgumentException(prefix + ": " + what + " '" + requested
                        + "' is not a field of " + entity.name()));
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
