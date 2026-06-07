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
        List<String> enumValues) {
}
