package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
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
import java.util.regex.PatternSyntaxException;

/**
 * Validates a {@link FullstackStarterRequest} and converts its DTO field shapes
 * into the strongly-typed {@link EntityDefinition}s the renderer consumes.
 *
 * <p>Throws {@link WizardArgumentException} — already mapped to HTTP 400 by the
 * existing controller exception handlers.
 */
public final class FullstackRequestValidator {

    static final Set<String> RESERVED_JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "null",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "true", "false", "try", "void", "volatile", "while", "yield",
            "record", "sealed", "permits", "var");

    private FullstackRequestValidator() {}

    public static List<EntityDefinition> validateAndConvert(FullstackStarterRequest body) {
        if (body == null) throw new WizardArgumentException("Request body is required");
        List<FullstackStarterRequest.EntityDefinitionDto> raw = body.entities();
        if (raw == null || raw.isEmpty()) {
            throw new WizardArgumentException("At least one entity is required");
        }

        // Relations reference other entities, so collect everything first (pass 1: names + fields)
        // and resolve/validate relations once all entity names are known (pass 2).
        record Parsed(int ei, String name, String tableName, String schema, List<FieldDefinition> fields,
                      Set<String> memberNames, List<FullstackStarterRequest.RelationDefinitionDto> rawRelations,
                      boolean readOnly, String viewQuery, List<String> listViews,
                      String label, String labelPlural, Map<String, Boolean> opts,
                      List<FullstackStarterRequest.FormSectionDto> rawSections,
                      FullstackStarterRequest.WorkflowDto rawWorkflow, int pkCount) {}

        Set<String> seenLowerNames = new HashSet<>();
        Map<String, String> canonicalByLower = new HashMap<>();
        // Primary-key count per entity (lowercased name) — used in pass 2 to reject a MANY_TO_ONE
        // pointing at a composite-PK entity (a single <field>Id FK can't address a composite key).
        Map<String, Integer> pkCountByLower = new HashMap<>();
        // Lowercased names of @Subselect-view entities — a MANY_TO_ONE may not target one
        // (it would force a broken inverse @OneToMany on an immutable view).
        Set<String> viewLowerNames = new HashSet<>();
        List<Parsed> parsed = new ArrayList<>(raw.size());
        for (int ei = 0; ei < raw.size(); ei++) {
            FullstackStarterRequest.EntityDefinitionDto e = raw.get(ei);
            if (e == null) throw new WizardArgumentException("entities[" + ei + "] is null");
            if (e.name() == null || e.name().isBlank()) {
                throw new WizardArgumentException("entities[" + ei + "].name is required");
            }
            String name = e.name().trim();
            if (!isValidJavaIdentifier(name)) {
                throw new WizardArgumentException("entities[" + ei + "].name '" + name + "' is not a valid identifier");
            }
            if (RESERVED_JAVA_KEYWORDS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new WizardArgumentException("entities[" + ei + "].name '" + name + "' is a reserved keyword");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!seenLowerNames.add(lower)) {
                throw new WizardArgumentException("Duplicate entity name (case-insensitive): " + name);
            }
            canonicalByLower.put(lower, name);

            // A SELECT-backed view maps to @Subselect and is inherently read-only.
            String viewQuery = (e.viewQuery() == null || e.viewQuery().isBlank())
                    ? null : e.viewQuery().trim();
            boolean readOnly = viewQuery != null || Boolean.TRUE.equals(e.readOnly());
            if (viewQuery != null) viewLowerNames.add(lower);

            List<FullstackStarterRequest.FieldDefinitionDto> rawFields = e.fields();
            if (rawFields == null || rawFields.isEmpty()) {
                throw new WizardArgumentException("entities[" + ei + "] '" + name + "' has no fields");
            }

            int pkCount = 0;
            boolean anyGenerated = false;
            Set<String> seenFieldNames = new HashSet<>();
            List<FieldDefinition> fields = new ArrayList<>(rawFields.size());
            for (int fi = 0; fi < rawFields.size(); fi++) {
                FullstackStarterRequest.FieldDefinitionDto f = rawFields.get(fi);
                if (f == null) {
                    throw new WizardArgumentException("entities[" + ei + "].fields[" + fi + "] is null");
                }
                if (f.name() == null || f.name().isBlank()) {
                    throw new WizardArgumentException("entities[" + ei + "].fields[" + fi + "].name is required");
                }
                String fname = f.name().trim();
                if (!isValidJavaIdentifier(fname)) {
                    throw new WizardArgumentException("Field name '" + fname + "' is not a valid identifier (entity '" + name + "')");
                }
                if (RESERVED_JAVA_KEYWORDS.contains(fname.toLowerCase(Locale.ROOT))) {
                    throw new WizardArgumentException("Field name '" + fname + "' is a reserved keyword (entity '" + name + "')");
                }
                if (!seenFieldNames.add(fname.toLowerCase(Locale.ROOT))) {
                    throw new WizardArgumentException("Duplicate field name '" + fname + "' (entity '" + name + "')");
                }

                FieldType type;
                try {
                    type = FieldType.forWireString(f.type());
                } catch (IllegalArgumentException iae) {
                    throw new WizardArgumentException("entity '" + name + "' field '" + fname + "': " + iae.getMessage());
                }

                boolean isPk = Boolean.TRUE.equals(f.primaryKey());
                if (isPk) pkCount++;

                if (type == FieldType.ENUM) {
                    if (f.enumValues() == null || f.enumValues().isEmpty()) {
                        throw new WizardArgumentException("ENUM field '" + fname + "' on entity '" + name + "' requires enumValues");
                    }
                    for (String v : f.enumValues()) {
                        if (v == null || v.isBlank() || !isValidJavaIdentifier(v)) {
                            throw new WizardArgumentException("Invalid enum constant '" + v + "' on field '" + fname + "'");
                        }
                        // Exact-case: Java keywords are all lower-case, so the conventional upper-case
                        // constants (NEW, DEFAULT, CLASS) are fine — only a literal `new` would not compile.
                        if (RESERVED_JAVA_KEYWORDS.contains(v)) {
                            throw new WizardArgumentException("Enum constant '" + v + "' is a reserved keyword (field '" + fname + "' on entity '" + name + "')");
                        }
                    }
                } else if (f.enumValues() != null && !f.enumValues().isEmpty()) {
                    throw new WizardArgumentException("enumValues only allowed when type=ENUM (field '" + fname + "' on entity '" + name + "')");
                }
                if (type != FieldType.ENUM && f.enumLabels() != null && !f.enumLabels().isEmpty()) {
                    throw new WizardArgumentException("enumLabels only allowed when type=ENUM (field '" + fname + "' on entity '" + name + "')");
                }
                Map<String, String> enumLabels = type == FieldType.ENUM
                        ? canonicalEnumLabels(f.enumLabels(), f.enumValues(), fname, name)
                        : Map.of();

                if (f.length() != null && type != FieldType.STRING) {
                    throw new WizardArgumentException("length only allowed on STRING fields (field '" + fname + "' on entity '" + name + "')");
                }
                if (f.length() != null && f.length() <= 0) {
                    throw new WizardArgumentException("length must be positive (field '" + fname + "' on entity '" + name + "')");
                }

                // Numeric bounds: only on numeric types, and min <= max when both are present.
                if ((f.min() != null || f.max() != null) && !type.isNumeric()) {
                    throw new WizardArgumentException("min/max only allowed on numeric fields (field '" + fname + "' on entity '" + name + "')");
                }
                // Integral types render the bounds into @Min/@Max (long-valued), so a fractional bound
                // would not compile; decimals are only meaningful on BIG_DECIMAL (@DecimalMin/@DecimalMax).
                if (type != FieldType.BIG_DECIMAL) {
                    for (BigDecimal bound : new BigDecimal[] {f.min(), f.max()}) {
                        if (bound == null) continue;
                        if (bound.stripTrailingZeros().scale() > 0) {
                            throw new WizardArgumentException("min/max must be whole numbers on integral fields (field '" + fname + "' on entity '" + name + "')");
                        }
                        try {
                            if (type == FieldType.INTEGER) bound.intValueExact(); else bound.longValueExact();
                        } catch (ArithmeticException ae) {
                            throw new WizardArgumentException("min/max is out of range for " + type + " (field '" + fname + "' on entity '" + name + "')");
                        }
                    }
                }
                if (f.min() != null && f.max() != null && f.min().compareTo(f.max()) > 0) {
                    throw new WizardArgumentException("min must be <= max (field '" + fname + "' on entity '" + name + "')");
                }

                // Regex pattern + email: only on STRING; pattern must be a compilable regex.
                boolean isEmail = Boolean.TRUE.equals(f.email());
                if (f.pattern() != null && !f.pattern().isBlank() && type != FieldType.STRING) {
                    throw new WizardArgumentException("pattern only allowed on STRING fields (field '" + fname + "' on entity '" + name + "')");
                }
                if (isEmail && type != FieldType.STRING) {
                    throw new WizardArgumentException("email only allowed on STRING fields (field '" + fname + "' on entity '" + name + "')");
                }
                if (f.pattern() != null && !f.pattern().isBlank()) {
                    try {
                        Pattern.compile(f.pattern());
                    } catch (PatternSyntaxException pse) {
                        throw new WizardArgumentException("pattern is not a valid regex (field '" + fname + "' on entity '" + name + "'): " + pse.getDescription());
                    }
                }

                boolean isGenerated = Boolean.TRUE.equals(f.generated());
                if (isGenerated) {
                    anyGenerated = true;
                    if (!isPk) {
                        throw new WizardArgumentException("'generated' only applies to the primary key (field '" + fname + "' on entity '" + name + "')");
                    }
                    if (type != FieldType.LONG && type != FieldType.INTEGER && type != FieldType.UUID) {
                        throw new WizardArgumentException("a generated primary key must be of type LONG, INTEGER, or UUID (field '" + fname + "' on entity '" + name + "')");
                    }
                }

                String pattern = (f.pattern() == null || f.pattern().isBlank()) ? null : f.pattern();
                String label = (f.label() == null || f.label().isBlank()) ? null : f.label().trim();
                String defaultValue = normalizeDefault(f.defaultValue(), type, isGenerated, f.length(),
                        f.enumValues(), fname, name);
                String column = parseColumnName(f.column(), "column", fname, name);
                if (column != null && viewQuery != null) {
                    throw new WizardArgumentException("Field '" + fname + "' on entity '" + name
                            + "' sets a column, but a SELECT-backed view maps fields by their names");
                }
                fields.add(new FieldDefinition(
                        fname,
                        type,
                        isPk,
                        isGenerated,
                        Boolean.TRUE.equals(f.required()),
                        Boolean.TRUE.equals(f.unique()),
                        f.length(),
                        f.min(),
                        f.max(),
                        pattern,
                        isEmail,
                        f.enumValues() == null ? List.of() : List.copyOf(f.enumValues()),
                        // Per-field search/filter opt-out: default true (current behavior) when the
                        // wire omits the flag, unlike the default-false required/unique above.
                        f.searchable() == null || f.searchable(),
                        f.filterable() == null || f.filterable(),
                        // Per-field display label (null when omitted → templates fall back to
                        // PascalCase name) and read-only flag (default false, like required/unique).
                        label,
                        Boolean.TRUE.equals(f.readOnly()),
                        defaultValue,
                        enumLabels,
                        column));
            }

            if (pkCount == 0) {
                throw new WizardArgumentException("Entity '" + name + "' has no primary key field");
            }
            // Composite keys are allowed (rendered via @IdClass), but a generated key requires a
            // single PK — JPA IDENTITY generation cannot target one column of a composite key.
            if (pkCount > 1 && anyGenerated) {
                throw new WizardArgumentException("Entity '" + name + "' has a generated primary key combined with a composite key (a generated key requires a single primary key)");
            }
            // A @Subselect view has no underlying table column to auto-generate.
            if (viewQuery != null && anyGenerated) {
                throw new WizardArgumentException("Entity '" + name + "' is a SELECT-backed view and cannot have a generated primary key");
            }
            pkCountByLower.put(lower, pkCount);

            String tableName = (e.tableName() == null || e.tableName().isBlank())
                    ? null : e.tableName().trim();
            String schema = (e.schema() == null || e.schema().isBlank())
                    ? null : e.schema().trim();
            // Both end up in Java string literals and native SQL, like a column name.
            if (tableName != null && !SQL_NAME.matcher(tableName).matches()) {
                throw new WizardArgumentException("tableName '" + tableName + "' on entity '" + name
                        + "' is not a valid table name (" + SQL_NAME_RULE + ")");
            }
            if (schema != null && !SQL_NAME.matcher(schema).matches()) {
                throw new WizardArgumentException("schema '" + schema + "' on entity '" + name
                        + "' is not a valid schema name (" + SQL_NAME_RULE + ")");
            }
            // Optional entity display labels — blank → null (templates fall back to the name).
            String label = (e.label() == null || e.label().isBlank()) ? null : e.label().trim();
            String labelPlural = (e.labelPlural() == null || e.labelPlural().isBlank())
                    ? null : e.labelPlural().trim();
            parsed.add(new Parsed(ei, name, tableName, schema, fields, seenFieldNames, e.relations(),
                    readOnly, viewQuery, resolveListViews(e), label, labelPlural,
                    parseEntityOpts(e.opts(), name), e.formSections(), e.workflow(), pkCount));
        }

        // Pass 2 — resolve and validate relations now that all entity names are known.
        List<EntityDefinition> result = new ArrayList<>(parsed.size());
        for (Parsed p : parsed) {
            // A @Subselect view can't own a foreign-key join column (v1 — no relations on views).
            if (p.viewQuery() != null && p.rawRelations() != null && !p.rawRelations().isEmpty()) {
                throw new WizardArgumentException("Entity '" + p.name()
                        + "' is a SELECT-backed view and cannot declare relations");
            }
            List<RelationDefinition> relations =
                    parseRelations(p.rawRelations(), p.name(), p.memberNames(), canonicalByLower,
                            pkCountByLower, viewLowerNames);
            checkColumnClashes(p.name(), p.fields(), relations);
            checkVersionName(p.name(), p.fields(), relations, p.readOnly(), p.viewQuery(), p.opts(), body);
            result.add(new EntityDefinition(p.name(), p.tableName(), p.schema(), p.fields(), relations,
                    p.readOnly(), p.viewQuery(), null, p.listViews(), p.label(), p.labelPlural(), p.opts(),
                    parseFormSections(p.rawSections(), p.name(), p.fields(), relations),
                    parseWorkflow(p.rawWorkflow(), p.name(), p.fields(), relations, p.readOnly(), p.pkCount())));
        }
        return result;
    }

    /**
     * Optimistic locking adds a {@code version} property mapped to a {@code version} column, so an
     * entity it applies to may not have a field, relation or column of that name already.
     */
    private static void checkVersionName(String entity, List<FieldDefinition> fields, List<RelationDefinition> relations,
                                         boolean readOnly, String viewQuery, Map<String, Boolean> opts,
                                         FullstackStarterRequest body) {
        List<String> scaffold = body.opts() == null ? null : body.opts().get("scaffold");
        boolean projectOpt = scaffold != null && scaffold.contains("optimisticLocking");
        Boolean override = opts.get("optimisticLocking");
        if (readOnly || viewQuery != null || !(override != null ? override : projectOpt)) return;
        boolean clash = fields.stream().anyMatch(f -> "version".equalsIgnoreCase(f.name())
                        || "version".equalsIgnoreCase(f.column()))
                || relations.stream().anyMatch(r -> "version".equalsIgnoreCase(r.fieldName())
                        || "version".equalsIgnoreCase(r.joinColumn()));
        if (clash) {
            throw new WizardArgumentException("Entity '" + entity + "' already has a 'version' field or column, which"
                    + " optimistic locking needs for itself — rename it, or turn optimistic locking off for this entity");
        }
    }

    /** How many titled sections an entity's form may have (a wizard follows them, one step each). */
    static final int MAX_FORM_SECTIONS = 6;
    static final int MAX_SECTION_TITLE = 60;

    /**
     * The entity's form sections: each titled, listing fields (not a generated key — the database
     * assigns it) and MANY_TO_ONE relations by name, matched ignoring case and canonicalized; a
     * name appears in one section at most. Null/empty: one untitled form.
     */
    static List<EntityDefinition.FormSection> parseFormSections(List<FullstackStarterRequest.FormSectionDto> raw,
                                                               String entity, List<FieldDefinition> fields,
                                                               List<RelationDefinition> relations) {
        if (raw == null || raw.isEmpty()) return List.of();
        if (raw.size() > MAX_FORM_SECTIONS) {
            throw new WizardArgumentException("Entity '" + entity + "' has more than " + MAX_FORM_SECTIONS + " form sections");
        }
        Map<String, String> askable = new LinkedHashMap<>();
        for (FieldDefinition f : fields) {
            if (!(f.primaryKey() && f.generated())) askable.put(f.name().toLowerCase(Locale.ROOT), f.name());
        }
        for (RelationDefinition r : relations) {
            if (r.type() == RelationType.MANY_TO_ONE) askable.put(r.fieldName().toLowerCase(Locale.ROOT), r.fieldName());
        }
        Set<String> seen = new HashSet<>();
        List<EntityDefinition.FormSection> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            FullstackStarterRequest.FormSectionDto section = raw.get(i);
            String where = "Entity '" + entity + "' form section " + (i + 1);
            String title = section == null || section.title() == null ? "" : section.title().trim();
            if (title.isEmpty()) throw new WizardArgumentException(where + " needs a title");
            if (title.length() > MAX_SECTION_TITLE) {
                throw new WizardArgumentException(where + " has a title over " + MAX_SECTION_TITLE + " characters");
            }
            List<String> names = new ArrayList<>();
            for (String name : section.fields() == null ? List.<String>of() : section.fields()) {
                String canonical = name == null ? null : askable.get(name.trim().toLowerCase(Locale.ROOT));
                if (canonical == null) {
                    throw new WizardArgumentException(where + " ('" + title + "') lists '" + name
                            + "', which is not one of its fields or relations");
                }
                if (!seen.add(canonical)) {
                    throw new WizardArgumentException("Entity '" + entity + "' lists '" + canonical + "' in two form sections");
                }
                names.add(canonical);
            }
            if (names.isEmpty()) throw new WizardArgumentException(where + " ('" + title + "') lists no fields");
            out.add(new EntityDefinition.FormSection(title, names));
        }
        return out;
    }

    static final int MAX_TRANSITIONS = 20;
    static final int MAX_TRANSITION_LABEL = 40;
    private static final java.util.regex.Pattern TRANSITION_NAME = java.util.regex.Pattern.compile("^[a-z][A-Za-z0-9]{0,39}$");
    /** The roles a transition may be limited to: the ldap-auth {@code Constants}. */
    static final List<String> WORKFLOW_ROLES = List.of("ADMIN", "USER");

    /**
     * An entity's workflow, with every name canonicalized. A workflow changes a row's status, so
     * the entity must be writable, table-backed and single-keyed (transitions address one row);
     * its status is a filterable enum (the list, the inbox and the board filter by it). Messages
     * read {@code Entity 'X' workflow: …}.
     */
    static EntityDefinition.Workflow parseWorkflow(FullstackStarterRequest.WorkflowDto raw, String entity,
                                                   List<FieldDefinition> fields, List<RelationDefinition> relations,
                                                   boolean readOnly, int pkCount) {
        if (raw == null) return null;
        String where = "Entity '" + entity + "' workflow: ";
        if (readOnly) throw new WizardArgumentException(where + "a read-only entity cannot change state");
        if (pkCount != 1) throw new WizardArgumentException(where + "needs a single primary key — a transition addresses one row");
        Map<String, FieldDefinition> byLower = new LinkedHashMap<>();
        for (FieldDefinition f : fields) byLower.put(f.name().toLowerCase(Locale.ROOT), f);
        Map<String, RelationDefinition> relationsByLower = new LinkedHashMap<>();
        for (RelationDefinition r : relations) {
            if (r.type() == RelationType.MANY_TO_ONE) relationsByLower.put(r.fieldName().toLowerCase(Locale.ROOT), r);
        }

        String statusName = raw.statusField() == null ? "" : raw.statusField().trim();
        if (statusName.isEmpty()) throw new WizardArgumentException(where + "statusField is required");
        FieldDefinition status = byLower.get(statusName.toLowerCase(Locale.ROOT));
        if (status == null) throw new WizardArgumentException(where + "statusField '" + statusName + "' is not a field of " + entity);
        if (status.primaryKey() || status.type() != FieldType.ENUM || !status.filterable()) {
            throw new WizardArgumentException(where + "statusField '" + status.name() + "' must be a filterable, non-key enum field");
        }
        List<String> states = status.enumValues().stream().map(v -> v.toUpperCase(Locale.ROOT)).toList();
        java.util.function.Function<String, String> state = v -> {
            String s = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
            if (!states.contains(s)) {
                throw new WizardArgumentException(where + "'" + v + "' is not a state of " + status.name() + " ("
                        + String.join(", ", states) + ")");
            }
            return s;
        };
        String initial = raw.initial() == null || raw.initial().isBlank()
                ? (status.defaultValue() != null ? status.defaultValue() : states.get(0))
                : state.apply(raw.initial());
        if (status.defaultValue() != null && !status.defaultValue().equals(initial)) {
            throw new WizardArgumentException(where + "initial '" + initial + "' differs from " + status.name()
                    + "'s default '" + status.defaultValue() + "' — a new row starts in one of them");
        }

        String assignee = null;
        if (raw.assigneeField() != null && !raw.assigneeField().isBlank()) {
            FieldDefinition f = byLower.get(raw.assigneeField().trim().toLowerCase(Locale.ROOT));
            if (f == null || f.primaryKey() || f.unique() || f.type() != FieldType.STRING) {
                throw new WizardArgumentException(where + "assigneeField '" + raw.assigneeField().trim()
                        + "' must be a non-key, non-unique text field of " + entity + " (it holds a user id)");
            }
            assignee = f.name();
            // The inbox queue rides in the `inbox` request parameter, which a filter of that name would shadow.
            if (byLower.containsKey("inbox")) {
                throw new WizardArgumentException(where + "a field named 'inbox' clashes with the inbox queue parameter — rename it");
            }
        }
        String due = null;
        if (raw.dueField() != null && !raw.dueField().isBlank()) {
            FieldDefinition f = byLower.get(raw.dueField().trim().toLowerCase(Locale.ROOT));
            if (f == null || f.primaryKey() || !f.type().isTemporal()) {
                throw new WizardArgumentException(where + "dueField '" + raw.dueField().trim() + "' must be a non-key date field of " + entity);
            }
            due = f.name();
        }

        List<FullstackStarterRequest.TransitionDto> rawTransitions = raw.transitions() == null ? List.of() : raw.transitions();
        if (rawTransitions.isEmpty()) throw new WizardArgumentException(where + "needs at least one transition");
        if (rawTransitions.size() > MAX_TRANSITIONS) {
            throw new WizardArgumentException(where + "has more than " + MAX_TRANSITIONS + " transitions");
        }
        Set<String> names = new HashSet<>();
        List<EntityDefinition.Transition> transitions = new ArrayList<>();
        for (int i = 0; i < rawTransitions.size(); i++) {
            FullstackStarterRequest.TransitionDto t = rawTransitions.get(i);
            if (t == null) throw new WizardArgumentException(where + "transitions[" + i + "] is null");
            String name = t.name() == null ? "" : t.name().trim();
            if (!TRANSITION_NAME.matcher(name).matches()) {
                throw new WizardArgumentException(where + "transition name '" + name
                        + "' must start with a lower-case letter, then letters and digits (max 40)");
            }
            if (!names.add(name)) throw new WizardArgumentException(where + "two transitions are named '" + name + "'");
            String at = where + "transition '" + name + "' ";
            String label = t.label() == null || t.label().isBlank() ? null : t.label().trim();
            if (label != null && label.length() > MAX_TRANSITION_LABEL) {
                throw new WizardArgumentException(at + "has a label over " + MAX_TRANSITION_LABEL + " characters");
            }
            if (t.from() == null || t.from().isEmpty()) throw new WizardArgumentException(at + "needs the states it moves 'from'");
            Set<String> from = new LinkedHashSet<>();
            for (String f : t.from()) from.add(state.apply(f));
            if (t.to() == null || t.to().isBlank()) throw new WizardArgumentException(at + "needs the state it moves 'to'");
            String to = state.apply(t.to());
            if (from.contains(to)) throw new WizardArgumentException(at + "moves from " + to + " to itself");
            Set<String> roles = new LinkedHashSet<>();
            for (String r : t.roles() == null ? List.<String>of() : t.roles()) {
                String role = r == null ? "" : r.trim().toUpperCase(Locale.ROOT);
                if (!WORKFLOW_ROLES.contains(role)) {
                    throw new WizardArgumentException(at + "has an unknown role '" + r + "' (expected ADMIN or USER)");
                }
                roles.add(role);
            }
            List<String> requires = new ArrayList<>();
            for (String r : t.requires() == null ? List.<String>of() : t.requires()) {
                String key = r == null ? "" : r.trim().toLowerCase(Locale.ROOT);
                FieldDefinition f = byLower.get(key);
                RelationDefinition rel = relationsByLower.get(key);
                String canonical = f != null ? f.name() : rel != null ? rel.fieldName() : null;
                if (canonical == null) throw new WizardArgumentException(at + "requires '" + r + "', which is not a field or relation of " + entity);
                if (f != null && (f.primaryKey() || f.readOnly() || f.name().equals(status.name()))) {
                    throw new WizardArgumentException(at + "requires '" + f.name() + "', which a transition cannot set"
                            + (f.name().equals(status.name()) ? " (it is the status the transition changes)" : " (a key or read-only field)"));
                }
                if (requires.contains(canonical)) throw new WizardArgumentException(at + "requires '" + canonical + "' twice");
                requires.add(canonical);
            }
            transitions.add(new EntityDefinition.Transition(name, label, List.copyOf(from), to, List.copyOf(roles), requires));
        }
        return new EntityDefinition.Workflow(status.name(), initial, assignee, due, transitions);
    }

    /**
     * Type-checks a field's {@code defaultValue} against its {@link FieldType} and returns it in
     * canonical form (ENUM → the upper-cased constant, temporal → ISO, numbers as parsed), or null
     * when absent/blank. A default on a generated primary key is rejected (the database assigns it).
     */
    /** Longest display label accepted for an enum constant. */
    static final int MAX_ENUM_LABEL_LENGTH = 80;

    /**
     * Validates the optional per-constant display labels of an ENUM field and returns them keyed
     * by the upper-cased constant (the spelling the generated Java enum and the templates use).
     * A key is matched case-insensitively against {@code enumValues}; an unknown key, a blank
     * label or one over {@link #MAX_ENUM_LABEL_LENGTH} chars is a 400 naming the field and key.
     */
    static Map<String, String> canonicalEnumLabels(Map<String, String> raw, List<String> enumValues,
                                                   String fieldName, String entityName) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> byConstant = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim();
            String constant = null;
            for (String v : enumValues) {
                if (v != null && v.equalsIgnoreCase(key)) { constant = v.toUpperCase(Locale.ROOT); break; }
            }
            if (constant == null) {
                throw new WizardArgumentException("enumLabels key '" + key + "' is not one of the enumValues "
                        + enumValues + " (field '" + fieldName + "' on entity '" + entityName + "')");
            }
            String label = e.getValue() == null ? "" : e.getValue().trim();
            if (label.isEmpty()) {
                throw new WizardArgumentException("enumLabels['" + key + "'] must not be blank (field '"
                        + fieldName + "' on entity '" + entityName + "')");
            }
            if (label.length() > MAX_ENUM_LABEL_LENGTH) {
                throw new WizardArgumentException("enumLabels['" + key + "'] is longer than " + MAX_ENUM_LABEL_LENGTH
                        + " characters (field '" + fieldName + "' on entity '" + entityName + "')");
            }
            byConstant.put(constant, label);
        }
        return Map.copyOf(byConstant);
    }

    static String normalizeDefault(String raw, FieldType type, boolean generated, Integer length,
                                   List<String> enumValues, String fname, String entityName) {
        if (raw == null || raw.isBlank()) return null;
        String where = " (field '" + fname + "' on entity '" + entityName + "')";
        if (generated) {
            throw new WizardArgumentException("defaultValue is not allowed on a generated primary key" + where);
        }
        String v = raw.trim();
        try {
            return switch (type) {
                case STRING, TEXT -> {
                    if (length != null && raw.length() > length) {
                        throw new WizardArgumentException("defaultValue exceeds length " + length + where);
                    }
                    yield raw;
                }
                case LONG -> Long.toString(Long.parseLong(v));
                case INTEGER -> Integer.toString(Integer.parseInt(v));
                case BIG_DECIMAL -> new BigDecimal(v).toPlainString();
                case BOOLEAN -> {
                    if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                        throw new NumberFormatException();
                    }
                    yield v.toLowerCase(Locale.ROOT);
                }
                case LOCAL_DATE -> LocalDate.parse(v).toString();
                case LOCAL_DATE_TIME -> LocalDateTime.parse(v).toString();
                case UUID -> java.util.UUID.fromString(v).toString();
                case ENUM -> {
                    List<String> values = enumValues == null ? List.of() : enumValues;
                    for (String c : values) {
                        if (c != null && c.equalsIgnoreCase(v)) yield c.toUpperCase(Locale.ROOT);
                    }
                    throw new WizardArgumentException("defaultValue '" + v + "' is not one of the enumValues "
                            + values + where);
                }
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new WizardArgumentException("defaultValue '" + v + "' is not a valid " + type + where);
        } catch (IllegalArgumentException e) { // UUID.fromString
            throw new WizardArgumentException("defaultValue '" + v + "' is not a valid " + type + where);
        }
    }

    /**
     * Validates the per-entity scaffold-opt overrides: every key must be one of the overridable
     * options ({@link EntityScaffoldContext#SCAFFOLD_OPT_FLAGS}); a null value is dropped (= inherit
     * the project setting). Null/empty maps are fine and yield an empty result.
     */
    private static Map<String, Boolean> parseEntityOpts(Map<String, Boolean> raw, String entityName) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, Boolean> opts = new HashMap<>();
        for (Map.Entry<String, Boolean> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!EntityScaffoldContext.SCAFFOLD_OPT_FLAGS.containsKey(key)) {
                throw new WizardArgumentException("Unknown scaffold option '" + key + "' on entity "
                        + entityName + " (known: "
                        + String.join(", ", EntityScaffoldContext.SCAFFOLD_OPT_FLAGS.keySet()) + ")");
            }
            if (entry.getValue() != null) opts.put(key, entry.getValue());
        }
        return opts;
    }

    /** Resolves the enabled list-view modes from the wire DTO: the new {@code listViews} when present,
     *  else the legacy single {@code listView} as a one-element list, else null (→ defaults to [table]
     *  in {@link EntityDefinition}). Normalization/validation of the values happens there. */
    private static List<String> resolveListViews(FullstackStarterRequest.EntityDefinitionDto e) {
        if (e.listViews() != null && !e.listViews().isEmpty()) return e.listViews();
        if (e.listView() != null && !e.listView().isBlank()) return List.of(e.listView());
        return null;
    }

    /**
     * Parses and validates one entity's relations. Each relation must be {@code MANY_TO_ONE}
     * (v1), carry a valid, collision-free {@code fieldName}, and point at another entity
     * defined in the same request. The target name is canonicalized to its declared spelling.
     */
    private static List<RelationDefinition> parseRelations(
            List<FullstackStarterRequest.RelationDefinitionDto> rawRelations,
            String entityName,
            Set<String> memberNames,
            Map<String, String> canonicalByLower,
            Map<String, Integer> pkCountByLower,
            Set<String> viewLowerNames) {
        if (rawRelations == null || rawRelations.isEmpty()) {
            return List.of();
        }
        List<RelationDefinition> relations = new ArrayList<>(rawRelations.size());
        for (int ri = 0; ri < rawRelations.size(); ri++) {
            FullstackStarterRequest.RelationDefinitionDto r = rawRelations.get(ri);
            if (r == null) {
                throw new WizardArgumentException("entity '" + entityName + "' relations[" + ri + "] is null");
            }
            RelationType type;
            try {
                type = RelationType.forWireString(r.type());
            } catch (IllegalArgumentException iae) {
                throw new WizardArgumentException("entity '" + entityName + "' relation: " + iae.getMessage());
            }
            if (type != RelationType.MANY_TO_ONE) {
                throw new WizardArgumentException("entity '" + entityName + "' relation type " + type
                        + " is not supported yet (only MANY_TO_ONE)");
            }

            if (r.fieldName() == null || r.fieldName().isBlank()) {
                throw new WizardArgumentException("entity '" + entityName + "' relations[" + ri + "].fieldName is required");
            }
            String fieldName = r.fieldName().trim();
            if (!isValidJavaIdentifier(fieldName)) {
                throw new WizardArgumentException("Relation field name '" + fieldName + "' is not a valid identifier (entity '" + entityName + "')");
            }
            if (RESERVED_JAVA_KEYWORDS.contains(fieldName.toLowerCase(Locale.ROOT))) {
                throw new WizardArgumentException("Relation field name '" + fieldName + "' is a reserved keyword (entity '" + entityName + "')");
            }
            // Collide against scalar fields and earlier relations (memberNames accumulates both).
            if (!memberNames.add(fieldName.toLowerCase(Locale.ROOT))) {
                throw new WizardArgumentException("Relation field name '" + fieldName + "' collides with another field/relation (entity '" + entityName + "')");
            }

            if (r.targetEntity() == null || r.targetEntity().isBlank()) {
                throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName + "' requires a targetEntity");
            }
            String targetLower = r.targetEntity().trim().toLowerCase(Locale.ROOT);
            String canonicalTarget = canonicalByLower.get(targetLower);
            if (canonicalTarget == null) {
                throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                        + "' targets unknown entity '" + r.targetEntity().trim() + "'");
            }
            // A single <field>Id foreign key cannot address a composite primary key, so a relation
            // may not point at a composite-PK entity in v1.
            Integer targetPkCount = pkCountByLower.get(targetLower);
            if (targetPkCount != null && targetPkCount > 1) {
                throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                        + "' targets composite-PK entity '" + canonicalTarget + "' — not supported");
            }
            if (viewLowerNames.contains(targetLower)) {
                throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                        + "' targets SELECT-backed view '" + canonicalTarget + "' — not supported");
            }

            relations.add(new RelationDefinition(type, fieldName, canonicalTarget,
                    Boolean.TRUE.equals(r.required()),
                    parseColumnName(r.joinColumn(), "joinColumn", fieldName, entityName)));
        }
        return relations;
    }

    /** A database name the generated code can splice into native SQL ({@code @Formula},
     *  {@code @SQLDelete}, native queries): letters, digits and {@code _ $ # @}, or one quoted in
     *  any of the three styles Hibernate reads ({@code "x"}, {@code `x`}, {@code [x]} — exact case,
     *  see {@link com.menora.initializr.sql.SqlNames}) of letters, digits, spaces and {@code _ $ # @ . -}. */
    static final Pattern SQL_NAME = Pattern.compile(
            "[A-Za-z_$#@][A-Za-z0-9_$#@]{0,127}"
                    + "|\"[A-Za-z0-9_$#@ .-]{1,128}\""
                    + "|`[A-Za-z0-9_$#@ .-]{1,128}`"
                    + "|\\[[A-Za-z0-9_$#@ .-]{1,128}]");

    static final String SQL_NAME_RULE = "letters, digits and _ $ # @, or a quoted name such as \"OrderDate\"";

    /** An optional {@code column}/{@code joinColumn}: blank → null (derived), else trimmed and checked. */
    static String parseColumnName(String raw, String prop, String member, String entity) {
        if (raw == null || raw.isBlank()) return null;
        String column = raw.trim();
        if (!SQL_NAME.matcher(column).matches()) {
            throw new WizardArgumentException(prop + " '" + column + "' of '" + member + "' on entity '" + entity
                    + "' is not a valid column name (" + SQL_NAME_RULE + ")");
        }
        return column;
    }

    /**
     * Two members of an entity may not map to one column. Only checked where an explicit
     * {@code column}/{@code joinColumn} is involved, so requests that never set one keep validating
     * exactly as before.
     */
    private static void checkColumnClashes(String entity, List<FieldDefinition> fields,
                                           List<RelationDefinition> relations) {
        record Member(String name, String column, boolean explicit) {}
        List<Member> members = new ArrayList<>();
        for (FieldDefinition f : fields) {
            members.add(new Member(f.name(), EntityScaffoldContext.columnOf(f), f.column() != null));
        }
        for (RelationDefinition r : relations) {
            members.add(new Member(r.fieldName(), EntityScaffoldContext.joinColumnOf(r), r.joinColumn() != null));
        }
        Map<String, List<Member>> byColumn = new LinkedHashMap<>();
        for (Member m : members) {
            byColumn.computeIfAbsent(m.column().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(m);
        }
        for (List<Member> same : byColumn.values()) {
            if (same.size() > 1 && same.stream().anyMatch(Member::explicit)) {
                throw new WizardArgumentException("'" + same.get(0).name() + "' and '" + same.get(1).name()
                        + "' on entity '" + entity + "' both map to column '" + same.get(0).column() + "'");
            }
        }
    }

    static boolean isValidJavaIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }
}
