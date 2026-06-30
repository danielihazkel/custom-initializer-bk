package com.menora.initializr.fullstack;

import java.util.List;

/**
 * One field on a user-defined entity. Submitted by clients as JSON; converted from
 * the wire form by {@link FullstackRequestValidator}.
 *
 * <p>{@code min}/{@code max} are numeric bounds (integral; rendered as Bean Validation
 * {@code @Min}/{@code @Max} on integral types or {@code @DecimalMin}/{@code @DecimalMax}
 * on {@code BigDecimal}). {@code pattern} is a regex and {@code email} a convenience flag,
 * both for {@code STRING} fields ({@code @Pattern} / {@code @Email}).
 *
 * <p>{@code searchable}/{@code filterable} are per-field opt-out flags, both defaulting to
 * {@code true} (resolved at the wire boundary in {@link FullstackRequestValidator}).
 * {@code searchable} only matters for STRING/TEXT fields (the text-search box); {@code filterable}
 * only for non-PK enum/boolean/temporal/numeric fields (the filter bar). Flags set on a field
 * whose type can't carry them are harmlessly ignored — the collection loops gate on type first.
 */
public record FieldDefinition(
        String name,
        FieldType type,
        boolean primaryKey,
        boolean generated,
        boolean required,
        boolean unique,
        Integer length,
        Long min,
        Long max,
        String pattern,
        boolean email,
        List<String> enumValues,
        boolean searchable,
        boolean filterable) {
}
