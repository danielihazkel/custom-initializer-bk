package com.menora.initializr.fullstack;

import java.util.Locale;

/**
 * Supported primitive field types for fullstack CRUD entities. v1 deliberately omits
 * relationships, embedded types, and collections.
 */
public enum FieldType {
    STRING("String", "string", "java.lang.String"),
    LONG("Long", "number", "java.lang.Long"),
    INTEGER("Integer", "number", "java.lang.Integer"),
    BOOLEAN("Boolean", "boolean", "java.lang.Boolean"),
    LOCAL_DATE("LocalDate", "string", "java.time.LocalDate"),
    LOCAL_DATE_TIME("LocalDateTime", "string", "java.time.LocalDateTime"),
    BIG_DECIMAL("BigDecimal", "number", "java.math.BigDecimal"),
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
    public boolean isNumeric() { return this == LONG || this == INTEGER || this == BIG_DECIMAL; }
    public boolean isBoolean() { return this == BOOLEAN; }
    public boolean isTemporal() { return this == LOCAL_DATE || this == LOCAL_DATE_TIME; }
    public boolean isEnum() { return this == ENUM; }

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
            default -> throw new IllegalArgumentException("Unknown field type: " + raw);
        };
    }
}
