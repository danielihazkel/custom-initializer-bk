package com.menora.initializr.fullstack;

import java.util.Locale;

/**
 * Kinds of entity relationship the fullstack scaffold can model: {@link #MANY_TO_ONE} (the
 * foreign-key-owning side), {@link #MANY_TO_MANY} (the owning side of a link through a join
 * table) and {@link #ONE_TO_MANY} (the read side of a MANY_TO_ONE the target declares).
 */
public enum RelationType {
    MANY_TO_ONE,
    ONE_TO_MANY,
    MANY_TO_MANY;

    /** Lenient parse — accepts canonical ({@code "MANY_TO_ONE"}), hyphen/space variants,
     *  and the JPA-style spelling ({@code "ManyToOne"}). */
    public static RelationType forWireString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Relation type is required");
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        // Normalize a camel-case "ManyToOne" → "MANY_TO_ONE".
        String camel = raw.trim()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (RelationType t : values()) {
            if (t.name().equals(key) || t.name().equals(camel)) return t;
        }
        throw new IllegalArgumentException("Unknown relation type: " + raw);
    }
}
