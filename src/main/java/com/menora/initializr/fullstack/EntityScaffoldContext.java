package com.menora.initializr.fullstack;

import com.menora.initializr.db.entity.ColorPaletteEntity;
import com.menora.initializr.gen.Naming;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the Mustache view-models exposed to fullstack templates.
 *
 * <p>Two map flavors:
 * <ul>
 *   <li><b>Project-wide</b> ({@link #buildProjectContext}): used for non-perEntity files
 *       and as the base for per-entity contexts. Includes a top-level {@code entities}
 *       list so a non-perEntity template can iterate (e.g. {@code AppRoutes.tsx}).</li>
 *   <li><b>Per-entity</b> ({@link #buildEntityContext}): project-wide + entity naming
 *       variants + {@code fields} iterable with per-field flags ({@code isString},
 *       {@code isPrimaryKey}, …).</li>
 * </ul>
 */
public final class EntityScaffoldContext {

    /**
     * The project-wide {@code opts.scaffold} options an entity may override individually via
     * {@link EntityDefinition#opts()}: option key -> the {@code optScaffold<X>} context flag it maps
     * to. Ordered so error messages list them deterministically. Options that are inherently
     * project-wide (openapi, secured, inverseCollections, seedData, rtl) are deliberately absent.
     */
    public static final Map<String, String> SCAFFOLD_OPT_FLAGS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("audit", "optScaffoldAudit");
        m.put("softDelete", "optScaffoldSoftDelete");
        m.put("csvExport", "optScaffoldCsvExport");
        m.put("bulkDelete", "optScaffoldBulkDelete");
        m.put("bulkUpdate", "optScaffoldBulkUpdate");
        m.put("tests", "optScaffoldTests");
        SCAFFOLD_OPT_FLAGS = java.util.Collections.unmodifiableMap(m);
    }

    private EntityScaffoldContext() {}

    public static Map<String, Object> buildProjectContext(
            String artifactId,
            String groupId,
            String version,
            String packageName,
            String domainPackage,
            String javaVersion,
            String packaging,
            List<EntityDefinition> entities) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("artifactId", artifactId);
        ctx.put("groupId", groupId);
        ctx.put("version", version);
        ctx.put("packageName", packageName);
        ctx.put("packagePath", packageName == null ? "" : packageName.replace('.', '/'));
        ctx.put("javaVersion", javaVersion);
        ctx.put("packaging", packaging);

        // The base package the generated CRUD classes live under (defaults to packageName),
        // split into one conventional sub-package per layer. Constrained at the controller to be
        // at or below packageName so default component/entity scanning still finds the beans.
        String domain = (domainPackage == null || domainPackage.isBlank()) ? packageName : domainPackage;
        ctx.put("domainPackage", domain);
        putPackage(ctx, "entityPackage", domain, "entity");
        putPackage(ctx, "repositoryPackage", domain, "repository");
        putPackage(ctx, "dtoPackage", domain, "dto");
        putPackage(ctx, "servicePackage", domain, "service");
        putPackage(ctx, "controllerPackage", domain, "controller");

        // Relations reference other entities, so resolve a per-entity summary (PK type/name +
        // naming variants) up front and stash it so the per-entity context (built later by
        // buildEntityContext) can resolve relation targets too.
        Map<String, Map<String, Object>> summaries = buildSummaries(entities);
        ctx.put(ENTITY_SUMMARIES_KEY, summaries);
        Map<String, List<Map<String, Object>>> inverses = buildInverseRelations(entities);
        ctx.put(INVERSE_RELATIONS_KEY, inverses);

        List<Map<String, Object>> entityViews = new ArrayList<>(entities.size());
        for (int i = 0; i < entities.size(); i++) {
            Map<String, Object> view = entityViewModel(entities.get(i), summaries, inverses);
            view.put("first", i == 0);
            view.put("last", i == entities.size() - 1);
            entityViews.add(view);
        }
        ctx.put("entities", entityViews);
        // Demo-data seeding order (optScaffoldSeedData): parents before children, writable only.
        List<Map<String, Object>> seedViews = buildSeedOrder(entities, entityViews);
        ctx.put("seedEntities", seedViews);
        ctx.put("hasSeedEntities", !seedViews.isEmpty());
        return ctx;
    }

    /**
     * Adds the frontend page layout to a (frontend) project context. With no pages this only sets
     * {@code hasPages=false}, which keeps the classic shell (one dashboard + one list page per
     * entity) byte-for-byte. With pages, the shell's nav/switch iterates {@code navPages} and each
     * page renders once as {@code src/app/screens/<PageName>Screen.tsx} from the per-page context
     * {@link FullstackRenderer} builds (project context + one entry of {@code pages}).
     *
     * <p>Must run after {@link #buildProjectContext}: widgets and list pages resolve their entity
     * through the {@code entities} view-models.
     */
    @SuppressWarnings("unchecked")
    public static void putPageContext(Map<String, Object> ctx, List<PageDefinition> pages) {
        ctx.put("hasPages", !pages.isEmpty());
        if (pages.isEmpty()) return;

        Map<String, Map<String, Object>> entityByPascal = new LinkedHashMap<>();
        for (Map<String, Object> ev : (List<Map<String, Object>>) ctx.get("entities")) {
            entityByPascal.put((String) ev.get("EntityName"), ev);
        }
        Map<String, Map<String, Object>> summaries = (Map<String, Map<String, Object>>) ctx.get(ENTITY_SUMMARIES_KEY);
        PageLinks links = PageLinks.of(pages);

        Map<String, Map<String, Object>> viewById = new LinkedHashMap<>();
        for (PageDefinition p : pages) {
            Map<String, Object> pv = new LinkedHashMap<>();
            pv.put("pageId", p.id());
            pv.put("PageName", Naming.toPascalCase(p.id()));
            pv.put("hidden", p.hidden());
            pv.put("pageIsEntityList", p.type() == PageDefinition.Type.ENTITY_LIST);
            pv.put("pageIsDashboard", p.type() == PageDefinition.Type.DASHBOARD);
            pv.put("pageIsTabs", p.type() == PageDefinition.Type.TABS);
            pv.put("pageIsMasterDetail", p.type() == PageDefinition.Type.MASTER_DETAIL);
            pv.put("pageIsRecord", p.type() == PageDefinition.Type.RECORD);
            pv.put("pageIsReport", p.type() == PageDefinition.Type.REPORT);
            pv.put("pageIsWizard", p.type() == PageDefinition.Type.WIZARD);
            pv.put("hasPageDescription", p.description() != null);
            pv.put("pageDescriptionExpr", p.description() == null ? null : tsString(p.description()));
            pv.put("needsNavigate", false);
            String defaultTitleExpr;
            switch (p.type()) {
                case ENTITY_LIST -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    pv.put("EntityName", ev.get("EntityName"));
                    pv.put("entityNameKebab", ev.get("entityNameKebab"));
                    pv.put("entityNamePluralKebab", ev.get("entityNamePluralKebab"));
                    pv.put("hasPresetFilter", !p.presetFilter().isEmpty());
                    pv.put("presetFilterTs", presetFilterTs(p.presetFilter()));
                    String presetTs = presetFilterTs(p.presetFilter());
                    // The same object without its braces, to merge the route's filters into.
                    pv.put("presetFilterEntriesTs", presetTs == null ? null : presetTs.substring(2, presetTs.length() - 2));
                    putRecordLink(pv, "", p.entity(), links, summaries);
                    pv.put("needsNavigate", pv.get("hasRecordPage"));
                    // A filterable list also opens with the filters in its route (#/orders?status=OPEN).
                    pv.put("listTakesQuery", Boolean.TRUE.equals(ev.get("hasFilters")));
                    // New opens the entity's wizard page, when it has one.
                    String wizard = links.wizardPageOf(p.entity());
                    pv.put("hasWizard", wizard != null);
                    pv.put("wizardPageId", wizard);
                    if (wizard != null) pv.put("needsNavigate", true);
                    // How the list opens (each absent: the EntityPage default). Column and field
                    // names are validated identifiers; the sort object matches the generated SortSpec.
                    pv.put("hasColumns", !p.columns().isEmpty());
                    pv.put("columnsTs", p.columns().isEmpty() ? null
                            : "[" + String.join(", ", p.columns().stream().map(EntityScaffoldContext::tsString).toList()) + "]");
                    pv.put("hasSort", p.sort() != null);
                    pv.put("sortTs", p.sort() == null ? null
                            : "{ field: " + tsString(p.sort().field()) + ", direction: '" + (p.sort().desc() ? "desc" : "asc") + "' }");
                    pv.put("hasInitialView", p.view() != null);
                    pv.put("initialViewOverride", p.view());
                    pv.put("hasPageSize", p.pageSize() != null);
                    pv.put("pageSize", p.pageSize());
                    pv.put("hasListPresentation", p.hasListPresentation());
                    // A page title replaces the entity page's own heading (its plural label).
                    pv.put("hasPageTitle", p.title() != null);
                    pv.put("navIcon", "Table2");
                    defaultTitleExpr = tsString((String) ev.get("entityLabelPlural"));
                }
                case DASHBOARD -> {
                    putDashboard(pv, p, entityByPascal, summaries, links);
                    pv.put("navIcon", "LayoutDashboard");
                    defaultTitleExpr = "t('dashboard')";
                }
                case REPORT -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    pv.put("EntityName", ev.get("EntityName"));
                    pv.put("entityNameKebab", ev.get("entityNameKebab"));
                    pv.put("entityNamePluralKebab", ev.get("entityNamePluralKebab"));
                    // The report builds the same FilterDescriptor[] as the entity page. A per-page
                    // context is project context + page view-model, so the entity's filter fields
                    // have to be copied in — EntityPage is deliberately left untouched.
                    pv.put("filterFields", ev.get("filterFields"));
                    pv.put("hasFilters", ev.get("hasFilters"));
                    pv.put("hasPresetFilter", !p.presetFilter().isEmpty());
                    pv.put("presetFilterTs", presetFilterTs(p.presetFilter()));
                    // A per-page screen never runs through buildEntityContext, so the entity's own
                    // csvExport override has to be resolved against the project opt here.
                    Object csvOverride = ev.get("csvExportOverride");
                    pv.put("reportHasExport", csvOverride != null ? Boolean.TRUE.equals(csvOverride)
                            : Boolean.TRUE.equals(ctx.get("optScaffoldCsvExport")));
                    putCharts(pv, p, ev, links);
                    pv.put("navIcon", "BarChart3");
                    defaultTitleExpr = "t('xReport', { x: " + tsString((String) ev.get("entityLabelPlural")) + " })";
                }
                case MASTER_DETAIL -> {
                    Map<String, Object> parent = entityByPascal.get(Naming.toPascalCase(p.parent()));
                    Map<String, Object> child = entityByPascal.get(Naming.toPascalCase(p.child()));
                    Map<String, Object> parentSummary = summaries.get(p.parent().toLowerCase(Locale.ROOT));
                    pv.put("parentEntityName", parent.get("EntityName"));
                    pv.put("parentEntityNameKebab", parent.get("entityNameKebab"));
                    pv.put("parentPkName", parentSummary.get("pkName"));
                    // The selected parent travels in the URL as a string; a numeric key is parsed back.
                    pv.put("parentPkIsNumber", "number".equals(parentSummary.get("pkTsType")));
                    pv.put("parentLabelField", parentSummary.get("labelField"));
                    pv.put("parentHasLabel", parentSummary.get("labelField") != null);
                    pv.put("parentSearchable", parent.get("hasStringFields"));
                    pv.put("parentLabelPluralExpr", tsString((String) parent.get("entityLabelPlural")));
                    pv.put("parentLabelExpr", tsString((String) parent.get("entityLabel")));
                    pv.put("childEntityName", child.get("EntityName"));
                    pv.put("childEntityNameKebab", child.get("entityNameKebab"));
                    pv.put("childLabelPluralExpr", tsString((String) child.get("entityLabelPlural")));
                    pv.put("viaParam", Naming.toCamelCase(p.via()) + "Id");
                    putRecordLink(pv, "parent", p.parent(), links, summaries);
                    putRecordLink(pv, "child", p.child(), links, summaries);
                    pv.put("needsNavigate", Boolean.TRUE.equals(pv.get("parentHasRecordPage"))
                            || Boolean.TRUE.equals(pv.get("childHasRecordPage")));
                    pv.put("navIcon", "PanelLeft");
                    defaultTitleExpr = tsString((String) parent.get("entityLabelPlural"));
                }
                case RECORD -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    Map<String, Object> summary = summaries.get(p.entity().toLowerCase(Locale.ROOT));
                    pv.put("EntityName", ev.get("EntityName"));
                    pv.put("entityNameKebab", ev.get("entityNameKebab"));
                    pv.put("entityNamePluralKebab", ev.get("entityNamePluralKebab"));
                    pv.put("pkName", summary.get("pkName"));
                    pv.put("labelField", summary.get("labelField"));
                    pv.put("hasLabel", summary.get("labelField") != null);
                    pv.put("entityLabelExpr", tsString((String) ev.get("entityLabel")));
                    String back = links.homeOf(p.entity());
                    pv.put("hasBack", back != null);
                    pv.put("backPageId", back);
                    // A writable entity's record page edits (form drawer) and deletes the row itself.
                    boolean recordMutable = Boolean.TRUE.equals(ev.get("mutable"));
                    // With a wizard page, Edit reopens the row in the wizard's steps instead.
                    String editWizard = recordMutable ? links.wizardPageOf(p.entity()) : null;
                    pv.put("recordMutable", recordMutable);
                    pv.put("recordEditsInWizard", editWizard != null);
                    pv.put("recordEditsInDrawer", recordMutable && editWizard == null);
                    pv.put("wizardPageId", editWizard);
                    pv.put("recordHasIcons", back != null || recordMutable);
                    // With soft delete the page's Delete toast offers Undo (POST /restore, then the
                    // restored row is reopened). Like csvExport, the entity's own override is
                    // resolved against the project opt here, because a page context never runs
                    // through buildEntityContext. A record page always has a single key, so the
                    // composite-key exclusion of softDeleteApplicable never applies.
                    Object softDeleteOverride = ev.get("softDeleteOverride");
                    boolean recordSoftDeletes = recordMutable && (softDeleteOverride != null
                            ? Boolean.TRUE.equals(softDeleteOverride)
                            : Boolean.TRUE.equals(ctx.get("optScaffoldSoftDelete")));
                    pv.put("recordSoftDeletes", recordSoftDeletes);
                    List<Map<String, Object>> tabViews = new ArrayList<>();
                    boolean navigates = back != null || editWizard != null || recordSoftDeletes;
                    for (int i = 0; i < p.childTabs().size(); i++) {
                        PageDefinition.ChildTab tab = p.childTabs().get(i);
                        Map<String, Object> cv = entityByPascal.get(Naming.toPascalCase(tab.entity()));
                        Map<String, Object> tv = new LinkedHashMap<>();
                        // Tab 0 is the record's own details.
                        tv.put("tabIndex", i + 1);
                        tv.put("tabId", cv.get("entityNameKebab"));
                        tv.put("childEntityName", cv.get("EntityName"));
                        tv.put("childEntityNameKebab", cv.get("entityNameKebab"));
                        tv.put("viaParam", Naming.toCamelCase(tab.via()) + "Id");
                        tv.put("tabTitleExpr", tsString((String) cv.get("entityLabelPlural")));
                        putRecordLink(tv, "child", tab.entity(), links, summaries);
                        navigates |= Boolean.TRUE.equals(tv.get("childHasRecordPage"));
                        tabViews.add(tv);
                    }
                    pv.put("childTabs", tabViews);
                    pv.put("hasChildTabs", !tabViews.isEmpty());
                    pv.put("needsNavigate", navigates);
                    putHeaderStats(pv, p, entityByPascal, tabViews);
                    pv.put("navIcon", "Table2");
                    defaultTitleExpr = tsString((String) ev.get("entityLabel"));
                }
                case WIZARD -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    putWizard(pv, p, ev, links, summaries);
                    pv.put("navIcon", "Wand2");
                    defaultTitleExpr = "t('newX', { x: " + tsString((String) ev.get("entityLabel")) + " })";
                }
                default -> {
                    pv.put("navIcon", "Layers");
                    // The validator requires a tabs page title; this fallback is never used.
                    defaultTitleExpr = tsString(p.id());
                }
            }
            pv.put("pageTitleExpr", p.title() != null ? tsString(p.title()) : defaultTitleExpr);
            if (p.icon() != null) pv.put("navIcon", p.icon());
            pv.put("navGroup", p.group());
            // Pages restricted to roles: the shell hides them from the nav and guards their route.
            pv.put("hasRoles", !p.roles().isEmpty());
            pv.put("rolesTs", "[" + String.join(", ", p.roles().stream().map(r -> "'" + r + "'").toList()) + "]");
            viewById.put(p.id(), pv);
        }

        // Tabs last: a tab's label and onNavigate plumbing come from the page it embeds.
        Map<String, PageDefinition> pageById = new LinkedHashMap<>();
        for (PageDefinition p : pages) pageById.put(p.id(), p);
        for (PageDefinition p : pages) {
            if (p.type() != PageDefinition.Type.TABS) continue;
            Map<String, Object> pv = viewById.get(p.id());
            List<Map<String, Object>> tabViews = new ArrayList<>();
            boolean needsNavigate = false;
            boolean hasTabCounts = false;
            for (int i = 0; i < p.tabs().size(); i++) {
                PageDefinition.Tab tab = p.tabs().get(i);
                Map<String, Object> target = viewById.get(tab.page());
                boolean targetNavigates = Boolean.TRUE.equals(target.get("needsNavigate"));
                needsNavigate |= targetNavigates;
                Map<String, Object> tv = new LinkedHashMap<>();
                tv.put("tabIndex", i);
                tv.put("tabId", tab.page());
                tv.put("tabTitleExpr", tab.title() != null ? tsString(tab.title()) : target.get("pageTitleExpr"));
                tv.put("TargetName", target.get("PageName"));
                tv.put("targetNeedsNavigate", targetNavigates);
                // An embedded list page drops its own heading: the tab strip is its heading.
                boolean targetIsList = Boolean.TRUE.equals(target.get("pageIsEntityList"));
                tv.put("tabTargetIsList", targetIsList);
                if (targetIsList) target.put("isTabTarget", true);
                // A tab over a list page shows the list's row count (its own preset applied): the
                // list endpoint's page metadata, asked for one row. The params are validated
                // constants, so they are spliced verbatim (triple-stash: `&` must not be escaped).
                tv.put("tabCounted", targetIsList);
                if (targetIsList) {
                    String preset = presetQuery(pageById.get(tab.page()).presetFilter());
                    tv.put("countPath", "/api/" + target.get("entityNamePluralKebab"));
                    tv.put("countParams", preset == null ? "" : preset);
                    hasTabCounts = true;
                }
                tv.put("first", i == 0);
                tv.put("last", i == p.tabs().size() - 1);
                tabViews.add(tv);
            }
            pv.put("tabs", tabViews);
            pv.put("hasTabCounts", hasTabCounts);
            pv.put("needsNavigate", needsNavigate);
        }

        // Links widgets last too: a tile's label and icon are the nav entry of the page it opens.
        for (Map<String, Object> pv : viewById.values()) {
            if (!Boolean.TRUE.equals(pv.get("usesLinks"))) continue;
            Set<String> icons = new TreeSet<>();
            for (Map<String, Object> wv : (List<Map<String, Object>>) pv.get("widgets")) {
                if (!Boolean.TRUE.equals(wv.get("widgetIsLinks"))) continue;
                List<Map<String, Object>> items = new ArrayList<>();
                for (String id : (List<String>) wv.get("linkIds")) {
                    Map<String, Object> target = viewById.get(id);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("pageId", id);
                    item.put("labelExpr", target.get("pageTitleExpr"));
                    item.put("navIcon", target.get("navIcon"));
                    icons.add((String) target.get("navIcon"));
                    items.add(item);
                }
                wv.put("links", items);
            }
            pv.put("linkIcons", String.join(", ", icons));
            pv.put("hasLinkIcons", !icons.isEmpty());
            pv.put("needsNavigate", true);
        }

        // What the shell hands a screen from its route: a tabs page its open tab (and a way to change
        // it), a filterable list page the filters in the hash. Record pages get their id separately.
        for (Map<String, Object> pv : viewById.values()) {
            String routeProps = "";
            if (Boolean.TRUE.equals(pv.get("pageIsTabs"))) {
                routeProps = " tab={route.arg} onTabChange={tab => go('" + pv.get("pageId") + "', tab)}";
            } else if (Boolean.TRUE.equals(pv.get("wizardEditable"))) {
                // #/<wizard>/<id> edits that row; a new id is a fresh wizard.
                routeProps = " key={route.arg ?? ''} editId={route.arg}";
            } else if (Boolean.TRUE.equals(pv.get("pageIsMasterDetail"))) {
                // The selected parent is the route arg (#/customers/42).
                routeProps = " selectedId={route.arg} onSelect={id => go('" + pv.get("pageId") + "', id)}";
            } else if (Boolean.TRUE.equals(pv.get("listTakesQuery"))) {
                routeProps = " filters={route.query}";
            } else if (Boolean.TRUE.equals(pv.get("pageIsDashboard")) && Boolean.TRUE.equals(pv.get("hasDateRange"))) {
                // The period picker's choice rides in the hash (#/desk?period=30d), rewritten in place.
                routeProps = " period={route.query.period} onPeriodChange={period => setQuery({ period })}";
            } else if (Boolean.TRUE.equals(pv.get("pageIsReport")) && Boolean.TRUE.equals(pv.get("hasFilters"))) {
                // The report's filter bar likewise (#/revenue?region=NORTH).
                routeProps = " query={route.query} onQueryChange={setQuery}";
            }
            pv.put("routeProps", routeProps);
            // The screen's destructured props, in a fixed order. A dashboard with a period picker
            // and a filterable report take their route state as optional props (absent when the
            // screen is embedded as a tab, where they keep local state instead).
            boolean dashboardPeriod = Boolean.TRUE.equals(pv.get("pageIsDashboard")) && Boolean.TRUE.equals(pv.get("hasDateRange"));
            boolean reportQuery = Boolean.TRUE.equals(pv.get("pageIsReport")) && Boolean.TRUE.equals(pv.get("hasFilters"));
            List<String> screenParams = new ArrayList<>();
            if (Boolean.TRUE.equals(pv.get("listTakesQuery"))) screenParams.add("filters");
            if (dashboardPeriod) screenParams.addAll(List.of("period: routePeriod", "onPeriodChange"));
            if (reportQuery) screenParams.addAll(List.of("query", "onQueryChange"));
            if (Boolean.TRUE.equals(pv.get("needsNavigate"))) screenParams.add("onNavigate");
            if (Boolean.TRUE.equals(pv.get("isTabTarget"))) screenParams.add("embedded");
            pv.put("screenParams", String.join(", ", screenParams));
            pv.put("hasScreenProps", !screenParams.isEmpty());
            pv.put("takesQuery", dashboardPeriod || reportQuery);
        }

        // Screens import the i18n `t` only when one of their label expressions calls it — the
        // generated lint rejects an unused import. Master-detail and record screens always use it.
        for (Map<String, Object> pv : viewById.values()) {
            List<Object> exprs = new ArrayList<>();
            exprs.add(pv.get("pageTitleExpr"));
            for (String listKey : List.of("widgets", "tabs", "childTabs")) {
                for (Map<String, Object> item : (List<Map<String, Object>>) pv.getOrDefault(listKey, List.of())) {
                    exprs.add(item.get("titleExpr"));
                    exprs.add(item.get("tabTitleExpr"));
                    for (Map<String, Object> link : (List<Map<String, Object>>) item.getOrDefault("links", List.of())) {
                        exprs.add(link.get("labelExpr"));
                    }
                }
            }
            pv.put("usesT", Boolean.TRUE.equals(pv.get("pageIsMasterDetail")) || Boolean.TRUE.equals(pv.get("pageIsRecord"))
                    || Boolean.TRUE.equals(pv.get("pageIsReport")) || Boolean.TRUE.equals(pv.get("pageIsWizard"))
                    || exprs.stream().anyMatch(e -> e instanceof String s && s.startsWith("t(")));
        }

        List<Map<String, Object>> all = new ArrayList<>(viewById.values());
        List<Map<String, Object>> nav = all.stream().filter(v -> !Boolean.TRUE.equals(v.get("hidden"))).toList();
        List<Map<String, Object>> records = all.stream().filter(v -> Boolean.TRUE.equals(v.get("pageIsRecord"))).toList();
        List<Map<String, Object>> routes = new ArrayList<>(nav);
        routes.addAll(records);
        // A hidden wizard is still a route: a list page's New opens it.
        all.stream().filter(v -> Boolean.TRUE.equals(v.get("pageIsWizard")) && Boolean.TRUE.equals(v.get("hidden")))
                .forEach(routes::add);
        ctx.put("pages", all);
        ctx.put("navPages", nav);
        // Everything the shell can show: the nav pages, plus record pages (opened with an id).
        ctx.put("routePages", routes);
        ctx.put("recordPages", records);
        ctx.put("hasRecordPages", !records.isEmpty());
        // Routes outside the nav (record pages, hidden wizards): the header names them from here,
        // and one with a home page shows it as a breadcrumb.
        List<Map<String, Object>> offNav = routes.stream().filter(v -> !nav.contains(v)).toList();
        ctx.put("offNavPages", offNav);
        ctx.put("hasOffNavPages", !offNav.isEmpty());
        List<Map<String, Object>> crumbs = offNav.stream().filter(v -> Boolean.TRUE.equals(v.get("hasBack"))).toList();
        ctx.put("crumbPages", crumbs);
        ctx.put("hasBreadcrumbs", !crumbs.isEmpty());
        ctx.put("hasPageRoles", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("hasRoles"))));
        // The shell declares its `goView` helper only when some screen takes onNavigate.
        ctx.put("hasNavigatingScreens", routes.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("needsNavigate"))));
        // ...and its `setQuery` (a route rewrite) only when a routed screen keeps state in the hash.
        ctx.put("hasQueryScreens", routes.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("takesQuery"))));
        ctx.put("initialPageId", nav.get(0).get("pageId"));
        // The lucide names the shell imports: the nav icons in use plus its own chrome, sorted.
        Set<String> icons = new TreeSet<>(List.of("Menu", "Moon", "Sun"));
        nav.forEach(v -> icons.add((String) v.get("navIcon")));
        ctx.put("navIconImports", String.join(", ", icons));
        putNavGroups(ctx, nav);
        ctx.put("hasDashboardPages", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("pageIsDashboard"))));
        ctx.put("hasReportPages", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("pageIsReport"))));
        // widgets.tsx backs both the dashboard screens and the report screen's chart.
        ctx.put("hasWidgets", Boolean.TRUE.equals(ctx.get("hasDashboardPages"))
                || Boolean.TRUE.equals(ctx.get("hasReportPages"))
                || all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("hasHeaderStats"))));
        // links.tsx (the launcher tiles) only for a dashboard that has a links widget.
        ctx.put("hasLinksWidgets", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("usesLinks"))));
        // Per-entity contexts read this: an entity with a wizard gets the stepped form and the
        // list page's onCreate.
        ctx.put(WIZARD_PAGES_KEY, links.wizardPageByEntity());
        // Per-entity contexts read this too: a relation to an entity with a record page links there.
        ctx.put(RECORD_PAGES_KEY, links.recordPageByEntity());
        // Per-entity contexts read this too: only an entity whose list page sets columns / sort /
        // view / pageSize gets the props for them, so every other EntityPage keeps its bytes.
        Set<String> listConfigured = new LinkedHashSet<>();
        for (PageDefinition p : pages) {
            if (p.type() == PageDefinition.Type.ENTITY_LIST && p.hasListPresentation()) listConfigured.add(p.entity());
        }
        ctx.put(LIST_PRESENTATION_KEY, listConfigured);
        ctx.put("hasTabsPages", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("pageIsTabs"))));
    }

    /**
     * The nav as sections: pages sharing a {@code group} are listed together under its name, in the
     * order the group first appears; ungrouped pages form label-less sections of their own between
     * them. Without any group the whole nav is one section labelled "Main", as it always was.
     */
    private static void putNavGroups(Map<String, Object> ctx, List<Map<String, Object>> nav) {
        boolean grouped = nav.stream().anyMatch(v -> v.get("navGroup") != null);
        List<Map<String, Object>> sections = new ArrayList<>();
        Map<String, List<Map<String, Object>>> itemsByGroup = new LinkedHashMap<>();
        List<Map<String, Object>> loose = null;
        for (Map<String, Object> v : nav) {
            String group = (String) v.get("navGroup");
            if (group == null) {
                if (loose == null) {
                    loose = new ArrayList<>();
                    Map<String, Object> section = new LinkedHashMap<>();
                    section.put("hasGroupLabel", !grouped);
                    section.put("isGroup", false);
                    section.put("groupLabelExpr", grouped ? "''" : "t('main')");
                    section.put("items", loose);
                    sections.add(section);
                }
                loose.add(v);
                continue;
            }
            // A group interrupts a run of ungrouped pages; the next ungrouped page starts a new one.
            loose = null;
            List<Map<String, Object>> items = itemsByGroup.get(group);
            if (items == null) {
                items = new ArrayList<>();
                itemsByGroup.put(group, items);
                Map<String, Object> section = new LinkedHashMap<>();
                section.put("hasGroupLabel", true);
                section.put("isGroup", true);
                section.put("groupLabelExpr", tsString(group));
                section.put("items", items);
                sections.add(section);
            }
            items.add(v);
        }
        for (int i = 0; i < sections.size(); i++) sections.get(i).put("sectionIndex", i);
        ctx.put("navGroups", sections);
        ctx.put("hasNavGroups", grouped);
    }

    /**
     * Where the pages of a layout link to: an entity's record page (opened from its rows), and its
     * "home" — the first visible list page, else a visible master-detail page listing it as the
     * parent, else a visible tabs page embedding one of its list pages.
     */
    private record PageLinks(Map<String, String> recordPageByEntity, Map<String, String> homeByEntity,
                             Map<String, String> listPageByEntity, Map<String, String> wizardPageByEntity) {

        static PageLinks of(List<PageDefinition> pages) {
            Map<String, String> records = new LinkedHashMap<>();
            Map<String, String> homes = new LinkedHashMap<>();
            Map<String, PageDefinition> byId = new LinkedHashMap<>();
            for (PageDefinition p : pages) byId.put(p.id(), p);
            for (PageDefinition p : pages) {
                if (p.type() == PageDefinition.Type.RECORD) records.put(p.entity(), p.id());
                if (p.type() == PageDefinition.Type.ENTITY_LIST && !p.hidden()) homes.putIfAbsent(p.entity(), p.id());
            }
            Map<String, String> lists = new LinkedHashMap<>(homes);
            for (PageDefinition p : pages) {
                if (p.type() == PageDefinition.Type.MASTER_DETAIL && !p.hidden()) homes.putIfAbsent(p.parent(), p.id());
            }
            for (PageDefinition p : pages) {
                if (p.type() != PageDefinition.Type.TABS || p.hidden()) continue;
                for (PageDefinition.Tab tab : p.tabs()) {
                    PageDefinition target = byId.get(tab.page());
                    if (target.type() == PageDefinition.Type.ENTITY_LIST) homes.putIfAbsent(target.entity(), p.id());
                    if (target.type() == PageDefinition.Type.MASTER_DETAIL) homes.putIfAbsent(target.parent(), p.id());
                }
            }
            Map<String, String> wizards = new LinkedHashMap<>();
            for (PageDefinition p : pages) {
                if (p.type() == PageDefinition.Type.WIZARD) wizards.put(p.entity(), p.id());
            }
            return new PageLinks(records, homes, lists, wizards);
        }

        String recordPageOf(String entity) { return recordPageByEntity.get(entity); }

        /** The entity's first visible list page — what a drill-down opens, filtered — or null. */
        String listPageOf(String entity) { return listPageByEntity.get(entity); }

        /** The entity's wizard page — where its list page's New goes — or null. */
        String wizardPageOf(String entity) { return wizardPageByEntity.get(entity); }

        String homeOf(String entity) { return homeByEntity.get(entity); }
    }

    /** {@code <prefix>HasRecordPage}/{@code <prefix>RecordPageId}/{@code <prefix>RecordPk} (prefix ""
     *  gives {@code hasRecordPage}/{@code recordPageId}/{@code recordPk}): whether rows of {@code entity}
     *  open on a record page, which one, and the key field whose value is the route argument. */
    private static void putRecordLink(Map<String, Object> view, String prefix, String entity, PageLinks links,
                                      Map<String, Map<String, Object>> summaries) {
        String page = links.recordPageOf(entity);
        view.put(prefix.isEmpty() ? "hasRecordPage" : prefix + "HasRecordPage", page != null);
        view.put(prefix.isEmpty() ? "recordPageId" : prefix + "RecordPageId", page);
        view.put(prefix.isEmpty() ? "recordPk" : prefix + "RecordPk",
                summaries.get(entity.toLowerCase(Locale.ROOT)).get("pkName"));
    }

    @SuppressWarnings("unchecked")
    private static void putDashboard(Map<String, Object> pv, PageDefinition p,
                                     Map<String, Map<String, Object>> entityByPascal,
                                     Map<String, Map<String, Object>> summaries,
                                     PageLinks links) {
        List<Map<String, Object>> widgetViews = new ArrayList<>();
        // The `<Enum>Labels` consts the bar charts read, grouped into one import per entity module
        // (deduped, declaration order).
        Map<String, Set<String>> labelRefsByModule = new LinkedHashMap<>();
        boolean needsNavigate = false;
        boolean usesRange = false;
        boolean usesStatsQuery = false;
        boolean usesQueryOf = false;
        boolean usesBucketRange = false;
        for (int i = 0; i < p.widgets().size(); i++) {
            PageDefinition.Widget w = p.widgets().get(i);
            if (w.kind() == PageDefinition.WidgetKind.TEXT) {
                widgetViews.add(textWidgetView(w, i));
                continue;
            }
            if (w.kind() == PageDefinition.WidgetKind.LINKS) {
                widgetViews.add(linksWidgetView(w, i));
                continue;
            }
            Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(w.entity()));
            Map<String, Object> summary = summaries.get(w.entity().toLowerCase(Locale.ROOT));
            String entityLabels = tsString((String) ev.get("entityLabelPlural"));
            Map<String, Object> wv = new LinkedHashMap<>();
            wv.put("widgetKey", "w" + i);
            wv.put("widgetIsKpi", w.kind() == PageDefinition.WidgetKind.KPI);
            // A donut is a breakdown drawn as a ring: the same card, with `donut`.
            wv.put("widgetIsBar", w.kind() == PageDefinition.WidgetKind.BAR || w.kind() == PageDefinition.WidgetKind.DONUT);
            wv.put("isDonut", w.kind() == PageDefinition.WidgetKind.DONUT);
            wv.put("widgetIsStacked", w.kind() == PageDefinition.WidgetKind.STACKED);
            wv.put("widgetIsText", false);
            wv.put("widgetIsLine", w.kind() == PageDefinition.WidgetKind.LINE);
            wv.put("widgetIsRecent", w.kind() == PageDefinition.WidgetKind.RECENT);
            wv.put("widgetIsTop", w.kind() == PageDefinition.WidgetKind.TOP);
            wv.put("widgetIsProgress", w.kind() == PageDefinition.WidgetKind.PROGRESS);
            // `count` is the default everywhere, so only a real reduction reaches the props.
            boolean reduces = w.agg() != null && w.agg() != PageDefinition.Agg.COUNT;
            wv.put("hasAgg", reduces);
            wv.put("agg", w.agg() == null ? null : w.agg().wire());
            wv.put("aggField", w.field());
            wv.put("bucket", w.bucket() == null ? null : w.bucket().wire());
            wv.put("path", "/api/" + ev.get("entityNamePluralKebab"));
            String spanClass = SPAN_CLASSES.get(w.span() - 1);
            wv.put("hasSpanClass", !spanClass.isEmpty());
            wv.put("spanClass", spanClass);
            // The filter params the widget's queries carry: its fixed preset, then the dashboard
            // period over its date field (a TS expression, since the period is screen state).
            String preset = presetQuery(w.presetFilter());
            String paramsExpr = params(preset, rangeExpr(ev, w.dateField(), false));
            usesRange |= w.dateField() != null;
            usesStatsQuery |= preset != null && w.dateField() != null;
            wv.put("hasParams", paramsExpr != null);
            wv.put("paramsExpr", paramsExpr);
            // A kpi's change against the previous period: the same params, one period back. The
            // picker's "all time" has no previous period, so the tile then shows no change.
            wv.put("hasCompare", w.compare());
            wv.put("compareParamsExpr", w.compare() ? params(preset, rangeExpr(ev, w.dateField(), true)) : null);
            wv.put("target", w.target() == null ? null : w.target().toPlainString());
            String target = links.homeOf(w.entity());
            wv.put("hasTarget", target != null);
            wv.put("targetPageId", target);
            // "View all" on a filtered widget opens the list with the same filters, when its home is
            // a list page that takes them from the route.
            boolean openWithQuery = paramsExpr != null && target != null && target.equals(links.listPageOf(w.entity()))
                    && Boolean.TRUE.equals(ev.get("hasFilters"));
            wv.put("openWithQuery", openWithQuery);
            usesQueryOf |= openWithQuery;
            needsNavigate |= target != null;
            String defaultTitle;
            switch (w.kind()) {
                // A reducing tile is titled by what it reduces ("Total Amount"), a counting one by
                // what it counts.
                case KPI, PROGRESS -> defaultTitle = reduces ? aggTitle(w, ev) : entityLabels;
                case LINE -> {
                    wv.put("groupBy", w.groupBy());
                    Map<String, Object> fv = fieldOf(ev, w.groupBy());
                    wv.put("groupByIsDateTime", Boolean.TRUE.equals(fv.get("isDateTime")));
                    // A point opens the list over its day/month/year.
                    boolean drill = putDrill(wv, ev, w.entity(), links, w.groupBy(), paramsExpr);
                    usesBucketRange |= drill;
                    usesQueryOf |= drill && paramsExpr != null;
                    needsNavigate |= drill;
                    defaultTitle = "t('xOverTime', { x: "
                            + (reduces ? aggTitle(w, ev) : entityLabels) + " })";
                }
                case STACKED -> {
                    Map<String, Object> rank = rankView(ev, w.groupBy());
                    wv.putAll(rank);
                    Map<String, Object> split = rankView(ev, w.series());
                    wv.put("seriesField", w.series());
                    wv.put("hasSeriesLabels", split.get("hasLabels"));
                    wv.put("seriesLabelsRef", split.get("labelsRef"));
                    for (Object ref : new Object[] {rank.get("labelsRef"), split.get("labelsRef")}) {
                        if (ref != null) {
                            labelRefsByModule.computeIfAbsent((String) ev.get("entityNameKebab"), k -> new LinkedHashSet<>())
                                    .add((String) ref);
                        }
                    }
                    boolean drill = putDrill(wv, ev, w.entity(), links, (String) rank.get("drillKey"), paramsExpr);
                    usesQueryOf |= drill && paramsExpr != null;
                    needsNavigate |= drill;
                    defaultTitle = "t('xByYAndZ', { x: " + (reduces ? aggTitle(w, ev) : entityLabels)
                            + ", y: " + rank.get("groupLabelExpr") + ", z: " + split.get("groupLabelExpr") + " })";
                }
                case BAR, TOP, DONUT -> {
                    Map<String, Object> rank = rankView(ev, w.groupBy());
                    wv.putAll(rank);
                    String labelsRef = (String) rank.get("labelsRef");
                    if (labelsRef != null) {
                        labelRefsByModule.computeIfAbsent((String) ev.get("entityNameKebab"), k -> new LinkedHashSet<>())
                                .add(labelsRef);
                    }
                    // A bar or a ranked row opens the list filtered to that group.
                    boolean drill = putDrill(wv, ev, w.entity(), links, (String) rank.get("drillKey"), paramsExpr);
                    usesQueryOf |= drill && paramsExpr != null;
                    needsNavigate |= drill;
                    if (w.kind() == PageDefinition.WidgetKind.TOP) wv.put("limit", w.limit());
                    String measured = reduces ? aggTitle(w, ev) : entityLabels;
                    defaultTitle = "t('" + (w.kind() == PageDefinition.WidgetKind.TOP ? "topXByY" : "xByY")
                            + "', { x: " + measured + ", y: " + rank.get("groupLabelExpr") + " })";
                }
                default -> {
                    wv.put("limit", w.limit());
                    // Newest first by the chosen column; the key column reads as "#12".
                    wv.put("sortField", w.sortBy() != null ? w.sortBy() : summary.get("pkName"));
                    wv.put("hasKeyField", w.sortBy() != null);
                    wv.put("keyField", summary.get("pkName"));
                    Object labelField = summary.get("labelField");
                    wv.put("displayField", labelField != null ? labelField : summary.get("pkName"));
                    // A recent row opens its record page, when the entity has one.
                    putRecordLink(wv, "", w.entity(), links, summaries);
                    needsNavigate |= Boolean.TRUE.equals(wv.get("hasRecordPage"));
                    defaultTitle = "t('recentX', { x: " + entityLabels + " })";
                }
            }
            wv.put("titleExpr", w.title() != null ? tsString(w.title()) : defaultTitle);
            widgetViews.add(wv);
        }
        pv.put("widgets", widgetViews);
        for (String kind : List.of("Kpi", "Bar", "Line", "Recent", "Top", "Progress", "Stacked", "Text", "Links")) {
            pv.put("uses" + kind, widgetViews.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("widgetIs" + kind))));
        }
        usesStatsQuery |= widgetViews.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("hasCompare"))
                && ((String) v.get("compareParamsExpr")).startsWith("statsQuery("));
        List<Map<String, Object>> labelImports = new ArrayList<>();
        labelRefsByModule.forEach((kebab, refs) ->
                labelImports.add(Map.of("entityNameKebab", kebab, "labelsRefs", String.join(", ", refs))));
        pv.put("labelImports", labelImports);
        pv.put("needsNavigate", needsNavigate);
        pv.put("hasDateRange", p.dateRange() != null);
        pv.put("dateRangeDefault", p.dateRange() == null ? null : p.dateRange().wire());
        // stats.ts helpers the screen imports.
        pv.put("usesRangeParams", usesRange);
        pv.put("usesStatsQuery", usesStatsQuery);
        pv.put("usesQueryOf", usesQueryOf);
        pv.put("usesBucketRange", usesBucketRange);
        pv.put("usesStatsHelpers", p.dateRange() != null || usesStatsQuery || usesQueryOf || usesBucketRange);
    }

    /** The widget's filter params as a TS expression: its preset, the period, or both; null for none. */
    private static String params(String preset, String range) {
        if (preset == null) return range;
        if (range == null) return tsString(preset);
        return "statsQuery(" + tsString(preset) + ", " + range + ")";
    }

    /** {@code rangeParams('soldOn', false, period)} (the previous period with {@code previous}), or
     *  null when the widget has no period date. */
    private static String rangeExpr(Map<String, Object> ev, String dateField, boolean previous) {
        if (dateField == null) return null;
        Map<String, Object> df = fieldOf(ev, dateField);
        return "rangeParams('" + dateField + "', " + Boolean.TRUE.equals(df.get("isDateTime")) + ", period"
                + (previous ? ", true" : "") + ")";
    }

    /**
     * What a bar chart or top list groups by, for its screen: the {@code /stats} key, how a group is
     * labelled (an enum's labels, or — for a relation — the target's rows by id), the heading of the
     * column, and the list filter a group drills into.
     */
    /** A links widget's view before its tiles are resolved (a target may be declared later): the
     *  page ids, and the title and width like a text card. */
    private static Map<String, Object> linksWidgetView(PageDefinition.Widget w, int index) {
        Map<String, Object> wv = new LinkedHashMap<>();
        wv.put("widgetKey", "w" + index);
        for (String kind : List.of("Kpi", "Bar", "Line", "Recent", "Top", "Progress", "Stacked", "Text")) {
            wv.put("widgetIs" + kind, false);
        }
        wv.put("widgetIsLinks", true);
        wv.put("hasTitle", w.title() != null);
        wv.put("titleExpr", w.title() == null ? null : tsString(w.title()));
        wv.put("linkIds", w.pages());
        String spanClass = SPAN_CLASSES.get(w.span() - 1);
        wv.put("hasSpanClass", !spanClass.isEmpty());
        wv.put("spanClass", spanClass);
        wv.put("hasParams", false);
        wv.put("hasCompare", false);
        return wv;
    }

    /** A text widget's view: its title (if any) and paragraphs as TS string literals. */
    private static Map<String, Object> textWidgetView(PageDefinition.Widget w, int index) {
        Map<String, Object> wv = new LinkedHashMap<>();
        wv.put("widgetKey", "w" + index);
        for (String kind : List.of("Kpi", "Bar", "Line", "Recent", "Top", "Progress", "Stacked", "Links")) {
            wv.put("widgetIs" + kind, false);
        }
        wv.put("widgetIsText", true);
        wv.put("hasTitle", w.title() != null);
        wv.put("titleExpr", w.title() == null ? null : tsString(w.title()));
        List<Map<String, Object>> paragraphs = new ArrayList<>();
        for (String para : w.text().split("\\n\\s*\\n")) {
            String trimmed = para.strip();
            if (!trimmed.isEmpty()) paragraphs.add(Map.of("textExpr", tsString(trimmed)));
        }
        wv.put("paragraphs", paragraphs);
        String spanClass = SPAN_CLASSES.get(w.span() - 1);
        wv.put("hasSpanClass", !spanClass.isEmpty());
        wv.put("spanClass", spanClass);
        wv.put("hasParams", false);
        wv.put("hasCompare", false);
        return wv;
    }

    private static Map<String, Object> rankView(Map<String, Object> ev, String groupBy) {
        Map<String, Object> out = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> rel = ((List<Map<String, Object>>) ev.get("relations")).stream()
                .filter(r -> groupBy.equalsIgnoreCase((String) r.get("fieldName"))).findFirst().orElse(null);
        if (rel != null) {
            String name = (String) rel.get("fieldName");
            out.put("groupBy", name);
            out.put("hasLabels", false);
            out.put("labelsRef", null);
            out.put("isRelationRank", true);
            out.put("optionsPath", "/api/" + rel.get("targetEntityKebabPlural"));
            out.put("optionValue", rel.get("targetPkName"));
            out.put("hasOptionLabel", rel.get("targetLabelField") != null);
            out.put("optionLabel", rel.get("targetLabelField"));
            out.put("groupLabelExpr", tsString(Naming.toPascalCase(name)));
            out.put("drillKey", rel.get("fkFieldName"));
            return out;
        }
        Map<String, Object> fv = fieldOf(ev, groupBy);
        Object enumType = Boolean.TRUE.equals(fv.get("isEnum")) ? fv.get("enumTypeName") : null;
        out.put("groupBy", groupBy);
        out.put("hasLabels", enumType != null);
        out.put("labelsRef", enumType == null ? null : enumType + "Labels");
        out.put("isRelationRank", false);
        out.put("groupLabelExpr", tsString((String) fv.get("label")));
        out.put("drillKey", groupBy);
        return out;
    }

    /**
     * Drill-down: a click on a group (a bar, a ranked row, a point in time) opens the entity's list
     * page filtered to it. Only when the entity has a visible list page and the list can filter by
     * {@code filterKey} — a filter it does not have would be silently dropped, showing every row.
     * Sets {@code hasDrill}/{@code drillPageId}/{@code drillKey}.
     */
    @SuppressWarnings("unchecked")
    private static boolean putDrill(Map<String, Object> view, Map<String, Object> ev, String entity, PageLinks links,
                                    String filterKey, String paramsExpr) {
        String listPage = links.listPageOf(entity);
        boolean filterable = ((List<Map<String, Object>>) ev.getOrDefault("filterFields", List.of())).stream()
                .anyMatch(f -> filterKey.equals(f.get("name")));
        boolean drill = listPage != null && filterable;
        view.put("hasDrill", drill);
        view.put("drillPageId", drill ? listPage : null);
        view.put("drillKey", filterKey);
        view.put("drillBase", drill && paramsExpr != null ? "...queryOf(" + paramsExpr + "), " : "");
        return drill;
    }

    /**
     * A wizard page: its steps (the fields each shows and the validation keys it owns — a relation
     * reports under {@code <relation>Id}), the defaults the form starts from, and where a saved
     * record goes (its record page, else the entity's home, else a fresh wizard).
     */
    @SuppressWarnings("unchecked")
    private static void putWizard(Map<String, Object> pv, PageDefinition p, Map<String, Object> ev, PageLinks links,
                                  Map<String, Map<String, Object>> summaries) {
        pv.put("EntityName", ev.get("EntityName"));
        pv.put("entityNameKebab", ev.get("entityNameKebab"));
        pv.put("entityNamePluralKebab", ev.get("entityNamePluralKebab"));
        pv.put("entityLabelExpr", tsString((String) ev.get("entityLabel")));
        Set<String> relationNames = new java.util.HashSet<>();
        for (Map<String, Object> rv : (List<Map<String, Object>>) ev.get("relations")) {
            relationNames.add((String) rv.get("fieldName"));
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < p.steps().size(); i++) {
            PageDefinition.Step step = p.steps().get(i);
            List<String> fields = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (String name : step.fields()) {
                String camel = Naming.toCamelCase(name);
                boolean relation = relationNames.contains(camel);
                String field = relation ? camel : name;
                fields.add(tsString(field));
                keys.add(tsString(relation ? camel + "Id" : name));
            }
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("stepTitleExpr", step.title() != null ? tsString(step.title()) : "t('stepX', { x: " + (i + 1) + " })");
            sv.put("fieldsTs", String.join(", ", fields));
            sv.put("keysTs", String.join(", ", keys));
            steps.add(sv);
        }
        pv.put("steps", steps);
        // The form starts from the fields' defaults, as the entity page's New does.
        List<String> defaults = new ArrayList<>();
        for (Map<String, Object> fv : (List<Map<String, Object>>) ev.get("fields")) {
            if (Boolean.TRUE.equals(fv.get("hasDefault"))) defaults.add(fv.get("name") + ": " + fv.get("defaultTs"));
        }
        pv.put("initialTs", defaults.isEmpty() ? "{}" : "{ " + String.join(", ", defaults) + " }");
        putRecordLink(pv, "", p.entity(), links, summaries);
        String home = links.homeOf(p.entity());
        pv.put("hasBack", home != null);
        pv.put("backPageId", home);
        pv.put("needsNavigate", Boolean.TRUE.equals(pv.get("hasRecordPage")) || home != null);
        // A single-key entity's wizard also edits: #/<wizard>/<id> loads the row and saves it with a PUT.
        boolean editable = !Boolean.TRUE.equals(ev.get("hasCompositePk"));
        pv.put("wizardEditable", editable);
        pv.put("wizardHasProps", editable || Boolean.TRUE.equals(pv.get("needsNavigate")));
    }

    /** A record page's header tiles: a count (or aggregate) of each related list's rows for the
     *  record, through the list's filter param; a tile whose list is a tab opens that tab. */
    private static void putHeaderStats(Map<String, Object> pv, PageDefinition p,
                                       Map<String, Map<String, Object>> entityByPascal,
                                       List<Map<String, Object>> tabViews) {
        List<Map<String, Object>> stats = new ArrayList<>();
        for (PageDefinition.HeaderStat s : p.headerStats()) {
            Map<String, Object> cv = entityByPascal.get(Naming.toPascalCase(s.child()));
            Map<String, Object> sv = new LinkedHashMap<>();
            boolean reduces = s.agg() != PageDefinition.Agg.COUNT;
            sv.put("path", "/api/" + cv.get("entityNamePluralKebab"));
            sv.put("hasAgg", reduces);
            sv.put("agg", s.agg().wire());
            sv.put("aggField", s.field());
            sv.put("viaParam", Naming.toCamelCase(s.via()) + "Id");
            String label = (String) cv.get("entityLabelPlural");
            sv.put("titleExpr", s.title() != null ? tsString(s.title())
                    : reduces ? aggTitleExpr(s.agg(), (String) fieldOf(cv, s.field()).get("label")) : tsString(label));
            Map<String, Object> tab = tabViews.stream()
                    .filter(tv -> cv.get("EntityName").equals(tv.get("childEntityName"))).findFirst().orElse(null);
            sv.put("hasTab", tab != null);
            sv.put("tabIndex", tab == null ? null : tab.get("tabIndex"));
            stats.add(sv);
        }
        pv.put("headerStats", stats);
        pv.put("hasHeaderStats", !stats.isEmpty());
    }

    /** Grid classes per widget span (1–4 columns of the dashboard's sm:2 / lg:4 grid). Literal
     *  strings in the generated screen, so Tailwind's scanner sees them. */
    private static final List<String> SPAN_CLASSES = List.of(
            "", "sm:col-span-2", "sm:col-span-2 lg:col-span-3", "sm:col-span-2 lg:col-span-4");

    /** {@code {status=OPEN, paid=true}} → {@code paid=true&status=OPEN} (sorted); null when empty.
     *  Values are validated constants/booleans, so nothing needs URL-encoding. */
    private static String presetQuery(Map<String, String> filter) {
        if (filter.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> en : new java.util.TreeMap<>(filter).entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(en.getKey()).append('=').append(en.getValue());
        }
        return sb.toString();
    }

    /**
     * A report's charts, as its screen's view-model: for each, which component draws it, the fixed
     * half of the {@code /stats} query it asks for (the filter bar appends its values), the headings
     * of its values, and where a click on a group drills to. The first chart also gets the totals
     * table.
     */
    private static void putCharts(Map<String, Object> pv, PageDefinition p, Map<String, Object> ev, PageLinks links) {
        List<Map<String, Object>> charts = new ArrayList<>();
        Set<String> labelRefs = new LinkedHashSet<>();
        boolean drills = false;
        boolean usesBucketRange = false;
        for (int i = 0; i < p.charts().size(); i++) {
            PageDefinition.Chart chart = p.charts().get(i);
            Map<String, Object> cv = new LinkedHashMap<>();
            // The grouping as a top widget sees it: an enum's labels, or a relation's target list
            // (bars named from it, the drill key being the FK filter param).
            Map<String, Object> rank = rankView(ev, chart.groupBy());
            boolean relation = Boolean.TRUE.equals(rank.get("isRelationRank"));
            Map<String, Object> fv = relation ? null : fieldOf(ev, chart.groupBy());
            boolean overTime = chart.bucket() != null;
            cv.put("chartIsLine", overTime);
            cv.put("chartIsBar", !overTime);
            cv.put("chartGroupBy", chart.groupBy());
            cv.put("chartIsRelation", relation);
            if (relation) {
                cv.put("optionsPath", rank.get("optionsPath"));
                cv.put("optionValue", rank.get("optionValue"));
                cv.put("hasOptionLabel", rank.get("hasOptionLabel"));
                cv.put("optionLabel", rank.get("optionLabel"));
            }
            String groupLabelExpr = (String) rank.get("groupLabelExpr");
            cv.put("chartGroupLabelExpr", groupLabelExpr);
            String labelsRef = (String) rank.get("labelsRef");
            cv.put("chartHasLabels", labelsRef != null);
            cv.put("chartLabelsRef", labelsRef);
            if (labelsRef != null) labelRefs.add(labelsRef);
            boolean reduces = chart.agg() != PageDefinition.Agg.COUNT;
            String measure = reduces ? aggTitleExpr(chart.agg(), (String) fieldOf(ev, chart.field()).get("label")) : null;
            cv.put("chartValueHeaderExpr", reduces ? measure : "t('rows')");
            cv.put("chartTitleExpr", "t('" + (overTime ? "xOverTime" : "xByY") + "', { x: "
                    + (reduces ? measure : tsString((String) ev.get("entityLabelPlural")))
                    + (overTime ? "" : ", y: " + groupLabelExpr) + " })");
            cv.put("chartIsFirst", i == 0);
            StringBuilder query = new StringBuilder("groupBy=").append(chart.groupBy());
            if (overTime) query.append("&bucket=").append(chart.bucket().wire());
            if (reduces) query.append("&agg=").append(chart.agg().wire()).append("&field=").append(chart.field());
            cv.put("rollupQuery", query.toString());
            // A bar opens the list on its value (a relation's on its id); a point in time on its day/month/year.
            boolean drill = putDrill(cv, ev, p.entity(), links, (String) rank.get("drillKey"), null);
            cv.put("drillExpr", overTime
                    ? "...bucketRange('" + chart.groupBy() + "', key, " + Boolean.TRUE.equals(fv.get("isDateTime")) + ")"
                    : rank.get("drillKey") + ": key");
            drills |= drill;
            usesBucketRange |= drill && overTime;
            charts.add(cv);
        }
        pv.put("charts", charts);
        pv.put("hasManyCharts", charts.size() > 1);
        pv.put("firstChart", charts.subList(0, 1));
        pv.put("moreCharts", charts.subList(1, charts.size()));
        pv.put("chartLabelsRefs", String.join(", ", labelRefs));
        pv.put("hasChartLabels", !labelRefs.isEmpty());
        pv.put("usesBucketRange", usesBucketRange);
        pv.put("needsNavigate", drills);
    }

    /** One field of an entity view-model, by its wire name. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldOf(Map<String, Object> entityView, String name) {
        return ((List<Map<String, Object>>) entityView.get("fields")).stream()
                .filter(f -> name.equals(f.get("name"))).findFirst().orElseThrow();
    }

    /** {@code t('aggSum', { x: 'Amount' })} — what a reducing tile or chart is called when the
     *  request gave it no title of its own. */
    private static String aggTitle(PageDefinition.Widget w, Map<String, Object> entityView) {
        return aggTitleExpr(w.agg(), (String) fieldOf(entityView, w.field()).get("label"));
    }

    private static String aggTitleExpr(PageDefinition.Agg agg, String fieldLabel) {
        String key = "agg" + agg.wire().substring(0, 1).toUpperCase(Locale.ROOT) + agg.wire().substring(1);
        return "t('" + key + "', { x: " + tsString(fieldLabel) + " })";
    }

    /** One entry of a {@code /stats} whitelist: enough to splice the column into the generated
     *  {@code switch} and to label it. */
    private static Map<String, Object> statsField(Map<String, Object> fv) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", fv.get("name"));
        out.put("Name", fv.get("Name"));
        out.put("label", fv.get("label"));
        out.put("javaType", fv.get("javaType"));
        return out;
    }

    /** {@code {status=OPEN}} → {@code { status: 'OPEN' }}; values are validated constants/booleans. */
    private static String presetFilterTs(Map<String, String> filter) {
        if (filter.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{ ");
        int i = 0;
        for (Map.Entry<String, String> en : new java.util.TreeMap<>(filter).entrySet()) {
            if (i++ > 0) sb.append(", ");
            sb.append(en.getKey()).append(": ").append(tsString(en.getValue()));
        }
        return sb.append(" }").toString();
    }

    /**
     * The list views the generated page of {@code entity} actually offers: the requested
     * {@code listViews}, intersected with what its fields support — table and cards always, kanban
     * needs a breakdown field (the first enum, else the first boolean) on a writable entity, calendar
     * a temporal field — in the fixed table/cards/kanban/calendar order; {@code [table]} when nothing
     * requested fits. Derived from the entity model alone and shared with
     * {@link FullstackPageValidator}, so a list page's {@code view} is checked against exactly what
     * renders.
     */
    static List<String> emittedListViews(EntityDefinition entity) {
        Set<String> requested = new LinkedHashSet<>(entity.listViews());
        boolean breakdown = entity.fields().stream().anyMatch(f -> f.type().isEnum() || f.type().isBoolean());
        boolean temporal = entity.fields().stream().anyMatch(f -> f.type().isTemporal());
        List<String> emitted = new ArrayList<>();
        if (requested.contains("table")) emitted.add("table");
        if (requested.contains("cards")) emitted.add("cards");
        if (requested.contains("kanban") && breakdown && !entity.readOnly()) emitted.add("kanban");
        if (requested.contains("calendar") && temporal) emitted.add("calendar");
        if (emitted.isEmpty()) emitted.add("table");
        return emitted;
    }

    /** A user-supplied string as a single-quoted TS literal (apostrophes, backslashes and line
     *  breaks escaped). */
    static String tsString(String s) {
        return "'" + escapeTsSingleQuoted(s).replace("\r", "").replace("\n", "\\n") + "'";
    }

    /** Internal key under which the entity → wizard page lookup rides in the (frontend) project
     *  context, for {@link #buildEntityContext}. Not referenced by any template. */
    private static final String WIZARD_PAGES_KEY = "__wizardPages";

    /** Internal key under which the entity → record page lookup rides in the (frontend) project
     *  context: a relation whose target has a record page renders as a link to it. Not referenced
     *  by any template. */
    private static final String RECORD_PAGES_KEY = "__recordPages";

    /** Internal key under which the names of the entities whose list pages set a presentation
     *  (columns / sort / view / pageSize) ride in the (frontend) project context, for
     *  {@link #buildEntityContext}'s {@code listConfigurable}. Not referenced by any template. */
    private static final String LIST_PRESENTATION_KEY = "__listPresentationEntities";

    /** Internal key under which the entity-summary lookup rides in the project context.
     *  Not referenced by any template. */
    private static final String ENTITY_SUMMARIES_KEY = "__entitySummaries";

    /** Internal key under which the inverse-relation lookup (lower(parent) → inverse views) rides
     *  in the project context, so per-entity contexts can resolve their @OneToMany collections. */
    private static final String INVERSE_RELATIONS_KEY = "__inverseRelations";

    /**
     * Derives inverse ({@code @OneToMany}) collections from the owning {@code MANY_TO_ONE} relations:
     * for each child entity A with a {@code MANY_TO_ONE} to parent B, B gets a read-only collection of
     * A. Keyed by {@code lower(parentName)}. The collection is named after the pluralized child (e.g.
     * a {@code Customer} targeted by {@code Order.customer} gets an {@code orders} collection with
     * {@code mappedBy = "customer"}).
     */
    private static Map<String, List<Map<String, Object>>> buildInverseRelations(List<EntityDefinition> entities) {
        Map<String, List<Map<String, Object>>> byLower = new LinkedHashMap<>();
        for (EntityDefinition child : entities) {
            for (RelationDefinition rel : child.relations()) {
                if (rel.type() != RelationType.MANY_TO_ONE) continue;
                String parentLower = rel.targetEntity().toLowerCase(Locale.ROOT);
                String childCamel = Naming.toCamelCase(child.name());
                String coll = Naming.pluralize(childCamel);
                Map<String, Object> inv = new LinkedHashMap<>();
                inv.put("childEntity", Naming.toPascalCase(child.name()));
                inv.put("childEntityCamel", childCamel);
                inv.put("mappedBy", Naming.toCamelCase(rel.fieldName()));
                inv.put("collectionField", coll);
                inv.put("CollectionField", Naming.toPascalCase(coll));
                // SQL names for the parent's @Formula child count: the child's table (custom name
                // or the default snake-plural, schema-qualified when set) and its FK column —
                // the same derivations the child's own @Table/@JoinColumn use.
                String childTable = child.tableName() != null ? child.tableName()
                        : Naming.pluralize(Naming.toSnakeCase(child.name()));
                inv.put("childTableRef", (child.schema() != null ? child.schema() + "." : "") + childTable);
                inv.put("childJoinColumn", Naming.toSnakeCase(rel.fieldName()) + "_id");
                byLower.computeIfAbsent(parentLower, k -> new ArrayList<>()).add(inv);
            }
        }
        for (List<Map<String, Object>> list : byLower.values()) {
            for (int i = 0; i < list.size(); i++) {
                list.get(i).put("last", i == list.size() - 1);
            }
        }
        return byLower;
    }

    /** Builds a {@code lower(name) → summary} lookup with each entity's PK type/name and
     *  naming variants, so a relation can resolve its target's FK id type and class name. */
    private static Map<String, Map<String, Object>> buildSummaries(List<EntityDefinition> entities) {
        Map<String, Map<String, Object>> summaries = new LinkedHashMap<>();
        for (EntityDefinition e : entities) {
            FieldDefinition pk = e.fields().stream()
                    .filter(FieldDefinition::primaryKey).findFirst().orElse(null);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("pascal", Naming.toPascalCase(e.name()));
            s.put("camel", Naming.toCamelCase(e.name()));
            s.put("kebab", Naming.toKebabCase(e.name()));
            s.put("kebabPlural", Naming.pluralize(Naming.toKebabCase(e.name())));
            String pkName = pk == null ? "id" : pk.name();
            s.put("pkName", pkName);
            s.put("PkName", Naming.toPascalCase(pkName));
            s.put("pkJavaType", pk == null ? "Long" : pk.type().javaType());
            s.put("pkTsType", pk == null ? "number" : pk.type().tsType());
            // First non-PK string field — used as a human-readable label in the frontend FK <select>.
            FieldDefinition labelField = e.fields().stream()
                    .filter(f -> f.type().isString() && !f.primaryKey()).findFirst().orElse(null);
            s.put("labelField", labelField == null ? null : labelField.name());
            // SQL names for a referencing entity's @Formula label subselect: this entity's table
            // (custom name or default snake-plural, schema-qualified) and the PK/label columns —
            // the same derivations its own @Table/@Column use.
            String table = e.tableName() != null ? e.tableName() : Naming.pluralize(Naming.toSnakeCase(e.name()));
            s.put("tableRef", (e.schema() != null && !e.schema().isBlank() ? e.schema() + "." : "") + table);
            s.put("pkColumn", Naming.toSnakeCase(pkName));
            s.put("labelColumn", labelField == null ? null : Naming.toSnakeCase(labelField.name()));
            summaries.put(e.name().toLowerCase(Locale.ROOT), s);
        }
        return summaries;
    }

    /**
     * Adds color-palette variables to a project context so frontend theme templates
     * (e.g. {@code index.css.mustache}) can resolve brand colors. Exposes a {@code palette}
     * map ({@code primary}/{@code secondary}/{@code accent}/{@code error}, blanks for nulls)
     * plus {@code hasPaletteAccent}/{@code hasPaletteError} section flags. Because per-entity
     * contexts are copied from the project context, per-entity templates inherit these too.
     */
    public static void putPaletteVars(Map<String, Object> ctx, ColorPaletteEntity palette) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", palette.getPaletteId());
        p.put("name", palette.getName());
        p.put("primary", palette.getPrimary());
        p.put("secondary", palette.getSecondary());
        p.put("accent", palette.getAccent() == null ? "" : palette.getAccent());
        p.put("error", palette.getError() == null ? "" : palette.getError());
        ctx.put("palette", p);
        ctx.put("hasPaletteAccent", palette.getAccent() != null && !palette.getAccent().isBlank());
        ctx.put("hasPaletteError", palette.getError() != null && !palette.getError().isBlank());
    }

    /** The frontend value-input control a bulk-editable field uses in the bulk-edit bar. Mirrors the
     *  form's control matrix: enum/boolean → &lt;select&gt;, temporal → date pickers, numeric → number,
     *  everything else (string/text/uuid) → a plain text input. */
    private static String bulkInputKind(Map<String, Object> fv) {
        if (Boolean.TRUE.equals(fv.get("isEnum"))) return "enum";
        if (Boolean.TRUE.equals(fv.get("isBoolean"))) return "boolean";
        if (Boolean.TRUE.equals(fv.get("isDate"))) return "date";
        if (Boolean.TRUE.equals(fv.get("isDateTime"))) return "datetime";
        if (Boolean.TRUE.equals(fv.get("isNumeric"))) return "number";
        return "text";
    }

    /** A single kanban lane: {@code value} is matched against the grouping field's stringified
     *  value, {@code label} is the column heading. */
    private static Map<String, Object> kanbanColumn(String value, String label, String labelExpr) {
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("value", value);
        col.put("label", label);
        // The TS expression the page emits for the lane heading: a quoted literal for an enum
        // lane, `t('trueLabel')`/`t('falseLabel')` for the boolean lanes.
        col.put("labelExpr", labelExpr);
        return col;
    }

    /** Escapes a string for embedding in a Java or JS double-quoted string literal
     *  (backslash and double-quote only — both languages share C-style escaping). */
    private static String escapeStringLiteral(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Escapes a string for embedding in a single-quoted TS/JS literal (backslash and apostrophe —
     *  Hebrew labels often carry a geresh). */
    static String escapeTsSingleQuoted(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** Fallback display label for an enum constant without a user label:
     *  {@code IN_PROGRESS} -> {@code In progress}. */
    static String humanizeConstant(String constant) {
        String words = constant.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        if (words.isEmpty()) return constant;
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Puts {@code <name>} = {@code base.layer} and {@code <name>Path} = the slash form. */
    private static void putPackage(Map<String, Object> ctx, String name, String base, String layer) {
        String pkg = (base == null || base.isBlank()) ? layer : base + "." + layer;
        ctx.put(name, pkg);
        ctx.put(name + "Path", pkg.replace('.', '/'));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildEntityContext(
            Map<String, Object> projectContext,
            EntityDefinition entity) {
        Map<String, Object> ctx = new LinkedHashMap<>(projectContext);
        Map<String, Map<String, Object>> summaries =
                (Map<String, Map<String, Object>>) projectContext.get(ENTITY_SUMMARIES_KEY);
        Map<String, List<Map<String, Object>>> inverses =
                (Map<String, List<Map<String, Object>>>) projectContext.get(INVERSE_RELATIONS_KEY);
        ctx.putAll(entityViewModel(entity, summaries == null ? Map.of() : summaries,
                inverses == null ? Map.of() : inverses));
        // Page layouts only: a relation whose target has a record page renders as a link to it
        // (table cell and detail row). Backend contexts and layout-free frontends never carry the
        // lookup, so their relation view-models — and generated bytes — are untouched.
        Map<String, String> recordPages = (Map<String, String>) projectContext.get(RECORD_PAGES_KEY);
        if (recordPages != null && !recordPages.isEmpty()) {
            List<Map<String, Object>> linked = new ArrayList<>();
            for (Map<String, Object> rel : (List<Map<String, Object>>) ctx.get("relations")) {
                Map<String, Object> rc = new LinkedHashMap<>(rel);
                String target = String.valueOf(rel.get("targetEntity")).toLowerCase(Locale.ROOT);
                String recordPage = recordPages.entrySet().stream()
                        .filter(e -> e.getKey().toLowerCase(Locale.ROOT).equals(target))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                rc.put("targetHasRecordPage", recordPage != null);
                rc.put("targetRecordPageId", recordPage);
                linked.add(rc);
            }
            ctx.put("relations", linked);
        }
        // Page layouts only: the list page can be scoped to one parent through a relation filter
        // (master-detail and record pages), so it takes a `scope` prop.
        ctx.put("pageScopeable", Boolean.TRUE.equals(projectContext.get("hasPages")) && !entity.relations().isEmpty());
        // An entity with a wizard page: its form takes `only` (one step's fields) and its list page
        // takes `onCreate` (New opens the wizard instead of the drawer).
        Map<String, String> wizards = (Map<String, String>) projectContext.get(WIZARD_PAGES_KEY);
        boolean hasWizard = wizards != null && wizards.containsKey(entity.name());
        ctx.put("hasWizardPage", hasWizard);
        ctx.put("formHasSteps", hasWizard);
        // An entity whose list page sets a presentation: its page takes columns / initialSort /
        // initialView / initialPageSize props (false in backend contexts and for every other entity).
        Set<String> listConfigured = (Set<String>) projectContext.get(LIST_PRESENTATION_KEY);
        ctx.put("listConfigurable", listConfigured != null && listConfigured.contains(entity.name()));
        // Per-entity scaffold-opt overrides: resolve `override ?? projectOpt` for every overridable
        // option and store it under the same optScaffold<X> key, so it shadows the project-level
        // value for this entity only. Both the per-entity templates ({{#optScaffoldCsvExport}} ...)
        // and the per-entity file gates (FullstackRenderer evaluates gatedBy against this context)
        // see the resolved value; the *Applicable flags below are derived from it too.
        for (Map.Entry<String, String> opt : SCAFFOLD_OPT_FLAGS.entrySet()) {
            Boolean override = entity.opts().get(opt.getKey());
            if (override != null) {
                ctx.put(opt.getValue(), override);
            }
        }
        // Soft-delete is opt-in (optScaffoldSoftDelete) but its @SQLDelete WHERE clause only handles
        // a single PK column, so it is skipped for composite-PK entities. Computed per entity once
        // the project-level opt flag and the entity's hasCompositePk are both in the merged context.
        // Read-only / @Subselect view entities never delete and have no real table column to mark,
        // so soft-delete is skipped for them too (mutable == false).
        boolean mutable = !Boolean.TRUE.equals(ctx.get("readOnly"));
        ctx.put("softDeleteApplicable",
                Boolean.TRUE.equals(ctx.get("optScaffoldSoftDelete"))
                        && !Boolean.TRUE.equals(ctx.get("hasCompositePk"))
                        && mutable);
        // Audit timestamps (created/updated) only make sense for writable, table-backed entities —
        // a @Subselect view would have to project created_at/updated_at columns that may not exist.
        ctx.put("auditApplicable",
                Boolean.TRUE.equals(ctx.get("optScaffoldAudit")) && mutable);
        // Bulk delete (opt-in) deletes by a list of single-column ids, so it is offered only for
        // writable, single-PK entities — a composite key can't be addressed by one id list.
        ctx.put("bulkDeleteApplicable",
                Boolean.TRUE.equals(ctx.get("optScaffoldBulkDelete"))
                        && !Boolean.TRUE.equals(ctx.get("hasCompositePk"))
                        && mutable);
        // Bulk field-edit (opt-in) PATCHes one field across a list of single-column ids, so — like
        // bulk delete — it needs a writable, single-PK entity; and it needs ≥1 editable non-PK field
        // to set (bulkUpdatableFields, derived in entityViewModel).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bulkFields = (List<Map<String, Object>>) ctx.get("bulkUpdatableFields");
        ctx.put("bulkUpdateApplicable",
                Boolean.TRUE.equals(ctx.get("optScaffoldBulkUpdate"))
                        && !Boolean.TRUE.equals(ctx.get("hasCompositePk"))
                        && mutable
                        && bulkFields != null && !bulkFields.isEmpty());
        // The row-selection substrate (checkboxes, `selected` state) is shared by both bulk actions,
        // so it is emitted when either is applicable.
        ctx.put("bulkSelectApplicable",
                Boolean.TRUE.equals(ctx.get("bulkDeleteApplicable"))
                        || Boolean.TRUE.equals(ctx.get("bulkUpdateApplicable")));
        return ctx;
    }

    private static Map<String, Object> entityViewModel(
            EntityDefinition entity, Map<String, Map<String, Object>> summaries,
            Map<String, List<Map<String, Object>>> inverseByLower) {
        Map<String, Object> view = new LinkedHashMap<>();

        String pascal = Naming.toPascalCase(entity.name());
        String camel = Naming.toCamelCase(entity.name());
        String snake = Naming.toSnakeCase(entity.name());
        String kebab = Naming.toKebabCase(entity.name());
        String pascalPlural = Naming.pluralize(pascal);
        String camelPlural = Naming.pluralize(camel);
        String kebabPlural = Naming.pluralize(kebab);
        String snakePlural = Naming.pluralize(snake);

        view.put("EntityName", pascal);
        view.put("entityName", camel);
        view.put("entity_name", snake);
        view.put("entityNameKebab", kebab);
        view.put("EntityNamePlural", pascalPlural);
        view.put("entityNamePlural", camelPlural);
        view.put("entityNamePluralKebab", kebabPlural);
        view.put("entity_name_plural", snakePlural);
        // Human-facing display labels for the generated frontend (nav, page titles, dashboard,
        // dialogs). Optional — fall back to the PascalCase name and its derived plural, mirroring
        // the per-field `label`. `entityLabelPlural` prefers an explicit plural label, then the
        // singular label, then the derived plural (auto-pluralizing a localized label is unsafe).
        String label = (entity.label() != null && !entity.label().isBlank()) ? entity.label() : pascal;
        String labelPlural = (entity.labelPlural() != null && !entity.labelPlural().isBlank())
                ? entity.labelPlural()
                : (entity.label() != null && !entity.label().isBlank()) ? entity.label() : pascalPlural;
        view.put("entityLabel", label);
        view.put("entityLabelPlural", labelPlural);
        view.put("tableName", entity.tableName() != null ? entity.tableName() : snakePlural);
        view.put("schema", entity.schema());
        view.put("hasSchema", entity.schema() != null && !entity.schema().isBlank());
        // Read-only / view flags — gate CRUD in the backend & frontend templates.
        // `isView` swaps @Table for @Immutable/@Subselect; `mutable` gates create/update/delete.
        view.put("readOnly", entity.readOnly());
        view.put("mutable", !entity.readOnly());
        view.put("isView", entity.isView());
        view.put("viewQuery", entity.viewQuery());

        List<Map<String, Object>> fieldViews = new ArrayList<>(entity.fields().size());
        Map<String, Object> pkView = null;
        List<Map<String, Object>> pkViews = new ArrayList<>();
        List<Map<String, Object>> nonPkViews = new ArrayList<>();
        Set<String> imports = new TreeSet<>();

        for (int i = 0; i < entity.fields().size(); i++) {
            FieldDefinition f = entity.fields().get(i);
            Map<String, Object> fv = fieldViewModel(pascal, f);
            fv.put("first", i == 0);
            fv.put("last", i == entity.fields().size() - 1);
            fieldViews.add(fv);
            if (f.primaryKey()) {
                if (pkView == null) pkView = fv;  // first PK drives the single-PK pkField.* back-compat
                // A lightweight copy with its own first/last so composite-key iteration ({{#pkFields}})
                // does not corrupt the field's own first/last (used by Dto's comma logic).
                Map<String, Object> pk = new LinkedHashMap<>();
                pk.put("name", fv.get("name"));
                pk.put("Name", fv.get("Name"));
                pk.put("column", fv.get("column"));
                pk.put("javaType", fv.get("javaType"));
                pk.put("tsType", fv.get("tsType"));
                pkViews.add(pk);
            } else {
                nonPkViews.add(fv);
            }
            if (f.type() != FieldType.ENUM && f.type().javaImport() != null
                    && !f.type().javaImport().startsWith("java.lang.")) {
                imports.add(f.type().javaImport());
            }
        }
        // Re-tag last on nonPkViews
        for (int i = 0; i < nonPkViews.size(); i++) {
            nonPkViews.get(i).put("lastNonPk", i == nonPkViews.size() - 1);
        }
        for (int i = 0; i < pkViews.size(); i++) {
            pkViews.get(i).put("first", i == 0);
            pkViews.get(i).put("last", i == pkViews.size() - 1);
        }

        boolean hasCompositePk = pkViews.size() > 1;
        String keyClassName = pascal + "Id";
        // Pre-built path-variable segment for composite keys, e.g. "/{orderId}/{lineNo}". Built here
        // so the controller template emits a plain string and avoids Mustache triple-brace clashes.
        StringBuilder pkPath = new StringBuilder();
        for (Map<String, Object> pk : pkViews) {
            pkPath.append("/{").append(pk.get("name")).append('}');
        }
        view.put("pkPath", pkPath.toString());
        // Fields eligible for bulk field-edit: non-PK, non-read-only scalar fields (relations are
        // deliberately excluded in v1). Each entry is a shallow copy of the field view-model — so the
        // backend switch reuses the same type flags (isEnum/isIntegral/…) and the frontend gets a
        // `bulkInputKind` picking its value control — with its own `last` for comma/join logic.
        List<Map<String, Object>> bulkUpdatableViews = new ArrayList<>();
        for (Map<String, Object> fv : nonPkViews) {
            if (Boolean.TRUE.equals(fv.get("isReadOnly"))) continue;
            Map<String, Object> bf = new LinkedHashMap<>(fv);
            bf.put("bulkInputKind", bulkInputKind(fv));
            bulkUpdatableViews.add(bf);
        }
        for (int i = 0; i < bulkUpdatableViews.size(); i++) {
            bulkUpdatableViews.get(i).put("last", i == bulkUpdatableViews.size() - 1);
        }
        view.put("bulkUpdatableFields", bulkUpdatableViews);
        view.put("hasBulkUpdatableFields", !bulkUpdatableViews.isEmpty());

        view.put("fields", fieldViews);
        view.put("hasFieldDefaults", fieldViews.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("hasDefault"))));
        view.put("nonPkFields", nonPkViews);
        view.put("pkField", pkView);
        view.put("pkFields", pkViews);
        view.put("hasCompositePk", hasCompositePk);
        view.put("keyClassName", keyClassName);
        // The repository/service id type and controller path: a single field's Java type, or the
        // generated @IdClass key class when the entity has a composite primary key.
        view.put("pkType", hasCompositePk ? keyClassName
                : (pkView == null ? "Long" : (String) pkView.get("javaType")));
        view.put("hasEnumFields", fieldViews.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("isEnum"))));

        // Text-backed fields drive the generated search Specification (lower()/LIKE) — both STRING
        // and TEXT columns qualify, so the search box appears whenever either is present.
        List<Map<String, Object>> stringFieldViews = new ArrayList<>();
        for (Map<String, Object> fv : fieldViews) {
            if ((Boolean.TRUE.equals(fv.get("isString")) || Boolean.TRUE.equals(fv.get("isText")))
                    && Boolean.TRUE.equals(fv.get("isSearchable"))) {
                stringFieldViews.add(fv);
            }
        }
        for (int i = 0; i < stringFieldViews.size(); i++) {
            stringFieldViews.get(i).put("lastString", i == stringFieldViews.size() - 1);
        }
        view.put("stringFields", stringFieldViews);
        view.put("hasStringFields", !stringFieldViews.isEmpty());

        // Dashboard breakdown: the first ENUM field (else the first BOOLEAN) becomes a grouped
        // bar chart on the generated home page. Low-cardinality columns chart well; free-text and
        // numeric columns don't, so only enum/boolean qualify.
        Map<String, Object> breakdown = null;
        for (Map<String, Object> fv : fieldViews) {
            if (Boolean.TRUE.equals(fv.get("isEnum"))) { breakdown = fv; break; }
        }
        if (breakdown == null) {
            for (Map<String, Object> fv : fieldViews) {
                if (Boolean.TRUE.equals(fv.get("isBoolean"))) { breakdown = fv; break; }
            }
        }
        view.put("hasBreakdown", breakdown != null);
        view.put("breakdownField", breakdown == null ? null : breakdown.get("name"));
        view.put("breakdownLabel", breakdown == null ? null : breakdown.get("Name"));
        boolean breakdownIsEnum = breakdown != null && Boolean.TRUE.equals(breakdown.get("isEnum"));
        view.put("breakdownIsEnum", breakdownIsEnum);
        // The per-entity enum type whose `...Labels` const the dashboard chart reads (null for a
        // boolean breakdown, whose two values need no label map).
        view.put("breakdownEnumTypeName", breakdownIsEnum ? breakdown.get("enumTypeName") : null);

        // Aggregation endpoint (GET /api/x/stats): the columns it may group by, bucket over and
        // reduce. Derived from the entity alone — deliberately not from the page layout, because the
        // backend render context never sees `pages` (putPageContext is frontend-only), and the two
        // render paths have to agree on whether the endpoint exists.
        List<Map<String, Object>> statsGroupByFields = new ArrayList<>();
        List<Map<String, Object>> statsDateFields = new ArrayList<>();
        List<Map<String, Object>> statsNumericFields = new ArrayList<>();
        for (Map<String, Object> fv : fieldViews) {
            boolean isPk = Boolean.TRUE.equals(fv.get("isPrimaryKey"));
            if (Boolean.TRUE.equals(fv.get("isEnum")) || Boolean.TRUE.equals(fv.get("isBoolean"))) {
                // Primary keys included on purpose: `hasBreakdown` above charts the first enum
                // whether or not it is the key, and this whitelist has to be able to answer it.
                Map<String, Object> sf = statsField(fv);
                sf.put("isRelation", false);
                statsGroupByFields.add(sf);
            } else if (!isPk && Boolean.TRUE.equals(fv.get("isTemporal"))) {
                statsDateFields.add(statsField(fv));
            } else if (!isPk && Boolean.TRUE.equals(fv.get("isNumeric"))) {
                statsNumericFields.add(statsField(fv));
            }
        }
        // A MANY_TO_ONE groups by the id it points at: "top customers by revenue". The key is the
        // Java field name, which is also what the frontend sends (and its filter param minus "Id").
        for (RelationDefinition rel : entity.relations()) {
            if (rel.type() != RelationType.MANY_TO_ONE) continue;
            Map<String, Object> target = summaries == null ? null
                    : summaries.get(rel.targetEntity().toLowerCase(Locale.ROOT));
            Map<String, Object> sf = new LinkedHashMap<>();
            sf.put("name", Naming.toCamelCase(rel.fieldName()));
            sf.put("isRelation", true);
            sf.put("targetPkName", target != null ? target.get("pkName") : "id");
            statsGroupByFields.add(sf);
        }
        view.put("statsGroupByFields", statsGroupByFields);
        view.put("hasStatsGroupByFields", !statsGroupByFields.isEmpty());
        view.put("statsDateFields", statsDateFields);
        view.put("hasStatsDateFields", !statsDateFields.isEmpty());
        view.put("statsNumericFields", statsNumericFields);
        view.put("hasStatsNumericFields", !statsNumericFields.isEmpty());
        // Nothing to group, bucket or reduce means the endpoint could only answer a bare count,
        // which the list endpoint's page metadata (?size=1) already gives — so it is not emitted.
        view.put("statsApplicable", !statsGroupByFields.isEmpty() || !statsDateFields.isEmpty()
                || !statsNumericFields.isEmpty());
        // Exposed so a per-page report screen can resolve this entity's Export button: a page
        // context is project context + page view-model and never runs through buildEntityContext,
        // where the opt overrides are normally applied. null = inherit the project flag.
        view.put("csvExportOverride", entity.opts().get("csvExport"));
        view.put("softDeleteOverride", entity.opts().get("softDelete"));

        // Kanban board view (listView == "kanban"): reuse the breakdown field as the grouping
        // column and turn its distinct values into lanes. Dragging a card writes the new lane value
        // back via the entity's update endpoint, so kanban needs a writable entity — a read-only
        // entity that asked for kanban falls back to the table view below.
        boolean mutableEntity = !entity.readOnly();
        boolean kanbanIsEnum = breakdown != null && Boolean.TRUE.equals(breakdown.get("isEnum"));
        boolean kanbanApplicable = breakdown != null && mutableEntity;
        view.put("kanbanField", breakdown == null ? null : breakdown.get("name"));
        view.put("kanbanLabel", breakdown == null ? null : breakdown.get("Name"));
        view.put("kanbanIsEnum", kanbanIsEnum);
        List<Map<String, Object>> kanbanColumns = new ArrayList<>();
        if (breakdown != null) {
            if (kanbanIsEnum) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> evs = (List<Map<String, Object>>) breakdown.get("enumValues");
                for (Map<String, Object> ev : evs) {
                    String laneLabel = String.valueOf(ev.get("label"));
                    kanbanColumns.add(kanbanColumn(String.valueOf(ev.get("value")), laneLabel,
                            "'" + escapeTsSingleQuoted(laneLabel) + "'"));
                }
            } else { // boolean breakdown — two fixed lanes, headed by the generated app's own i18n words
                kanbanColumns.add(kanbanColumn("true", "True", "t('trueLabel')"));
                kanbanColumns.add(kanbanColumn("false", "False", "t('falseLabel')"));
            }
        }
        for (int i = 0; i < kanbanColumns.size(); i++) {
            kanbanColumns.get(i).put("last", i == kanbanColumns.size() - 1);
        }
        view.put("kanbanColumns", kanbanColumns);
        view.put("kanbanEnumTypeName", kanbanIsEnum ? breakdown.get("enumTypeName") : null);

        // Calendar view (listView == "calendar"): bucket records onto a month grid by their first
        // temporal (LOCAL_DATE / LOCAL_DATE_TIME) field. No writable requirement — it only reads.
        Map<String, Object> calendarFieldView = null;
        for (Map<String, Object> fv : fieldViews) {
            if (Boolean.TRUE.equals(fv.get("isTemporal"))) { calendarFieldView = fv; break; }
        }
        boolean calendarApplicable = calendarFieldView != null;
        view.put("calendarField", calendarFieldView == null ? null : calendarFieldView.get("name"));
        view.put("calendarLabel", calendarFieldView == null ? null : calendarFieldView.get("Name"));

        // Type-aware filter bar: every non-PK enum / boolean / temporal / numeric field becomes a
        // filter control. The view-model carries one entry per filterable field with a kind flag the
        // FilterBar switches on; the backend Specification reads the matching query params.
        List<Map<String, Object>> filterFieldViews = new ArrayList<>();
        for (Map<String, Object> fv : fieldViews) {
            if (Boolean.TRUE.equals(fv.get("isPrimaryKey"))) continue;
            if (!Boolean.TRUE.equals(fv.get("isFilterable"))) continue;
            boolean isEnumF = Boolean.TRUE.equals(fv.get("isEnum"));
            boolean isBoolF = Boolean.TRUE.equals(fv.get("isBoolean"));
            boolean isTemporalF = Boolean.TRUE.equals(fv.get("isTemporal"));
            boolean isNumericF = Boolean.TRUE.equals(fv.get("isNumeric"));
            if (!(isEnumF || isBoolF || isTemporalF || isNumericF)) continue;
            Map<String, Object> ff = new LinkedHashMap<>();
            ff.put("name", fv.get("name"));
            ff.put("Name", fv.get("Name"));
            ff.put("label", fv.get("label"));
            // The Java type the backend Filters carrier / @RequestParam uses for this field. For an
            // enum this is the per-entity enum type (Spring binds the request String to it by name).
            ff.put("javaType", fv.get("javaType"));
            ff.put("isEnumFilter", isEnumF);
            ff.put("isBooleanFilter", isBoolF);
            ff.put("isTemporalFilter", isTemporalF);
            ff.put("isNumericFilter", isNumericF);
            ff.put("isDate", fv.get("isDate"));
            ff.put("isDateTime", fv.get("isDateTime"));
            ff.put("enumValues", fv.get("enumValues"));
            ff.put("enumTypeName", fv.get("enumTypeName"));
            ff.put("isRelationFilter", false);
            filterFieldViews.add(ff);
        }
        // (relation filters are appended below, once the relation view-models exist)

        // The set of views the page actually generates: the user-requested listViews, intersected
        // with what this entity's fields support (table/cards always; kanban needs a breakdown field
        // + a writable entity; calendar needs a temporal field), order preserved. Falls back to
        // [table] if nothing requested is supported. A runtime toggle is emitted only for 2+ views;
        // initialView is the first. viewModeType is the TS union the template seeds useState with.
        List<String> emitted = emittedListViews(entity);
        view.put("viewTable", emitted.contains("table"));
        view.put("viewCards", emitted.contains("cards"));
        view.put("viewKanban", emitted.contains("kanban"));
        view.put("viewCalendar", emitted.contains("calendar"));
        view.put("hasViewToggle", emitted.size() > 1);
        view.put("initialView", emitted.get(0));
        StringBuilder union = new StringBuilder();
        for (int i = 0; i < emitted.size(); i++) {
            if (i > 0) union.append(" | ");
            union.append('\'').append(emitted.get(i)).append('\'');
        }
        view.put("viewModeType", union.toString());

        // Relations (MANY_TO_ONE foreign keys). Each resolves its target's PK type/name from the
        // summary lookup so the entity gets a typed @ManyToOne, the DTO exposes the key as
        // <field>Id, and the service can stub the reference on create/update.
        List<Map<String, Object>> relationViews = new ArrayList<>(entity.relations().size());
        for (int i = 0; i < entity.relations().size(); i++) {
            RelationDefinition rel = entity.relations().get(i);
            Map<String, Object> target = summaries == null ? null
                    : summaries.get(rel.targetEntity().toLowerCase(Locale.ROOT));
            String relField = Naming.toCamelCase(rel.fieldName());
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("fieldName", relField);
            rv.put("FieldName", Naming.toPascalCase(rel.fieldName()));
            rv.put("fkFieldName", relField + "Id");
            rv.put("joinColumn", Naming.toSnakeCase(rel.fieldName()) + "_id");
            rv.put("targetEntity", target != null ? target.get("pascal") : Naming.toPascalCase(rel.targetEntity()));
            rv.put("targetEntityCamel", target != null ? target.get("camel") : Naming.toCamelCase(rel.targetEntity()));
            rv.put("targetEntityKebab", target != null ? target.get("kebab") : Naming.toKebabCase(rel.targetEntity()));
            rv.put("targetEntityKebabPlural", target != null ? target.get("kebabPlural")
                    : Naming.pluralize(Naming.toKebabCase(rel.targetEntity())));
            rv.put("targetPkName", target != null ? target.get("pkName") : "id");
            rv.put("TargetPkName", target != null ? target.get("PkName") : "Id");
            rv.put("targetPkJavaType", target != null ? target.get("pkJavaType") : "Long");
            String targetPkTs = target != null ? (String) target.get("pkTsType") : "number";
            rv.put("targetPkTsType", targetPkTs);
            rv.put("isTargetPkNumeric", "number".equals(targetPkTs));
            // First non-PK string field on the target, shown as the readable option label (else the id).
            Object labelField = target == null ? null : target.get("labelField");
            rv.put("targetLabelField", labelField);
            rv.put("hasTargetLabel", labelField != null);
            // SQL names for the entity's @Formula `<field>Label` column (a per-row subselect of the
            // target's label column, so the DTO can show a name instead of a raw FK id without an
            // open session — open-in-view is off in the generated app).
            rv.put("targetTableRef", target != null ? target.get("tableRef") : Naming.pluralize(Naming.toSnakeCase(rel.targetEntity())));
            rv.put("targetPkColumn", target != null ? target.get("pkColumn") : "id");
            rv.put("targetLabelColumn", target == null ? null : target.get("labelColumn"));
            rv.put("required", rel.required());
            rv.put("isManyToOne", rel.type() == RelationType.MANY_TO_ONE);
            rv.put("last", i == entity.relations().size() - 1);
            relationViews.add(rv);
        }
        boolean hasRequiredRelations = relationViews.stream()
                .anyMatch(m -> Boolean.TRUE.equals(m.get("required")));
        view.put("relations", relationViews);
        view.put("hasRelations", !relationViews.isEmpty());
        view.put("hasRelationLabels", relationViews.stream()
                .anyMatch(m -> Boolean.TRUE.equals(m.get("hasTargetLabel"))));
        // The frontend validator (model/validate.ts) needs its `blank` helper only when something
        // is required: a client-supplied PK, a required non-PK field, or a required relation —
        // emitting it otherwise would trip the generated project's no-unused-vars lint rule.
        boolean hasBlankChecks = hasRequiredRelations
                || fieldViews.stream().anyMatch(m ->
                        (Boolean.TRUE.equals(m.get("isPrimaryKey")) && !Boolean.TRUE.equals(m.get("isGenerated")))
                        || (!Boolean.TRUE.equals(m.get("isPrimaryKey")) && Boolean.TRUE.equals(m.get("isRequired"))));
        view.put("hasBlankChecks", hasBlankChecks);
        // Same lint concern for the validator's `t`/`tf` string imports: only import them when at
        // least one check (blank, length, min/max, pattern, email) is actually emitted.
        view.put("hasValidationChecks",
                hasBlankChecks
                        || fieldViews.stream().anyMatch(m ->
                                !Boolean.TRUE.equals(m.get("isGenerated"))
                                && (Boolean.TRUE.equals(m.get("hasLength")) || Boolean.TRUE.equals(m.get("hasMin"))
                                        || Boolean.TRUE.equals(m.get("hasMax")) || Boolean.TRUE.equals(m.get("hasPattern"))
                                        || Boolean.TRUE.equals(m.get("isEmail")))));
        // And for the form (ui/<Entity>Form.tsx): it reads a chrome string only for a generated PK's
        // hint, boolean/enum <option> labels, and the relation picker's loading placeholder.
        view.put("formUsesStrings",
                !relationViews.isEmpty()
                        || fieldViews.stream().anyMatch(m ->
                                (Boolean.TRUE.equals(m.get("isPrimaryKey")) && Boolean.TRUE.equals(m.get("isGenerated")))
                                || Boolean.TRUE.equals(m.get("isBoolean"))
                                || Boolean.TRUE.equals(m.get("isEnum"))));

        // Filter by relation FK ("orders of customer 7"): one filter entry per MANY_TO_ONE, keyed
        // by the DTO's <field>Id, equality on the relation's target PK. The frontend renders a
        // <select> fed from the target's list endpoint (labelled like the form's FK picker).
        for (Map<String, Object> rv : relationViews) {
            Map<String, Object> ff = new LinkedHashMap<>();
            ff.put("name", rv.get("fkFieldName"));
            ff.put("Name", rv.get("FieldName") + "Id");
            ff.put("label", rv.get("FieldName"));
            ff.put("javaType", rv.get("targetPkJavaType"));
            ff.put("isEnumFilter", false);
            ff.put("isBooleanFilter", false);
            ff.put("isTemporalFilter", false);
            ff.put("isNumericFilter", false);
            ff.put("isRelationFilter", true);
            ff.put("relationField", rv.get("fieldName"));
            ff.put("targetPkName", rv.get("targetPkName"));
            ff.put("targetEntityKebabPlural", rv.get("targetEntityKebabPlural"));
            ff.put("targetLabelField", rv.get("targetLabelField"));
            ff.put("hasTargetLabel", rv.get("hasTargetLabel"));
            ff.put("enumValues", List.of());
            filterFieldViews.add(ff);
        }
        for (int i = 0; i < filterFieldViews.size(); i++) {
            filterFieldViews.get(i).put("last", i == filterFieldViews.size() - 1);
        }
        view.put("filterFields", filterFieldViews);
        view.put("hasFilters", !filterFieldViews.isEmpty());
        // The generated Service builds a JPA Specification when it has either text search or
        // type-aware filters — gates the Specification import / machinery in the template.
        view.put("needsSpecification", !stringFieldViews.isEmpty() || !filterFieldViews.isEmpty());

        // Inverse @OneToMany collections derived from other entities' MANY_TO_ONE relations (opt-in,
        // rendered only when optScaffoldInverse). Exposed read-only — the DTO surfaces a count.
        List<Map<String, Object>> inverseViews = inverseByLower == null ? List.of()
                : inverseByLower.getOrDefault(entity.name().toLowerCase(Locale.ROOT), List.of());
        view.put("inverseRelations", inverseViews);
        view.put("hasInverseRelations", !inverseViews.isEmpty());

        // Aggregate flags so the DTO template only imports a Bean Validation constraint it
        // actually uses. @NotNull is skipped on a generated PK (it is null until persisted);
        // a required relation's FK id, however, does carry @NotNull.
        view.put("hasNotNullFields", hasRequiredRelations || fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("isRequired")) && !Boolean.TRUE.equals(m.get("isGenerated"))));
        view.put("hasSizeFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasLength"))));
        // @Min/@Max apply to integral fields; @DecimalMin/@DecimalMax to BigDecimal; @Pattern/@Email
        // to strings. Each gates its own DTO import so we never import an unused constraint.
        view.put("hasMinFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMin")) && Boolean.TRUE.equals(m.get("isIntegral"))));
        view.put("hasMaxFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMax")) && Boolean.TRUE.equals(m.get("isIntegral"))));
        view.put("hasDecimalMinFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMin")) && Boolean.TRUE.equals(m.get("isBigDecimal"))));
        view.put("hasDecimalMaxFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasMax")) && Boolean.TRUE.equals(m.get("isBigDecimal"))));
        view.put("hasPatternFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("hasPattern"))));
        view.put("hasEmailFields", fieldViews.stream().anyMatch(
                m -> Boolean.TRUE.equals(m.get("isEmail"))));

        List<Map<String, Object>> importViews = new ArrayList<>(imports.size());
        for (String imp : imports) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", imp);
            importViews.add(m);
        }
        view.put("imports", importViews);
        return view;
    }

    private static Map<String, Object> fieldViewModel(String entityPascal, FieldDefinition f) {
        Map<String, Object> fv = new LinkedHashMap<>();
        fv.put("name", f.name());
        String pascalName = Naming.toPascalCase(f.name());
        fv.put("Name", pascalName);
        fv.put("column", Naming.toSnakeCase(f.name()));
        // Human-facing display override for the generated UI (table header, form label, filter
        // chip, detail row). Defaults to the PascalCase name when no custom label was supplied —
        // done here because Mustache has no default operator.
        fv.put("label", (f.label() != null && !f.label().isBlank()) ? f.label() : pascalName);
        // Locked-after-create read-only: the form disables it on edit and Service.update skips it.
        fv.put("isReadOnly", f.readOnly());
        fv.put("isEditable", !f.readOnly());

        String javaType;
        String enumTypeName = null;
        if (f.type() == FieldType.ENUM) {
            enumTypeName = entityPascal + Naming.toPascalCase(f.name()) + "Type";
            javaType = enumTypeName;
        } else {
            javaType = f.type().javaType();
        }
        fv.put("javaType", javaType);
        fv.put("tsType", f.type().tsType());
        fv.put("enumTypeName", enumTypeName);

        boolean isIntegral = f.type() == FieldType.LONG || f.type() == FieldType.INTEGER;
        boolean isBigDecimal = f.type() == FieldType.BIG_DECIMAL;
        fv.put("isPrimaryKey", f.primaryKey());
        fv.put("isGenerated", f.generated());
        fv.put("isRequired", f.required());
        fv.put("isUnique", f.unique());
        fv.put("isString", f.type().isString());
        fv.put("isText", f.type().isText());
        fv.put("isUuid", f.type().isUuid());
        fv.put("isNumeric", f.type().isNumeric());
        fv.put("isIntegral", isIntegral);
        fv.put("isBigDecimal", isBigDecimal);
        fv.put("isBoolean", f.type().isBoolean());
        fv.put("isTemporal", f.type().isTemporal());
        fv.put("isDate", f.type() == FieldType.LOCAL_DATE);
        fv.put("isDateTime", f.type() == FieldType.LOCAL_DATE_TIME);
        fv.put("isEnum", f.type().isEnum());
        fv.put("hasLength", f.length() != null);
        fv.put("length", f.length());
        // Numeric bounds (rendered as @Min/@Max on integral types, @DecimalMin/@DecimalMax on BigDecimal).
        // Rendered as strings so the same value drops into @Min(0) / @DecimalMin(value = "0.5") /
        // min="0.5" / Number(x) < 0.5 verbatim: integral bounds print as plain integers, decimal
        // bounds in plain (non-scientific) notation.
        fv.put("hasMin", f.min() != null);
        fv.put("min", boundLiteral(f.min(), isBigDecimal));
        fv.put("hasMax", f.max() != null);
        fv.put("max", boundLiteral(f.max(), isBigDecimal));
        // String constraints. The pattern is injected into Java (@Pattern(regexp="..")) and JS
        // ("..") string literals, so backslashes and double-quotes are escaped once here — the
        // C-style escaping is valid in both languages.
        boolean hasPattern = f.pattern() != null && !f.pattern().isBlank();
        fv.put("hasPattern", hasPattern);
        fv.put("pattern", f.pattern());
        fv.put("patternEscaped", hasPattern ? escapeStringLiteral(f.pattern()) : null);
        fv.put("isEmail", f.email());
        // Per-field search/filter opt-out. Consulted by the stringFields / filterFields collection
        // loops, which gate on field type first — so these flags are inert on ineligible types.
        fv.put("isSearchable", f.searchable());
        fv.put("isFilterable", f.filterable());

        // Optional default value (validated + canonicalized by FullstackRequestValidator), rendered
        // as the entity field's Java initializer (`= …`, resolved inside the entity class so a nested
        // enum is unqualified) and as the TS literal the form seeds a new record with.
        fv.put("hasDefault", f.hasDefault());
        fv.put("defaultValue", f.defaultValue());
        fv.put("defaultJava", f.hasDefault() ? defaultJavaExpression(f, enumTypeName) : null);
        fv.put("defaultTs", f.hasDefault() ? defaultTsLiteral(f) : null);

        // Java expression the demo-data loader (optScaffoldSeedData) assigns to this field for row
        // number `i` (1-based). Unique per row where uniqueness matters (strings carry i, integral
        // values are i within any min/max bounds), so seeded rows never clash on unique columns.
        // A declared default wins for non-key, non-unique fields.
        fv.put("seedExpr", seedExpression(entityPascal, f, (String) fv.get("label"), enumTypeName));

        if (f.type() == FieldType.ENUM) {
            List<Map<String, Object>> values = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            boolean anyCustomLabel = false;
            for (String v : f.enumValues()) {
                String constant = v.toUpperCase(Locale.ROOT);
                if (!seen.add(constant)) continue;
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("value", constant);
                // Display label: the user's, else a humanized constant ("IN_PROGRESS" -> "In progress").
                // `label` is the raw text; `labelTs` is safe inside a single-quoted TS literal.
                String custom = f.enumLabels().get(constant);
                String label = custom != null ? custom : humanizeConstant(constant);
                anyCustomLabel |= custom != null;
                ev.put("label", label);
                ev.put("labelTs", escapeTsSingleQuoted(label));
                values.add(ev);
            }
            for (int i = 0; i < values.size(); i++) {
                values.get(i).put("last", i == values.size() - 1);
            }
            fv.put("enumValues", values);
            fv.put("hasEnumLabels", anyCustomLabel);
        } else {
            fv.put("enumValues", List.of());
            fv.put("hasEnumLabels", false);
        }
        return fv;
    }

    /**
     * The Java initializer expression for a field's validated {@code defaultValue}. {@code enumRef}
     * is the enum type reference to use — the bare nested name inside the entity class, or the
     * {@code Entity.EnumType} form from outside it (demo-data loader).
     */
    static String defaultJavaExpression(FieldDefinition f, String enumRef) {
        String v = f.defaultValue();
        return switch (f.type()) {
            case STRING, TEXT -> "\"" + escapeJavaLiteral(v) + "\"";
            case LONG -> v + "L";
            case INTEGER -> v;
            case BIG_DECIMAL -> "new BigDecimal(\"" + v + "\")";
            case BOOLEAN -> v;
            case LOCAL_DATE -> "LocalDate.parse(\"" + v + "\")";
            case LOCAL_DATE_TIME -> "LocalDateTime.parse(\"" + v + "\")";
            case UUID -> "java.util.UUID.fromString(\"" + v + "\")";
            case ENUM -> enumRef + "." + v;
        };
    }

    /** The TypeScript literal for a field's validated {@code defaultValue}: numbers and booleans
     *  bare, everything else (strings, temporal ISO forms, UUIDs, enum constants) single-quoted. */
    static String defaultTsLiteral(FieldDefinition f) {
        String v = f.defaultValue();
        return switch (f.type()) {
            case LONG, INTEGER, BIG_DECIMAL, BOOLEAN -> v;
            default -> "'" + v.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'";
        };
    }

    /** Escapes for a Java double-quoted literal (also newlines/tabs, unlike {@link #escapeStringLiteral},
     *  because a TEXT default may span lines). */
    private static String escapeJavaLiteral(String s) {
        return escapeStringLiteral(s).replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** See {@code seedExpr} in {@link #fieldViewModel}. The helper calls ({@code label}, {@code text},
     *  {@code bounded}) are static methods of the generated {@code DemoDataLoader}. */
    static String seedExpression(String entityPascal, FieldDefinition f, String label, String enumTypeName) {
        // A declared default is the natural demo value — except on keys and unique columns, where
        // every row must differ, so those keep the row-numbered expressions below.
        if (f.hasDefault() && !f.primaryKey() && !f.unique()) {
            return defaultJavaExpression(f, entityPascal + "." + enumTypeName);
        }
        String lbl = escapeStringLiteral(label == null ? f.name() : label);
        return switch (f.type()) {
            case ENUM -> entityPascal + "." + enumTypeName + ".values()[(i - 1) % "
                    + entityPascal + "." + enumTypeName + ".values().length]";
            case STRING -> f.email()
                    ? "\"user\" + i + \"@example.com\""
                    : "label(\"" + lbl + "\", i, " + (f.length() != null ? f.length() : "Integer.MAX_VALUE") + ")";
            case TEXT -> "text(\"" + lbl + "\", i)";
            case LONG -> "bounded(i, " + longLiteral(f.min()) + ", " + longLiteral(f.max()) + ")";
            case INTEGER -> "(int) bounded(i, " + longLiteral(f.min()) + ", " + longLiteral(f.max()) + ")";
            case BIG_DECIMAL -> "java.math.BigDecimal.valueOf(bounded(i, " + longLiteral(f.min()) + ", "
                    + longLiteral(f.max()) + "))";
            case BOOLEAN -> "i % 2 == 0";
            case LOCAL_DATE -> "java.time.LocalDate.now().minusDays(i)";
            case LOCAL_DATE_TIME -> "java.time.LocalDateTime.now().minusHours(i)";
            case UUID -> "java.util.UUID.randomUUID()";
        };
    }

    private static String longLiteral(BigDecimal v) {
        return v == null ? "null" : v.setScale(0, RoundingMode.DOWN).toPlainString() + "L";
    }

    /** A bound in the form the templates splice into Java and TS source (see the fv.put above). */
    private static String boundLiteral(BigDecimal v, boolean decimal) {
        if (v == null) return null;
        return decimal ? v.toPlainString() : v.setScale(0, RoundingMode.DOWN).toPlainString();
    }

    /**
     * Orders the seedable (writable, table-backed) entities so every {@code MANY_TO_ONE} parent is
     * seeded before its children, and marks each relation with {@code targetSeeded} — true when the
     * target's rows exist by the time this entity is seeded (so the loader can pick a parent), false
     * for self-references, read-only targets, or the back edge of a cycle (left null). Cycles fall
     * back to declaration order. Each entry is a copy of the entity view-model plus
     * {@code seedFirst}/{@code seedLast}.
     */
    static List<Map<String, Object>> buildSeedOrder(List<EntityDefinition> entities,
                                                    List<Map<String, Object>> entityViews) {
        Map<String, Integer> indexByLower = new LinkedHashMap<>();
        for (int i = 0; i < entities.size(); i++) {
            indexByLower.put(entities.get(i).name().toLowerCase(Locale.ROOT), i);
        }
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            if (!entities.get(i).readOnly()) remaining.add(i);
        }
        Set<String> placed = new LinkedHashSet<>();
        List<Integer> order = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Integer next = null;
            for (Integer idx : remaining) {
                EntityDefinition e = entities.get(idx);
                boolean ready = true;
                for (RelationDefinition rel : e.relations()) {
                    String t = rel.targetEntity().toLowerCase(Locale.ROOT);
                    Integer ti = indexByLower.get(t);
                    boolean seedableTarget = ti != null && !entities.get(ti).readOnly();
                    if (seedableTarget && !t.equals(e.name().toLowerCase(Locale.ROOT)) && !placed.contains(t)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) { next = idx; break; }
            }
            if (next == null) next = remaining.get(0);   // cycle: take the first remaining as-is
            remaining.remove(next);
            placed.add(entities.get(next).name().toLowerCase(Locale.ROOT));
            order.add(next);
        }
        List<Map<String, Object>> seeds = new ArrayList<>(order.size());
        Set<String> seededSoFar = new LinkedHashSet<>();
        for (int n = 0; n < order.size(); n++) {
            int idx = order.get(n);
            EntityDefinition e = entities.get(idx);
            Map<String, Object> sv = new LinkedHashMap<>(entityViews.get(idx));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rels = (List<Map<String, Object>>) sv.get("relations");
            List<Map<String, Object>> relCopies = new ArrayList<>();
            for (int r = 0; r < e.relations().size(); r++) {
                Map<String, Object> rc = new LinkedHashMap<>(rels.get(r));
                String t = e.relations().get(r).targetEntity().toLowerCase(Locale.ROOT);
                rc.put("targetSeeded", seededSoFar.contains(t));
                relCopies.add(rc);
            }
            sv.put("relations", relCopies);
            sv.put("seedFirst", n == 0);
            sv.put("seedLast", n == order.size() - 1);
            seeds.add(sv);
            seededSoFar.add(e.name().toLowerCase(Locale.ROOT));
        }
        return seeds;
    }
}
