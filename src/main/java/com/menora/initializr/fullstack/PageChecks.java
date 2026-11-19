package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;

import java.util.Locale;
import java.util.Map;

import static com.menora.initializr.fullstack.FullstackPageValidator.*;
import static com.menora.initializr.fullstack.PageListRules.*;
import static com.menora.initializr.fullstack.PagePresets.*;
import static com.menora.initializr.fullstack.DashboardWidgetRules.*;
import static com.menora.initializr.fullstack.ReportChartRules.*;
import static com.menora.initializr.fullstack.PageStructureRules.*;

/** The small checks every page type shares. Part of {@link FullstackPageValidator}. */
final class PageChecks {

    private PageChecks() {}

    /** The entity's field named {@code requested}, matched ignoring case (so a hand-typed
     *  {@code "Status"} finds {@code status}); the returned field carries the declared spelling. */
    static FieldDefinition fieldOf(String prefix, EntityDefinition entity, String requested, String what) {
        return entity.fields().stream()
                .filter(f -> f.name().equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new WizardArgumentException(prefix + ": " + what + " '" + requested
                        + "' is not a field of " + entity.name()));
    }

    static EntityDefinition requireEntity(Map<String, EntityDefinition> byLower, String raw, String prefix) {
        String name = trimToNull(raw);
        if (name == null) throw new WizardArgumentException(prefix + ": entity is required");
        EntityDefinition e = byLower.get(name.toLowerCase(Locale.ROOT));
        if (e == null) throw new WizardArgumentException(prefix + ": unknown entity '" + name + "'");
        return e;
    }

    static String checkLength(String value, int max, String what) {
        if (value != null && value.length() > max) {
            throw new WizardArgumentException(what + " must be at most " + max + " characters");
        }
        return value;
    }

    static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
