package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;
import com.menora.initializr.sql.SqlNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The lists of rows that point at an entity (declared ONE_TO_MANY and derived inverse
 * collections), their columns, and the per-entity summaries other entities' views read.
 * Part of {@link EntityScaffoldContext}, split out by area.
 */
final class RelationViews {

    private RelationViews() {}

    /**
     * Derives inverse ({@code @OneToMany}) collections from the owning {@code MANY_TO_ONE} relations:
     * for each child entity A with a {@code MANY_TO_ONE} to parent B, B gets a read-only collection of
     * A. Keyed by {@code lower(parentName)}. The collection is named after the pluralized child (e.g.
     * a {@code Customer} targeted by {@code Order.customer} gets an {@code orders} collection with
     * {@code mappedBy = "customer"}).
     */
    /**
     * The parent-side collection a child's MANY_TO_ONE derives: the child's plural
     * ({@code orderLines}). A child with two links to one parent ({@code Transfer.source} and
     * {@code Transfer.target}, both an {@code Account}) would give that parent the same name twice,
     * so each is told apart by its relation: {@code transfersBySource}, {@code transfersByTarget}.
     */
    static String inverseCollectionName(EntityDefinition child, RelationDefinition rel) {
        String plural = Naming.pluralize(Naming.toCamelCase(child.name()));
        long toSameParent = child.relations().stream()
                .filter(r -> r.type() == RelationType.MANY_TO_ONE
                        && r.targetEntity().equalsIgnoreCase(rel.targetEntity()))
                .count();
        return toSameParent > 1 ? plural + "By" + Naming.toPascalCase(rel.fieldName()) : plural;
    }

    /**
     * The ONE_TO_MANY the parent declares over this link, or null: the same collection, under the
     * name the model gave it, generated whether or not {@code inverseCollections} is on.
     */
    static RelationDefinition declaredListOf(List<EntityDefinition> entities, EntityDefinition child,
                                             RelationDefinition rel) {
        for (EntityDefinition parent : entities) {
            if (!parent.name().equalsIgnoreCase(rel.targetEntity())) continue;
            for (RelationDefinition list : parent.children()) {
                if (list.targetEntity().equalsIgnoreCase(child.name())
                        && rel.fieldName().equalsIgnoreCase(list.mappedBy())) return list;
            }
        }
        return null;
    }

