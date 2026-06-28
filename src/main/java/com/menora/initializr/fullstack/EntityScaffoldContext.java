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
        view.put("fields", fieldViews);
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
            if (Boolean.TRUE.equals(fv.get("isString")) || Boolean.TRUE.equals(fv.get("isText"))) {
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
            boolean isEnumF = Boolean.TRUE.equals(fv.get("isEnum"));
            boolean isBoolF = Boolean.TRUE.equals(fv.get("isBoolean"));
            boolean isTemporalF = Boolean.TRUE.equals(fv.get("isTemporal"));
            boolean isNumericF = Boolean.TRUE.equals(fv.get("isNumeric"));
            if (!(isEnumF || isBoolF || isTemporalF || isNumericF)) continue;
            Map<String, Object> ff = new LinkedHashMap<>();
            ff.put("name", fv.get("name"));
            ff.put("Name", fv.get("Name"));
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
            rv.put("required", rel.required());
            rv.put("isManyToOne", rel.type() == RelationType.MANY_TO_ONE);
            rv.put("last", i == entity.relations().size() - 1);
            relationViews.add(rv);
        }
        boolean hasRequiredRelations = relationViews.stream()
                .anyMatch(m -> Boolean.TRUE.equals(m.get("required")));
        view.put("relations", relationViews);
        view.put("hasRelations", !relationViews.isEmpty());

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
        fv.put("Name", Naming.toPascalCase(f.name()));
        fv.put("column", Naming.toSnakeCase(f.name()));

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
        fv.put("hasMin", f.min() != null);
        fv.put("min", f.min());
        fv.put("hasMax", f.max() != null);
        fv.put("max", f.max());
        // String constraints. The pattern is injected into Java (@Pattern(regexp="..")) and JS
        // ("..") string literals, so backslashes and double-quotes are escaped once here — the
        // C-style escaping is valid in both languages.
        boolean hasPattern = f.pattern() != null && !f.pattern().isBlank();
        fv.put("hasPattern", hasPattern);
        fv.put("pattern", f.pattern());
        fv.put("patternEscaped", hasPattern ? escapeStringLiteral(f.pattern()) : null);
        fv.put("isEmail", f.email());

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
}
