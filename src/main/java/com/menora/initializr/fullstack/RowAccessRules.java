package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Row-level access ({@code access.owners} / {@code access.rows}): whose a row is, checked once an
 * entity's fields, workflow and compositions are known. Part of {@link FullstackRequestValidator}.
 *
 * <p>An owner is {@code createdBy} — whoever created the row, which needs the audit columns, so
 * the entity's audit is turned on — or a stored text field that holds a user id. A row is a
 * caller's when any owner names them. Admins are never limited.
 */
final class RowAccessRules {

    private RowAccessRules() {}

    /** The entities with their owners settled (names as declared, audit on where the creator owns). */
    static List<EntityDefinition> resolve(List<EntityDefinition> entities) {
        // Child entity → the parent whose form edits its rows.
        Map<String, String> composedBy = composedBy(entities);
        List<EntityDefinition> out = new ArrayList<>(entities.size());
        for (int ei = 0; ei < entities.size(); ei++) {
            EntityDefinition e = entities.get(ei);
            if (!e.access().rowLimited()) {
                out.add(e);
                continue;
            }
            try {
                out.add(settle(e, composedBy.get(e.name())));
            } catch (WizardArgumentException problem) {
                throw problem.at("access").at("entities[" + ei + "]");
            }
        }
        return out;
    }

    /** Child entity name → its composing parent's name. */
    static Map<String, String> composedBy(List<EntityDefinition> entities) {
        Map<String, String> composedBy = new LinkedHashMap<>();
        for (EntityDefinition parent : entities) {
            for (RelationDefinition list : parent.children()) {
                if (list.composition()) composedBy.putIfAbsent(list.targetEntity(), parent.name());
            }
        }
        return composedBy;
    }

    private static EntityDefinition settle(EntityDefinition e, String parent) {
        EntityDefinition.Access access = e.access();
        String where = "Entity '" + e.name() + "' access: ";
        if (e.viewQuery() != null) {
            throw new WizardArgumentException(where + "a SELECT-backed view has no rows of its own to belong to anyone").on("owners");
        }
        if (parent != null) {
            throw new WizardArgumentException(where + "its rows are part of '" + parent
                    + "' (a composition) and follow that entity's owners — set them there").on("owners");
        }
        List<String> owners = new ArrayList<>();
        for (int oi = 0; oi < access.owners().size(); oi++) {
            String name;
            try {
                name = owner(e, access.owners().get(oi));
            } catch (WizardArgumentException problem) {
                throw problem.on("owners[" + oi + "]");
            }
            if (owners.contains(name)) {
                throw new WizardArgumentException(where + "owner '" + name + "' is named twice").on("owners[" + oi + "]");
            }
            owners.add(name);
        }
        boolean creator = owners.contains(EntityDefinition.Access.CREATED_BY);
        if ("ADMIN".equals(access.read())) {
            throw new WizardArgumentException(where + "only admins read '" + e.name()
                    + "', and admins are never limited — owners have nobody to limit").on("owners");
        }
        if (access.ownWritesOnly() && "ADMIN".equals(access.write())) {
            throw new WizardArgumentException(where + "rows OWN_WRITES limits who changes a row, and only admins change '"
                    + e.name() + "' — set write to USER or SIGNED_IN, or rows to OWN").on("rows");
        }
        if (e.readOnly() && access.ownWritesOnly()) {
            throw new WizardArgumentException(where + "'" + e.name() + "' is read-only: there are no changes for OWN_WRITES to limit")
                    .on("rows");
        }
        if (e.readOnly() && creator) {
            throw new WizardArgumentException(where + "'" + e.name()
                    + "' is read-only: the application creates none of its rows, so none has a creator").on("owners");
        }
        if (creator && Boolean.FALSE.equals(e.opts().get("audit"))) {
            throw new WizardArgumentException(where + "createdBy as an owner needs the audit columns, which this entity turns off (opts.audit: false)")
                    .on("owners");
        }
        String assignee = e.workflow() == null ? null : e.workflow().assigneeField();
        if (assignee != null && owners.size() == 1 && owners.get(0).equals(assignee)) {
            // create() empties the assignee: whoever makes a row could never see or change it.
            throw new WizardArgumentException(where + "the only owner is the workflow's assignee ('" + assignee
                    + "'), which a new row never has — whoever creates a row could not own it. Add createdBy or another field")
                    .on("owners");
        }
        EntityDefinition settled = e.withAccess(new EntityDefinition.Access(access.read(), access.write(), owners, access.rows()));
        return creator ? settled.withOpt("audit", true) : settled;
    }

    /** {@code createdBy}, or the text field of that name as declared. */
    private static String owner(EntityDefinition e, String raw) {
        if (raw.equalsIgnoreCase(EntityDefinition.Access.CREATED_BY)) return EntityDefinition.Access.CREATED_BY;
        String where = "Entity '" + e.name() + "' access: owner '" + raw + "' ";
        FieldDefinition f = e.fields().stream().filter(x -> x.name().equalsIgnoreCase(raw)).findFirst()
                .orElseThrow(() -> new WizardArgumentException(where + "is not a field of '" + e.name()
                        + "' (an owner is createdBy or a text field holding a user id)"));
        if (f.type() != FieldType.STRING) {
            throw new WizardArgumentException(where + "is " + f.type() + ": a user id is kept in a STRING field");
        }
        if (f.primaryKey()) throw new WizardArgumentException(where + "is the key, which names the row, not a user");
        if (f.computed()) throw new WizardArgumentException(where + "is computed: an owner is a value somebody sets");
        if (f.showWhen() != null) {
            throw new WizardArgumentException(where + "has a show-when condition, which empties it while hidden");
        }
        return f.name();
    }
}
