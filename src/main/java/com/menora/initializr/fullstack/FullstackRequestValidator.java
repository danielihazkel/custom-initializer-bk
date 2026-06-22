package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    private static final Set<String> RESERVED_JAVA_KEYWORDS = Set.of(
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
                      boolean readOnly, String viewQuery) {}

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
                if (!seenFieldNames.add(fname)) {
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
                        if (RESERVED_JAVA_KEYWORDS.contains(v.toLowerCase(Locale.ROOT))) {
                            throw new WizardArgumentException("Enum constant '" + v + "' is a reserved keyword (field '" + fname + "' on entity '" + name + "')");
                        }
                    }
                } else if (f.enumValues() != null && !f.enumValues().isEmpty()) {
                    throw new WizardArgumentException("enumValues only allowed when type=ENUM (field '" + fname + "' on entity '" + name + "')");
                }

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
                if (f.min() != null && f.max() != null && f.min() > f.max()) {
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
                    if (type != FieldType.LONG && type != FieldType.INTEGER) {
                        throw new WizardArgumentException("a generated primary key must be of type LONG or INTEGER (field '" + fname + "' on entity '" + name + "')");
                    }
                }

                String pattern = (f.pattern() == null || f.pattern().isBlank()) ? null : f.pattern();
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
                        f.enumValues() == null ? List.of() : List.copyOf(f.enumValues())));
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
            parsed.add(new Parsed(ei, name, tableName, schema, fields, seenFieldNames, e.relations(),
                    readOnly, viewQuery));
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
            result.add(new EntityDefinition(p.name(), p.tableName(), p.schema(), p.fields(), relations,
                    p.readOnly(), p.viewQuery()));
        }
        return result;
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
            if (!memberNames.add(fieldName)) {
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
                    Boolean.TRUE.equals(r.required())));
        }
        return relations;
    }

    private static boolean isValidJavaIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }
}
