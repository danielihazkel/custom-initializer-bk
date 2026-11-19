package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;
import com.menora.initializr.sql.SqlNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Row-level access in the view-model: what the generated service, the attachment check, the live
 * stream and the frontend read to keep a user to their own rows ({@code access.owners} /
 * {@code access.rows}, settled by {@link RowAccessRules}).
 *
 * <p>An entity's rows are limited by its own owners, or — a composed child, whose rows are part
 * of its parent — by its parent's, read through the composition link. Either way the answer to
 * "is this row the caller's" is written four ways here, from one description: a criteria
 * predicate (the list), a Java test on a loaded row, JPQL over an alias (a link to such a row)
 * and native SQL over an alias (a soft-deleted row, an attachment's holder).
 */
final class RowAccessContext {

    private RowAccessContext() {}

    /** Under this key the rules ride in the project context, for {@link #putEntity}. No template reads it. */
    static final String KEY = "__rowAccess";

    /**
     * Whose an entity's rows are: by the owners of {@code holder} — the entity itself, or the
     * parent that composes it, reached through the entity's {@code link} (null for itself).
     */
    record Rule(EntityDefinition holder, RelationDefinition link) {

        boolean ownOnly() {
            return holder.access().ownRowsOnly();
        }

        boolean viaParent() {
            return link != null;
        }
    }

    private record Rules(Map<String, Rule> byEntity, Map<String, EntityDefinition> entities) {}

    /** Entity name → its rule, for every entity whose rows belong to users (in the request's order). */
    static Map<String, Rule> rulesOf(List<EntityDefinition> entities) {
        Map<String, EntityDefinition> byName = new LinkedHashMap<>();
        for (EntityDefinition e : entities) byName.put(e.name(), e);
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (EntityDefinition e : entities) {
            if (e.access().rowLimited()) rules.put(e.name(), new Rule(e, null));
        }
        for (EntityDefinition parent : entities) {
            if (!parent.access().rowLimited()) continue;
            for (RelationDefinition list : parent.children()) {
                EntityDefinition child = byName.get(list.targetEntity());
                if (!list.composition() || child == null || list.mappedBy() == null) continue;
                child.relations().stream().filter(r -> r.fieldName().equals(list.mappedBy())).findFirst()
                        .ifPresent(link -> rules.put(child.name(), new Rule(parent, link)));
            }
        }
        return rules;
    }

    /**
     * {@code hasRowAccess} (any entity's rows belong to users: {@code web/RowAccess} and its 403),
     * {@code hasOwnWrites} (some entity shows every row and lets its owners change it: the
     * frontend asks who the caller is) and the lookup {@link #putEntity} reads. Both render paths.
     */
    static void putProject(Map<String, Object> ctx, List<EntityDefinition> entities) {
        Map<String, Rule> rules = rulesOf(entities);
        Map<String, EntityDefinition> byName = new LinkedHashMap<>();
        for (EntityDefinition e : entities) byName.put(e.name(), e);
        ctx.put(KEY, new Rules(rules, byName));
        ctx.put("hasRowAccess", !rules.isEmpty());
        ctx.put("hasOwnWrites", rules.values().stream().anyMatch(r -> !r.ownOnly()));
    }

    /** The per-entity keys: see the class comment and each key below. */
    static void putEntity(Map<String, Object> ctx, EntityDefinition entity) {
        Rules all = ctx.get(KEY) instanceof Rules rules ? rules : new Rules(Map.of(), Map.of());
        Rule rule = all.byEntity().get(entity.name());
        boolean limited = rule != null;
        boolean mutable = Boolean.TRUE.equals(ctx.get("mutable"));
        ctx.put("rowLimited", limited);
        ctx.put("rowsOwnOnly", limited && rule.ownOnly());
        ctx.put("rowsOwnWrites", limited && !rule.ownOnly());
        ctx.put("rowsViaParent", limited && rule.viaParent());
        boolean restoresOwned = limited && Boolean.TRUE.equals(ctx.get("softDeleteApplicable"));
        if (limited) {
            ctx.put("rowMineSpecJava", specMine(rule));
            ctx.put("rowMineJava", javaMine(rule, "row"));
            ctx.put("rowOwnersText", ownersText(rule));
            // A row its user creates: theirs by the audit column, or by an owner field left empty.
            boolean self = !rule.viaParent();
            boolean byCreating = self && rule.holder().access().ownedByCreator();
            String assignee = entity.workflow() == null ? null : entity.workflow().assigneeField();
            List<Map<String, Object>> fill = new ArrayList<>();
            if (self && !byCreating) {
                for (String owner : rule.holder().access().owners()) {
                    if (owner.equals(assignee)) continue; // create() empties it
                    fill.add(new LinkedHashMap<>(Map.of("Name", Naming.toPascalCase(owner), "name", owner)));
                }
            }
            ctx.put("rowOwnsOnCreate", mutable && self && !byCreating);
            ctx.put("rowFillOwners", fill);
            ctx.put("rowFillNames", String.join(" or ", fill.stream().map(f -> String.valueOf(f.get("name"))).toList()));
            if (rule.viaParent()) {
                EntityDefinition parent = rule.holder();
                ctx.put("RowParentEntity", parent.name());
                ctx.put("RowParentLink", Naming.toPascalCase(rule.link().fieldName()));
                ctx.put("rowParentIdName", Naming.toCamelCase(rule.link().fieldName()) + "Id");
                ctx.put("RowParentPkName", Naming.toPascalCase(keyOf(parent).name()));
                ctx.put("rowParentMineJava", javaMine(new Rule(parent, null), "parent"));
            }
            if (restoresOwned) {
                // The restriction hides a deleted row from every entity query: asked natively.
                ctx.put("rowOwnedSqlJava", SqlNames.javaLiteral("select count(*) from " + SchemaNames.tableRefOf(entity)
                        + " t where t." + SchemaNames.columnOf(keyOf(entity)) + " = :id and " + sqlMine(rule, entity, "t")));
            }
        }
        ctx.put("rowRestoresOwned", restoresOwned);

        // Links to rows a user may not see: a link to one is a link to no row.
        List<Map<String, Object>> ownLinks = new ArrayList<>();
        List<Map<String, Object>> ownSets = new ArrayList<>();
        if (mutable) {
            for (RelationDefinition rel : entity.relations()) {
                Rule target = all.byEntity().get(rel.targetEntity());
                if (target == null || !target.ownOnly()) continue;
                // A composed child's link to its parent is checked as the parent it is.
                if (limited && rule.viaParent() && rule.link().fieldName().equals(rel.fieldName())) continue;
                ownLinks.add(linkView(rel, target, all));
            }
            for (RelationDefinition set : entity.collections()) {
                Rule target = all.byEntity().get(set.targetEntity());
                if (target != null && target.ownOnly()) ownSets.add(linkView(set, target, all));
            }
        }
        ctx.put("ownLinks", ownLinks);
        ctx.put("ownSets", ownSets);
        boolean checksLinks = !ownLinks.isEmpty() || !ownSets.isEmpty();
        ctx.put("hasOwnLinks", checksLinks);
        ctx.put("usesRowAccess", limited || checksLinks);
        if (checksLinks || restoresOwned || (limited && rule.viaParent() && mutable)) ctx.put("usesEntityManager", true);
    }

    private static Map<String, Object> linkView(RelationDefinition rel, Rule target, Rules all) {
        EntityDefinition entity = all.entities().get(rel.targetEntity());
        FieldDefinition key = keyOf(entity);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("FieldName", Naming.toPascalCase(rel.fieldName()));
        v.put("fieldName", rel.fieldName());
        v.put("idName", Naming.toCamelCase(rel.fieldName()) + "Id");
        v.put("targetEntity", entity.name());
        v.put("TargetPkName", Naming.toPascalCase(key.name()));
        v.put("targetPkName", key.name());
        v.put("mineJpql", jpqlMine(target, "t"));
        return v;
    }

    static FieldDefinition keyOf(EntityDefinition entity) {
        return entity.fields().stream().filter(FieldDefinition::primaryKey).findFirst().orElseThrow();
    }

    /** {@code cb.or(cb.equal(root.get("createdBy"), me), …)} — over {@code root}, {@code cb} and {@code me}. */
    static String specMine(Rule rule) {
        String path = rule.viaParent() ? "root.get(\"" + rule.link().fieldName() + "\")" : "root";
        List<String> tests = new ArrayList<>();
        for (String owner : rule.holder().access().owners()) tests.add("cb.equal(" + path + ".get(\"" + owner + "\"), me)");
        return tests.size() == 1 ? tests.get(0) : "cb.or(" + String.join(", ", tests) + ")";
    }

    /** {@code me.equals(row.getCreatedBy()) || …} — over a loaded {@code row} and a non-null {@code me}. */
    static String javaMine(Rule rule, String row) {
        String holder = rule.viaParent() ? row + ".get" + Naming.toPascalCase(rule.link().fieldName()) + "()" : row;
        List<String> tests = new ArrayList<>();
        for (String owner : rule.holder().access().owners()) {
            tests.add("me.equals(" + holder + ".get" + Naming.toPascalCase(owner) + "())");
        }
        String any = String.join(" || ", tests);
        return rule.viaParent() ? holder + " != null && (" + any + ")" : any;
    }

    /** {@code t.createdBy = :me or …} — over a JPQL alias. */
    static String jpqlMine(Rule rule, String alias) {
        String holder = rule.viaParent() ? alias + "." + rule.link().fieldName() : alias;
        List<String> tests = new ArrayList<>();
        for (String owner : rule.holder().access().owners()) tests.add(holder + "." + owner + " = :me");
        return String.join(" or ", tests);
    }

    /**
     * {@code (t.created_by = :me or …)} over an alias of {@code entity}'s table — for a composed
     * child, an {@code exists} over the parent it belongs to. Names as the entity's own native SQL
     * writes them; not yet Java-escaped.
     */
    static String sqlMine(Rule rule, EntityDefinition entity, String alias) {
        if (!rule.viaParent()) return ownersSql(rule.holder(), alias);
        EntityDefinition parent = rule.holder();
        return "exists (select 1 from " + SchemaNames.tableRefOf(parent) + " p where p." + SchemaNames.columnOf(keyOf(parent))
                + " = " + alias + "." + SchemaNames.joinColumnOf(rule.link()) + " and " + ownersSql(parent, "p") + ")";
    }

    private static String ownersSql(EntityDefinition holder, String alias) {
        List<String> tests = new ArrayList<>();
        for (String owner : holder.access().owners()) tests.add(alias + "." + ownerColumn(holder, owner) + " = :me");
        return "(" + String.join(" or ", tests) + ")";
    }

    /** The column an owner is kept in: the audit column, or the field's own. */
    static String ownerColumn(EntityDefinition holder, String owner) {
        if (owner.equals(EntityDefinition.Access.CREATED_BY)) return "created_by";
        return SchemaNames.columnOf(holder.fields().stream().filter(f -> f.name().equals(owner)).findFirst().orElseThrow());
    }

    /** "whoever created it or its assignee" — for a comment and a message. */
    private static String ownersText(Rule rule) {
        List<String> names = new ArrayList<>();
        for (String owner : rule.holder().access().owners()) {
            names.add(owner.equals(EntityDefinition.Access.CREATED_BY) ? "whoever created it" : "its " + owner);
        }
        String text = String.join(" or ", names);
        return rule.viaParent() ? "the " + rule.holder().name() + " it belongs to: " + text : text;
    }
}
