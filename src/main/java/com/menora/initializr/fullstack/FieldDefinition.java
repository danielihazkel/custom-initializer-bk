package com.menora.initializr.fullstack;

import java.math.BigDecimal;
import java.util.List;

/**
 * One field on a user-defined entity. Submitted by clients as JSON; converted from
 * the wire form by {@link FullstackRequestValidator}.
 *
 * <p>{@code min}/{@code max} are numeric bounds (whole numbers on integral types, any
 * decimal on {@code BigDecimal}; rendered as Bean Validation
 * {@code @Min}/{@code @Max} on integral types or {@code @DecimalMin}/{@code @DecimalMax}
 * on {@code BigDecimal}). {@code pattern} is a regex and {@code email} a convenience flag,
 * both for {@code STRING} fields ({@code @Pattern} / {@code @Email}).
 *
 * <p>{@code searchable}/{@code filterable} are per-field opt-out flags, both defaulting to
 * {@code true} (resolved at the wire boundary in {@link FullstackRequestValidator}).
 * {@code searchable} only matters for STRING/TEXT fields (the text-search box); {@code filterable}
 * only for non-PK enum/boolean/temporal/numeric fields (the filter bar). Flags set on a field
 * whose type can't carry them are harmlessly ignored — the collection loops gate on type first.
 *
 * <p>{@code label} is an optional human-facing display override for the generated UI
 * (table header, form label, filter chip, detail row); when null/blank the templates fall
 * back to the PascalCase of {@code name}. Enables e.g. Hebrew column names. {@code readOnly}
 * marks the field <em>locked after create</em> — editable when creating a new row but disabled
 * on edit and never overwritten in {@code Service.update} (mirrors non-generated-PK behavior).
 *
 * <p>{@code defaultValue} is an optional, already type-checked default in canonical string form
 * (validated and normalized by {@link FullstackRequestValidator} — e.g. an ENUM default is the
 * upper-cased constant, a LOCAL_DATE the ISO form). Rendered as the entity field's Java
 * initializer, as the form's initial value for a new row, and by the demo-data seeder for
 * non-key, non-unique fields. Never set on a generated PK.
 */
public record FieldDefinition(
        String name,
        FieldType type,
        boolean primaryKey,
        boolean generated,
        boolean required,
        boolean unique,
        Integer length,
        BigDecimal min,
        BigDecimal max,
        String pattern,
        boolean email,
        List<String> enumValues,
        boolean searchable,
        boolean filterable,
        String label,
        boolean readOnly,
        String defaultValue) {

    /** Back-compat constructor without the {@code label}/{@code readOnly}/{@code defaultValue}
     *  per-field props (no custom label, editable, no default). Keeps existing callers/tests compiling. */
    public FieldDefinition(String name, FieldType type, boolean primaryKey, boolean generated,
                           boolean required, boolean unique, Integer length, BigDecimal min, BigDecimal max,
                           String pattern, boolean email, List<String> enumValues,
                           boolean searchable, boolean filterable) {
        this(name, type, primaryKey, generated, required, unique, length, min, max,
                pattern, email, enumValues, searchable, filterable, null, false, null);
    }

    /** Back-compat constructor without {@code defaultValue} (no default). */
    public FieldDefinition(String name, FieldType type, boolean primaryKey, boolean generated,
                           boolean required, boolean unique, Integer length, BigDecimal min, BigDecimal max,
                           String pattern, boolean email, List<String> enumValues,
                           boolean searchable, boolean filterable, String label, boolean readOnly) {
        this(name, type, primaryKey, generated, required, unique, length, min, max,
                pattern, email, enumValues, searchable, filterable, label, readOnly, null);
    }

    /** True when a validated default is present. */
    public boolean hasDefault() {
        return defaultValue != null;
    }
}
