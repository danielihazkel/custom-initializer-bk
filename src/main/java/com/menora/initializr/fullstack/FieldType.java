package com.menora.initializr.fullstack;

import java.util.Locale;

/**
 * Supported primitive field types for fullstack CRUD entities. v1 deliberately omits
 * relationships, embedded types, and collections.
 */
public enum FieldType {
    STRING("String", "string", "java.lang.String"),
    // Long text (notes/descriptions). Maps to a SQL TEXT column (columnDefinition) rather than
    // @Lob/CLOB so the generated search Specification's lower()/LIKE still works; rendered as a
    // <textarea> in the form. Same Java type as STRING, so it carries no extra import.
    TEXT("String", "string", "java.lang.String"),
    LONG("Long", "number", "java.lang.Long"),
    INTEGER("Integer", "number", "java.lang.Integer"),
    BOOLEAN("Boolean", "boolean", "java.lang.Boolean"),
    LOCAL_DATE("LocalDate", "string", "java.time.LocalDate"),
    LOCAL_DATE_TIME("LocalDateTime", "string", "java.time.LocalDateTime"),
    BIG_DECIMAL("BigDecimal", "number", "java.math.BigDecimal"),
    // Java type is the fully-qualified java.util.UUID with a null import on purpose: the bare type
    // is used unqualified in the Repository/Service (which have no per-field import block), so the
    // FQN keeps every render site compiling without threading an import through them.
    UUID("java.util.UUID", "string", null),
    ENUM("", "string", null); // javaType set per-field to <EntityName><FieldName>

    private final String javaType;
    private final String tsType;
    private final String javaImport;

    FieldType(String javaType, String tsType, String javaImport) {
        this.javaType = javaType;
        this.tsType = tsType;
        this.javaImport = javaImport;
    }

    public String javaType() { return javaType; }
    public String tsType() { return tsType; }
    public String javaImport() { return javaImport; }

    public boolean isString() { return this == STRING; }
    public boolean isText() { return this == TEXT; }
    public boolean isUuid() { return this == UUID; }
    public boolean isNumeric() { return this == LONG || this == INTEGER || this == BIG_DECIMAL; }
    public boolean isBoolean() { return this == BOOLEAN; }
    public boolean isTemporal() { return this == LOCAL_DATE || this == LOCAL_DATE_TIME; }
    public boolean isEnum() { return this == ENUM; }

    /** STRING or TEXT — both back the generated search Specification (lower()/LIKE). */
    public boolean isTextual() { return this == STRING || this == TEXT; }

    /** Lenient parse — accepts canonical names ({@code "STRING"}), Java-style ({@code "String"}),
     *  and underscored variants ({@code "local_date"}). */
    public static FieldType forWireString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Field type is required");
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (FieldType t : values()) {
            if (t.name().equals(key)) return t;
            if (t.javaType.equalsIgnoreCase(raw.trim())) return t;
        }
        // Convenience aliases
        return switch (key) {
            case "INT" -> INTEGER;
            case "BOOL" -> BOOLEAN;
            case "DATE" -> LOCAL_DATE;
            case "DATETIME", "TIMESTAMP" -> LOCAL_DATE_TIME;
            case "DECIMAL", "NUMERIC" -> BIG_DECIMAL;
            case "LONGTEXT", "CLOB" -> TEXT;
            case "GUID" -> UUID;
            default -> throw new IllegalArgumentException("Unknown field type: " + raw);
        };
    }
}
