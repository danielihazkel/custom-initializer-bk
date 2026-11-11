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
        checkScaffoldOptions(body);

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
            try {
                FullstackStarterRequest.EntityDefinitionDto e = raw.get(ei);
                if (e == null) throw new WizardArgumentException("entities[" + ei + "] is null");
                if (e.name() == null || e.name().isBlank()) {
                    throw new WizardArgumentException("entities[" + ei + "].name is required").on("name");
                }
                String name = e.name().trim();
                if (!isValidJavaIdentifier(name)) {
                    throw new WizardArgumentException("entities[" + ei + "].name '" + name + "' is not a valid identifier").on("name");
                }
                if (RESERVED_JAVA_KEYWORDS.contains(name.toLowerCase(Locale.ROOT))) {
                    throw new WizardArgumentException("entities[" + ei + "].name '" + name + "' is a reserved keyword").on("name");
                }
                String taken = ReservedNames.reasonFor(name);
                if (taken != null) {
                    throw new WizardArgumentException("entities[" + ei + "].name '" + name + "' cannot be used: it is "
                            + taken + " — pick another name (a label can still say '" + name + "')").on("name");
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (!seenLowerNames.add(lower)) {
                    throw new WizardArgumentException("Duplicate entity name (case-insensitive): " + name).on("name");
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
                    try {
                        FullstackStarterRequest.FieldDefinitionDto f = rawFields.get(fi);
                        if (f == null) {
                            throw new WizardArgumentException("entities[" + ei + "].fields[" + fi + "] is null");
                        }
                        if (f.name() == null || f.name().isBlank()) {
                            throw new WizardArgumentException("entities[" + ei + "].fields[" + fi + "].name is required").on("name");
                        }
                        String fname = f.name().trim();
                        if (!isValidJavaIdentifier(fname)) {
                            throw new WizardArgumentException("Field name '" + fname + "' is not a valid identifier (entity '" + name + "')").on("name");
                        }
                        if (RESERVED_JAVA_KEYWORDS.contains(fname.toLowerCase(Locale.ROOT))) {
                            throw new WizardArgumentException("Field name '" + fname + "' is a reserved keyword (entity '" + name + "')").on("name");
                        }
                        if (!seenFieldNames.add(fname.toLowerCase(Locale.ROOT))) {
                            throw new WizardArgumentException("Duplicate field name '" + fname + "' (entity '" + name + "')").on("name");
                        }

                        FieldType type;
                        try {
                            type = FieldType.forWireString(f.type());
                        } catch (IllegalArgumentException iae) {
                            throw new WizardArgumentException("entity '" + name + "' field '" + fname + "': " + iae.getMessage()).on("type");
                        }

                        boolean isPk = Boolean.TRUE.equals(f.primaryKey());
                        if (isPk) pkCount++;

                        if (type == FieldType.ENUM) {
                            if (f.enumValues() == null || f.enumValues().isEmpty()) {
                                throw new WizardArgumentException("ENUM field '" + fname + "' on entity '" + name + "' requires enumValues").on("enumValues");
                            }
                            for (String v : f.enumValues()) {
                                if (v == null || v.isBlank() || !isValidJavaIdentifier(v)) {
                                    throw new WizardArgumentException("Invalid enum constant '" + v + "' on field '" + fname + "'").on("enumValues");
                                }
                                // Exact-case: Java keywords are all lower-case, so the conventional upper-case
                                // constants (NEW, DEFAULT, CLASS) are fine — only a literal `new` would not compile.
                                if (RESERVED_JAVA_KEYWORDS.contains(v)) {
                                    throw new WizardArgumentException("Enum constant '" + v + "' is a reserved keyword (field '" + fname + "' on entity '" + name + "')").on("enumValues");
                                }
                            }
                        } else if (f.enumValues() != null && !f.enumValues().isEmpty()) {
                            throw new WizardArgumentException("enumValues only allowed when type=ENUM (field '" + fname + "' on entity '" + name + "')").on("enumValues");
                        }
                        if (type != FieldType.ENUM && f.enumLabels() != null && !f.enumLabels().isEmpty()) {
                            throw new WizardArgumentException("enumLabels only allowed when type=ENUM (field '" + fname + "' on entity '" + name + "')").on("enumLabels");
                        }
                        Map<String, String> enumLabels = type == FieldType.ENUM
                                ? canonicalEnumLabels(f.enumLabels(), f.enumValues(), fname, name)
                                : Map.of();

                        if (f.length() != null && type != FieldType.STRING) {
                            throw new WizardArgumentException("length only allowed on STRING fields (field '" + fname + "' on entity '" + name + "')").on("length");
                        }
                        if (f.length() != null && f.length() <= 0) {
                            throw new WizardArgumentException("length must be positive (field '" + fname + "' on entity '" + name + "')").on("length");
                        }

                        // Numeric bounds: only on numeric types, and min <= max when both are present.
                        if ((f.min() != null || f.max() != null) && !type.isNumeric()) {
                            throw new WizardArgumentException("min/max only allowed on numeric fields (field '" + fname + "' on entity '" + name + "')");
                        }
                        // Integral types render the bounds into @Min/@Max (long-valued), so a fractional bound
                        // would not compile; decimals are only meaningful on BIG_DECIMAL (@DecimalMin/@DecimalMax).
                        if (!type.isDecimal()) {
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
                            throw new WizardArgumentException("pattern only allowed on STRING fields (field '" + fname + "' on entity '" + name + "')").on("pattern");
                        }
                        if (isEmail && type != FieldType.STRING) {
                            throw new WizardArgumentException("email only allowed on STRING fields (field '" + fname + "' on entity '" + name + "')");
                        }
                        if (f.pattern() != null && !f.pattern().isBlank()) {
                            try {
                                Pattern.compile(f.pattern());
                            } catch (PatternSyntaxException pse) {
                                throw new WizardArgumentException("pattern is not a valid regex (field '" + fname + "' on entity '" + name + "'): " + pse.getDescription()).on("pattern");
                            }
                        }

                        if (type.isAttachment() && viewQuery != null) {
                            throw new WizardArgumentException("a " + type + " field needs a table to keep its file's id in, "
                                    + "which a SELECT-backed view is not (field '" + fname + "' on entity '" + name + "')").on("type");
                        }
                        if (type.isAttachment() && Boolean.TRUE.equals(f.unique())) {
                            throw new WizardArgumentException("unique does not apply to a " + type + " field: every upload "
                                    + "is its own file (field '" + fname + "' on entity '" + name + "')").on("type");
                        }
                        if (isPk && !type.keyable()) {
                            throw new WizardArgumentException("a " + type + " field cannot be the primary key (field '"
                                    + fname + "' on entity '" + name + "')").on("type");
                        }
                        // A MONEY field's currency: three letters, upper-cased; ILS when omitted.
                        String currency = null;
                        if (f.currency() != null && !f.currency().isBlank()) {
                            if (!type.isMoney()) {
                                throw new WizardArgumentException("currency only allowed on MONEY fields (field '"
                                        + fname + "' on entity '" + name + "')").on("currency");
                            }
                            currency = f.currency().trim().toUpperCase(Locale.ROOT);
                            if (!currency.matches("[A-Z]{3}")) {
                                throw new WizardArgumentException("currency '" + f.currency().trim()
                                        + "' is not a three-letter currency code such as ILS or USD (field '"
                                        + fname + "' on entity '" + name + "')").on("currency");
                            }
                        } else if (type.isMoney()) {
                            currency = FieldDefinition.DEFAULT_CURRENCY;
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
                                column,
                                currency));
                    } catch (WizardArgumentException problem) {
                        throw problem.at("fields[" + fi + "]");
                    }
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
                            + "' is not a valid table name (" + SQL_NAME_RULE + ")").on("tableName");
                }
                if (schema != null && !SQL_NAME.matcher(schema).matches()) {
                    throw new WizardArgumentException("schema '" + schema + "' on entity '" + name
                            + "' is not a valid schema name (" + SQL_NAME_RULE + ")").on("schema");
                }
                // Optional entity display labels — blank → null (templates fall back to the name).
                String label = (e.label() == null || e.label().isBlank()) ? null : e.label().trim();
                String labelPlural = (e.labelPlural() == null || e.labelPlural().isBlank())
                        ? null : e.labelPlural().trim();
                parsed.add(new Parsed(ei, name, tableName, schema, fields, seenFieldNames, e.relations(),
                        readOnly, viewQuery, resolveListViews(e), label, labelPlural,
                        parseEntityOpts(e.opts(), name), e.formSections(), e.workflow(), pkCount));
            } catch (WizardArgumentException problem) {
                throw problem.at("entities[" + ei + "]");
            }
        }

        // An entity may not be named like a class another entity generates (OrderDto beside Order).
        for (Parsed p : parsed) {
            String owner = ReservedNames.ownerOfGeneratedClass(p.name(), seenLowerNames);
            if (owner != null) {
                throw new WizardArgumentException("entities[" + p.ei() + "].name '" + p.name()
                        + "' cannot be used: entity '" + canonicalByLower.get(owner)
                        + "' already generates a class of that name — pick another name",
                        "entities[" + p.ei() + "].name");
            }
        }

        // Pass 2 — resolve and validate relations now that all entity names are known.
        List<EntityDefinition> result = new ArrayList<>(parsed.size());
        for (Parsed p : parsed) {
            try {
                // A @Subselect view can't own a foreign-key join column (v1 — no relations on views).
                if (p.viewQuery() != null && p.rawRelations() != null && !p.rawRelations().isEmpty()) {
                    throw new WizardArgumentException("Entity '" + p.name()
                            + "' is a SELECT-backed view and cannot declare relations");
                }
                List<RelationDefinition> declared =
                        parseRelations(p.rawRelations(), p.name(), p.memberNames(), canonicalByLower,
                                pkCountByLower, viewLowerNames);
                // The foreign keys this entity owns, and — apart from them — its sets of other
                // rows. Everything downstream that walks `relations` means the former.
                List<RelationDefinition> relations = declared.stream()
                        .filter(r -> r.type() == RelationType.MANY_TO_ONE).toList();
                List<RelationDefinition> collections = declared.stream()
                        .filter(r -> r.type() == RelationType.MANY_TO_MANY).toList();
                // …and the lists of other entities' rows that point at it.
                List<RelationDefinition> children = declared.stream()
                        .filter(r -> r.type() == RelationType.ONE_TO_MANY).toList();
                checkColumnClashes(p.name(), p.fields(), relations);
                checkVersionName(p.name(), p.fields(), relations, p.readOnly(), p.viewQuery(), p.opts(), body);
                result.add(new EntityDefinition(p.name(), p.tableName(), p.schema(), p.fields(), relations,
                        p.readOnly(), p.viewQuery(), null, p.listViews(), p.label(), p.labelPlural(), p.opts(),
                        parseFormSections(p.rawSections(), p.name(), p.fields(), relations),
                        at("workflow", () -> parseWorkflow(p.rawWorkflow(), p.name(), p.fields(), relations,
                                p.readOnly(), p.pkCount())),
                        collections, children));
            } catch (WizardArgumentException problem) {
                throw problem.at("entities[" + p.ei() + "]");
            }
        }
        result = resolveChildLists(result, body);
        checkGeneratedMemberClashes(result, body);
        return result;
    }

    /**
     * A ONE_TO_MANY is the other side of a MANY_TO_ONE its target declares. Now that every
     * entity's foreign keys are known, settle which one: the {@code mappedBy} it names, or the
     * only one the target has to this entity.
     */
    private static List<EntityDefinition> resolveChildLists(List<EntityDefinition> entities,
                                                            FullstackStarterRequest body) {
        List<EntityDefinition> out = new ArrayList<>(entities.size());
        // Child entity → the parent whose form edits its rows: a row has one owner.
        Map<String, String> composedBy = new LinkedHashMap<>();
        for (int ei = 0; ei < entities.size(); ei++) {
            EntityDefinition parent = entities.get(ei);
            if (parent.children().isEmpty()) {
                out.add(parent);
                continue;
            }
            List<RelationDefinition> resolved = new ArrayList<>(parent.children().size());
            for (RelationDefinition list : parent.children()) {
                try {
                    RelationDefinition link = linkBehind(parent, list, entities);
                    boolean twice = resolved.stream().anyMatch(r -> r.targetEntity().equals(list.targetEntity())
                            && r.mappedBy().equals(link.fieldName()));
                    if (twice) {
                        throw new WizardArgumentException("entity '" + parent.name() + "' relation '" + list.fieldName()
                                + "' lists the same rows as another ONE_TO_MANY of this entity ('"
                                + list.targetEntity() + "' by '" + link.fieldName() + "')").on("mappedBy");
                    }
                    if (list.composition()) checkComposition(parent, list, link, entities, composedBy);
                    resolved.add(list.withMappedBy(link.fieldName()));
                } catch (WizardArgumentException problem) {
                    throw problem.at("relations[" + rawRelationIndex(body, ei, list.fieldName()) + "]")
                            .at("entities[" + ei + "]");
                }
            }
            out.add(parent.withChildren(resolved));
        }
        return out;
    }

    /**
     * A composition makes the child's rows part of the parent: edited in the parent's form, saved
     * and removed with it. That only works for a child that is plainly a row of its parent —
     * every other shape is refused here, saying which rule it breaks.
     */
    private static void checkComposition(EntityDefinition parent, RelationDefinition list, RelationDefinition link,
                                         List<EntityDefinition> entities, Map<String, String> composedBy) {
        EntityDefinition child = entities.stream()
                .filter(e -> e.name().equals(list.targetEntity())).findFirst().orElseThrow();
        String where = "entity '" + parent.name() + "' relation '" + list.fieldName() + "': composition — ";
        if (child.name().equals(parent.name())) {
            throw new WizardArgumentException(where + "an entity cannot be composed of its own rows").on("composition");
        }
        if (parent.readOnly() || parent.viewQuery() != null) {
            throw new WizardArgumentException(where + "'" + parent.name()
                    + "' is read-only, so it has no form to edit the rows in").on("composition");
        }
        if (child.readOnly() || child.viewQuery() != null) {
            throw new WizardArgumentException(where + "'" + child.name() + "' is read-only: its rows cannot be saved").on("composition");
        }
        List<FieldDefinition> keys = child.fields().stream().filter(FieldDefinition::primaryKey).toList();
        if (keys.size() != 1 || !keys.get(0).generated()) {
            throw new WizardArgumentException(where + "'" + child.name()
                    + "' needs a single generated key (a new row gets its id from the database)").on("composition");
        }
        if (!link.required()) {
            throw new WizardArgumentException(where + "'" + link.fieldName() + "' on '" + child.name()
                    + "' must be required (a composed row always belongs to its " + parent.name() + ")").on("composition");
        }
        if (child.workflow() != null) {
            throw new WizardArgumentException(where + "'" + child.name()
                    + "' has a workflow: its rows change by their own moves, not with a parent's save").on("composition");
        }
        for (FieldDefinition f : child.fields()) {
            if (f.type() == FieldType.FILE || f.type() == FieldType.IMAGE) {
                throw new WizardArgumentException(where + "'" + child.name() + "' has an attachment field ('" + f.name()
                        + "'), which a row of a parent's form cannot upload").on("composition");
            }
        }
        if (!child.collections().isEmpty() || !child.children().isEmpty()) {
            throw new WizardArgumentException(where + "'" + child.name()
                    + "' has lists of its own (a MANY_TO_MANY or a ONE_TO_MANY), which a row of a parent's form cannot edit")
                    .on("composition");
        }
        String owner = composedBy.putIfAbsent(child.name(), parent.name() + "." + list.fieldName());
        if (owner != null) {
            throw new WizardArgumentException(where + "'" + child.name() + "' is already composed by '" + owner
                    + "': a row has one owner").on("composition");
        }
    }

    /** The MANY_TO_ONE of {@code list}'s target that points back at {@code parent}. */
    private static RelationDefinition linkBehind(EntityDefinition parent, RelationDefinition list,
                                                 List<EntityDefinition> entities) {
        EntityDefinition child = entities.stream()
                .filter(e -> e.name().equals(list.targetEntity())).findFirst().orElseThrow();
        List<RelationDefinition> links = child.relations().stream()
                .filter(r -> r.targetEntity().equalsIgnoreCase(parent.name())).toList();
        String names = String.join(", ", links.stream().map(RelationDefinition::fieldName).toList());
        String where = "entity '" + parent.name() + "' relation '" + list.fieldName() + "'";
        if (list.mappedBy() != null) {
            return links.stream().filter(r -> r.fieldName().equalsIgnoreCase(list.mappedBy())).findFirst()
                    .orElseThrow(() -> new WizardArgumentException(where + ": mappedBy '" + list.mappedBy()
                            + "' is not a MANY_TO_ONE of '" + child.name() + "' to '" + parent.name() + "'"
                            + (links.isEmpty() ? "" : " (it has: " + names + ")")).on("mappedBy"));
        }
        if (links.isEmpty()) {
            throw new WizardArgumentException(where + " is a ONE_TO_MANY to '" + child.name()
                    + "', which has no MANY_TO_ONE to '" + parent.name() + "' — add that relation to '"
                    + child.name() + "' first").on("targetEntity");
        }
        if (links.size() > 1) {
            throw new WizardArgumentException(where + ": '" + child.name() + "' has several MANY_TO_ONEs to '"
                    + parent.name() + "' (" + names + ") — name one with mappedBy").on("mappedBy");
        }
        return links.get(0);
    }

    /** Where in the request's own list a relation sits (the parsed lists are split by kind). */
    private static int rawRelationIndex(FullstackStarterRequest body, int ei, String fieldName) {
        List<FullstackStarterRequest.RelationDefinitionDto> raw = body.entities().get(ei).relations();
        for (int ri = 0; raw != null && ri < raw.size(); ri++) {
            FullstackStarterRequest.RelationDefinitionDto r = raw.get(ri);
            if (r != null && r.fieldName() != null && r.fieldName().trim().equals(fieldName)) return ri;
        }
        return 0;
    }

    /**
     * The project's {@code opts.scaffold} names must be options the generator has. They used to be
     * read by name and anything else ignored, so a misspelt {@code softdelete} generated a project
     * without soft delete and said nothing.
     */
    private static void checkScaffoldOptions(FullstackStarterRequest body) {
        List<String> scaffold = body.opts() == null ? null : body.opts().get("scaffold");
        if (scaffold == null) return;
        for (int i = 0; i < scaffold.size(); i++) {
            String key = scaffold.get(i);
            if (key == null || !ScaffoldOption.isKnown(key)) {
                throw new WizardArgumentException("Unknown scaffold option '" + key + "' in opts.scaffold[" + i
                        + "] (known: " + String.join(", ", ScaffoldOption.keys()) + ")",
                        "opts.scaffold[" + i + "]");
            }
        }
    }

    /** Runs one section of the request, naming it in the path of whatever it rejects. */
    private static <T> T at(String segment, java.util.function.Supplier<T> section) {
        try {
            return section.get();
        } catch (WizardArgumentException problem) {
            throw problem.at(segment);
        }
    }

    /** Whether a scaffold option is on for an entity: its own override, else the project's. */
    private static boolean scaffoldOn(String key, EntityDefinition entity, FullstackStarterRequest body) {
        Boolean override = entity.opts() == null ? null : entity.opts().get(key);
        if (override != null) return override;
        List<String> scaffold = body.opts() == null ? null : body.opts().get("scaffold");
        return scaffold != null && scaffold.contains(key);
    }

    /**
     * Besides the members a model names, an entity gets ones the generator adds: {@code <relation>Id}
     * and {@code <relation>Label} for each MANY_TO_ONE, {@code createdAt}/{@code updatedAt} under
     * {@code audit}, {@code deleted} under {@code softDelete}, and — under
     * {@code inverseCollections} — a collection and its {@code <collection>Count} for every entity
     * that links to it. A field or relation spelled like one of those would be declared twice, so
     * the project would not compile; say so here instead. ({@code version} under optimistic
     * locking has its own check, {@link #checkVersionName}.)
     */
    private static void checkGeneratedMemberClashes(List<EntityDefinition> entities, FullstackStarterRequest body) {
        List<String> scaffold = body.opts() == null ? null : body.opts().get("scaffold");
        boolean inverse = scaffold != null && scaffold.contains("inverseCollections");
        Map<String, List<String>> collections = inverse
                ? EntityScaffoldContext.inverseCollectionNames(entities) : Map.of();

        for (EntityDefinition e : entities) {
            // Lower-cased member → what it is, in the words of the error.
            Map<String, String> members = new LinkedHashMap<>();
            for (FieldDefinition f : e.fields()) members.put(f.name().toLowerCase(Locale.ROOT), "field '" + f.name() + "'");
            for (RelationDefinition r : e.relations()) {
                members.put(r.fieldName().toLowerCase(Locale.ROOT), "relation '" + r.fieldName() + "'");
            }
            List<String[]> generated = new ArrayList<>();
            for (RelationDefinition r : e.relations()) {
                if (r.type() != RelationType.MANY_TO_ONE) continue;
                generated.add(new String[] {r.fieldName() + "Id", "the id of relation '" + r.fieldName() + "'"});
                // The label exists only when the target has something to show: a non-key string.
                boolean targetHasLabel = entities.stream()
                        .filter(t -> t.name().equalsIgnoreCase(r.targetEntity()))
                        .flatMap(t -> t.fields().stream())
                        .anyMatch(f -> !f.primaryKey() && f.type() == FieldType.STRING);
                if (targetHasLabel) {
                    generated.add(new String[] {r.fieldName() + "Label", "the label of relation '" + r.fieldName() + "'"});
                }
            }
            for (RelationDefinition c : e.collections()) {
                members.put(c.fieldName().toLowerCase(Locale.ROOT), "relation '" + c.fieldName() + "'");
                generated.add(new String[] {c.fieldName() + "Ids", "the ids of relation '" + c.fieldName() + "'"});
                generated.add(new String[] {c.fieldName() + "Labels", "the labels of relation '" + c.fieldName() + "'"});
            }
            for (RelationDefinition c : e.children()) {
                members.put(c.fieldName().toLowerCase(Locale.ROOT), "relation '" + c.fieldName() + "'");
                generated.add(new String[] {c.fieldName() + "Count", "the count of relation '" + c.fieldName() + "'"});
            }
            for (FieldDefinition f : e.fields()) {
                if (f.type().isAttachment()) {
                    generated.add(new String[] {f.name() + "Name", "the file name of '" + f.name() + "'"});
                }
            }
            boolean mutable = !e.readOnly() && e.viewQuery() == null;
            if (mutable && scaffoldOn("audit", e, body)) {
                generated.add(new String[] {"createdAt", "the 'audit' option's created-at timestamp"});
                generated.add(new String[] {"updatedAt", "the 'audit' option's updated-at timestamp"});
            }
            long keys = e.fields().stream().filter(FieldDefinition::primaryKey).count();
            if (mutable && keys == 1 && scaffoldOn("softDelete", e, body)) {
                generated.add(new String[] {"deleted", "the 'softDelete' option's flag"});
            }
            for (String collection : collections.getOrDefault(e.name().toLowerCase(Locale.ROOT), List.of())) {
                generated.add(new String[] {collection, "the collection of rows that link to it (option 'inverseCollections')"});
                generated.add(new String[] {collection + "Count", "the count of rows that link to it (option 'inverseCollections')"});
            }
            for (String[] g : generated) {
                String clash = members.putIfAbsent(g[0].toLowerCase(Locale.ROOT), g[1]);
                if (clash != null) {
                    throw new WizardArgumentException("Entity '" + e.name() + "': " + clash + " clashes with '"
                            + g[0] + "', which the generator adds as " + g[1] + " — rename it")
                            .at("entities[" + entities.indexOf(e) + "]");
                }
            }
        }
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
            try {
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
            } catch (WizardArgumentException problem) {
                throw problem.at("formSections[" + i + "]");
            }
        }
        return out;
    }

    static final int MAX_TRANSITIONS = 20;
    static final int MAX_TRANSITION_LABEL = 40;
    static final java.util.regex.Pattern TRANSITION_NAME = java.util.regex.Pattern.compile("^[a-z][A-Za-z0-9]{0,39}$");
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
            if (f.required()) {
                throw new WizardArgumentException(where + "assigneeField '" + f.name()
                        + "' cannot be required — a task nobody has taken has no assignee");
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
            try {
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
                    if (f != null && f.name().equals(assignee)) {
                        throw new WizardArgumentException(at + "requires '" + f.name()
                                + "', which a transition cannot set (the assignee changes by claiming and reassigning)");
                    }
                    if (requires.contains(canonical)) throw new WizardArgumentException(at + "requires '" + canonical + "' twice");
                    requires.add(canonical);
                }
                transitions.add(new EntityDefinition.Transition(name, label, List.copyOf(from), to, List.copyOf(roles), requires));
            } catch (WizardArgumentException problem) {
                throw problem.at("transitions[" + i + "]");
            }
        }
        return new EntityDefinition.Workflow(status.name(), initial, assignee, due, transitions);
    }

    /**
     * Type-checks a field's {@code defaultValue} against its {@link FieldType} and returns it in
     * canonical form (ENUM → the upper-cased constant, temporal → ISO, numbers as parsed), or null
     * when absent/blank. A default on a generated primary key is rejected (the database assigns it).
     */
    /** Longest display label accepted for a relation. */
    static final int MAX_RELATION_LABEL = 80;

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
                        + enumValues + " (field '" + fieldName + "' on entity '" + entityName + "')").on("enumLabels");
            }
            String label = e.getValue() == null ? "" : e.getValue().trim();
            if (label.isEmpty()) {
                throw new WizardArgumentException("enumLabels['" + key + "'] must not be blank (field '"
                        + fieldName + "' on entity '" + entityName + "')").on("enumLabels");
            }
            if (label.length() > MAX_ENUM_LABEL_LENGTH) {
                throw new WizardArgumentException("enumLabels['" + key + "'] is longer than " + MAX_ENUM_LABEL_LENGTH
                        + " characters (field '" + fieldName + "' on entity '" + entityName + "')").on("enumLabels");
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
            throw new WizardArgumentException("defaultValue is not allowed on a generated primary key" + where).on("defaultValue");
        }
        String v = raw.trim();
        try {
            return switch (type) {
                case STRING, TEXT, MARKDOWN -> {
                    if (length != null && raw.length() > length) {
                        throw new WizardArgumentException("defaultValue exceeds length " + length + where).on("defaultValue");
                    }
                    yield raw;
                }
                case LONG -> Long.toString(Long.parseLong(v));
                case INTEGER -> Integer.toString(Integer.parseInt(v));
                case BIG_DECIMAL -> new BigDecimal(v).toPlainString();
                case MONEY -> {
                    BigDecimal amount = new BigDecimal(v);
                    if (amount.stripTrailingZeros().scale() > 2) {
                        throw new WizardArgumentException("defaultValue '" + v
                                + "' has more than two decimals, which a MONEY field keeps" + where).on("defaultValue");
                    }
                    yield amount.toPlainString();
                }
                // "09:30" or "09:30:15" — normalized to what LocalTime prints.
                case LOCAL_TIME -> java.time.LocalTime.parse(v).toString();
                // ISO-8601 in UTC: "2026-01-31T08:00:00Z".
                case INSTANT -> java.time.Instant.parse(v).toString();
                case FILE, IMAGE -> throw new WizardArgumentException("defaultValue is not allowed on a " + type
                        + " field — a file is uploaded, not typed" + where).on("defaultValue");
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
                            + values + where).on("defaultValue");
                }
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new WizardArgumentException("defaultValue '" + v + "' is not a valid " + type + where).on("defaultValue");
        } catch (IllegalArgumentException e) { // UUID.fromString
            throw new WizardArgumentException("defaultValue '" + v + "' is not a valid " + type + where).on("defaultValue");
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
            try {
                FullstackStarterRequest.RelationDefinitionDto r = rawRelations.get(ri);
                if (r == null) {
                    throw new WizardArgumentException("entity '" + entityName + "' relations[" + ri + "] is null");
                }
                RelationType type;
                try {
                    type = RelationType.forWireString(r.type());
                } catch (IllegalArgumentException iae) {
                    throw new WizardArgumentException("entity '" + entityName + "' relation: " + iae.getMessage()).on("type");
                }
                boolean oneToMany = type == RelationType.ONE_TO_MANY;
                boolean manyToMany = type == RelationType.MANY_TO_MANY;

                if (r.fieldName() == null || r.fieldName().isBlank()) {
                    throw new WizardArgumentException("entity '" + entityName + "' relations[" + ri + "].fieldName is required").on("fieldName");
                }
                String fieldName = r.fieldName().trim();
                if (!isValidJavaIdentifier(fieldName)) {
                    throw new WizardArgumentException("Relation field name '" + fieldName + "' is not a valid identifier (entity '" + entityName + "')").on("fieldName");
                }
                if (RESERVED_JAVA_KEYWORDS.contains(fieldName.toLowerCase(Locale.ROOT))) {
                    throw new WizardArgumentException("Relation field name '" + fieldName + "' is a reserved keyword (entity '" + entityName + "')").on("fieldName");
                }
                // Collide against scalar fields and earlier relations (memberNames accumulates both).
                if (!memberNames.add(fieldName.toLowerCase(Locale.ROOT))) {
                    throw new WizardArgumentException("Relation field name '" + fieldName + "' collides with another field/relation (entity '" + entityName + "')").on("fieldName");
                }

                if (r.targetEntity() == null || r.targetEntity().isBlank()) {
                    throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName + "' requires a targetEntity").on("targetEntity");
                }
                String targetLower = r.targetEntity().trim().toLowerCase(Locale.ROOT);
                String canonicalTarget = canonicalByLower.get(targetLower);
                if (canonicalTarget == null) {
                    throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                            + "' targets unknown entity '" + r.targetEntity().trim() + "'");
                }
                // A single <field>Id foreign key cannot address a composite primary key, so a relation
                // may not point at a composite-PK entity in v1. (A ONE_TO_MANY holds no key of its
                // target: the rows it lists may be keyed any way.)
                Integer targetPkCount = pkCountByLower.get(targetLower);
                if (!oneToMany && targetPkCount != null && targetPkCount > 1) {
                    throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                            + "' targets composite-PK entity '" + canonicalTarget + "' — not supported");
                }
                if (viewLowerNames.contains(targetLower)) {
                    throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                            + "' targets SELECT-backed view '" + canonicalTarget + "' — not supported");
                }

                String joinTable = null;
                String inverseJoinColumn = null;
                if (manyToMany) {
                    // The join table holds one key of each side, so both need a single one; and a
                    // link from an entity to itself has two ends the model cannot tell apart.
                    if (targetLower.equals(entityName.toLowerCase(Locale.ROOT))) {
                        throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                                + "' is a MANY_TO_MANY to itself — not supported (model the link as an entity"
                                + " with two MANY_TO_ONEs)").on("targetEntity");
                    }
                    Integer ownPkCount = pkCountByLower.get(entityName.toLowerCase(Locale.ROOT));
                    if (ownPkCount != null && ownPkCount > 1) {
                        throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                                + "' is a MANY_TO_MANY, which a composite-key entity cannot own").on("type");
                    }
                    if (Boolean.TRUE.equals(r.required())) {
                        throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                                + "': required does not apply to a MANY_TO_MANY (it may always be empty)").on("required");
                    }
                    joinTable = parseColumnName(r.joinTable(), "joinTable", fieldName, entityName);
                    inverseJoinColumn = parseColumnName(r.inverseJoinColumn(), "inverseJoinColumn", fieldName, entityName);
                } else if ((r.joinTable() != null && !r.joinTable().isBlank())
                        || (r.inverseJoinColumn() != null && !r.inverseJoinColumn().isBlank())) {
                    throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                            + "': joinTable and inverseJoinColumn only apply to a MANY_TO_MANY").on("joinTable");
                }

                // A ONE_TO_MANY reads the link its target holds; it has no column of its own and
                // nothing to require. Which of the target's foreign keys it reads is settled once
                // every entity's are known (resolveChildLists).
                String mappedBy = null;
                if (oneToMany) {
                    if (Boolean.TRUE.equals(r.required())) {
                        throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                                + "': required does not apply to a ONE_TO_MANY (require the MANY_TO_ONE on '"
                                + canonicalTarget + "' instead)").on("required");
                    }
                    if (r.joinColumn() != null && !r.joinColumn().isBlank()) {
                        throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                                + "': joinColumn does not apply to a ONE_TO_MANY (the column belongs to the"
                                + " MANY_TO_ONE on '" + canonicalTarget + "')").on("joinColumn");
                    }
                    if (r.mappedBy() != null && !r.mappedBy().isBlank()) {
                        mappedBy = r.mappedBy().trim();
                        if (!isValidJavaIdentifier(mappedBy)) {
                            throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                                    + "': mappedBy '" + mappedBy + "' is not a valid identifier").on("mappedBy");
                        }
                    }
                } else if (r.mappedBy() != null && !r.mappedBy().isBlank()) {
                    throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                            + "': mappedBy only applies to a ONE_TO_MANY").on("mappedBy");
                }

                String label = (r.label() == null || r.label().isBlank()) ? null : r.label().trim();
                if (label != null && label.length() > MAX_RELATION_LABEL) {
                    throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                            + "': label is over " + MAX_RELATION_LABEL + " characters").on("label");
                }

                boolean composition = Boolean.TRUE.equals(r.composition());
                if (composition && !oneToMany) {
                    throw new WizardArgumentException("entity '" + entityName + "' relation '" + fieldName
                            + "': composition only applies to a ONE_TO_MANY (the rows that point at this entity)").on("composition");
                }

                relations.add(new RelationDefinition(type, fieldName, canonicalTarget,
                        Boolean.TRUE.equals(r.required()),
                        parseColumnName(r.joinColumn(), "joinColumn", fieldName, entityName),
                        joinTable, inverseJoinColumn, mappedBy, label, composition));
            } catch (WizardArgumentException problem) {
                throw problem.at("relations[" + ri + "]");
            }
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
                    + "' is not a valid column name (" + SQL_NAME_RULE + ")").on(prop);
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

    /**
     * The names a model may use: ASCII Java identifiers. Java itself allows any Unicode letter,
     * but these names become file names, URL segments, table and column names and JSON keys, and
     * the editor has always held them to ASCII — one rule on both sides (a label carries the
     * display text, in any script).
     */
    static final String IDENTIFIER_RULE = "^[A-Za-z_$][A-Za-z0-9_$]*$";
    private static final Pattern IDENTIFIER = Pattern.compile(IDENTIFIER_RULE);

    static boolean isValidJavaIdentifier(String s) {
        return s != null && IDENTIFIER.matcher(s).matches();
    }
}
