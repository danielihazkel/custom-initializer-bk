package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

        Set<String> seenLowerNames = new HashSet<>();
        List<EntityDefinition> result = new ArrayList<>(raw.size());
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

            List<FullstackStarterRequest.FieldDefinitionDto> rawFields = e.fields();
            if (rawFields == null || rawFields.isEmpty()) {
                throw new WizardArgumentException("entities[" + ei + "] '" + name + "' has no fields");
            }

            int pkCount = 0;
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

                fields.add(new FieldDefinition(
                        fname,
                        type,
                        isPk,
                        Boolean.TRUE.equals(f.generated()),
                        Boolean.TRUE.equals(f.required()),
                        Boolean.TRUE.equals(f.unique()),
                        f.length(),
                        f.enumValues() == null ? List.of() : List.copyOf(f.enumValues())));
            }

            if (pkCount == 0) {
                throw new WizardArgumentException("Entity '" + name + "' has no primary key field");
            }
            if (pkCount > 1) {
                throw new WizardArgumentException("Entity '" + name + "' has multiple primary key fields (only one allowed in v1)");
            }

            String tableName = (e.tableName() == null || e.tableName().isBlank())
                    ? null : e.tableName().trim();
            result.add(new EntityDefinition(name, tableName, fields));
        }
        return result;
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
