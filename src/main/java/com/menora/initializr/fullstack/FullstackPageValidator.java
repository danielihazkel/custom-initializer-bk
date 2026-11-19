package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;
import com.menora.initializr.fullstack.FullstackStarterRequest.PageDefinitionDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.menora.initializr.fullstack.PageListRules.*;
import static com.menora.initializr.fullstack.PagePresets.*;
import static com.menora.initializr.fullstack.DashboardWidgetRules.*;
import static com.menora.initializr.fullstack.ReportChartRules.*;
import static com.menora.initializr.fullstack.PageStructureRules.*;
import static com.menora.initializr.fullstack.PageChecks.*;

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
    /** An activity feed: how many changes it lists by default, and at least; and how many entities it reads. */
    static final int DEFAULT_ACTIVITY_LIMIT = 10;
    static final int MIN_ACTIVITY_LIMIT = 5;
    static final int MAX_ACTIVITY_ENTITIES = 6;

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
    static final List<String> LIST_VIEWS = List.of("table", "cards", "kanban", "calendar", "gallery");

    /** The lucide icons a page may put in the nav. The shell imports exactly the ones in use, so the
     *  list is a whitelist rather than "any lucide name": a typo would otherwise fail the build of the
     *  generated project instead of the request. */
    static final List<String> NAV_ICONS = List.of(
            "BarChart3", "Building2", "Calendar", "Columns3", "FilePlus", "FileText", "Inbox", "Layers", "LayoutDashboard",
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
        Map<String, EntityDefinition> entitiesByLower = new LinkedHashMap<>();
        for (EntityDefinition e : entities) entitiesByLower.put(e.name().toLowerCase(Locale.ROOT), e);

        // Pass 1: ids + types, so tabs (and links widgets) can reference pages declared after them.
        Map<String, PageDefinition.Type> typeById = new LinkedHashMap<>();
        Map<String, Boolean> hiddenById = new LinkedHashMap<>();
        // The entity a list or report page shows (canonical name), so a widget's drillTo can be
        // checked against a page declared after it.
        Map<String, String> entityById = new HashMap<>();
        for (int pi = 0; pi < raw.size(); pi++) {
            try {
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
                EntityDefinition shown = p.entity() == null ? null : entitiesByLower.get(p.entity().trim().toLowerCase(Locale.ROOT));
                if (shown != null) entityById.put(id, shown.name());
            } catch (WizardArgumentException problem) {
                throw problem.at("pages[" + pi + "]");
            }
        }
        // Decided here, before any page is checked in depth: a layout with nothing in the nav gets
        // this message rather than one about a widget that happens to point at a hidden page.
        if (hiddenById.values().stream().allMatch(Boolean::booleanValue)) {
            throw new WizardArgumentException("At least one page must be visible in the navigation");
        }

        List<PageDefinition> pages = new ArrayList<>(raw.size());
        Map<String, String> recordPageByEntity = new HashMap<>();
        // The page that creates an entity's rows: a wizard or a form, one per entity.
        Map<String, String> createPageByEntity = new HashMap<>();
        Map<String, String> importPageByEntity = new HashMap<>();
        String searchPage = null;
        boolean anyVisible = false;
        for (PageDefinitionDto p : raw) {
            try {
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
                                presetFilter(prefix, entity, p.presetFilter(), entitiesByLower), null, null)
                                .withListPresentation(columns(prefix, entity, p.columns(), audit),
                                        sort(prefix, entity, p.sort(), audit), view(prefix, entity, p.view()),
                                        pageSize(prefix, p.pageSize()))
                                .withDetail(detail)
                                .withSpec(listSpec(prefix, entity, p.hideActions(), scaffoldOpts));
                    }
                    case DASHBOARD -> {
                        PageDefinition.DateRange range = parseDateRange(id, p.dateRange());
                        List<PageDefinition.Widget> widgets = widgets(id, p.widgets(), entitiesByLower, range != null,
                                typeById, hiddenById, entityById, scaffoldOpts);
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
                        List<String> filters = dashboardFilters(id, p.filters(), widgets, entitiesByLower);
                        yield new PageDefinition(id, type, title, description, hidden, null, null, widgets, null)
                                .withDateRange(range)
                                .withRefreshSeconds(refresh)
                                .withSpec(filters.isEmpty() ? null : new PageDefinition.DashboardSpec(filters));
                    }
                    case TABS -> {
                        // No entity to borrow a name from, so the nav label has to be given.
                        if (title == null) throw new WizardArgumentException("Page '" + id + "' (tabs) needs a title");
                        yield new PageDefinition(id, type, title, description, hidden, null, null, null,
                                tabs(id, p.tabs(), typeById));
                    }
                    case TREE -> {
                        String prefix = "Page '" + id + "' (tree)";
                        EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                        requireSinglePk(prefix, entity);
                        // The link that makes the tree is kept where a master-detail keeps its own.
                        yield new PageDefinition(id, type, title, description, hidden, entity.name(), null, null, null,
                                null, null, treeLink(prefix, entity, trimToNull(p.via())), null, null, null, null, null, null, null);
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
                                presetFilter("Page '" + id + "'", entity, p.presetFilter(), entitiesByLower), charts(prefix, entity, p));
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
                                headerStats(prefix, entity, p.headerStats(), childTabs, entitiesByLower))
                                // Absent and false are one thing: no spec, the page's old bytes.
                                .withSpec(Boolean.TRUE.equals(p.printable()) ? new PageDefinition.RecordSpec(true) : null);
                    }
                    case WIZARD -> {
                        String prefix = "Page '" + id + "' (wizard)";
                        EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                        if (entity.readOnly()) {
                            throw new WizardArgumentException(prefix + ": " + entity.name()
                                    + " is read-only, so there is nothing to create");
                        }
                        checkOneCreatePage(prefix, entity, id, type, createPageByEntity, typeById);
                        yield PageDefinition.wizard(id, title, description, hidden, entity.name(),
                                steps(prefix, entity, p.steps()));
                    }
                    case FORM -> {
                        String prefix = "Page '" + id + "' (form)";
                        EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                        if (entity.readOnly()) {
                            throw new WizardArgumentException(prefix + ": " + entity.name()
                                    + " is read-only, so there is nothing to create");
                        }
                        checkOneCreatePage(prefix, entity, id, type, createPageByEntity, typeById);
                        yield new PageDefinition(id, type, title, description, hidden, entity.name(), null, null, null);
                    }
                    case INBOX -> {
                        String prefix = "Page '" + id + "' (inbox)";
                        EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                        EntityDefinition.Workflow wf = entity.workflow();
                        if (wf == null || wf.assigneeField() == null) {
                            throw new WizardArgumentException(prefix + ": " + entity.name()
                                    + " has no workflow with an assignee — an inbox lists rows by who they are assigned to");
                        }
                        List<String> shown = List.of(wf.statusField(), wf.assigneeField(), wf.dueField() == null ? "" : wf.dueField());
                        List<String> defaults = defaultCardFields(entity, wf.statusField()).stream()
                                .filter(f -> !shown.contains(f)).toList();
                        List<String> cards = cardFields(prefix, entity, p.cardFields(), defaults);
                        Map<String, String> preset = presetFilter("Page '" + id + "'", entity, p.presetFilter(), entitiesByLower);
                        // Soonest due first; without a due date, the newest first.
                        PageDefinition.ListSort sort = p.sort() != null
                                ? sort(prefix, entity, p.sort(), auditApplies(entity, scaffoldOpts))
                                : wf.dueField() != null ? new PageDefinition.ListSort(wf.dueField(), false)
                                : new PageDefinition.ListSort(entity.fields().stream().filter(FieldDefinition::primaryKey)
                                        .findFirst().orElseThrow().name(), true);
                        yield new PageDefinition(id, type, title, description, hidden, entity.name(), preset, null, null)
                                .withListPresentation(null, sort, null, null)
                                .withSpec(new PageDefinition.InboxSpec(inboxQueues(prefix, p.queues()), cards));
                    }
                    case CALENDAR -> {
                        String prefix = "Page '" + id + "' (calendar)";
                        EntityDefinition entity = requireEntity(entitiesByLower, p.entity(), prefix);
                        PageDefinition.CalendarSpec spec = calendar(prefix, entity, p);
                        Map<String, String> preset = presetFilter("Page '" + id + "'", entity, p.presetFilter(), entitiesByLower);
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
                        Map<String, String> preset = presetFilter("Page '" + id + "'", entity, p.presetFilter(), entitiesByLower);
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
            } catch (WizardArgumentException problem) {
                throw problem.at("pages[" + raw.indexOf(p) + "]");
            }
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
                + "' (expected entity-list, dashboard, tabs, master-detail, record, report, wizard, form,"
                + " calendar, board, content, import, search or inbox)");
    }

    /** An entity has one page that creates its rows — a wizard or a form — since New, a calendar's
     *  day and the record page's Edit all open it. */
    private static void checkOneCreatePage(String prefix, EntityDefinition entity, String id, PageDefinition.Type type,
                                           Map<String, String> createPageByEntity, Map<String, PageDefinition.Type> typeById) {
        String previous = createPageByEntity.putIfAbsent(entity.name(), id);
        if (previous == null) return;
        PageDefinition.Type previousType = typeById.get(previous);
        throw new WizardArgumentException(prefix + ": " + entity.name() + " already has a " + previousType.wire()
                + " page ('" + previous + "')"
                + (previousType != type ? " — an entity has a wizard or a form, not both" : ""));
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
                && type != PageDefinition.Type.IMPORT && type != PageDefinition.Type.FORM
                && type != PageDefinition.Type.INBOX && type != PageDefinition.Type.TREE
                && trimToNull(p.entity()) != null) {
            throw new WizardArgumentException(prefix + "does not take 'entity'");
        }
        if (type != PageDefinition.Type.ENTITY_LIST && type != PageDefinition.Type.REPORT
                && type != PageDefinition.Type.CALENDAR && type != PageDefinition.Type.BOARD
                && type != PageDefinition.Type.INBOX
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
            if (type != PageDefinition.Type.TREE && trimToNull(p.via()) != null) {
                throw new WizardArgumentException(prefix + "does not take 'via'");
            }
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
        if (type != PageDefinition.Type.DASHBOARD && p.filters() != null && !p.filters().isEmpty()) {
            throw new WizardArgumentException(prefix + "does not take 'filters' (only a dashboard does — a list or report page has its own filter bar)");
        }
        if (type != PageDefinition.Type.WIZARD && p.steps() != null && !p.steps().isEmpty()) {
            throw new WizardArgumentException(prefix + "does not take 'steps'");
        }
        if (type != PageDefinition.Type.RECORD && p.headerStats() != null) {
            throw new WizardArgumentException(prefix + "does not take 'headerStats'");
        }
        if (type != PageDefinition.Type.ENTITY_LIST && type != PageDefinition.Type.MASTER_DETAIL) {
            if (p.columns() != null) throw new WizardArgumentException(prefix + "does not take 'columns'");
            if (p.sort() != null && type != PageDefinition.Type.BOARD && type != PageDefinition.Type.INBOX) {
                throw new WizardArgumentException(prefix + "does not take 'sort'");
            }
        }
        if (type != PageDefinition.Type.CALENDAR) {
            if (trimToNull(p.dateField()) != null) throw new WizardArgumentException(prefix + "does not take 'dateField'");
            if (trimToNull(p.endField()) != null) throw new WizardArgumentException(prefix + "does not take 'endField'");
            if (p.modes() != null) throw new WizardArgumentException(prefix + "does not take 'modes'");
        }
        if (type != PageDefinition.Type.BOARD) {
            if (trimToNull(p.laneField()) != null) throw new WizardArgumentException(prefix + "does not take 'laneField'");
            if (p.lanes() != null) throw new WizardArgumentException(prefix + "does not take 'lanes'");
            if (p.cardFields() != null && type != PageDefinition.Type.INBOX) throw new WizardArgumentException(prefix + "does not take 'cardFields'");
            if (p.wipLimits() != null) throw new WizardArgumentException(prefix + "does not take 'wipLimits'");
            if (p.laneSize() != null) throw new WizardArgumentException(prefix + "does not take 'laneSize'");
        }
        if (type != PageDefinition.Type.RECORD && p.printable() != null) {
            throw new WizardArgumentException(prefix + "does not take 'printable'");
        }
        if (type != PageDefinition.Type.INBOX && p.queues() != null) {
            throw new WizardArgumentException(prefix + "does not take 'queues'");
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
            if (p.hideActions() != null && !p.hideActions().isEmpty()) {
                throw new WizardArgumentException(prefix + "does not take 'hideActions'");
            }
        }
    }

    // ── List presentation (entity-list pages) ───────────────────────────────

    /**
     * Whether the generated list of {@code entity} carries the audit columns: the entity's own
     * {@code audit} override, else the project's {@code audit} scaffold opt, and only for a writable
     * entity (EntityScaffoldContext.auditApplicable).
     */
    /** As {@link #validateNav(FullstackStarterRequest.NavDto, List, List)}, for a model without workflows. */
    public static PageDefinition.Nav validateNav(FullstackStarterRequest.NavDto raw, List<PageDefinition> pages) {
        return validateNav(raw, pages, List.of());
    }

    /** The request's {@code nav}, checked against the layout: absent is null (the default shell);
     *  given, it needs a page layout, a known style (sidebar by default) and boolean flags — and
     *  the bell needs something to count: an entity whose workflow assigns its tasks. */
    public static PageDefinition.Nav validateNav(FullstackStarterRequest.NavDto raw, List<PageDefinition> pages,
                                                 List<EntityDefinition> entities) {
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
        boolean notifications = Boolean.TRUE.equals(raw.notifications());
        if (notifications && entities.stream().noneMatch(e -> e.workflow() != null && e.workflow().assigneeField() != null)) {
            throw new WizardArgumentException("nav.notifications needs an entity whose workflow has an assigneeField"
                    + " — the bell counts the tasks assigned to the user").on("notifications");
        }
        return new PageDefinition.Nav(navStyle, Boolean.TRUE.equals(raw.collapsibleGroups()),
                Boolean.TRUE.equals(raw.userMenu()), notifications, Boolean.TRUE.equals(raw.commandPalette()));
    }

    static final Set<String> PRESET_PERIODS = Set.of("7d", "30d", "90d", "ytd", "12m");

    /** At most this many dashboard filters. */
    static final int MAX_DASHBOARD_FILTERS = 4;
}