    /** Per parent entity (lower-cased name), the collection names its children's relations derive
     *  — those the parent does not name itself with a ONE_TO_MANY. */
    static Map<String, List<String>> inverseCollectionNames(List<EntityDefinition> entities) {
        Map<String, List<String>> byLower = new LinkedHashMap<>();
        for (EntityDefinition child : entities) {
            for (RelationDefinition rel : child.relations()) {
                if (rel.type() != RelationType.MANY_TO_ONE) continue;
                if (declaredListOf(entities, child, rel) != null) continue;
                byLower.computeIfAbsent(rel.targetEntity().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(inverseCollectionName(child, rel));
            }
        }
        return byLower;
    }

    static Map<String, List<Map<String, Object>>> buildInverseRelations(
            List<EntityDefinition> entities, Map<String, Map<String, Object>> summaries) {
        Map<String, List<Map<String, Object>>> byLower = new LinkedHashMap<>();
        for (EntityDefinition child : entities) {
            for (RelationDefinition rel : child.relations()) {
                if (rel.type() != RelationType.MANY_TO_ONE) continue;
                String parentLower = rel.targetEntity().toLowerCase(Locale.ROOT);
                String childCamel = Naming.toCamelCase(child.name());
                // A ONE_TO_MANY the parent declares is this collection under the model's own name,
                // generated whatever the option says, and shown as a table in the parent's details.
                RelationDefinition declared = declaredListOf(entities, child, rel);
                String coll = declared != null ? Naming.toCamelCase(declared.fieldName()) : inverseCollectionName(child, rel);
                Map<String, Object> inv = new LinkedHashMap<>();
                inv.put("declared", declared != null);
                // The child's rows are part of the parent: its form edits them, its save carries them.
                boolean composed = declared != null && declared.composition();
                inv.put("composition", composed);
                if (composed) {
                    Map<String, Object> childSummary = summaries.get(child.name().toLowerCase(Locale.ROOT));
                    inv.put("MappedBy", Naming.toPascalCase(rel.fieldName()));
                    inv.put("ChildPkName", childSummary != null ? childSummary.get("PkName") : "Id");
                    inv.put("childPkJavaType", childSummary != null ? childSummary.get("pkJavaType") : "Long");
                    // The child's other foreign keys: a row carries each as an id stub, which the
                    // parent's service swaps for a managed reference before saving.
                    List<Map<String, Object>> refs = new ArrayList<>();
                    for (RelationDefinition other : child.relations()) {
                        if (other == rel || other.type() != RelationType.MANY_TO_ONE) continue;
                        Map<String, Object> target = summaries.get(other.targetEntity().toLowerCase(Locale.ROOT));
                        Map<String, Object> ref = new LinkedHashMap<>();
                        ref.put("FieldName", Naming.toPascalCase(other.fieldName()));
                        ref.put("targetEntity", Naming.toPascalCase(other.targetEntity()));
                        ref.put("TargetPkName", target != null ? target.get("PkName") : "Id");
                        refs.add(ref);
                    }
                    inv.put("childRefs", refs);
                    // Rules, show-when or computed fields: the parent's save runs them on each row.
                    inv.put("childHasRules", child.hasRowLogic());
                }
                if (declared != null) {
                    inv.put("listTitleTs", TemplateText.tsLiteralBody(declared.label() != null && !declared.label().isBlank()
                            ? declared.label() : TemplateText.humanizeIdentifier(coll)));
                    inv.put("childPath", "/api/" + Naming.pluralize(Naming.toKebabCase(child.name())));
                    inv.put("childKebab", Naming.toKebabCase(child.name()));
                    inv.put("filterParam", Naming.toCamelCase(rel.fieldName()) + "Id");
                    List<Map<String, Object>> columns = childColumns(child, rel, summaries);
                    inv.put("childColumns", columns);
                    inv.put("labelMaps", columns.stream().filter(c -> c.get("enumTypeName") != null)
                            .map(c -> c.get("enumTypeName") + "Labels").toList());
                }
                inv.put("childEntity", Naming.toPascalCase(child.name()));
                inv.put("childEntityCamel", childCamel);
                inv.put("mappedBy", Naming.toCamelCase(rel.fieldName()));
                inv.put("collectionField", coll);
                inv.put("CollectionField", Naming.toPascalCase(coll));
                // What the UI calls the collection (its count column): the declared ONE_TO_MANY's
                // label, else its name as before.
                TemplateText.putText(inv, "collectionLabel", declared != null && declared.label() != null && !declared.label().isBlank()
                        ? declared.label() : Naming.toPascalCase(coll));
                // SQL names for the parent's @Formula child count: the child's table (custom name
                // or the default snake-plural, schema-qualified when set) and its FK column —
                // the same derivations the child's own @Table/@JoinColumn use.
                inv.put("childTableRef", SqlNames.javaLiteral(SchemaNames.tableRefOf(child)));
                inv.put("childJoinColumn", SqlNames.javaLiteral(SchemaNames.joinColumnOf(rel)));
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

    /** How many columns of its child rows a parent's details show. */
    static final int CHILD_LIST_COLUMNS = 6;

    /**
     * The columns of a ONE_TO_MANY's table in the parent's details: the child's short values in
     * the model's order (no generated key, long text or attachment), then its other foreign keys
     * by label — the link back to the parent is the same on every row, so it is left out.
     */
    private static List<Map<String, Object>> childColumns(EntityDefinition child, RelationDefinition link,
                                                          Map<String, Map<String, Object>> summaries) {
        String childPascal = Naming.toPascalCase(child.name());
        List<Map<String, Object>> columns = new ArrayList<>();
        for (FieldDefinition f : child.fields()) {
            if (f.primaryKey() && f.generated()) continue;
            if (f.type() == FieldType.TEXT || f.type().isMarkdown() || f.type().isAttachment()) continue;
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("key", f.name());
            col.put("labelTs", TemplateText.escapeTsSingleQuoted(
                    f.label() != null && !f.label().isBlank() ? f.label() : Naming.toPascalCase(f.name())));
            col.put("alt", null);
            col.put("hasAlt", false);
            col.put("isEnum", f.type() == FieldType.ENUM);
            col.put("enumTypeName", f.type() == FieldType.ENUM
                    ? childPascal + Naming.toPascalCase(f.name()) + "Type" : null);
            columns.add(col);
        }
        for (RelationDefinition r : child.relations()) {
            if (r == link) continue;
            String name = Naming.toCamelCase(r.fieldName());
            Map<String, Object> target = summaries.get(r.targetEntity().toLowerCase(Locale.ROOT));
            boolean labelled = target != null && target.get("labelField") != null;
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("key", labelled ? name + "Label" : name + "Id");
            col.put("labelTs", TemplateText.tsLiteralBody(TemplateText.labelOf(r)));
            col.put("alt", labelled ? name + "Id" : null);
            col.put("hasAlt", labelled);
            col.put("isEnum", false);
            col.put("enumTypeName", null);
            columns.add(col);
        }
        return columns.size() > CHILD_LIST_COLUMNS ? new ArrayList<>(columns.subList(0, CHILD_LIST_COLUMNS)) : columns;
    }

    /** Builds a {@code lower(name) → summary} lookup with each entity's PK type/name and
     *  naming variants, so a relation can resolve its target's FK id type and class name. */
    static Map<String, Map<String, Object>> buildSummaries(List<EntityDefinition> entities) {
        Map<String, Map<String, Object>> summaries = new LinkedHashMap<>();
        for (EntityDefinition e : entities) {
            FieldDefinition pk = e.fields().stream()
                    .filter(FieldDefinition::primaryKey).findFirst().orElse(null);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("pascal", Naming.toPascalCase(e.name()));
            s.put("camel", Naming.toCamelCase(e.name()));
            // What a page over this entity asks of the caller (effectiveRoles).
            s.put("readLevel", e.access().read());
            s.put("writeLevel", e.access().write());
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
            // The list's `q` text search covers the label field: a link to this entity can be picked
            // by typing its name (RelationPicker) instead of from a one-page <select>.
            s.put("labelSearchable", labelField != null && labelField.searchable());
            // SQL names for a referencing entity's @Formula label subselect: this entity's table
            // (custom name or default snake-plural, schema-qualified) and the PK/label columns —
            // the same derivations its own @Table/@Column use.
            s.put("tableRef", SqlNames.javaLiteral(SchemaNames.tableRefOf(e)));
            s.put("pkColumn", pk == null ? "id" : SqlNames.javaLiteral(SchemaNames.columnOf(pk)));
            s.put("labelColumn", labelField == null ? null : SqlNames.javaLiteral(SchemaNames.columnOf(labelField)));
            // Whether optimistic locking can apply to it (a writable table) and its own override:
            // a relation to a versioned entity must point at a managed reference, not an id stub.
            s.put("lockable", !e.readOnly() && !e.isView());
            s.put("lockingOverride", e.opts().get("optimisticLocking"));
            summaries.put(e.name().toLowerCase(Locale.ROOT), s);
        }
        return summaries;
    }
}
