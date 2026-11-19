package com.menora.initializr.fullstack;

import com.menora.initializr.db.entity.ColorPaletteEntity;

import com.menora.initializr.gen.Naming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 *
 * <p>The parts live by area in this package: {@link EntityViewModel} and {@link FieldViewModel}
 * (one entity, one field), {@link RelationViews} (child lists and the entity summaries),
 * {@link FeatureContext} (the scaffold options' flags), {@link PageContext} and
 * {@link DashboardContext} (the page layout), {@link TemplateText} (escaping text spliced into
 * code) and {@link SchemaNames} (table and column names).
 */
public final class EntityScaffoldContext {

    /**
     * The project-wide {@code opts.scaffold} options an entity may override individually via
     * {@link EntityDefinition#opts()}: option key -> the {@code optScaffold<X>} context flag it maps
     * to. Ordered so error messages list them deterministically. Options that are inherently
     * project-wide (openapi, secured, inverseCollections, seedData, rtl) are deliberately absent.
     */
    public static final Map<String, String> SCAFFOLD_OPT_FLAGS = ScaffoldOption.perEntityFlags();

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
        Map<String, Map<String, Object>> summaries = RelationViews.buildSummaries(entities);
        ctx.put(ENTITY_SUMMARIES_KEY, summaries);
        Map<String, List<Map<String, Object>>> inverses = RelationViews.buildInverseRelations(entities, summaries);
        ctx.put(INVERSE_RELATIONS_KEY, inverses);

        List<Map<String, Object>> entityViews = new ArrayList<>(entities.size());
        for (int i = 0; i < entities.size(); i++) {
            Map<String, Object> view = EntityViewModel.of(entities.get(i), summaries, inverses);
            view.put("first", i == 0);
            view.put("last", i == entities.size() - 1);
            entityViews.add(view);
        }
        ctx.put("entities", entityViews);
        // The shared frontend files a field type brings: format.ts (money, instants) and Markdown.tsx.
        // The multi-pick control ships with the first MANY_TO_MANY.
        ctx.put("hasCollectionFields", entities.stream().anyMatch(e -> !e.collections().isEmpty()));
        // …and the read-only table of child rows with the first ONE_TO_MANY.
        ctx.put("hasChildListTables", entities.stream().anyMatch(e -> !e.children().isEmpty()));
        // …and the rows editor with the first composition (rows edited in their parent's form).
        ctx.put("hasCompositionRows", entities.stream()
                .anyMatch(e -> e.children().stream().anyMatch(RelationDefinition::composition)));
        boolean attachments = entities.stream().flatMap(e -> e.fields().stream()).anyMatch(f -> f.type().isAttachment());
        // File and image fields: the attachment package on the backend, the upload call and the
        // AttachmentField on the frontend (both render paths read it).
        ctx.put("hasAttachments", attachments);
        // A field that holds several files: attachment/AttachedFile and the AttachmentsField list.
        ctx.put("hasMultiFiles", entities.stream().flatMap(e -> e.fields().stream()).anyMatch(FieldDefinition::multiple));
        ctx.put("hasFormatHelpers", attachments || entities.stream().flatMap(e -> e.fields().stream())
                .anyMatch(f -> f.type().isMoney() || f.type().isInstant()
                        || f.type() == FieldType.LOCAL_DATE || f.type() == FieldType.LOCAL_DATE_TIME));
        ctx.put("hasMarkdownFields", entities.stream().flatMap(e -> e.fields().stream())
                .anyMatch(f -> f.type().isMarkdown()));
        // Any workflow: the shared WorkflowException, CurrentUser and GET /api/me (both render paths).
        ctx.put("hasWorkflows", entities.stream().anyMatch(e -> e.workflow() != null));
        // An entity listed as a gallery: the shared GalleryView and its word in the view switch.
        ctx.put("hasGalleryViews", entities.stream().anyMatch(e -> EntityViewModel.emittedListViews(e).contains("gallery")));
        // A workflow that logs its moves: the backend's workflow package, the frontend's history list.
        ctx.put("hasWorkflowMoves", entities.stream().anyMatch(e -> e.workflow() != null && e.workflow().trackMoves()));
        // ...which a shell that shows the signed-in user needs too: each render path adds its own.
        ctx.put("usesCurrentUser", entities.stream().anyMatch(e -> e.workflow() != null));
        // Rows that belong to users (access.owners): web/RowAccess, and who each entity's rows are.
        RowAccessContext.putProject(ctx, entities);
        if (Boolean.TRUE.equals(ctx.get("hasRowAccess"))) ctx.put("usesCurrentUser", true);
        // Demo-data seeding order (optScaffoldSeedData): parents before children, writable only.
        List<Map<String, Object>> seedViews = buildSeedOrder(entities, entityViews);
        ctx.put("seedEntities", seedViews);
        ctx.put("hasSeedEntities", !seedViews.isEmpty());
        return ctx;
    }

    /** Internal key under which the entity → wizard page lookup rides in the (frontend) project
     *  context, for {@link #buildEntityContext}. Not referenced by any template. */
    static final String WIZARD_PAGES_KEY = "__wizardPages";

    /** Internal key under which the entity → create page (wizard or form) lookup rides in the
     *  (frontend) project context, for {@link #buildEntityContext}. Not referenced by any template. */
    static final String CREATE_PAGES_KEY = "__createPages";

    /** Internal key under which the entity → record page lookup rides in the (frontend) project
     *  context: a relation whose target has a record page renders as a link to it. Not referenced
     *  by any template. */
    static final String RECORD_PAGES_KEY = "__recordPages";

    /** Internal key under which the entity → import page lookup rides in the (frontend) project
     *  context: a list page's Import goes to the entity's import page. Not referenced by any template. */
    static final String IMPORT_PAGES_KEY = "__importPages";

    /** Internal key under which the names of the entities whose list pages set a presentation
     *  (columns / sort / view / pageSize) ride in the (frontend) project context, for
     *  {@link #buildEntityContext}'s {@code listConfigurable}. Not referenced by any template. */
    static final String LIST_PRESENTATION_KEY = "__listPresentationEntities";

    /** Entities with a list page whose rows open in a side pane. */
    static final String SIDE_DETAIL_KEY = "__sideDetailEntities";

    /** Entities with a list page in the nav, whose list state rides in the route. */
    static final String LIST_STATE_KEY = "__listStateEntities";

    /** Entities with a list page that hides some of its actions. */
    static final String LIST_ACTIONS_KEY = "__listActionEntities";

    /** Internal key under which the entity-summary lookup rides in the project context.
     *  Not referenced by any template. */
    static final String ENTITY_SUMMARIES_KEY = "__entitySummaries";

    /** Internal key under which the inverse-relation lookup (lower(parent) → inverse views) rides
     *  in the project context, so per-entity contexts can resolve their @OneToMany collections. */
    private static final String INVERSE_RELATIONS_KEY = "__inverseRelations";

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

    /**
     * What the rows editor of a composed child draws ({@code <Child>Rows.tsx}): a column per value
     * a client may set and per foreign key but the link to the parent, and which imports the
     * file needs (the generated lint rejects an unused one).
     */
    @SuppressWarnings("unchecked")
    private static void putRows(Map<String, Object> ctx) {
        List<Map<String, Object>> rowFields = new ArrayList<>();
        for (Map<String, Object> field : (List<Map<String, Object>>) ctx.get("fields")) {
            if (Boolean.TRUE.equals(field.get("isPrimaryKey")) || Boolean.TRUE.equals(field.get("isAttachment"))
                    || Boolean.TRUE.equals(field.get("isWorkflowLocked"))) continue;
            Map<String, Object> row = new LinkedHashMap<>(field);
            // Long text is one line in a table cell.
            Object inputType = field.get("inputType");
            row.put("rowInputType", Boolean.TRUE.equals(field.get("isText")) || inputType == null ? "text" : inputType);
            rowFields.add(row);
        }
        List<Map<String, Object>> rowRelations = ((List<Map<String, Object>>) ctx.get("relations")).stream()
                .filter(rel -> !Boolean.TRUE.equals(rel.get("isCompositionLink"))).toList();
        boolean options = rowRelations.stream().anyMatch(rel -> !Boolean.TRUE.equals(rel.get("targetSearchable")));
        ctx.put("rowFields", rowFields);
        ctx.put("rowRelations", rowRelations);
        ctx.put("rowsUseOptions", options);
        ctx.put("rowsUsePicker", rowRelations.stream().anyMatch(rel -> Boolean.TRUE.equals(rel.get("targetSearchable"))));
        ctx.put("rowsUseStrings", options || rowFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isBoolean"))));
        ctx.put("rowsUseInstant", rowFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isInstant"))));
        // A computed cell is shown as it will be saved; a field shown on a condition is a dash while it does not hold.
        ctx.put("rowsUseRules", rowFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isComputed"))
                || Boolean.TRUE.equals(f.get("hasShowWhen"))));
        // …through a link too: the rows editor reads what the parent's form fetched (unknown otherwise).
        ctx.put("rowsReadLinks", Boolean.TRUE.equals(ctx.get("formReadsLinks")));
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
        ctx.putAll(EntityViewModel.of(entity, summaries == null ? Map.of() : summaries,
                inverses == null ? Map.of() : inverses));
        // The lists of rows that point at this entity, as generated: every ONE_TO_MANY it declares,
        // and — under inverseCollections — the ones derived for the links it does not name.
        boolean derive = Boolean.TRUE.equals(projectContext.get("optScaffoldInverse"));
        List<Map<String, Object>> childLists = new ArrayList<>();
        for (Map<String, Object> inv : (List<Map<String, Object>>) ctx.get("inverseRelations")) {
            if (derive || Boolean.TRUE.equals(inv.get("declared"))) childLists.add(inv);
        }
        List<Map<String, Object>> childTables = childLists.stream()
                .filter(inv -> Boolean.TRUE.equals(inv.get("declared"))).toList();
        ctx.put("childLists", childLists);
        ctx.put("hasChildLists", !childLists.isEmpty());
        ctx.put("childTables", childTables);
        ctx.put("hasChildTables", !childTables.isEmpty());
        // A composed child: the relation that is its link to the parent is never asked of a
        // client inside the parent's rows (the parent is the link), so the DTO does not require it.
        ctx.put("isComposedChild", false);
        ctx.put("parentReadsRows", false);
        Map<String, Set<String>> rowsReadBy = (Map<String, Set<String>>) ctx.get(FeatureContext.ROWS_READ_KEY);
        if (inverses != null) {
            for (Map.Entry<String, List<Map<String, Object>>> byParent : inverses.entrySet()) {
                List<Map<String, Object>> ofParent = byParent.getValue();
                for (Map<String, Object> inv : ofParent) {
                    if (!Boolean.TRUE.equals(inv.get("composition"))
                            || !String.valueOf(inv.get("childEntity")).equals(ctx.get("EntityName"))) continue;
                    ctx.put("isComposedChild", true);
                    ctx.put("CompositionLinkName", inv.get("MappedBy"));
                    ctx.put("compositionLinkName", inv.get("mappedBy"));
                    // Its parent's rules or computed fields read these rows: a change made through
                    // this entity's own endpoints runs them again (and the rows editor keeps each
                    // row as it will be saved, so the parent's form follows).
                    Set<String> read = rowsReadBy == null ? null : rowsReadBy.get(byParent.getKey());
                    boolean parentReads = read != null && read.contains(String.valueOf(inv.get("collectionField")));
                    Map<String, Object> parent = summaries == null ? null : summaries.get(byParent.getKey());
                    if (parentReads && parent != null) {
                        ctx.put("parentReadsRows", true);
                        ctx.put("CompositionParent", parent.get("pascal"));
                        ctx.put("CompositionParentPk", parent.get("PkName"));
                        ctx.put("compositionParentLabelJava", TemplateText.escapeJavaLiteral(String.valueOf(parent.get("pascal"))));
                    }
                    ctx.put("rowsDeriveLive", parentReads && (Boolean.TRUE.equals(ctx.get("hasComputedFields"))
                            || Boolean.TRUE.equals(ctx.get("hasShowWhenFields"))));
                    for (Map<String, Object> rel : (List<Map<String, Object>>) ctx.get("relations")) {
                        if (String.valueOf(rel.get("fieldName")).equals(inv.get("mappedBy"))) {
                            rel.put("isCompositionLink", true);
                            rel.put("dtoRequired", false);
                        }
                    }
                    putRows(ctx);
                }
            }
        }
        // The enum label maps those tables read, once per child entity. A list of the entity's
        // own rows (Employee.reports) reads the maps its details already import.
        Map<String, Set<String>> labelMaps = new LinkedHashMap<>();
        for (Map<String, Object> inv : childTables) {
            if (String.valueOf(inv.get("childEntity")).equals(ctx.get("EntityName"))) continue;
            labelMaps.computeIfAbsent((String) inv.get("childKebab"), k -> new LinkedHashSet<>())
                    .addAll((List<String>) inv.get("labelMaps"));
        }
        List<Map<String, Object>> labelMapImports = new ArrayList<>();
        labelMaps.forEach((from, names) -> {
            if (!names.isEmpty()) labelMapImports.add(Map.of("from", from, "names", String.join(", ", names)));
        });
        ctx.put("childTableImports", labelMapImports);
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
            // The form's groups hold the same relations: hand them the linked copies too.
            List<Map<String, Object>> groups = new ArrayList<>();
            for (Map<String, Object> group : (List<Map<String, Object>>) ctx.get("formGroups")) {
                Map<String, Object> gc = new LinkedHashMap<>(group);
                gc.put("groupRelations", ((List<Map<String, Object>>) group.get("groupRelations")).stream()
                        .map(r -> linked.stream().filter(l -> l.get("fieldName").equals(r.get("fieldName"))).findFirst().orElse(r))
                        .toList());
                groups.add(gc);
            }
            ctx.put("formGroups", groups);
        }
        // Page layouts only: the list page can be scoped to one parent through a relation filter
        // (master-detail and record pages), so it takes a `scope` prop.
        ctx.put("pageScopeable", Boolean.TRUE.equals(projectContext.get("hasPages")) && !entity.relations().isEmpty());
        // An entity with a create page: its list page takes `onCreate` (New opens that page instead
        // of the drawer). With a wizard its form also takes `only` (one step's fields).
        Map<String, String> creates = (Map<String, String>) projectContext.get(CREATE_PAGES_KEY);
        ctx.put("hasCreatePage", creates != null && creates.containsKey(entity.name()));
        Map<String, String> wizards = (Map<String, String>) projectContext.get(WIZARD_PAGES_KEY);
        ctx.put("formHasSteps", (wizards != null && wizards.containsKey(entity.name()))
                || Boolean.TRUE.equals(ctx.get("workflowRequiresFields")));
        // A field shown only while its condition holds: the form's guard, with the step's own.
        boolean steps = Boolean.TRUE.equals(ctx.get("formHasSteps"));
        if (ctx.get("fields") instanceof List<?> fieldList) {
            for (Object o : fieldList) {
                Map<String, Object> fv = (Map<String, Object>) o;
                if (!Boolean.TRUE.equals(fv.get("hasShowWhen"))) continue;
                fv.put("guardOpen", "{" + (steps ? "show('" + fv.get("name") + "') && " : "") + fv.get("showWhenTs") + " && (");
                fv.put("guardClose", ")}");
            }
        }
        // An entity with an import page: its list page takes `onImport` (Import opens that page
        // instead of the drawer).
        Map<String, String> imports = (Map<String, String>) projectContext.get(IMPORT_PAGES_KEY);
        ctx.put("hasImportPage", imports != null && imports.containsKey(entity.name()));
        // An entity whose list page sets a presentation: its page takes columns / initialSort /
        // initialView / initialPageSize props (false in backend contexts and for every other entity).
        Set<String> listConfigured = (Set<String>) projectContext.get(LIST_PRESENTATION_KEY);
        ctx.put("listConfigurable", listConfigured != null && listConfigured.contains(entity.name()));
        Set<String> sideDetail = (Set<String>) projectContext.get(SIDE_DETAIL_KEY);
        ctx.put("listSideDetail", sideDetail != null && sideDetail.contains(entity.name()));
        Set<String> listActions = (Set<String>) projectContext.get(LIST_ACTIONS_KEY);
        ctx.put("listActionsConfigurable", listActions != null && listActions.contains(entity.name()));
        Map<String, String> recordPageOf = (Map<String, String>) projectContext.get(RECORD_PAGES_KEY);
        ctx.put("entityHasRecordPage", recordPageOf != null && recordPageOf.keySet().stream()
                .anyMatch(k -> k.equalsIgnoreCase(entity.name())));
        Set<String> listState = (Set<String>) projectContext.get(LIST_STATE_KEY);
        ctx.put("listUrlState", listState != null && listState.contains(entity.name()));
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
        // A deleted row keeps its unique values: the service asks about one before it saves.
        boolean deletedHoldsKeys = Boolean.TRUE.equals(ctx.get("softDeleteApplicable"))
                && ctx.get("uniqueProbes") instanceof List<?> probes && !probes.isEmpty();
        ctx.put("deletedHoldsKeys", deletedHoldsKeys);
        // Audit timestamps (created/updated) only make sense for writable, table-backed entities —
        // a @Subselect view would have to project created_at/updated_at columns that may not exist.
        ctx.put("auditApplicable",
                Boolean.TRUE.equals(ctx.get("optScaffoldAudit")) && mutable);
        // The change log: writable, table-backed, single-key rows (the log addresses a row by its id).
        ctx.put("historyApplicable", Boolean.TRUE.equals(ctx.get("optScaffoldHistory")) && mutable
                && !Boolean.TRUE.equals(ctx.get("isView")) && !Boolean.TRUE.equals(ctx.get("hasCompositePk")));
        // Entity events: the same rows as the change log (an event names its row by one id).
        boolean eventsApplicable = Boolean.TRUE.equals(ctx.get("optScaffoldEvents")) && mutable
                && !Boolean.TRUE.equals(ctx.get("isView")) && !Boolean.TRUE.equals(ctx.get("hasCompositePk"));
        ctx.put("eventsApplicable", eventsApplicable);
        // A soft-deleted row's restore is plain SQL Hibernate does not see: the service reports it.
        ctx.put("eventsRestores", eventsApplicable && Boolean.TRUE.equals(ctx.get("softDeleteApplicable")));
        // A move names its transition in the event, and may e-mail somebody.
        ctx.put("transitionEvents", Boolean.TRUE.equals(ctx.get("hasWorkflow"))
                && (eventsApplicable || Boolean.TRUE.equals(ctx.get("workflowNotifies"))));
        // Optimistic locking (a @Version column, stale updates answered 409) — writable tables only.
        ctx.put("lockingApplicable", Boolean.TRUE.equals(ctx.get("optScaffoldOptimisticLocking"))
                && mutable && !Boolean.TRUE.equals(ctx.get("isView")));
        // CSV import (the csvImport opt, or an import page — which switches the opt on for its
        // entity) creates rows, so it needs a writable entity. The import checks each row's
        // relations through the EntityManager, which the stats rollup also uses.
        boolean importApplicable = Boolean.TRUE.equals(ctx.get("optScaffoldCsvImport")) && mutable;
        ctx.put("importApplicable", importApplicable);
        ctx.put("importValidates", importApplicable && Boolean.TRUE.equals(ctx.get("hasValidation")));
        // A relation set from an id alone is a stub without the target's version, which Hibernate
        // takes for an unsaved row when the target is versioned (optimistic locking) — every save of
        // such a relation failed. The service swaps those stubs for managed references.
        boolean projectLocking = Boolean.TRUE.equals(projectContext.get("optScaffoldOptimisticLocking"));
        boolean lockedRelations = false;
        for (Map<String, Object> rel : (List<Map<String, Object>>) ctx.get("relations")) {
            Map<String, Object> target = summaries == null ? null
                    : summaries.get(String.valueOf(rel.get("targetEntity")).toLowerCase(Locale.ROOT));
            Object override = target == null ? null : target.get("lockingOverride");
            boolean locked = target != null && Boolean.TRUE.equals(target.get("lockable"))
                    && (override != null ? Boolean.TRUE.equals(override) : projectLocking);
            rel.put("targetLocked", locked);
            lockedRelations |= locked;
        }
        ctx.put("hasLockedRelations", lockedRelations && mutable);
        // …and to resolve the ids of a MANY_TO_MANY into the rows they name.
        // The rows a composition saves with this entity. A soft-deleted parent keeps its rows (it can
        // be restored); one that is really deleted takes them with it.
        List<Map<String, Object>> compositions = childLists.stream()
                .filter(inv -> Boolean.TRUE.equals(inv.get("composition"))).toList();
        boolean keepsRows = Boolean.TRUE.equals(ctx.get("softDeleteApplicable"));
        for (Map<String, Object> inv : compositions) {
            inv.put("cascadeJava", keepsRows
                    ? "{jakarta.persistence.CascadeType.PERSIST, jakarta.persistence.CascadeType.MERGE}"
                    : "{jakarta.persistence.CascadeType.PERSIST, jakarta.persistence.CascadeType.MERGE, jakarta.persistence.CascadeType.REMOVE}");
            // A rule may show its message on the rows themselves (under their editor).
            Object withMessages = ctx.get("rowsWithMessages");
            inv.put("showsMessage", withMessages instanceof Set<?> lists && lists.contains(inv.get("collectionField")));
        }
        if (Boolean.TRUE.equals(ctx.get("optScaffoldXlsxExport"))) FeatureContext.putXlsxColumns(ctx);
        // One busy flag for whichever exports the list page offers.
        ctx.put("usesDownload", Boolean.TRUE.equals(ctx.get("optScaffoldCsvExport"))
                || Boolean.TRUE.equals(ctx.get("optScaffoldXlsxExport")));
        boolean hasCompositions = !compositions.isEmpty() && mutable;
        ctx.put("compositions", hasCompositions ? compositions : List.of());
        ctx.put("hasCompositions", hasCompositions);
        ctx.put("usesEntityManager", Boolean.TRUE.equals(ctx.get("statsApplicable")) || importApplicable
                || (lockedRelations && mutable) || (Boolean.TRUE.equals(ctx.get("hasCollections")) && mutable)
                || hasCompositions || (Boolean.TRUE.equals(ctx.get("readsLinks")) && mutable) || deletedHoldsKeys
                || Boolean.TRUE.equals(ctx.get("parentReadsRows")));
        // Rows that belong to users: the service's checks, and the links to such rows it guards.
        RowAccessContext.putEntity(ctx, entity);
        // The detail view words a boolean (t('trueLabel')) and formats dates for the app's locale,
        // besides the audit rows — so it imports t / LOCALE exactly when one of them is drawn
        // (the generated lint rejects an unused import).
        List<Map<String, Object>> detailFields = (List<Map<String, Object>>) ctx.get("fields");
        boolean audit = Boolean.TRUE.equals(ctx.get("auditApplicable"));
        boolean detailBool = detailFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isBoolean")));
        ctx.put("detailUsesT", audit || detailBool);
        ctx.put("detailUsesLocale", audit);
        ctx.put("detailUsesI18n", audit || detailBool);
        // The shared formatters this entity's list and detail call (money, instants), the Markdown
        // renderer its detail draws, and the instant conversion its form does — each imported only
        // where used, for the same lint.
        List<String> formatImports = new ArrayList<>();
        if (detailFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isAttachment")))) formatImports.add("attachmentUrl");
        if (detailFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isDate")))) formatImports.add("formatDate");
        if (detailFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isDateTime")))) formatImports.add("formatDateTime");
        if (detailFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isInstant")))) formatImports.add("formatInstant");
        if (detailFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isMoney")))) formatImports.add("formatMoney");
        ctx.put("usesFormat", !formatImports.isEmpty());
        ctx.put("formatImports", String.join(", ", formatImports));
        ctx.put("hasMarkdownField", detailFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isMarkdown"))));
        ctx.put("formUsesInstant", detailFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isInstant"))
                && !Boolean.TRUE.equals(f.get("isWorkflowStatus"))));
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
        // Inline cell edit (opt-in, frontend only): the table's scalar cells — the same fields bulk
        // edit can set — are edited in place and saved with the entity's PUT, one row at a time.
        ctx.put("inlineEditApplicable",
                Boolean.TRUE.equals(ctx.get("optScaffoldInlineEdit"))
                        && !Boolean.TRUE.equals(ctx.get("hasCompositePk"))
                        && mutable
                        && Boolean.TRUE.equals(ctx.get("viewTable"))
                        && bulkFields != null && !bulkFields.isEmpty());
        // â€¦and so are its sets of linked rows (a many-to-many's cell opens the picker).
        ctx.put("inlineLinks", Boolean.TRUE.equals(ctx.get("inlineEditApplicable"))
                && Boolean.TRUE.equals(ctx.get("hasCollections")));
        // A bulk change can set one of the entity's own sets too.
        ctx.put("bulkSetsApplicable", Boolean.TRUE.equals(ctx.get("bulkUpdateApplicable"))
                && Boolean.TRUE.equals(ctx.get("hasBulkSets")));
        // A row that is really deleted leaves the sets whose other side it is (a soft-deleted one
        // stays in them, for a restore).
        ctx.put("unlinksOnDelete", mutable && Boolean.TRUE.equals(ctx.get("hasInverseSets"))
                && !Boolean.TRUE.equals(ctx.get("softDeleteApplicable")));
        // The row-selection substrate (checkboxes, `selected` state) is shared by both bulk actions,
        // so it is emitted when either is applicable.
        ctx.put("bulkSelectApplicable",
                Boolean.TRUE.equals(ctx.get("bulkDeleteApplicable"))
                        || Boolean.TRUE.equals(ctx.get("bulkUpdateApplicable")));
        // A bulk edit holds its value to the DTO's Bean Validation, like a single save: the service
        // takes the Validator for that, or for the CSV import's row checks.
        boolean bulkValidates = Boolean.TRUE.equals(ctx.get("bulkUpdateApplicable"))
                && Boolean.TRUE.equals(ctx.get("hasValidation"));
        ctx.put("bulkValidates", bulkValidates);
        // …and so does a workflow move, for the fields it asks for.
        boolean transitionValidates = Boolean.TRUE.equals(ctx.get("workflowRequiresFields"))
                && Boolean.TRUE.equals(ctx.get("hasValidation"));
        ctx.put("transitionValidates", transitionValidates);
        // …and a version put back from the change history.
        boolean restoreValidates = Boolean.TRUE.equals(ctx.get("historyApplicable"))
                && Boolean.TRUE.equals(ctx.get("hasValidation"));
        ctx.put("restoreValidates", restoreValidates);
        ctx.put("usesValidator", bulkValidates || transitionValidates || restoreValidates
                || Boolean.TRUE.equals(ctx.get("importValidates")));
        // Reassigning is the ADMIN's when the project can tell roles (an ldap-auth dependency);
        // without one there are no roles and any signed-in user may. The service asks CurrentUser
        // for that, or for a transition limited to roles.
        boolean assignNeedsAdmin = Boolean.TRUE.equals(ctx.get("hasAssignee")) && Boolean.TRUE.equals(ctx.get("hasLdapAuth"));
        ctx.put("assignNeedsAdmin", assignNeedsAdmin);
        ctx.put("serviceUsesCurrentUser", assignNeedsAdmin || Boolean.TRUE.equals(ctx.get("workflowUsesRoles")));
        // The bulk-editable fields a row cannot be without: a bulk edit may not clear them.
        List<String> requiredBulk = bulkFields == null ? List.of() : bulkFields.stream()
                .filter(f -> Boolean.TRUE.equals(f.get("isRequired")))
                .map(f -> "\"" + f.get("name") + "\"").toList();
        ctx.put("hasRequiredBulkFields", !requiredBulk.isEmpty());
        ctx.put("requiredBulkFieldsJava", String.join(", ", requiredBulk));
        // A list page's hideActions only reach a list that has an action to hide (the validator
        // rejects them on a read-only entity without an export).
        boolean hidesActions = Boolean.TRUE.equals(ctx.get("listActionsConfigurable"))
                && (mutable || Boolean.TRUE.equals(ctx.get("optScaffoldCsvExport"))
                        || Boolean.TRUE.equals(ctx.get("optScaffoldXlsxExport")));
        // With ldap-auth the backend checks who may write: the list offers New, Edit, Delete,
        // Import and the bulk bar to those callers only. An entity anyone signed in may write
        // needs no such check, and neither does a project without the dependency.
        // Where everyone sees a row and whose it is changes it, the list asks who the caller is too.
        boolean writeChecked = Boolean.TRUE.equals(ctx.get("hasLdapAuth")) && mutable
                && (!"SIGNED_IN".equals(entity.access().write()) || Boolean.TRUE.equals(ctx.get("listOwnWrites")));
        // The page takes a hideActions prop / asks the caller's roles / has the can() helper.
        ctx.put("listHidesActions", hidesActions);
        ctx.put("writeChecked", writeChecked);
        ctx.put("listActionsConfigurable", hidesActions || writeChecked);
        // The tests option: the entity's API test (backend) or validator test (frontend), from the
        // views GeneratedTests put on the project context.
        GeneratedTests.putEntity(ctx, entity);
        return ctx;
    }

    /**
     * Orders the seedable (writable, table-backed) entities so every {@code MANY_TO_ONE} parent is
     * seeded before its children, and marks each relation with {@code targetSeeded} — true when the
     * target's rows exist by the time this entity is seeded (so the loader can pick a parent), false
     * for self-references, read-only targets, or the back edge of a cycle (left null). Cycles fall
     * back to declaration order. Each entry is a copy of the entity view-model plus
     * {@code seedFirst}/{@code seedLast}.
     *
     * <p>A set of linked rows (a MANY_TO_MANY this entity owns) is filled from the target's rows, so
     * the target comes first too ({@code seedSets}). Rows edited in their parent's form (a
     * composition) are made before the parent and saved with it — two to each ({@code seedComposed}
     * on the child, {@code seedRows} on the parent) — so a parent's rule or total over its rows
     * has rows to read.
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
        // Child entity (lower-cased) → the parent whose rows it is, and the link that says so.
        Map<String, EntityDefinition> parentOf = new LinkedHashMap<>();
        Map<String, String> parentLinkOf = new LinkedHashMap<>();
        for (EntityDefinition parent : entities) {
            for (RelationDefinition list : parent.children()) {
                if (!list.composition() || parent.readOnly()) continue;
                parentOf.put(list.targetEntity().toLowerCase(Locale.ROOT), parent);
                parentLinkOf.put(list.targetEntity().toLowerCase(Locale.ROOT), list.mappedBy());
            }
        }
        Set<String> placed = new LinkedHashSet<>();
        List<Integer> order = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Integer next = null;
            for (Integer idx : remaining) {
                EntityDefinition e = entities.get(idx);
                String self = e.name().toLowerCase(Locale.ROOT);
                List<String> needs = new ArrayList<>();
                for (RelationDefinition rel : e.relations()) {
                    // A composed row does not wait for its parent: the parent waits for it.
                    if (rel.fieldName().equals(parentLinkOf.get(self))) continue;
                    needs.add(rel.targetEntity().toLowerCase(Locale.ROOT));
                }
                for (RelationDefinition set : e.ownedCollections()) needs.add(set.targetEntity().toLowerCase(Locale.ROOT));
                for (RelationDefinition list : e.children()) {
                    if (list.composition()) needs.add(list.targetEntity().toLowerCase(Locale.ROOT));
                }
                boolean ready = true;
                for (String t : needs) {
                    Integer ti = indexByLower.get(t);
                    boolean seedableTarget = ti != null && !entities.get(ti).readOnly();
                    if (seedableTarget && !t.equals(self) && !placed.contains(t)) {
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
                String self = e.name().toLowerCase(Locale.ROOT);
                // A composed row gets its parent when the parent takes it, below.
                boolean toParent = e.relations().get(r).fieldName().equals(parentLinkOf.get(self));
                rc.put("targetSeeded", seededSoFar.contains(t) && !toParent);
                // Demo rows a rule is broken by are left out, so a parent with rules may have none.
                rc.put("targetMayHaveNoRows", seededSoFar.contains(t) && !toParent
                        && !entities.get(indexByLower.get(t)).allRules().isEmpty());
                relCopies.add(rc);
            }
            sv.put("relations", relCopies);
            String lower = e.name().toLowerCase(Locale.ROOT);
            sv.put("seedComposed", parentOf.containsKey(lower));
            sv.put("seedParent", parentOf.containsKey(lower) ? Naming.toPascalCase(parentOf.get(lower).name()) : null);
            List<Map<String, Object>> seedSets = new ArrayList<>();
            for (RelationDefinition set : e.ownedCollections()) {
                String t = set.targetEntity().toLowerCase(Locale.ROOT);
                // Rows made for a parent are not saved yet: nothing links to them here.
                if (!seededSoFar.contains(t) || parentOf.containsKey(t)) continue;
                Map<String, Object> ss = new LinkedHashMap<>();
                ss.put("FieldName", Naming.toPascalCase(set.fieldName()));
                ss.put("targetEntityCamel", Naming.toCamelCase(set.targetEntity()));
                seedSets.add(ss);
            }
            sv.put("seedSets", seedSets);
            List<Map<String, Object>> seedRows = new ArrayList<>();
            for (RelationDefinition list : e.children()) {
                String t = list.targetEntity().toLowerCase(Locale.ROOT);
                if (!list.composition() || !seededSoFar.contains(t) || parentOf.get(t) != e) continue;
                Map<String, Object> sr = new LinkedHashMap<>();
                sr.put("CollectionField", Naming.toPascalCase(list.fieldName()));
                sr.put("childCamel", Naming.toCamelCase(list.targetEntity()));
                sr.put("MappedBy", Naming.toPascalCase(list.mappedBy()));
                seedRows.add(sr);
            }
            sv.put("seedRows", seedRows);
            sv.put("seedFirst", n == 0);
            sv.put("seedLast", n == order.size() - 1);
            seeds.add(sv);
            seededSoFar.add(e.name().toLowerCase(Locale.ROOT));
        }
        return seeds;
    }
}
