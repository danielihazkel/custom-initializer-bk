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
    ENUM("", "string", null), // javaType set per-field to <EntityName><FieldName>

    // Round 9. Declared after the types above on purpose: forWireString matches a Java type name
    // against the constants in order, so "String" and "BigDecimal" keep meaning STRING and
    // BIG_DECIMAL.
    //
    // A time of day ("09:30"), no date and no zone. An <input type="time"> in the form.
    LOCAL_TIME("LocalTime", "string", "java.time.LocalTime"),
    // A point in time, stored and sent in UTC (ISO-8601 with a Z); the form shows and takes it in
    // the browser's zone. Shown and sorted like any column; not a filter, chart or calendar date —
    // those work in local dates, which a zoned instant is not.
    INSTANT("Instant", "string", "java.time.Instant"),
    // An amount of money: a BigDecimal with two decimals (precision 19, scale 2) and a currency
    // (FieldDefinition.currency, a field-wide ISO code), formatted for the app's locale. Sums,
    // averages, ranges and charts treat it as the number it is.
    MONEY("BigDecimal", "number", "java.math.BigDecimal"),
    // Long text written in Markdown — the content page's subset — shown formatted in the detail
    // view. Stored, searched and edited like TEXT.
    MARKDOWN("String", "string", "java.lang.String"),
    // An uploaded file, and one that must be a picture. The column holds the id of a row in the
    // generated attachment table (a UUID, null without a file); the file itself is uploaded on
    // its own and fetched only when opened. Neither searched, filtered, sorted on nor bulk-edited.
    FILE("java.util.UUID", "string", null),
    IMAGE("java.util.UUID", "string", null);

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
    /** Long text: a TEXT column and a textarea. TEXT, and MARKDOWN which is TEXT shown formatted. */
    public boolean isText() { return this == TEXT || this == MARKDOWN; }
    public boolean isMarkdown() { return this == MARKDOWN; }
    public boolean isUuid() { return this == UUID; }
    public boolean isNumeric() { return this == LONG || this == INTEGER || this == BIG_DECIMAL || this == MONEY; }
    /** A number with decimals: bounds and steps need not be whole. */
    public boolean isDecimal() { return this == BIG_DECIMAL || this == MONEY; }
    public boolean isMoney() { return this == MONEY; }
    public boolean isTime() { return this == LOCAL_TIME; }
    public boolean isInstant() { return this == INSTANT; }
    /** A file or an image: an attachment id. */
    public boolean isAttachment() { return this == FILE || this == IMAGE; }
    public boolean isImage() { return this == IMAGE; }
    public boolean isBoolean() { return this == BOOLEAN; }
    public boolean isTemporal() { return this == LOCAL_DATE || this == LOCAL_DATE_TIME; }
    public boolean isEnum() { return this == ENUM; }

    /** STRING or TEXT — both back the generated search Specification (lower()/LIKE). */
    public boolean isTextual() { return this == STRING || isText(); }

    /**
     * Whether a field of this type may be (part of) the primary key. The Round 9 types may not: a
     * key travels in URLs and relation ids, which an amount, a formatted text, a bare time or an
     * instant are not fit for.
     */
    public boolean keyable() {
        return this != LOCAL_TIME && this != INSTANT && this != MONEY && this != MARKDOWN && !isAttachment();
    }

    /**
     * Whether a list can be sorted by the field. Not by long text (a TEXT/CLOB column does not
     * sort on every database) and not by a file (its value is an id, which orders nothing).
     */
    public boolean sortable() {
        return this != TEXT && this != MARKDOWN && !isAttachment();
    }

    /**
     * Whether the bulk-edit bar and inline cells offer the field: they take one typed value, which
     * suits neither a formatted text nor a time the browser must convert.
     */
    public boolean bulkEditable() {
        return this != LOCAL_TIME && this != INSTANT && this != MARKDOWN && !isAttachment();
    }

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
        FieldType alias = ALIASES.get(key);
        if (alias == null) throw new IllegalArgumentException("Unknown field type: " + raw);
        return alias;
    }

    /**
     * Convenience spellings {@link #forWireString} takes besides a type's own name and its Java
     * type, upper-cased — the SQL-ish names people type and paste. Served to the editor with the
     * schema, so its quick-add accepts the same ones.
     */
    public static final java.util.Map<String, FieldType> ALIASES;
    static {
        java.util.Map<String, FieldType> m = new java.util.LinkedHashMap<>();
        m.put("INT", INTEGER);
        m.put("BOOL", BOOLEAN);
        m.put("DATE", LOCAL_DATE);
        m.put("DATETIME", LOCAL_DATE_TIME);
        m.put("TIMESTAMP", LOCAL_DATE_TIME);
        m.put("DECIMAL", BIG_DECIMAL);
        m.put("NUMERIC", BIG_DECIMAL);
        m.put("LONGTEXT", TEXT);
        m.put("CLOB", TEXT);
        m.put("GUID", UUID);
        m.put("TIME", LOCAL_TIME);
        m.put("RICH_TEXT", MARKDOWN);
        m.put("RICHTEXT", MARKDOWN);
        m.put("ATTACHMENT", FILE);
        m.put("PHOTO", IMAGE);
        m.put("PICTURE", IMAGE);
        ALIASES = java.util.Collections.unmodifiableMap(m);
    }
}
