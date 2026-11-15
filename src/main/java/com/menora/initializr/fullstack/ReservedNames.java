package com.menora.initializr.fullstack;

import com.menora.initializr.gen.Naming;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Entity names the generator cannot use, because the class or component it would generate
 * collides with something the generated code already names: a type the templates import
 * ({@code Page}, {@code List}, {@code Table}), an annotation ({@code Entity}, {@code Column}),
 * a fixed class whose bean name or file path the entity's own would take
 * ({@code Me} → {@code MeController}, {@code Dashboard} → {@code src/pages/dashboard}), or a
 * hook ({@code State} → {@code useState}).
 *
 * <p>The list lives in {@code fullstack/reserved-type-names.txt}, one name per line, grouped
 * under {@code # reason} headings that become the error message. It is not maintained by
 * memory: {@code ReservedNamesGuardTest} derives the clashing names from the templates
 * themselves and fails when one is missing here, so a template that starts importing a new
 * type cannot quietly make a new entity name uncompilable. The Java types of the field types
 * are added from {@link FieldType}, so a new field type reserves its own.
 */
public final class ReservedNames {

    static final String RESOURCE = "fullstack/reserved-type-names.txt";

    /** Suffixes of the classes generated per entity: {@code Order} + {@code Dto} is taken by {@code Order}. */
    static final List<String> GENERATED_SUFFIXES = List.of("Dto", "Service", "Controller", "Repository", "Id");

    /** Lower-cased name → why it is taken. */
    private static final Map<String, String> REASONS = load();

    private ReservedNames() {
    }

    /**
     * Why {@code entityName} cannot be an entity's name, or null when it can. Matched ignoring
     * case on the class name the generator derives ({@link Naming#toPascalCase}).
     */
    public static String reasonFor(String entityName) {
        if (entityName == null) return null;
        return REASONS.get(Naming.toPascalCase(entityName).toLowerCase(Locale.ROOT));
    }

    /** Every reserved name, lower-cased — what the editor mirrors. */
    public static Set<String> all() {
        return Collections.unmodifiableSet(new TreeSet<>(REASONS.keySet()));
    }

    /**
     * The entity whose generated class {@code entityName} would take ({@code OrderDto} beside
     * {@code Order}), or null. {@code lowerNames} are the request's entity names, lower-cased.
     */
    public static String ownerOfGeneratedClass(String entityName, Set<String> lowerNames) {
        String lower = Naming.toPascalCase(entityName).toLowerCase(Locale.ROOT);
        for (String suffix : GENERATED_SUFFIXES) {
            String s = suffix.toLowerCase(Locale.ROOT);
            if (lower.length() > s.length() && lower.endsWith(s)
                    && lowerNames.contains(lower.substring(0, lower.length() - s.length()))) {
                return lower.substring(0, lower.length() - s.length());
            }
        }
        return null;
    }

    private static Map<String, String> load() {
        Map<String, String> reasons = new LinkedHashMap<>();
        String text;
        try {
            text = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing " + RESOURCE, e);
        }
        String reason = "a name the generated code already uses";
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("##")) continue;
            if (line.startsWith("#")) {
                reason = line.substring(1).trim();
                continue;
            }
            reasons.putIfAbsent(line.toLowerCase(Locale.ROOT), reason);
        }
        for (FieldType type : FieldType.values()) {
            String java = type.javaType();
            if (java == null || java.isBlank()) continue;
            String simple = java.substring(java.lastIndexOf('.') + 1);
            reasons.putIfAbsent(simple.toLowerCase(Locale.ROOT), "the Java type of a field");
        }
        return reasons;
    }
}
