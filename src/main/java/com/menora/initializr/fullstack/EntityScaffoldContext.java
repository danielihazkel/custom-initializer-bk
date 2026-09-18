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
    private static Map<String, Object> kanbanColumn(String value, String label) {
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("value", value);
        col.put("label", label);
        return col;
    }

    /** Escapes a string for embedding in a Java or JS double-quoted string literal
     *  (backslash and double-quote only — both languages share C-style escaping). */
    private static String escapeStringLiteral(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
                    kanbanColumns.add(kanbanColumn(String.valueOf(ev.get("value")), String.valueOf(ev.get("value"))));
                }
            } else { // boolean breakdown — two fixed lanes
                kanbanColumns.add(kanbanColumn("true", "True"));
                kanbanColumns.add(kanbanColumn("false", "False"));
            }
        }
        view.put("kanbanColumns", kanbanColumns);

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
            ff.put("isRelationFilter", false);
            filterFieldViews.add(ff);
        }
        // (relation filters are appended below, once the relation view-models exist)

        // The set of views the page actually generates: the user-requested listViews, intersected
        // with what this entity's fields support (table/cards always; kanban needs a breakdown field
        // + a writable entity; calendar needs a temporal field), order preserved. Falls back to
        // [table] if nothing requested is supported. A runtime toggle is emitted only for 2+ views;
        // initialView is the first. viewModeType is the TS union the template seeds useState with.
        Set<String> requested = new LinkedHashSet<>(entity.listViews());
        List<String> emitted = new ArrayList<>();
        if (requested.contains("table")) emitted.add("table");
        if (requested.contains("cards")) emitted.add("cards");
        if (requested.contains("kanban") && kanbanApplicable) emitted.add("kanban");
        if (requested.contains("calendar") && calendarApplicable) emitted.add("calendar");
        if (emitted.isEmpty()) emitted.add("table");
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
            for (String v : f.enumValues()) {
                String constant = v.toUpperCase(Locale.ROOT);
                if (!seen.add(constant)) continue;
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("value", constant);
                values.add(ev);
            }
            for (int i = 0; i < values.size(); i++) {
                values.get(i).put("last", i == values.size() - 1);
            }
            fv.put("enumValues", values);
        } else {
            fv.put("enumValues", List.of());
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
