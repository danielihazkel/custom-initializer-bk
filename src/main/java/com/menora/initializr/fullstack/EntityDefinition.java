package com.menora.initializr.fullstack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One user-defined entity. The {@code tableName} is optional — when null,
 * {@link Naming#pluralize(String)} of the snake_case name is used. {@code schema} is the
 * optional SQL schema the table lives in (e.g. {@code entv} from {@code CREATE TABLE entv.test});
 * null/blank means no {@code @Table(schema=…)} is emitted. {@code relations} holds
 * {@link RelationType#MANY_TO_ONE} foreign keys to other entities in the request.
 *
 * <p>{@code readOnly} entities generate GET-only scaffolding (no create/update/delete).
 * {@code viewQuery}, when set, is a raw SELECT that maps the entity to a Hibernate
 * {@code @Immutable}/{@code @Subselect} view instead of a table — a view is always read-only.
 *
 * <p>{@code sourceSql} is informational provenance: the originating {@code CREATE TABLE}
 * statement a table-backed entity was imported from. It is surfaced read-only in the editor
 * and is <em>not</em> used during generation. Views carry their source SELECT in
 * {@code viewQuery} instead, so {@code sourceSql} stays null for them.
 *
 * <p>{@code opts} holds per-entity overrides of the project-wide {@code opts.scaffold} flags
 * (validated key -> true/false; see {@link EntityScaffoldContext#SCAFFOLD_OPT_FLAGS}). An absent
 * key inherits the project-level setting. Never null (empty when no overrides were supplied).
 */
public record EntityDefinition(
        String name,
        String tableName,
        String schema,
        List<FieldDefinition> fields,
        List<RelationDefinition> relations,
        boolean readOnly,
        String viewQuery,
        String sourceSql,
        List<String> listViews,
        String label,
        String labelPlural,
        Map<String, Boolean> opts,
        List<FormSection> formSections,
        Workflow workflow,
        List<RelationDefinition> collections,
        List<RelationDefinition> children,
        Access access) {

    /**
     * Who may call the entity's generated endpoints, when the project has an ldap-auth dependency
     * to ask (without one nothing is checked): {@code read} for the list, a row, the statistics
     * and the exports, {@code write} for everything that changes a row. Each is one of
     * {@link #LEVELS}, weakest first; {@code write} is never weaker than {@code read}.
     */
    public record Access(String read, String write) {
        /** Any signed-in user, a holder of the USER role, a holder of the ADMIN role. */
        public static final List<String> LEVELS = List.of("SIGNED_IN", "USER", "ADMIN");
        /** What an entity that says nothing gets: read for users, write for admins. */
        public static final Access DEFAULT = new Access("USER", "ADMIN");

        public boolean isDefault() {
            return equals(DEFAULT);
        }
    }

    /**
     * The entity's workflow: the enum field whose values are its states, the state a new row starts
     * in, the moves between states, and optionally the text field naming who the row is assigned to
     * and the date it is due by. Names are canonical (validated by FullstackRequestValidator).
     */
    public record Workflow(String statusField, String initial, String assigneeField, String dueField,
                           List<Transition> transitions, boolean trackMoves) {
        public Workflow {
            transitions = List.copyOf(transitions);
        }

        /** A workflow that keeps no log of its moves. */
        public Workflow(String statusField, String initial, String assigneeField, String dueField,
                        List<Transition> transitions) {
            this(statusField, initial, assigneeField, dueField, transitions, false);
        }

        /** The states a row can still move on from — every state some transition leaves. */
        public Set<String> openStates() {
            Set<String> open = new LinkedHashSet<>();
            for (Transition t : transitions) open.addAll(t.from());
            return open;
        }

        /** Whether some move e-mails somebody. */
        public boolean notifies() {
            return transitions.stream().anyMatch(t -> !t.mailTo().isEmpty());
        }
    }

    /** One move: from any of {@code from} to {@code to}, for the {@code roles} (empty: anyone),
     *  asking for the {@code requires} fields and relation field names. */
    public record Transition(String name, String label, List<String> from, String to, List<String> roles,
                             List<String> requires, List<String> mailTo) {
        /** Who a move can tell: the row's assignee, who created it, or everyone holding a role. */
        public static final List<String> RECIPIENTS = List.of("assignee", "creator", "ADMIN", "USER");

        public Transition {
            from = List.copyOf(from);
            roles = List.copyOf(roles);
            requires = List.copyOf(requires);
            mailTo = mailTo == null ? List.of() : List.copyOf(mailTo);
        }

        /** A move that tells nobody. */
        public Transition(String name, String label, List<String> from, String to, List<String> roles,
                          List<String> requires) {
            this(name, label, from, to, roles, requires, List.of());
        }
    }

    /** A titled group of the entity's form (and details): field and relation field names, in order. */
    public record FormSection(String title, List<String> fields) {
        public FormSection {
            fields = List.copyOf(fields);
        }
    }

    /** The list-view modes the generated entity page may render. */
    private static final Set<String> KNOWN_VIEWS = Set.of("table", "cards", "kanban", "calendar", "gallery");

    public EntityDefinition {
        relations = relations == null ? List.of() : List.copyOf(relations);
        viewQuery = (viewQuery == null || viewQuery.isBlank()) ? null : viewQuery;
        sourceSql = (sourceSql == null || sourceSql.isBlank()) ? null : sourceSql;
        label = (label == null || label.isBlank()) ? null : label.trim();
        labelPlural = (labelPlural == null || labelPlural.isBlank()) ? null : labelPlural.trim();
        if (viewQuery != null) readOnly = true;
        // The list-view modes the generated entity page generates — an ordered, deduped subset of
        // {table, cards, kanban, calendar}; the first is the page's initial mode. A toggle is emitted
        // only when more than one is enabled. Unknown values are dropped; an empty/null set defaults
        // to [table]. "kanban"/"calendar" are further down-graded at render time when the entity lacks
        // the field each needs (see EntityScaffoldContext: viewKanban / viewCalendar).
        LinkedHashSet<String> norm = new LinkedHashSet<>();
        if (listViews != null) {
            for (String v : listViews) {
                if (v == null) continue;
                String lv = v.trim().toLowerCase(Locale.ROOT);
                if (KNOWN_VIEWS.contains(lv)) norm.add(lv);
            }
        }
        if (norm.isEmpty()) norm.add("table");
        listViews = List.copyOf(norm);
        opts = opts == null ? Map.of() : Map.copyOf(opts);
        formSections = formSections == null ? List.of() : List.copyOf(formSections);
        collections = collections == null ? List.of() : List.copyOf(collections);
        children = children == null ? List.of() : List.copyOf(children);
        access = access == null ? Access.DEFAULT : access;
    }

    /** Every property but {@code access} (the default: read for users, write for admins). */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery, String sourceSql, List<String> listViews,
                            String label, String labelPlural, Map<String, Boolean> opts, List<FormSection> formSections,
                            Workflow workflow, List<RelationDefinition> collections, List<RelationDefinition> children) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listViews,
                label, labelPlural, opts, formSections, workflow, collections, children, null);
    }

    /**
     * Every property but the children (none): the ONE_TO_MANYs — lists of the rows of another
     * entity that point at this one through a MANY_TO_ONE of theirs. Read-only from here; like
     * the collections, kept apart from the foreign keys.
     */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery, String sourceSql, List<String> listViews,
                            String label, String labelPlural, Map<String, Boolean> opts, List<FormSection> formSections,
                            Workflow workflow, List<RelationDefinition> collections) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listViews,
                label, labelPlural, opts, formSections, workflow, collections, List.of());
    }

    /** The same entity with its ONE_TO_MANYs resolved (each naming the MANY_TO_ONE behind it). */
    public EntityDefinition withChildren(List<RelationDefinition> resolved) {
        return new EntityDefinition(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql,
                listViews, label, labelPlural, opts, formSections, workflow, collections, resolved, access);
    }

    /**
     * Every property but the collections (none). {@code relations} holds the MANY_TO_ONEs — the
     * foreign keys this entity owns, which is what every reader of it means — and
     * {@code collections} the relations that are sets of other rows (MANY_TO_MANY), declared apart
     * so nothing that walks the foreign keys has to tell them from these.
     */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery, String sourceSql, List<String> listViews,
                            String label, String labelPlural, Map<String, Boolean> opts, List<FormSection> formSections,
                            Workflow workflow) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listViews,
                label, labelPlural, opts, formSections, workflow, List.of());
    }

    /** Every property but the workflow (none). */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery, String sourceSql, List<String> listViews,
                            String label, String labelPlural, Map<String, Boolean> opts, List<FormSection> formSections) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listViews,
                label, labelPlural, opts, formSections, null);
    }

    /** Every property but the form sections (one untitled form). */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery, String sourceSql, List<String> listViews,
                            String label, String labelPlural, Map<String, Boolean> opts) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listViews,
                label, labelPlural, opts, List.of());
    }

    /** Overload without the per-entity scaffold {@code opts} overrides (none). */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery, String sourceSql, List<String> listViews,
                            String label, String labelPlural) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listViews,
                label, labelPlural, Map.of());
    }

    /** First enabled view — the generated page's initial mode. Back-compat for single-view readers. */
    /** The same entity with one scaffold option overridden (a page that needs it switches it on). */
    public EntityDefinition withOpt(String key, boolean value) {
        Map<String, Boolean> next = new java.util.LinkedHashMap<>(opts);
        next.put(key, value);
        return new EntityDefinition(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql,
                listViews, label, labelPlural, next, formSections, workflow, collections, children);
    }

    public String listView() {
        return listViews.get(0);
    }

    /** Overload without the entity display {@code label}/{@code labelPlural} (both default to null). */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery, String sourceSql, List<String> listViews) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, listViews, null, null);
    }

    /** Overload without {@code listViews} (defaults to [table]). */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery, String sourceSql) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, sourceSql, List.of("table"), null, null);
    }

    /** Overload without {@code sourceSql}/{@code listViews} — keeps existing call sites terse. */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations,
                            boolean readOnly, String viewQuery) {
        this(name, tableName, schema, fields, relations, readOnly, viewQuery, null, List.of("table"), null, null);
    }

    /** Convenience for a table-backed, read-write entity (the common case). */
    public EntityDefinition(String name, String tableName, String schema,
                            List<FieldDefinition> fields, List<RelationDefinition> relations) {
        this(name, tableName, schema, fields, relations, false, null, null, List.of("table"), null, null);
    }

    /** Back-compat overload for schemaless entities (keeps existing call sites terse). */
    public EntityDefinition(String name, String tableName, List<FieldDefinition> fields,
                            List<RelationDefinition> relations) {
        this(name, tableName, null, fields, relations);
    }

    /** Convenience for the common no-schema, no-relations case. */
    public EntityDefinition(String name, String tableName, List<FieldDefinition> fields) {
        this(name, tableName, null, fields, List.of());
    }

    /** True when this entity maps to a SELECT via {@code @Subselect} rather than a table. */
    public boolean isView() {
        return viewQuery != null;
    }
}
