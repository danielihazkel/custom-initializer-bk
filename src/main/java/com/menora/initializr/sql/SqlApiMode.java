package com.menora.initializr.sql;

import java.util.Locale;

/**
 * How much of a REST stack the SQL wizard generates on top of the parsed
 * entities. Mirrors {@code OpenApiWizardOptions.GenerationMode}.
 *
 * <ul>
 *   <li>{@link #NONE} — entities (+ optional repositories) only. The original,
 *       default behavior.</li>
 *   <li>{@link #ENTITY_DIRECT} — also emit Repository + Service + Controller,
 *       with the controller exposing the JPA entity directly (no DTO).</li>
 *   <li>{@link #INLINE_DTO} — also emit a record DTO with hand-written
 *       {@code from(entity)}/{@code toEntity()} (no extra dependency).</li>
 *   <li>{@link #MAPSTRUCT_DTO} — also emit a record DTO + a MapStruct
 *       {@code @Mapper} interface (auto-adds the {@code mapstruct} dependency).</li>
 * </ul>
 */
public enum SqlApiMode {
    NONE, ENTITY_DIRECT, INLINE_DTO, MAPSTRUCT_DTO;

    /** Lenient parse — null/blank/unknown falls back to {@link #NONE}. */
    public static SqlApiMode parse(String raw) {
        if (raw == null || raw.isBlank()) return NONE;
        try {
            return SqlApiMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    public boolean generatesApi() {
        return this != NONE;
    }

    public boolean generatesDto() {
        return this == INLINE_DTO || this == MAPSTRUCT_DTO;
    }
}
