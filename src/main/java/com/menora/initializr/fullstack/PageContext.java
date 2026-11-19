package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The page layout's view-model: {@link EntityScaffoldContext#buildProjectContext}'s pages,
 * navigation and shell (nav groups, user menu, notifications, palette), and each page type's
 * settings except dashboards and reports ({@link DashboardContext}).
 * Part of {@link EntityScaffoldContext}, split out by area.
 */
public final class PageContext {

    private PageContext() {}

    /**
     * Adds the frontend page layout to a (frontend) project context. With no pages this only sets
     * {@code hasPages=false}, which keeps the classic shell (one dashboard + one list page per
     * entity) byte-for-byte. With pages, the shell's nav/switch iterates {@code navPages} and each
     * page renders once as {@code src/app/screens/<PageName>Screen.tsx} from the per-page context
     * {@link FullstackRenderer} builds (project context + one entry of {@code pages}).
     *
     * <p>Must run after {@link EntityScaffoldContext#buildProjectContext}: widgets and list pages resolve their entity
     * through the {@code entities} view-models.
     */
    @SuppressWarnings("unchecked")
    public static void putPageContext(Map<String, Object> ctx, List<PageDefinition> pages) {
        putPageContext(ctx, pages, null);
    }

    /** As above, with the shell's navigation settings (null: a sidebar whose sections do not fold). */
    public static void putPageContext(Map<String, Object> ctx, List<PageDefinition> pages, PageDefinition.Nav navSettings) {
        ctx.put("hasPages", !pages.isEmpty());
        // The classic shell's own files (its dashboard page): a layout draws its own screens and
        // imported none of them — they used to be generated anyway.
        ctx.put("classicShell", pages.isEmpty());
        // The tailwind shell's nav variants; the Menora shell is a top bar already and ignores them.
        boolean navTopbar = navSettings != null && navSettings.style() == PageDefinition.NavStyle.TOPBAR;
        ctx.put("navTopbar", navTopbar);
        ctx.put("navCollapsibleGroups", navSettings != null && navSettings.collapsibleGroups());
        // The header's tools (both shells): nothing of them without the setting.
        boolean shellUserMenu = navSettings != null && navSettings.userMenu();
        boolean shellNotifications = navSettings != null && navSettings.notifications();
        boolean shellPalette = navSettings != null && navSettings.commandPalette();
        ctx.put("shellUserMenu", shellUserMenu);
        ctx.put("shellNotifications", shellNotifications);
        ctx.put("shellPalette", shellPalette);
        ctx.put("hasShellTools", shellUserMenu || shellNotifications || shellPalette);
        ctx.put("shellPopovers", shellUserMenu || shellNotifications);
        if (shellUserMenu || shellNotifications) ctx.put("usesCurrentUser", true);
        if (pages.isEmpty()) return;

        Map<String, Map<String, Object>> entityByPascal = new LinkedHashMap<>();
        for (Map<String, Object> ev : (List<Map<String, Object>>) ctx.get("entities")) {
            entityByPascal.put((String) ev.get("EntityName"), ev);
        }
        Map<String, Map<String, Object>> summaries = (Map<String, Map<String, Object>>) ctx.get(EntityScaffoldContext.ENTITY_SUMMARIES_KEY);
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
            pv.put("pageIsCalendar", p.type() == PageDefinition.Type.CALENDAR);
            pv.put("pageIsBoard", p.type() == PageDefinition.Type.BOARD);
            pv.put("pageIsContent", p.type() == PageDefinition.Type.CONTENT);
            pv.put("pageIsImport", p.type() == PageDefinition.Type.IMPORT);
            pv.put("pageIsSearch", p.type() == PageDefinition.Type.SEARCH);
            pv.put("pageIsForm", p.type() == PageDefinition.Type.FORM);
            pv.put("pageIsInbox", p.type() == PageDefinition.Type.INBOX);
            pv.put("pageIsTree", p.type() == PageDefinition.Type.TREE);
            pv.put("hasPageDescription", p.description() != null);
            pv.put("pageDescriptionExpr", p.description() == null ? null : TemplateText.tsString(p.description()));
            pv.put("needsNavigate", false);
            String defaultTitleExpr;
            switch (p.type()) {
                case ENTITY_LIST -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    pv.put("EntityName", ev.get("EntityName"));
                    pv.put("entityNameKebab", ev.get("entityNameKebab"));
                    pv.put("entityNamePluralKebab", ev.get("entityNamePluralKebab"));
                    pv.put("hasPresetFilter", !p.presetFilter().isEmpty());
                    pv.put("presetFilterTs", presetFilterTs(p.presetFilter(), ev));
                    String presetTs = presetFilterTs(p.presetFilter(), ev);
                    // A period preset is computed when the screen loads: the screen imports the helpers.
                    pv.put("presetUsesPeriod", presetHasPeriod(p.presetFilter()));
                    // The same object without its braces, to merge the route's filters into.
                    pv.put("presetFilterEntriesTs", presetTs == null ? null : presetTs.substring(2, presetTs.length() - 2));
                    putRecordLink(pv, "", p.entity(), links, summaries);
                    // Where a row opens: the record page when there is one, unless the page says the
                    // drawer or a side pane; a side pane keeps the open row in the route.
                    boolean opensRecord = Boolean.TRUE.equals(pv.get("hasRecordPage"))
                            && p.detail() != PageDefinition.Detail.DRAWER && p.detail() != PageDefinition.Detail.SIDE;
                    pv.put("opensRecord", opensRecord);
                    pv.put("detailSide", p.detail() == PageDefinition.Detail.SIDE);
                    pv.put("needsNavigate", opensRecord);
                    // A filterable list also opens with the filters in its route (#/orders?status=OPEN).
                    pv.put("listTakesQuery", Boolean.TRUE.equals(ev.get("hasFilters")));
                    // A list page in the nav keeps its whole state there — search, sort, page, rows
                    // per page, view and filters (#/orders?status=OPEN&_q=acme&_page=3) — so a
                    // reload, Back from a record and a shared link reopen the same list.
                    pv.put("listKeepsState", !p.hidden());
                    pv.put("presetFilterOrEmptyTs", presetTs == null ? "{}" : presetTs);
                    // Import opens the entity's import page, when it has one (else a drawer on the page).
                    String importPage = links.importPageOf(p.entity());
                    pv.put("hasImportPage", importPage != null);
                    pv.put("importPageId", importPage);
                    if (importPage != null) pv.put("needsNavigate", true);
                    // New opens the entity's create page (a wizard or a form), when it has one.
                    String create = links.createPageOf(p.entity());
                    pv.put("hasCreatePage", create != null);
                    pv.put("createPageId", create);
                    if (create != null) pv.put("needsNavigate", true);
                    // How the list opens (each absent: the EntityPage default). Column and field
                    // names are validated identifiers; the sort object matches the generated SortSpec.
                    pv.put("hasColumns", !p.columns().isEmpty());
                    pv.put("columnsTs", p.columns().isEmpty() ? null
                            : "[" + String.join(", ", p.columns().stream().map(TemplateText::tsString).toList()) + "]");
                    pv.put("hasSort", p.sort() != null);
                    pv.put("sortTs", p.sort() == null ? null
                            : "{ field: " + TemplateText.tsString(p.sort().field()) + ", direction: '" + (p.sort().desc() ? "desc" : "asc") + "' }");
                    pv.put("hasInitialView", p.view() != null);
                    pv.put("initialViewOverride", p.view());
                    pv.put("hasPageSize", p.pageSize() != null);
                    pv.put("pageSize", p.pageSize());
                    pv.put("hasListPresentation", p.hasListPresentation());
                    // The actions the page leaves out of its list (its EntityPage takes hideActions).
                    pv.put("hasHideActions", !p.hiddenActions().isEmpty());
                    pv.put("hideActionsTs", "[" + String.join(", ", p.hiddenActions().stream().map(a -> TemplateText.tsString(a.wire())).toList()) + "]");
                    // A page title replaces the entity page's own heading (its plural label).
                    pv.put("hasPageTitle", p.title() != null);
                    pv.put("navIcon", "Table2");
                    defaultTitleExpr = TemplateText.tsString((String) ev.get("entityLabelPlural"));
                }
                case DASHBOARD -> {
                    DashboardContext.putDashboard(pv, p, entityByPascal, summaries, links);
                    // The dashboard's own reload count adds to the live one (LiveUpdates), which it
                    // would otherwise hide from its widgets.
                    pv.put("dashLive", Boolean.TRUE.equals(pv.get("hasRefresh")) && Boolean.TRUE.equals(ctx.get("hasEvents")));
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
                    pv.put("presetFilterTs", presetFilterTs(p.presetFilter(), ev));
                    pv.put("presetUsesPeriod", presetHasPeriod(p.presetFilter()));
                    // A per-page screen never runs through buildEntityContext, so the entity's own
                    // csvExport override has to be resolved against the project opt here.
                    Object csvOverride = ev.get("csvExportOverride");
                    pv.put("reportHasExport", csvOverride != null ? Boolean.TRUE.equals(csvOverride)
                            : Boolean.TRUE.equals(ctx.get("optScaffoldCsvExport")));
                    DashboardContext.putCharts(pv, p, ev, links);
                    pv.put("usesStatsImport", Boolean.TRUE.equals(pv.get("usesBucketRange")) || presetHasPeriod(p.presetFilter()));
                    pv.put("navIcon", "BarChart3");
                    defaultTitleExpr = "t('xReport', { x: " + TemplateText.tsString((String) ev.get("entityLabelPlural")) + " })";
                }
                case MASTER_DETAIL -> {
                    Map<String, Object> parent = entityByPascal.get(Naming.toPascalCase(p.parent()));
                    Map<String, Object> child = entityByPascal.get(Naming.toPascalCase(p.child()));
                    Map<String, Object> parentSummary = summaries.get(p.parent().toLowerCase(Locale.ROOT));
                    pv.put("parentEntityName", parent.get("EntityName"));
                    pv.put("parentEntityNameKebab", parent.get("entityNameKebab"));
                    pv.put("parentEntityNamePluralKebab", parent.get("entityNamePluralKebab"));
                    pv.put("parentPkName", parentSummary.get("pkName"));
                    // The selected parent's own details above its rows; Edit for a writable parent.
                    boolean showParent = p.showParent();
                    boolean parentMutable = Boolean.TRUE.equals(parent.get("mutable"));
                    pv.put("showParent", showParent);
                    pv.put("showParentEdit", showParent && parentMutable);
                    // New on the parent list — without it a parent shown only here could never be
                    // created. It goes to the parent's form/wizard page, else opens the form drawer.
                    String parentCreatePage = parentMutable ? links.createPageOf(p.parent()) : null;
                    boolean createsInDrawer = parentMutable && parentCreatePage == null;
                    boolean hasDrawer = createsInDrawer || (showParent && parentMutable);
                    pv.put("parentCreatable", parentMutable);
                    pv.put("parentCreatesInPage", parentCreatePage != null);
                    pv.put("parentCreatePageId", parentCreatePage);
                    pv.put("parentCreatesInDrawer", createsInDrawer);
                    pv.put("parentHasDrawer", hasDrawer);
                    pv.put("parentUsesApi", showParent || createsInDrawer);
                    pv.put("parentNewTs", newRecordTs((List<Map<String, Object>>) parent.get("fields")));
                    List<String> features = new ArrayList<>();
                    if (showParent) features.add(parent.get("EntityName") + "Detail");
                    if (hasDrawer) {
                        features.add(parent.get("EntityName") + "Form");
                        features.add("validate" + parent.get("EntityName"));
                    }
                    pv.put("parentFeatureImports", features.isEmpty() ? null : String.join(", ", features));
                    pv.put("usesEffect", Boolean.TRUE.equals(parent.get("hasStringFields")) || showParent);
                    // The selected parent travels in the URL as a string; a numeric key is parsed back.
                    pv.put("parentPkIsNumber", "number".equals(parentSummary.get("pkTsType")));
                    pv.put("parentLabelField", parentSummary.get("labelField"));
                    pv.put("parentHasLabel", parentSummary.get("labelField") != null);
                    pv.put("parentSearchable", parent.get("hasStringFields"));
                    pv.put("parentLabelPluralExpr", TemplateText.tsString((String) parent.get("entityLabelPlural")));
                    pv.put("parentLabelExpr", TemplateText.tsString((String) parent.get("entityLabel")));
                    pv.put("childEntityName", child.get("EntityName"));
                    pv.put("childEntityNameKebab", child.get("entityNameKebab"));
                    pv.put("childLabelPluralExpr", TemplateText.tsString((String) child.get("entityLabelPlural")));
                    pv.put("viaParam", Naming.toCamelCase(p.via()) + "Id");
                    // How the child list opens (each absent: the entity page's default).
                    pv.put("childHasColumns", !p.columns().isEmpty());
                    pv.put("childColumnsTs", p.columns().isEmpty() ? null
                            : "[" + String.join(", ", p.columns().stream().map(TemplateText::tsString).toList()) + "]");
                    pv.put("childHasSort", p.sort() != null);
                    pv.put("childSortTs", p.sort() == null ? null
                            : "{ field: " + TemplateText.tsString(p.sort().field()) + ", direction: '" + (p.sort().desc() ? "desc" : "asc") + "' }");
                    putRecordLink(pv, "parent", p.parent(), links, summaries);
                    putRecordLink(pv, "child", p.child(), links, summaries);
                    pv.put("needsNavigate", Boolean.TRUE.equals(pv.get("parentHasRecordPage"))
                            || Boolean.TRUE.equals(pv.get("childHasRecordPage")) || parentCreatePage != null);
                    List<String> icons = new ArrayList<>();
                    if (Boolean.TRUE.equals(pv.get("parentHasRecordPage"))) icons.add("ArrowUpRight");
                    if (showParent && parentMutable) icons.add("Pencil");
                    if (parentMutable) icons.add("Plus");
                    pv.put("parentIconImports", icons.isEmpty() ? null : String.join(", ", icons));
                    pv.put("navIcon", "PanelLeft");
                    defaultTitleExpr = TemplateText.tsString((String) parent.get("entityLabelPlural"));
                }
                case RECORD -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    Map<String, Object> summary = summaries.get(p.entity().toLowerCase(Locale.ROOT));
                    // A Print button, and the classes that keep the page's own buttons off the paper.
                    pv.put("printable", p.printable());
                    pv.put("EntityName", ev.get("EntityName"));
                    pv.put("entityNameKebab", ev.get("entityNameKebab"));
                    pv.put("entityNamePluralKebab", ev.get("entityNamePluralKebab"));
                    pv.put("pkName", summary.get("pkName"));
                    pv.put("labelField", summary.get("labelField"));
                    pv.put("hasLabel", summary.get("labelField") != null);
                    pv.put("entityLabelExpr", TemplateText.tsString((String) ev.get("entityLabel")));
                    String back = links.homeOf(p.entity());
                    pv.put("hasBack", back != null);
                    pv.put("backPageId", back);
                    // A writable entity's record page edits (form drawer) and deletes the row itself.
                    boolean recordMutable = Boolean.TRUE.equals(ev.get("mutable"));
                    // With a create page, Edit reopens the row there (#/<page>/<id>) instead.
                    String editPage = recordMutable ? links.createPageOf(p.entity()) : null;
                    pv.put("recordMutable", recordMutable);
                    // With ldap-auth, Edit and Delete are offered to those who may write.
                    pv.put("recordWriteChecked", recordMutable && Boolean.TRUE.equals(ctx.get("hasLdapAuth"))
                            && !"SIGNED_IN".equals(ev.get("writeLevel")));
                    pv.put("recordWriteLevel", ev.get("writeLevel"));
                    // The record's workflow moves sit by its Edit and Delete.
                    pv.put("recordHasWorkflow", Boolean.TRUE.equals(ev.get("hasWorkflow")));
                    // A workflow that logs its moves: the record's history under its details.
                    boolean tracksMoves = Boolean.TRUE.equals(ev.get("tracksMoves"));
                    pv.put("recordTracksMoves", tracksMoves);
                    if (tracksMoves) {
                        Map<String, Object> statusField = fieldOf(ev, (String) ev.get("workflowStatusField"));
                        pv.put("moveLabelsRef", statusField.get("enumTypeName") + "Labels");
                        pv.put("moveTransitionsTs", "{ " + String.join(", ",
                                ((List<Map<String, Object>>) ev.get("workflowTransitions")).stream()
                                        .map(tv -> tv.get("name") + ": " + tv.get("labelTs")).toList()) + " }");
                    }
                    // The history option: a History tab (or, without tabs, a section) of the row's changes.
                    pv.put("recordHasHistory", ((List<Map<String, Object>>) ctx.getOrDefault("historyEntities", List.of()))
                            .stream().anyMatch(h -> h.get("EntityName").equals(ev.get("EntityName"))));
                    pv.put("recordEditsInPage", editPage != null);
                    pv.put("recordEditsInDrawer", recordMutable && editPage == null);
                    pv.put("createPageId", editPage);
                    // Previous / Next walk the rows of the list the record was opened from.
                    pv.put("recordHasIcons", true);
                    // The record's state at a glance: its first two enum fields as chips by the heading.
                    List<Map<String, Object>> statusFields = new ArrayList<>();
                    for (Map<String, Object> f : (List<Map<String, Object>>) ev.get("fields")) {
                        if (statusFields.size() < 2 && Boolean.TRUE.equals(f.get("isEnum")) && !Boolean.TRUE.equals(f.get("isPrimaryKey"))) {
                            statusFields.add(Map.of("name", f.get("name"), "enumTypeName", f.get("enumTypeName")));
                        }
                    }
                    pv.put("statusFields", statusFields);
                    pv.put("hasStatusFields", !statusFields.isEmpty());
                    pv.put("statusLabelsImport", String.join(", ", statusFields.stream().map(f -> f.get("enumTypeName") + "Labels").toList()));
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
                    boolean navigates = true;
                    for (int i = 0; i < p.childTabs().size(); i++) {
                        PageDefinition.ChildTab tab = p.childTabs().get(i);
                        Map<String, Object> cv = entityByPascal.get(Naming.toPascalCase(tab.entity()));
                        Map<String, Object> tv = new LinkedHashMap<>();
                        // Tab 0 is the record's own details.
                        tv.put("tabIndex", i + 1);
                        tv.put("tabHasColumns", !tab.columns().isEmpty());
                        tv.put("tabColumnsTs", tab.columns().isEmpty() ? null
                                : "[" + String.join(", ", tab.columns().stream().map(TemplateText::tsString).toList()) + "]");
                        tv.put("tabHasSort", tab.sort() != null);
                        tv.put("tabSortTs", tab.sort() == null ? null
                                : "{ field: " + TemplateText.tsString(tab.sort().field()) + ", direction: '" + (tab.sort().desc() ? "desc" : "asc") + "' }");
                        tv.put("tabId", cv.get("entityNameKebab"));
                        tv.put("childEntityName", cv.get("EntityName"));
                        tv.put("childEntityNameKebab", cv.get("entityNameKebab"));
                        tv.put("viaParam", Naming.toCamelCase(tab.via()) + "Id");
                        tv.put("tabTitleExpr", TemplateText.tsString((String) cv.get("entityLabelPlural")));
                        putRecordLink(tv, "child", tab.entity(), links, summaries);
                        navigates |= Boolean.TRUE.equals(tv.get("childHasRecordPage"));
                        tabViews.add(tv);
                    }
                    pv.put("childTabs", tabViews);
                    pv.put("hasChildTabs", !tabViews.isEmpty());
                    pv.put("needsNavigate", navigates);
                    putHeaderStats(pv, p, entityByPascal, tabViews);
                    pv.put("navIcon", "Table2");
                    defaultTitleExpr = TemplateText.tsString((String) ev.get("entityLabel"));
                }
                case WIZARD -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    putWizard(pv, p, ev, links, summaries);
                    pv.put("navIcon", "Wand2");
                    defaultTitleExpr = "t('newX', { x: " + TemplateText.tsString((String) ev.get("entityLabel")) + " })";
                }
                case INBOX -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    putInbox(pv, p, ev, links, summaries);
                    pv.put("navIcon", "Inbox");
                    defaultTitleExpr = "t('myTasks')";
                }
                case FORM -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    putForm(pv, p, ctx, ev, links, summaries);
                    pv.put("navIcon", "FilePlus");
                    defaultTitleExpr = "t('newX', { x: " + TemplateText.tsString((String) ev.get("entityLabel")) + " })";
                }
                case CALENDAR -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    putCalendar(pv, p, ctx, ev, links, summaries);
                    pv.put("navIcon", "Calendar");
                    defaultTitleExpr = TemplateText.tsString((String) ev.get("entityLabelPlural"));
                }
                case BOARD -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    putBoard(pv, p, ev, links, summaries);
                    pv.put("navIcon", "Columns3");
                    defaultTitleExpr = TemplateText.tsString((String) ev.get("entityLabelPlural"));
                }
                case CONTENT -> {
                    List<ContentMarkdown.Block> blocks = ContentMarkdown.parse(p.content().body());
                    pv.put("contentJsx", contentJsx(blocks));
                    pv.put("needsNavigate", blocks.stream().flatMap(b -> b.items().stream()).flatMap(List::stream)
                            .anyMatch(r -> r.kind() == ContentMarkdown.RunKind.PAGE_LINK));
                    pv.put("navIcon", "FileText");
                    defaultTitleExpr = TemplateText.tsString(p.id());
                }
                case TREE -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    Map<String, Object> summary = summaries.get(p.entity().toLowerCase(Locale.ROOT));
                    putEntityNames(pv, ev);
                    putRowNaming(pv, ev, summary);
                    // A row opens its record page when there is one, else the quick look.
                    putRecordLink(pv, "", p.entity(), links, summaries);
                    boolean record = Boolean.TRUE.equals(pv.get("hasRecordPage"));
                    pv.put("treeUsesDrawer", !record);
                    // The self-reference as the row carries it: the parent's id.
                    Map<String, Object> link = ((List<Map<String, Object>>) ev.get("relations")).stream()
                            .filter(r -> p.via().equals(r.get("fieldName"))).findFirst().orElseThrow();
                    pv.put("parentIdField", link.get("fkFieldName"));
                    // Read in name order, so each branch lists its rows that way.
                    Object labelField = summary.get("labelField");
                    pv.put("treeSortField", labelField != null ? labelField : summary.get("pkName"));
                    String create = links.createPageOf(p.entity());
                    pv.put("hasCreatePage", create != null);
                    pv.put("createPageId", create);
                    pv.put("needsNavigate", record || create != null);
                    pv.put("navIcon", "Layers");
                    defaultTitleExpr = TemplateText.tsString((String) ev.get("entityLabelPlural"));
                }
                case IMPORT -> {
                    Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(p.entity()));
                    pv.put("EntityName", ev.get("EntityName"));
                    pv.put("entityNameKebab", ev.get("entityNameKebab"));
                    pv.put("entityNamePluralKebab", ev.get("entityNamePluralKebab"));
                    pv.put("entityLabelPluralExpr", TemplateText.tsString((String) ev.get("entityLabelPlural")));
                    String home = links.homeOf(p.entity());
                    pv.put("hasBack", home != null);
                    pv.put("backPageId", home);
                    pv.put("needsNavigate", home != null);
                    pv.put("navIcon", "Upload");
                    defaultTitleExpr = "t('importX', { x: " + TemplateText.tsString((String) ev.get("entityLabelPlural")) + " })";
                }
                case SEARCH -> {
                    putSearch(pv, p, entityByPascal, links, summaries);
                    pv.put("navIcon", "Search");
                    defaultTitleExpr = "t('search')";
                }
                default -> {
                    pv.put("navIcon", "Layers");
                    // The validator requires a tabs page title; this fallback is never used.
                    defaultTitleExpr = TemplateText.tsString(p.id());
                }
            }
            pv.put("pageTitleExpr", p.title() != null ? TemplateText.tsString(p.title()) : defaultTitleExpr);
            if (p.icon() != null) pv.put("navIcon", p.icon());
            pv.put("navGroup", p.group());
            // Pages restricted to roles: the shell hides them from the nav and guards their route.
            List<String> pageRoles = effectiveRoles(p, ctx);
            pv.put("hasRoles", !pageRoles.isEmpty());
            pv.put("rolesTs", "[" + String.join(", ", pageRoles.stream().map(r -> "'" + r + "'").toList()) + "]");
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
            boolean tabsUseRangeParams = false;
            boolean tabsUseStatsQuery = false;
            for (int i = 0; i < p.tabs().size(); i++) {
                PageDefinition.Tab tab = p.tabs().get(i);
                Map<String, Object> target = viewById.get(tab.page());
                boolean targetNavigates = Boolean.TRUE.equals(target.get("needsNavigate"));
                needsNavigate |= targetNavigates;
                Map<String, Object> tv = new LinkedHashMap<>();
                tv.put("tabIndex", i);
                tv.put("tabId", tab.page());
                tv.put("tabTitleExpr", tab.title() != null ? TemplateText.tsString(tab.title()) : target.get("pageTitleExpr"));
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
                    PageDefinition listPage = pageById.get(tab.page());
                    String preset = presetQueryExpr(listPage.presetFilter(),
                            entityByPascal.get(Naming.toPascalCase(listPage.entity())));
                    tv.put("countPath", "/api/" + target.get("entityNamePluralKebab"));
                    tv.put("countParamsExpr", preset == null ? "''" : preset);
                    tabsUseRangeParams |= presetHasPeriod(listPage.presetFilter());
                    tabsUseStatsQuery |= preset != null && preset.startsWith("statsQuery(");
                    hasTabCounts = true;
                }
                tv.put("first", i == 0);
                tv.put("last", i == p.tabs().size() - 1);
                tabViews.add(tv);
            }
            pv.put("tabs", tabViews);
            pv.put("hasTabCounts", hasTabCounts);
            pv.put("tabsUseRangeParams", tabsUseRangeParams);
            pv.put("tabsUseStatsQuery", tabsUseStatsQuery);
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
            } else if (Boolean.TRUE.equals(pv.get("wizardEditable")) || Boolean.TRUE.equals(pv.get("formEditable"))) {
                // #/<wizard>/<id> edits that row; a new id is a fresh wizard (a form likewise).
                routeProps = " key={route.arg ?? ''} editId={route.arg}";
            } else if (Boolean.TRUE.equals(pv.get("pageIsMasterDetail"))) {
                // The selected parent is the route arg (#/customers/42).
                routeProps = " selectedId={route.arg} onSelect={id => go('" + pv.get("pageId") + "', id)}";
            } else if (Boolean.TRUE.equals(pv.get("listKeepsState"))) {
                // The list's state rides in the hash, rewritten in place; a side pane's row is the
                // route arg (#/tickets/42?_page=2), opened without losing the list's state.
                routeProps = (Boolean.TRUE.equals(pv.get("detailSide"))
                        ? " selectedId={route.arg} onSelect={id => go('" + pv.get("pageId") + "', id, route.query)}" : "")
                        + " query={route.query} onQueryChange={setQuery}";
            } else if (Boolean.TRUE.equals(pv.get("detailSide"))) {
                // The row open in the side pane likewise (#/tickets/42), with any route filters.
                routeProps = " selectedId={route.arg} onSelect={id => go('" + pv.get("pageId") + "', id)}"
                        + (Boolean.TRUE.equals(pv.get("listTakesQuery")) ? " filters={route.query}" : "");
            } else if (Boolean.TRUE.equals(pv.get("listTakesQuery"))) {
                routeProps = " filters={route.query}";
            } else if (Boolean.TRUE.equals(pv.get("pageIsDashboard")) && Boolean.TRUE.equals(pv.get("hasDashFilters"))) {
                // Filters (and the period, when there is a picker) ride in the hash (#/desk?region=NORTH&period=30d).
                routeProps = " query={route.query} onQueryChange={setQuery}";
            } else if (Boolean.TRUE.equals(pv.get("pageIsDashboard")) && Boolean.TRUE.equals(pv.get("hasDateRange"))) {
                // The period picker's choice rides in the hash (#/desk?period=30d), rewritten in place.
                routeProps = " period={route.query.period} onPeriodChange={period => setQuery({ period })}";
            } else if (Boolean.TRUE.equals(pv.get("pageIsReport")) && Boolean.TRUE.equals(pv.get("hasFilters"))) {
                // The report's filter bar likewise (#/revenue?region=NORTH).
                routeProps = " query={route.query} onQueryChange={setQuery}";
            } else if (Boolean.TRUE.equals(pv.get("pageIsCalendar")) || Boolean.TRUE.equals(pv.get("pageIsSearch"))
                    || Boolean.TRUE.equals(pv.get("pageIsInbox"))) {
                // A calendar's day and view (#/schedule?_date=2026-09-01&_mode=week) and a search's
                // words (#/find?q=acme) likewise.
                routeProps = " query={route.query} onQueryChange={setQuery}";
            }
            pv.put("routeProps", routeProps);
            // The screen's destructured props, in a fixed order. A dashboard with a period picker
            // and a filterable report take their route state as optional props (absent when the
            // screen is embedded as a tab, where they keep local state instead).
            boolean dashboardQuery = Boolean.TRUE.equals(pv.get("pageIsDashboard")) && Boolean.TRUE.equals(pv.get("hasDashFilters"));
            boolean dashboardPeriod = !dashboardQuery && Boolean.TRUE.equals(pv.get("pageIsDashboard")) && Boolean.TRUE.equals(pv.get("hasDateRange"));
            boolean reportQuery = Boolean.TRUE.equals(pv.get("pageIsReport")) && Boolean.TRUE.equals(pv.get("hasFilters"));
            boolean listState = Boolean.TRUE.equals(pv.get("listKeepsState"));
            boolean ownQuery = Boolean.TRUE.equals(pv.get("pageIsCalendar")) || Boolean.TRUE.equals(pv.get("pageIsSearch"))
                    || Boolean.TRUE.equals(pv.get("pageIsInbox"));
            List<String> screenParams = new ArrayList<>();
            if (Boolean.TRUE.equals(pv.get("detailSide"))) screenParams.addAll(List.of("selectedId", "onSelect"));
            if (listState) screenParams.addAll(List.of("query", "onQueryChange"));
            else if (Boolean.TRUE.equals(pv.get("listTakesQuery"))) screenParams.add("filters");
            if (dashboardPeriod) screenParams.addAll(List.of("period: routePeriod", "onPeriodChange"));
            if (dashboardQuery) screenParams.addAll(List.of("query: routeQuery", "onQueryChange"));
            if (reportQuery || ownQuery) screenParams.addAll(List.of("query", "onQueryChange"));
            if (Boolean.TRUE.equals(pv.get("needsNavigate"))) screenParams.add("onNavigate");
            if (Boolean.TRUE.equals(pv.get("isTabTarget"))) screenParams.add("embedded");
            pv.put("screenParams", String.join(", ", screenParams));
            pv.put("hasScreenProps", !screenParams.isEmpty());
            pv.put("takesQuery", dashboardPeriod || dashboardQuery || reportQuery || listState || ownQuery);
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
                    || Boolean.TRUE.equals(pv.get("pageIsCalendar")) || Boolean.TRUE.equals(pv.get("pageIsBoard"))
                    || Boolean.TRUE.equals(pv.get("pageIsImport")) || Boolean.TRUE.equals(pv.get("pageIsSearch"))
                    || Boolean.TRUE.equals(pv.get("pageIsTree"))
                    || Boolean.TRUE.equals(pv.get("pageIsForm")) || Boolean.TRUE.equals(pv.get("pageIsInbox"))
                    || exprs.stream().anyMatch(e -> e instanceof String s && s.startsWith("t(")));
        }

        List<Map<String, Object>> all = new ArrayList<>(viewById.values());
        List<Map<String, Object>> nav = all.stream().filter(v -> !Boolean.TRUE.equals(v.get("hidden"))).toList();
        List<Map<String, Object>> records = all.stream().filter(v -> Boolean.TRUE.equals(v.get("pageIsRecord"))).toList();
        List<Map<String, Object>> routes = new ArrayList<>(nav);
        routes.addAll(records);
        // A hidden wizard or form is still a route: a list page's New opens it. So is a hidden search
        // page: the header's search box opens it.
        all.stream().filter(v -> (Boolean.TRUE.equals(v.get("pageIsWizard")) || Boolean.TRUE.equals(v.get("pageIsForm"))
                        || Boolean.TRUE.equals(v.get("pageIsSearch")))
                        && Boolean.TRUE.equals(v.get("hidden")))
                .forEach(routes::add);
        ctx.put("pages", all);
        ctx.put("navPages", nav);
        // Everything the shell can show: the nav pages, plus record pages (opened with an id).
        ctx.put("routePages", routes);
        ctx.put("recordPages", records);
        ctx.put("hasRecordPages", !records.isEmpty());
        // A record page that prints: the print rules in the stylesheet and the button's word.
        ctx.put("hasPrintablePages", pages.stream().anyMatch(PageDefinition::printable));
        // Routes outside the nav (record pages, hidden wizards): the header names them from here,
        // and one with a home page shows it as a breadcrumb.
        List<Map<String, Object>> offNav = routes.stream().filter(v -> !nav.contains(v)).toList();
        ctx.put("offNavPages", offNav);
        ctx.put("hasOffNavPages", !offNav.isEmpty());
        List<Map<String, Object>> crumbs = offNav.stream().filter(v -> Boolean.TRUE.equals(v.get("hasBack"))).toList();
        ctx.put("crumbPages", crumbs);
        ctx.put("hasBreadcrumbs", !crumbs.isEmpty());
        ctx.put("hasPageRoles", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("hasRoles"))));
        if (shellUserMenu || shellNotifications || shellPalette) {
            putShell(ctx, pages, nav, all, shellPalette, shellNotifications, links, summaries);
        }
        // The shell declares its `goView` helper only when some screen takes onNavigate.
        ctx.put("hasNavigatingScreens", routes.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("needsNavigate"))));
        // ...and its `setQuery` (a route rewrite) only when a routed screen keeps state in the hash.
        ctx.put("hasQueryScreens", routes.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("takesQuery"))));
        ctx.put("initialPageId", nav.get(0).get("pageId"));
        // The lucide names the shell imports: the nav icons in use plus its own chrome, sorted.
        // A top bar has no off-canvas menu to open, so it imports no Menu icon.
        Set<String> icons = new TreeSet<>(navTopbar ? List.of("Moon", "Sun") : List.of("Menu", "Moon", "Sun"));
        nav.forEach(v -> icons.add((String) v.get("navIcon")));
        ctx.put("navIconImports", String.join(", ", icons));
        putNavGroups(ctx, nav);
        ctx.put("hasDashboardPages", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("pageIsDashboard"))));
        ctx.put("hasReportPages", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("pageIsReport"))));
        ctx.put("hasInboxPages", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("pageIsInbox"))));
        ctx.put("hasTreePages", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("pageIsTree"))));
        // widgets.tsx backs both the dashboard screens and the report screen's chart.
        ctx.put("hasWidgets", Boolean.TRUE.equals(ctx.get("hasDashboardPages"))
                || Boolean.TRUE.equals(ctx.get("hasReportPages"))
                || all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("hasHeaderStats")))
                // ...and for a period preset (rangeParams lives in stats.ts) on a list or a tab count.
                || all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("presetUsesPeriod"))
                        || Boolean.TRUE.equals(v.get("tabsUseRangeParams"))));
        // widgets-flow.tsx only for a dashboard that draws a funnel, a gauge or time in state.
        ctx.put("hasFlowWidgets", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("usesFlow"))));
        // links.tsx (the launcher tiles) only for a dashboard that has a links widget.
        ctx.put("hasLinksWidgets", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("usesLinks"))));
        // Per-entity contexts read these: an entity with a create page gets the list page's
        // onCreate, and one with a wizard the stepped form.
        ctx.put(EntityScaffoldContext.CREATE_PAGES_KEY, links.createPageByEntity());
        ctx.put(EntityScaffoldContext.WIZARD_PAGES_KEY, links.wizardPageByEntity());
        // Per-entity contexts read this too: a relation to an entity with a record page links there.
        ctx.put(EntityScaffoldContext.RECORD_PAGES_KEY, links.recordPageByEntity());
        // Per-entity contexts read this too: only an entity whose list page sets columns / sort /
        // view / pageSize gets the props for them, so every other EntityPage keeps its bytes.
        Set<String> listConfigured = new LinkedHashSet<>();
        for (PageDefinition p : pages) {
            if (p.type() == PageDefinition.Type.ENTITY_LIST && p.hasListPresentation()) listConfigured.add(p.entity());
            // A master-detail child list and a record's related list may open with their own columns / sort.
            if (p.type() == PageDefinition.Type.MASTER_DETAIL && p.hasListPresentation()) listConfigured.add(p.child());
            if (p.type() == PageDefinition.Type.RECORD) {
                for (PageDefinition.ChildTab tab : p.childTabs()) {
                    if (tab.hasListPresentation()) listConfigured.add(tab.entity());
                }
            }
            // A list widget always hands its page a page size, so its entity takes the props too.
            if (p.type() == PageDefinition.Type.DASHBOARD) {
                for (PageDefinition.Widget w : p.widgets()) {
                    if (w.kind() == PageDefinition.WidgetKind.LIST) listConfigured.add(w.entity());
                }
            }
        }
        ctx.put(EntityScaffoldContext.LIST_PRESENTATION_KEY, listConfigured);
        // Per-entity contexts read this too: only an entity with a side-pane list page gets the
        // pane and its props.
        Set<String> sideDetail = new LinkedHashSet<>();
        for (PageDefinition p : pages) {
            if (p.type() == PageDefinition.Type.ENTITY_LIST && p.detail() == PageDefinition.Detail.SIDE) sideDetail.add(p.entity());
        }
        ctx.put(EntityScaffoldContext.SIDE_DETAIL_KEY, sideDetail);
        // ...only an entity with a list page that hides actions takes hideActions...
        Set<String> listActions = new LinkedHashSet<>();
        for (PageDefinition p : pages) {
            if (p.type() == PageDefinition.Type.ENTITY_LIST && !p.hiddenActions().isEmpty()) listActions.add(p.entity());
        }
        ctx.put(EntityScaffoldContext.LIST_ACTIONS_KEY, listActions);
        // ...and only an entity with a list page in the nav reports its list state for the route.
        Set<String> listState = new LinkedHashSet<>();
        for (PageDefinition p : pages) {
            if (p.type() == PageDefinition.Type.ENTITY_LIST && !p.hidden()) listState.add(p.entity());
        }
        ctx.put(EntityScaffoldContext.LIST_STATE_KEY, listState);
        ctx.put("hasListStatePages", !listState.isEmpty());
        ctx.put("hasTabsPages", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("pageIsTabs"))));
        // The shared pieces the new page types bring, each only when a page uses it.
        ctx.put("hasCalendarPages", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("pageIsCalendar"))));
        ctx.put("hasBoardPages", all.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("pageIsBoard"))));
        // The header's search box opens the search page, when that page asks for it.
        PageDefinition searchPage = pages.stream().filter(p -> p.type() == PageDefinition.Type.SEARCH).findFirst().orElse(null);
        boolean shellSearch = searchPage != null && searchPage.search().shellSearch();
        ctx.put("hasShellSearch", shellSearch);
        ctx.put("searchPageId", shellSearch ? searchPage.id() : null);
        // Per-entity contexts read this: a list of an entity with an import page sends Import there.
        ctx.put(EntityScaffoldContext.IMPORT_PAGES_KEY, links.importPageByEntity());
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
                section.put("groupLabelExpr", TemplateText.tsString(group));
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
    record PageLinks(Map<String, String> recordPageByEntity, Map<String, String> homeByEntity,
                     Map<String, String> listPageByEntity, Map<String, String> wizardPageByEntity,
                     Map<String, String> createPageByEntity, Map<String, String> importPageByEntity) {

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
            Map<String, String> imports = new LinkedHashMap<>();
            for (PageDefinition p : pages) {
                if (p.type() == PageDefinition.Type.WIZARD) wizards.put(p.entity(), p.id());
                if (p.type() == PageDefinition.Type.IMPORT) imports.put(p.entity(), p.id());
            }
            // The page that creates (and edits) an entity's rows: its wizard or its form (never both).
            Map<String, String> creates = new LinkedHashMap<>(wizards);
            for (PageDefinition p : pages) {
                if (p.type() == PageDefinition.Type.FORM) creates.put(p.entity(), p.id());
            }
            return new PageLinks(records, homes, lists, wizards, creates, imports);
        }

        /** The entity's import page — where its list page's Import goes — or null. */
        String importPageOf(String entity) { return importPageByEntity.get(entity); }

        String recordPageOf(String entity) { return recordPageByEntity.get(entity); }

        /** The entity's first visible list page — what a drill-down opens, filtered — or null. */
        String listPageOf(String entity) { return listPageByEntity.get(entity); }

        /** The entity's create page — where its list page's New, a calendar's day and a record's
         *  Edit go — or null. */
        String createPageOf(String entity) { return createPageByEntity.get(entity); }

        String homeOf(String entity) { return homeByEntity.get(entity); }
    }

    /** A calendar page: the entity, its date field(s), its views and what opens or creates a row. */
    @SuppressWarnings("unchecked")
    private static void putCalendar(Map<String, Object> pv, PageDefinition p, Map<String, Object> ctx,
                                    Map<String, Object> ev, PageLinks links, Map<String, Map<String, Object>> summaries) {
        PageDefinition.CalendarSpec spec = p.calendar();
        Map<String, Object> summary = summaries.get(p.entity().toLowerCase(Locale.ROOT));
        putEntityNames(pv, ev);
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) ev.get("fields")) fields.put((String) f.get("name"), f);
        boolean dateTime = Boolean.TRUE.equals(fields.get(spec.dateField()).get("isDateTime"));
        pv.put("calendarField", spec.dateField());
        pv.put("calendarIsDateTime", dateTime);
        pv.put("hasEndField", spec.endField() != null);
        pv.put("calendarEndField", spec.endField());
        pv.put("endIsDateTime", spec.endField() != null && Boolean.TRUE.equals(fields.get(spec.endField()).get("isDateTime")));
        pv.put("modesTs", "[" + String.join(", ", spec.modes().stream().map(m -> "'" + m.wire() + "'").toList()) + "]");
        pv.put("hasModeToggle", spec.modes().size() > 1);
        pv.put("hasPresetFilter", !p.presetFilter().isEmpty());
        pv.put("presetFilterOrEmptyTs", p.presetFilter().isEmpty() ? "{}" : presetFilterTs(p.presetFilter(), ev));
        pv.put("presetUsesPeriod", presetHasPeriod(p.presetFilter()));
        putRowNaming(pv, ev, summary);
        putRecordLink(pv, "", p.entity(), links, summaries);
        boolean mutable = Boolean.TRUE.equals(ev.get("mutable"));
        String create = mutable ? links.createPageOf(p.entity()) : null;
        pv.put("calendarCreates", mutable);
        pv.put("calendarCreatesInPage", create != null);
        pv.put("calendarCreatesInDrawer", mutable && create == null);
        pv.put("createPageId", create);
        pv.put("needsNavigate", Boolean.TRUE.equals(pv.get("hasRecordPage")) || create != null);
    }

    /** A board page: its lanes (with their labels and limits) and what a card shows. */
    @SuppressWarnings("unchecked")
    private static void putBoard(Map<String, Object> pv, PageDefinition p, Map<String, Object> ev, PageLinks links,
                                 Map<String, Map<String, Object>> summaries) {
        PageDefinition.BoardSpec spec = p.board();
        Map<String, Object> summary = summaries.get(p.entity().toLowerCase(Locale.ROOT));
        putEntityNames(pv, ev);
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) ev.get("fields")) fields.put((String) f.get("name"), f);
        Map<String, Map<String, Object>> relations = new LinkedHashMap<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) ev.get("relations")) relations.put((String) r.get("fieldName"), r);
        Map<String, Object> lane = fields.get(spec.laneField());
        boolean laneIsBoolean = Boolean.TRUE.equals(lane.get("isBoolean"));
        pv.put("laneField", spec.laneField());
        pv.put("laneIsBoolean", laneIsBoolean);
        Map<String, String> labelTs = new LinkedHashMap<>();
        for (Map<String, Object> v : (List<Map<String, Object>>) lane.getOrDefault("enumValues", List.of())) {
            labelTs.put((String) v.get("value"), (String) v.get("labelTs"));
        }
        List<Map<String, Object>> lanes = new ArrayList<>();
        for (String value : spec.lanes()) {
            Map<String, Object> lv = new LinkedHashMap<>();
            lv.put("value", value);
            lv.put("labelExpr", laneIsBoolean ? ("true".equals(value) ? "t('trueLabel')" : "t('falseLabel')") : "'" + labelTs.get(value) + "'");
            Integer limit = spec.wipLimits().get(value);
            lv.put("hasLimit", limit != null);
            lv.put("limit", limit);
            lanes.add(lv);
        }
        pv.put("lanes", lanes);
        pv.put("laneSize", spec.laneSize());
        pv.put("hasWipLimits", !spec.wipLimits().isEmpty());
        // What a card shows, as TS expressions over its row `r`, the first as the heading.
        Set<String> labelImports = new TreeSet<>();
        List<Map<String, Object>> cards = cardViews(spec.cardFields(), fields, relations, labelImports);
        pv.put("cardFields", cards);
        pv.put("cardLabelImports", String.join(", ", labelImports));
        pv.put("hasCardLabelImports", !labelImports.isEmpty());
        putCardFormatImports(pv, cards);
        pv.put("hasPresetFilter", !p.presetFilter().isEmpty());
        pv.put("presetFilterOrEmptyTs", p.presetFilter().isEmpty() ? "{}" : presetFilterTs(p.presetFilter(), ev));
        pv.put("presetUsesPeriod", presetHasPeriod(p.presetFilter()));
        pv.put("boardSortTs", p.sort() == null ? "null"
                : "{ field: " + TemplateText.tsString(p.sort().field()) + ", direction: '" + (p.sort().desc() ? "desc" : "asc") + "' }");
        putRowNaming(pv, ev, summary);
        putRecordLink(pv, "", p.entity(), links, summaries);
        pv.put("boardMutable", Boolean.TRUE.equals(ev.get("mutable")));
        // Lanes of the workflow's status: a card moves by the transition between them, and the quick
        // look (no record page) offers the row's moves.
        boolean workflow = Boolean.TRUE.equals(ev.get("hasWorkflow"));
        pv.put("boardIsWorkflow", workflow && spec.laneField().equals(ev.get("workflowStatusField")));
        pv.put("boardHasWorkflow", workflow);
        pv.put("needsNavigate", Boolean.TRUE.equals(pv.get("hasRecordPage")));
        pv.put("hasCardDetails", cards.size() > 1);
        // The screen imports `t` only when something calls it (the generated lint rejects an unused
        // import): a move's messages, a boolean lane or card, the quick-look drawer's title.
        pv.put("boardUsesT", Boolean.TRUE.equals(ev.get("mutable")) || laneIsBoolean
                || !Boolean.TRUE.equals(pv.get("hasRecordPage"))
                || cards.stream().anyMatch(c -> ((String) c.get("renderTs")).contains("t('")));
    }

    /** A search page: each entity it searches, how its matches are named and where one opens. */
    @SuppressWarnings("unchecked")
    private static void putSearch(Map<String, Object> pv, PageDefinition p, Map<String, Map<String, Object>> entityByPascal,
                                  PageLinks links, Map<String, Map<String, Object>> summaries) {
        PageDefinition.SearchSpec spec = p.search();
        List<Map<String, Object>> groups = new ArrayList<>();
        boolean navigates = false;
        boolean drawers = false;
        for (int i = 0; i < spec.entities().size(); i++) {
            String entity = spec.entities().get(i);
            Map<String, Object> ev = entityByPascal.get(Naming.toPascalCase(entity));
            Map<String, Object> gv = new LinkedHashMap<>();
            gv.put("groupIndex", i);
            putEntityNames(gv, ev);
            putRowNaming(gv, ev, summaries.get(entity.toLowerCase(Locale.ROOT)));
            putRecordLink(gv, "", entity, links, summaries);
            // "See all" opens the entity's list page in the nav with the same words searched.
            String list = links.listPageOf(entity);
            gv.put("hasListPage", list != null);
            gv.put("listPageId", list);
            navigates |= list != null || Boolean.TRUE.equals(gv.get("hasRecordPage"));
            drawers |= !Boolean.TRUE.equals(gv.get("hasRecordPage"));
            groups.add(gv);
        }
        pv.put("searchGroups", groups);
        pv.put("perEntity", spec.perEntity());
        pv.put("searchUsesDrawer", drawers);
        pv.put("drawerGroups", groups.stream().filter(g -> !Boolean.TRUE.equals(g.get("hasRecordPage"))).toList());
        pv.put("needsNavigate", navigates);
    }

    /**
     * The header's tools ({@code ShellTools.tsx}): what the palette offers — {@code palettePages},
     * the nav's pages and the hidden forms that start a record, and {@code paletteEntities}, every
     * entity that is searched by text and has somewhere to open a match — and what the bell counts,
     * {@code bellSources}: every workflow that assigns its tasks, with the page they are worked on.
     */
    @SuppressWarnings("unchecked")
    private static void putShell(Map<String, Object> ctx, List<PageDefinition> pages, List<Map<String, Object>> nav,
                                 List<Map<String, Object>> all, boolean palette, boolean notifications,
                                 PageLinks links, Map<String, Map<String, Object>> summaries) {
        List<Map<String, Object>> entityViews = (List<Map<String, Object>>) ctx.get("entities");
        List<Object> exprs = new ArrayList<>();
        if (palette) {
            List<Map<String, Object>> palettePages = new ArrayList<>(nav);
            all.stream().filter(v -> Boolean.TRUE.equals(v.get("hidden"))
                            && (Boolean.TRUE.equals(v.get("pageIsWizard")) || Boolean.TRUE.equals(v.get("pageIsForm"))))
                    .forEach(palettePages::add);
            ctx.put("palettePages", palettePages);
            palettePages.forEach(v -> exprs.add(v.get("pageTitleExpr")));
            List<Map<String, Object>> found = new ArrayList<>();
            for (Map<String, Object> ev : entityViews) {
                if (found.size() == MAX_PALETTE_ENTITIES) break;
                if (!Boolean.TRUE.equals(ev.get("hasStringFields"))) continue;
                String name = (String) ev.get("entityDeclaredName");
                Map<String, Object> gv = new LinkedHashMap<>();
                putEntityNames(gv, ev);
                putRowNaming(gv, ev, summaries.get(name.toLowerCase(Locale.ROOT)));
                putRecordLink(gv, "", name, links, summaries);
                String list = links.listPageOf(name);
                gv.put("hasListPage", list != null);
                gv.put("listPageId", list);
                // A match needs somewhere to open: the record's page, else the entity's list.
                if (list == null && !Boolean.TRUE.equals(gv.get("hasRecordPage"))) continue;
                found.add(gv);
            }
            ctx.put("paletteEntities", found);
        }
        if (notifications) {
            List<Map<String, Object>> sources = new ArrayList<>();
            for (Map<String, Object> ev : entityViews) {
                if (!Boolean.TRUE.equals(ev.get("hasWorkflow")) || !Boolean.TRUE.equals(ev.get("hasAssignee"))) continue;
                String name = (String) ev.get("entityDeclaredName");
                Map<String, Object> sv = new LinkedHashMap<>();
                putEntityNames(sv, ev);
                Object due = ev.get("dueField");
                sv.put("hasDue", due != null);
                if (due != null) {
                    sv.put("dueField", due);
                    sv.put("dueIsDateTime", ((List<Map<String, Object>>) ev.get("fields")).stream()
                            .anyMatch(f -> due.equals(f.get("name")) && Boolean.TRUE.equals(f.get("isDateTime"))));
                }
                // Where the tasks are worked on: the entity's inbox page in the nav, else its home.
                PageDefinition inbox = pages.stream()
                        .filter(p -> p.type() == PageDefinition.Type.INBOX && !p.hidden() && name.equals(p.entity()))
                        .findFirst().orElse(null);
                String page = inbox != null ? inbox.id() : links.homeOf(name);
                sv.put("hasBellPage", page != null);
                sv.put("bellPageId", page);
                sv.put("hasBellQueues", inbox != null);
                if (inbox != null) {
                    sv.put("bellQueuesTs", "[" + String.join(", ", inbox.inbox().queues().stream()
                            .map(q -> TemplateText.tsString(q.wire())).toList()) + "]");
                }
                sources.add(sv);
            }
            ctx.put("bellSources", sources);
        }
        ctx.put("shellUsesT", exprs.stream().anyMatch(e -> e instanceof String s && s.contains("t(")));
    }

    /** The entities the palette searches at once: each is a request per keystroke pause. */
    private static final int MAX_PALETTE_ENTITIES = 8;

    /** The names a screen over one entity imports its hooks, types and pages by. */
    private static void putEntityNames(Map<String, Object> view, Map<String, Object> ev) {
        view.put("EntityName", ev.get("EntityName"));
        view.put("entityNameKebab", ev.get("entityNameKebab"));
        view.put("entityNamePluralKebab", ev.get("entityNamePluralKebab"));
        view.put("entityLabelExpr", TemplateText.tsString((String) ev.get("entityLabel")));
        view.put("entityLabelPluralExpr", TemplateText.tsString((String) ev.get("entityLabelPlural")));
    }

    /** How a row of an entity is named on a card, an event or a match — its label field, else its
     *  key — and its key as a TS expression (an array for a composite key). */
    @SuppressWarnings("unchecked")
    private static void putRowNaming(Map<String, Object> view, Map<String, Object> ev, Map<String, Object> summary) {
        String label = (String) summary.get("labelField");
        List<Map<String, Object>> pks = (List<Map<String, Object>>) ev.get("pkFields");
        boolean composite = pks.size() > 1;
        String keyTs = composite
                ? "[" + String.join(", ", pks.stream().map(k -> "r." + k.get("name")).toList()) + "]"
                : "r." + pks.get(0).get("name");
        String keyText = composite ? keyTs + ".join('/')" : "String(" + keyTs + ")";
        view.put("rowIdTs", keyTs);
        view.put("rowKeyTs", keyText);
        view.put("rowLabelTs", label != null
                ? "r." + label + " == null || r." + label + " === '' ? '#' + " + keyText + " : String(r." + label + ")"
                : "'#' + " + keyText);
        view.put("hasCompositeKey", composite);
        view.put("pkName", pks.get(0).get("name"));
    }

    /** A content page's blocks as JSX: every piece of text a quoted string ({@link TemplateText#tsString}), so
     *  nothing the page's author wrote can become markup. */
    static String contentJsx(List<ContentMarkdown.Block> blocks) {
        StringBuilder out = new StringBuilder();
        for (ContentMarkdown.Block b : blocks) {
            String indent = "        ";
            switch (b.kind()) {
                case HEADING -> {
                    String tag = "h" + (b.level() + 1);
                    String size = b.level() == 1 ? "text-xl" : b.level() == 2 ? "text-lg" : "text-base";
                    out.append(indent).append('<').append(tag).append(" className=\"").append(size)
                            .append(" font-semibold tracking-tight text-fg\">").append(runsJsx(b.items().get(0)))
                            .append("</").append(tag).append(">\n");
                }
                case PARAGRAPH -> out.append(indent).append("<p className=\"text-sm leading-relaxed text-fg\">")
                        .append(runsJsx(b.items().get(0))).append("</p>\n");
                case CALLOUT -> out.append(indent)
                        .append("<aside role=\"note\" className=\"rounded-lg border-s-4 border-brand bg-brand/5 px-4 py-3 text-sm text-fg\">")
                        .append(runsJsx(b.items().get(0))).append("</aside>\n");
                case BULLETS, NUMBERS -> {
                    String tag = b.kind() == ContentMarkdown.BlockKind.BULLETS ? "ul" : "ol";
                    String list = b.kind() == ContentMarkdown.BlockKind.BULLETS ? "list-disc" : "list-decimal";
                    out.append(indent).append('<').append(tag).append(" className=\"").append(list)
                            .append(" space-y-1 ps-5 text-sm text-fg\">\n");
                    for (List<ContentMarkdown.Run> item : b.items()) {
                        out.append(indent).append("  <li>").append(runsJsx(item)).append("</li>\n");
                    }
                    out.append(indent).append("</").append(tag).append(">\n");
                }
                case RULE -> out.append(indent).append("<hr className=\"border-border\" />\n");
            }
        }
        return out.toString();
    }

    private static String runsJsx(List<ContentMarkdown.Run> runs) {
        StringBuilder out = new StringBuilder();
        for (ContentMarkdown.Run r : runs) {
            String text = "{" + TemplateText.tsString(r.text()) + "}";
            switch (r.kind()) {
                case TEXT -> out.append(text);
                case BOLD -> out.append("<strong className=\"font-semibold\">").append(text).append("</strong>");
                case EM -> out.append("<em>").append(text).append("</em>");
                case CODE -> out.append("<code className=\"rounded bg-surface-2 px-1 py-0.5 text-[0.9em]\">").append(text).append("</code>");
                case PAGE_LINK -> out.append("<a href=\"#/").append(r.target())
                        .append("\" onClick={e => { e.preventDefault(); onNavigate('").append(r.target())
                        .append("') }} className=\"font-medium text-brand hover:underline\">").append(text).append("</a>");
                // A web address opens in a new tab; a mailto: hands over to the mail app.
                case LINK -> out.append("<a href={").append(TemplateText.tsString(r.target())).append("}")
                        .append(r.target().regionMatches(true, 0, "mailto:", 0, 7) ? "" : " target=\"_blank\" rel=\"noreferrer\"")
                        .append(" className=\"font-medium text-brand hover:underline\">")
                        .append(text).append("</a>");
            }
        }
        return out.toString();
    }

    /** {@code <prefix>HasRecordPage}/{@code <prefix>RecordPageId}/{@code <prefix>RecordPk} (prefix ""
     *  gives {@code hasRecordPage}/{@code recordPageId}/{@code recordPk}): whether rows of {@code entity}
     *  open on a record page, which one, and the key field whose value is the route argument. */
    static void putRecordLink(Map<String, Object> view, String prefix, String entity, PageLinks links,
                              Map<String, Map<String, Object>> summaries) {
        String page = links.recordPageOf(entity);
        view.put(prefix.isEmpty() ? "hasRecordPage" : prefix + "HasRecordPage", page != null);
        view.put(prefix.isEmpty() ? "recordPageId" : prefix + "RecordPageId", page);
        view.put(prefix.isEmpty() ? "recordPk" : prefix + "RecordPk",
                summaries.get(entity.toLowerCase(Locale.ROOT)).get("pkName"));
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
        pv.put("entityLabelExpr", TemplateText.tsString((String) ev.get("entityLabel")));
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
                fields.add(TemplateText.tsString(field));
                keys.add(TemplateText.tsString(relation ? camel + "Id" : name));
            }
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("stepTitleExpr", step.title() != null ? TemplateText.tsString(step.title()) : "t('stepX', { x: " + (i + 1) + " })");
            sv.put("fieldsTs", String.join(", ", fields));
            sv.put("keysTs", String.join(", ", keys));
            steps.add(sv);
        }
        // The rows edited with the record are a step of their own, after its fields. A message about
        // one of them is keyed by its place (lines[0].quantity): the step owns every key under the list.
        boolean rows = false;
        for (Map<String, Object> inv : (List<Map<String, Object>>) ev.getOrDefault("inverseRelations", List.of())) {
            if (!Boolean.TRUE.equals(inv.get("composition"))) continue;
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("stepTitleExpr", TemplateText.tsString((String) inv.get("collectionLabel")));
            sv.put("fieldsTs", TemplateText.tsString((String) inv.get("collectionField")));
            sv.put("keysTs", TemplateText.tsString((String) inv.get("collectionField")));
            steps.add(sv);
            rows = true;
        }
        pv.put("wizardHasRows", rows);
        pv.put("steps", steps);
        // The form starts from the fields' defaults, as the entity page's New does.
        pv.put("initialTs", newRecordTs((List<Map<String, Object>>) ev.get("fields")));
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

    /** The {@code @shared/ui/format} helpers a screen's cards call — imported only when one does
     *  (the generated lint rejects an unused import). */
    private static void putCardFormatImports(Map<String, Object> pv, List<Map<String, Object>> cards) {
        List<String> imports = new ArrayList<>();
        for (String helper : List.of("formatDate", "formatDateTime", "formatInstant", "formatMoney", "attachmentUrl")) {
            if (cards.stream().anyMatch(c -> ((String) c.get("renderTs")).contains(helper + "("))) imports.add(helper);
        }
        pv.put("hasCardFormatImports", !imports.isEmpty());
        pv.put("cardFormatImports", String.join(", ", imports));
    }

    /** Board cards' and inbox rows' cells, as TS expressions over the row `r`, the first naming it;
     *  the enum label maps they read are added to `labelImports`. */
    private static List<Map<String, Object>> cardViews(List<String> names, Map<String, Map<String, Object>> fields,
                                                       Map<String, Map<String, Object>> relations, Set<String> labelImports) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            Map<String, Object> cv = new LinkedHashMap<>();
            Map<String, Object> f = fields.get(name);
            String render;
            String label;
            if (f != null) {
                label = (String) f.get("label");
                if (Boolean.TRUE.equals(f.get("isEnum"))) labelImports.add(f.get("enumTypeName") + "Labels");
                render = "r." + name + " == null ? '\u2014' : " + (Boolean.TRUE.equals(f.get("isMultiFile"))
                        ? FieldViewModel.multiFileTs((FieldType) f.get(FieldViewModel.FIELD_TYPE_KEY), "r." + name, FieldViewModel.Display.CARD)
                        : FieldViewModel.displayTs((FieldType) f.get(FieldViewModel.FIELD_TYPE_KEY), "r." + name,
                        (String) f.get("enumTypeName"), (String) f.get("currency"), FieldViewModel.Display.CARD));
            } else {
                Map<String, Object> r = relations.get(name);
                label = (String) r.get("label");
                String fk = "r." + r.get("fkFieldName");
                String id = fk + " == null ? '\u2014' : '#' + String(" + fk + ")";
                render = Boolean.TRUE.equals(r.get("hasTargetLabel")) ? "r." + name + "Label ?? (" + id + ")" : id;
            }
            cv.put("renderTs", render);
            cv.put("labelTs", TemplateText.escapeTsSingleQuoted(label));
            cv.put("isHeading", i == 0);
            cards.add(cv);
        }
        return cards;
    }

    /** An inbox page: its queues, its row cells, the workflow's status, assignee and due columns,
     *  the sort and preset it lists with, and where a row opens. */
    @SuppressWarnings("unchecked")
    private static void putInbox(Map<String, Object> pv, PageDefinition p, Map<String, Object> ev, PageLinks links,
                                 Map<String, Map<String, Object>> summaries) {
        PageDefinition.InboxSpec spec = p.inbox();
        Map<String, Object> summary = summaries.get(p.entity().toLowerCase(Locale.ROOT));
        putEntityNames(pv, ev);
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) ev.get("fields")) fields.put((String) f.get("name"), f);
        Map<String, Map<String, Object>> relations = new LinkedHashMap<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) ev.get("relations")) relations.put((String) r.get("fieldName"), r);
        Set<String> labelImports = new TreeSet<>();
        List<Map<String, Object>> inboxCards = cardViews(spec.cardFields(), fields, relations, labelImports);
        pv.put("cardFields", inboxCards);
        putCardFormatImports(pv, inboxCards);
        Map<String, Object> status = fields.get((String) ev.get("workflowStatusField"));
        labelImports.add(status.get("enumTypeName") + "Labels");
        pv.put("cardLabelImports", String.join(", ", labelImports));
        pv.put("statusField", status.get("name"));
        pv.put("statusLabelTs", TemplateText.escapeTsSingleQuoted((String) status.get("label")));
        pv.put("statusLabels", status.get("enumTypeName") + "Labels");
        Map<String, Object> assignee = fields.get((String) ev.get("assigneeField"));
        pv.put("assigneeField", assignee.get("name"));
        pv.put("assigneeLabelTs", TemplateText.escapeTsSingleQuoted((String) assignee.get("label")));
        Object dueName = ev.get("dueField");
        pv.put("hasDue", dueName != null);
        if (dueName != null) {
            Map<String, Object> due = fields.get((String) dueName);
            pv.put("dueField", due.get("name"));
            pv.put("dueLabelTs", TemplateText.escapeTsSingleQuoted((String) due.get("label")));
        }
        List<Map<String, Object>> queues = new ArrayList<>();
        for (PageDefinition.InboxQueue q : spec.queues()) {
            Map<String, Object> qv = new LinkedHashMap<>();
            qv.put("value", q.wire());
            qv.put("labelKey", switch (q) {
                case MINE -> "myTasks";
                case UNASSIGNED -> "unassignedTasks";
                case OPEN -> "allOpenTasks";
            });
            queues.add(qv);
        }
        pv.put("queues", queues);
        pv.put("queueTypeTs", String.join(" | ", spec.queues().stream().map(q -> "'" + q.wire() + "'").toList()));
        pv.put("sortParam", p.sort().field() + "," + (p.sort().desc() ? "desc" : "asc"));
        pv.put("hasPresetFilter", !p.presetFilter().isEmpty());
        pv.put("presetFilterOrEmptyTs", p.presetFilter().isEmpty() ? "{}" : presetFilterTs(p.presetFilter(), ev));
        pv.put("presetUsesPeriod", presetHasPeriod(p.presetFilter()));
        putRowNaming(pv, ev, summary);
        putRecordLink(pv, "", p.entity(), links, summaries);
        pv.put("needsNavigate", Boolean.TRUE.equals(pv.get("hasRecordPage")));
    }

    /** A form page: the entity's whole form, creating at #/<page> and editing at #/<page>/<id>. */
    private static void putForm(Map<String, Object> pv, PageDefinition p, Map<String, Object> ctx, Map<String, Object> ev,
                                PageLinks links, Map<String, Map<String, Object>> summaries) {
        pv.put("EntityName", ev.get("EntityName"));
        pv.put("entityNameKebab", ev.get("entityNameKebab"));
        pv.put("entityNamePluralKebab", ev.get("entityNamePluralKebab"));
        pv.put("entityLabelExpr", TemplateText.tsString((String) ev.get("entityLabel")));
        // The form starts from the fields' defaults, as the entity page's New does.
        pv.put("initialTs", newRecordTs((List<Map<String, Object>>) ev.get("fields")));
        // Saved, a row opens its record page; else the entity's home; else the form starts over.
        putRecordLink(pv, "", p.entity(), links, summaries);
        String home = links.homeOf(p.entity());
        pv.put("hasBack", home != null);
        pv.put("backPageId", home);
        pv.put("needsNavigate", Boolean.TRUE.equals(pv.get("hasRecordPage")) || home != null);
        // A single-key entity's form also edits: #/<form>/<id> loads the row and saves it with a PUT.
        boolean editable = !Boolean.TRUE.equals(ev.get("hasCompositePk"));
        pv.put("formEditable", editable);
        pv.put("formHasProps", editable || Boolean.TRUE.equals(pv.get("needsNavigate")));
        // A stale save (optimistic locking, as the entity resolves the opt) offers the two ways out.
        Object lockingOverride = ev.get("optimisticLockingOverride");
        boolean locking = lockingOverride != null ? Boolean.TRUE.equals(lockingOverride)
                : Boolean.TRUE.equals(ctx.get("optScaffoldOptimisticLocking"));
        pv.put("formLocking", editable && locking && !Boolean.TRUE.equals(ev.get("isView")));
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
            DashboardContext.putAggCurrency(sv, cv, reduces ? s.field() : null);
            sv.put("viaParam", Naming.toCamelCase(s.via()) + "Id");
            String label = (String) cv.get("entityLabelPlural");
            sv.put("titleExpr", s.title() != null ? TemplateText.tsString(s.title())
                    : reduces ? aggTitleExpr(s.agg(), (String) fieldOf(cv, s.field()).get("label")) : TemplateText.tsString(label));
            Map<String, Object> tab = tabViews.stream()
                    .filter(tv -> cv.get("EntityName").equals(tv.get("childEntityName"))).findFirst().orElse(null);
            sv.put("hasTab", tab != null);
            sv.put("tabIndex", tab == null ? null : tab.get("tabIndex"));
            stats.add(sv);
        }
        pv.put("headerStats", stats);
        pv.put("hasHeaderStats", !stats.isEmpty());
    }

    /** {@code {status=OPEN, paid=true}} → {@code paid=true&status=OPEN} (sorted); null when empty.
     *  Values are validated constants/booleans, so nothing needs URL-encoding. */
    /** Whether a validated preset carries a period ({@code @30d}): those are computed at runtime. */
    static boolean presetHasPeriod(Map<String, String> filter) {
        return filter.values().stream().anyMatch(v -> v.startsWith("@"));
    }

    /** {@code rangeParams('placedAt', false, '30d')} — the list params of a period preset on {@code field}. */
    private static String periodExpr(Map<String, Object> ev, String field, String value) {
        Map<String, Object> fv = fieldOf(ev, field);
        return "rangeParams('" + field + "', " + Boolean.TRUE.equals(fv.get("isDateTime")) + ", '" + value.substring(1) + "')";
    }

    /**
     * A preset as the query-string half of a request, as a TS expression: the literal params
     * ({@code 'status=OPEN&totalMin=100'}), a period's {@code rangeParams(...)} call, or both joined by
     * {@code statsQuery(...)}; null for an empty preset.
     */
    static String presetQueryExpr(Map<String, String> filter, Map<String, Object> ev) {
        if (filter.isEmpty()) return null;
        StringBuilder literal = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> en : new java.util.TreeMap<>(filter).entrySet()) {
            if (en.getValue().startsWith("@")) {
                parts.add(periodExpr(ev, en.getKey(), en.getValue()));
                continue;
            }
            if (literal.length() > 0) literal.append('&');
            literal.append(en.getKey()).append('=').append(en.getValue());
        }
        if (literal.length() > 0) parts.add(0, TemplateText.tsString(literal.toString()));
        return parts.size() == 1 ? parts.get(0) : "statsQuery(" + String.join(", ", parts) + ")";
    }

    /** One field of an entity view-model, by its wire name. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> fieldOf(Map<String, Object> entityView, String name) {
        return ((List<Map<String, Object>>) entityView.get("fields")).stream()
                .filter(f -> name.equals(f.get("name"))).findFirst().orElseThrow();
    }

    static String aggTitleExpr(PageDefinition.Agg agg, String fieldLabel) {
        String key = "agg" + agg.wire().substring(0, 1).toUpperCase(Locale.ROOT) + agg.wire().substring(1);
        return "t('" + key + "', { x: " + TemplateText.tsString(fieldLabel) + " })";
    }

    /** {@code {status=OPEN}} → {@code { status: 'OPEN' }}; values are validated constants/booleans. */
    static String presetFilterTs(Map<String, String> filter, Map<String, Object> ev) {
        if (filter.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{ ");
        int i = 0;
        for (Map.Entry<String, String> en : new java.util.TreeMap<>(filter).entrySet()) {
            if (i++ > 0) sb.append(", ");
            // A period preset spreads the params it resolves to when the screen loads.
            if (en.getValue().startsWith("@")) sb.append("...queryOf(").append(periodExpr(ev, en.getKey(), en.getValue())).append(")");
            else sb.append(en.getKey()).append(": ").append(TemplateText.tsString(en.getValue()));
        }
        return sb.append(" }").toString();
    }

    /** A new record as a TS object literal: the fields' defaults, else {@code {}}. */
    private static String newRecordTs(List<Map<String, Object>> fields) {
        List<String> defaults = new ArrayList<>();
        for (Map<String, Object> fv : fields) {
            if (Boolean.TRUE.equals(fv.get("hasDefault"))) defaults.add(fv.get("name") + ": " + fv.get("defaultTs"));
        }
        return defaults.isEmpty() ? "{}" : "{ " + String.join(", ", defaults) + " }";
    }

    /**
     * The roles a page's route asks for: its own, and — with ldap-auth, where the backend refuses
     * the calls anyway — what its entity's access level asks. A page that only writes (a form, a
     * wizard, an import) takes the write level, any other page of one entity the read level; a
     * page over several entities (a dashboard, tabs, search) takes none from them.
     *
     * <p>The shell lets a user in who holds <em>any</em> of a page's roles, and an admin is a user
     * too, so a list of roles asks for its weakest; the page's own and the entity's both have to
     * hold, which is the stronger of the two. A page with neither keeps an empty list — and its
     * bytes.
     */
    @SuppressWarnings("unchecked")
    private static List<String> effectiveRoles(PageDefinition p, Map<String, Object> ctx) {
        List<String> own = p.roles();
        if (!Boolean.TRUE.equals(ctx.get("hasLdapAuth")) || p.entity() == null) return own;
        Map<String, Map<String, Object>> summaries = (Map<String, Map<String, Object>>) ctx.get(EntityScaffoldContext.ENTITY_SUMMARIES_KEY);
        Map<String, Object> entity = summaries == null ? null : summaries.get(p.entity().toLowerCase(Locale.ROOT));
        if (entity == null) return own;
        boolean writes = switch (p.type()) {
            case FORM, WIZARD, IMPORT -> true;
            default -> false;
        };
        Object level = entity.get(writes ? "writeLevel" : "readLevel");
        if (level == null || "SIGNED_IN".equals(level)) return own;
        boolean admin = "ADMIN".equals(level) || (!own.isEmpty() && !own.contains("USER"));
        return List.of(admin ? "ADMIN" : "USER");
    }
}
