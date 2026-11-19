package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.menora.initializr.fullstack.FullstackPageValidator.*;
import static com.menora.initializr.fullstack.PageListRules.*;
import static com.menora.initializr.fullstack.DashboardWidgetRules.*;
import static com.menora.initializr.fullstack.ReportChartRules.*;
import static com.menora.initializr.fullstack.PageStructureRules.*;
import static com.menora.initializr.fullstack.PageChecks.*;

/** Preset filters and date ranges of a page or a widget. Part of {@link FullstackPageValidator}. */
final class PagePresets {

    private PagePresets() {}

    /**
     * Preset filters: equality on a filterable enum/boolean field (enum constants canonicalized,
     * matched ignoring case; an enum may name several, {@code OPEN,IN_PROGRESS}, any of which
     * matches), the id(s) a MANY_TO_ONE points at (keyed by the relation's field name or its
     * {@code <field>Id} filter, kept as the latter), a range on a filterable date or number field ({@code 2026-01-01..2026-03-31},
     * {@code 100..500}, either side optional) or a period ending on the day the app is opened on a
     * date field ({@code last:7d}, {@code last:30d}, {@code last:90d}, {@code ytd}, {@code 12m}).
     * The result maps list params to literals — a range becomes {@code <field>From}/{@code <field>To}
     * or {@code <field>Min}/{@code <field>Max} — except a period, kept under the field's own name as
     * {@code @<period>} for the renderer to turn into a {@code rangeParams(...)} call (it depends on
     * today's date). {@code owner} names the page or widget in the error.
     */
    static Map<String, String> presetFilter(String owner, EntityDefinition entity, Map<String, String> raw,
                                            Map<String, EntityDefinition> entitiesByLower) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> en : raw.entrySet()) {
            String prefix = owner + " presetFilter '" + en.getKey() + "'";
            FieldDefinition field = entity.fields().stream()
                    .filter(f -> f.name().equalsIgnoreCase(en.getKey()))
                    .findFirst()
                    .orElse(null);
            if (field == null) {
                RelationDefinition relation = entity.relations().stream()
                        .filter(r -> r.fieldName().equalsIgnoreCase(en.getKey())
                                || (r.fieldName() + "Id").equalsIgnoreCase(en.getKey()))
                        .findFirst()
                        .orElseThrow(() -> new WizardArgumentException(prefix + ": no such field or relation on " + entity.name()));
                String ids = trimToNull(en.getValue());
                if (ids == null) throw new WizardArgumentException(prefix + ": value is required");
                out.put(relation.fieldName() + "Id", relationIds(prefix, relation, ids, entitiesByLower));
                continue;
            }
            FieldType type = field.type();
            if (field.primaryKey() || !field.filterable()
                    || !(type.isEnum() || type.isBoolean() || type.isTemporal() || type.isNumeric())) {
                throw new WizardArgumentException(prefix + ": only filterable enum, boolean, date or number fields can be preset");
            }
            String value = trimToNull(en.getValue());
            if (value == null) throw new WizardArgumentException(prefix + ": value is required");
            if (type.isBoolean()) {
                if (value.contains(",")) {
                    throw new WizardArgumentException(prefix + ": a yes/no field takes one value, got '" + value + "'");
                }
                String lower = value.toLowerCase(Locale.ROOT);
                if (!lower.equals("true") && !lower.equals("false")) {
                    throw new WizardArgumentException(prefix + ": expected true or false, got '" + value + "'");
                }
                out.put(field.name(), lower);
            } else if (type.isEnum()) {
                Set<String> picked = new HashSet<>();
                for (String part : value.split(",", -1)) {
                    String one = part.trim();
                    if (one.isEmpty()) throw new WizardArgumentException(prefix + ": '" + value + "' has an empty value");
                    picked.add(field.enumValues().stream()
                            .filter(c -> c.equalsIgnoreCase(one))
                            .findFirst()
                            .orElseThrow(() -> new WizardArgumentException(prefix + ": '" + one
                                    + "' is not one of " + field.enumValues())));
                }
                // In the enum's own order, so one choice always renders (and routes) the same way.
                out.put(field.name(), String.join(",", field.enumValues().stream().filter(picked::contains).toList()));
            } else if (type.isTemporal()) {
                String period = presetPeriod(value);
                if (period != null) {
                    out.put(field.name(), "@" + period);
                    continue;
                }
                String[] range = presetRange(prefix, value,
                        "a date range (2026-01-01..2026-03-31, either side optional) or a period (last:7d, last:30d, last:90d, ytd, 12m)");
                boolean dateTime = type == FieldType.LOCAL_DATE_TIME;
                String from = range[0] == null ? null : isoDate(prefix, range[0], dateTime, false);
                String to = range[1] == null ? null : isoDate(prefix, range[1], dateTime, true);
                if (from != null && to != null && from.compareTo(to) > 0) {
                    throw new WizardArgumentException(prefix + ": from '" + range[0] + "' is after to '" + range[1] + "'");
                }
                if (from != null) out.put(field.name() + "From", from);
                if (to != null) out.put(field.name() + "To", to);
            } else {
                String[] range = presetRange(prefix, value, "a number range (100..500, either side optional)");
                java.math.BigDecimal min = range[0] == null ? null : number(prefix, range[0], "min");
                java.math.BigDecimal max = range[1] == null ? null : number(prefix, range[1], "max");
                if (min != null && max != null && min.compareTo(max) > 0) {
                    throw new WizardArgumentException(prefix + ": min " + min.toPlainString() + " is above max " + max.toPlainString());
                }
                if (min != null) out.put(field.name() + "Min", min.toPlainString());
                if (max != null) out.put(field.name() + "Max", max.toPlainString());
            }
        }
        return out;
    }

    private static final Pattern PRESET_TEXT_ID = Pattern.compile("[A-Za-z0-9._~-]+");

    /**
     * The ids a relation preset names, comma-joined and checked against the target's key type:
     * whole numbers for an integral key, UUIDs (lower-cased) for a UUID one, and plain URL-safe
     * text otherwise — the value lands in a query string verbatim.
     */
    private static String relationIds(String prefix, RelationDefinition relation, String value,
                                      Map<String, EntityDefinition> entitiesByLower) {
        EntityDefinition target = entitiesByLower.get(relation.targetEntity().toLowerCase(Locale.ROOT));
        FieldType keyType = target == null ? FieldType.STRING : target.fields().stream()
                .filter(FieldDefinition::primaryKey)
                .findFirst()
                .map(FieldDefinition::type)
                .orElse(FieldType.STRING);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String part : value.split(",", -1)) {
            String id = part.trim();
            if (id.isEmpty()) throw new WizardArgumentException(prefix + ": '" + value + "' has an empty id");
            if (keyType == FieldType.LONG || keyType == FieldType.INTEGER) {
                try {
                    long n = Long.parseLong(id);
                    if (keyType == FieldType.INTEGER && (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE)) {
                        throw new NumberFormatException();
                    }
                    ids.add(Long.toString(n));
                } catch (NumberFormatException e) {
                    throw new WizardArgumentException(prefix + ": '" + id + "' is not a " + relation.targetEntity() + " id (a whole number)");
                }
            } else if (keyType == FieldType.UUID) {
                try {
                    ids.add(UUID.fromString(id).toString());
                } catch (IllegalArgumentException e) {
                    throw new WizardArgumentException(prefix + ": '" + id + "' is not a " + relation.targetEntity() + " id (a UUID)");
                }
            } else if (PRESET_TEXT_ID.matcher(id).matches()) {
                ids.add(id);
            } else {
                throw new WizardArgumentException(prefix + ": '" + id + "' may hold only letters, digits and . _ ~ -");
            }
        }
        return String.join(",", ids);
    }

    /** The wire id of a period preset ({@code last:30d} → {@code 30d}, {@code ytd}), or null for anything else. */
    private static String presetPeriod(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        if (v.startsWith("last:")) v = v.substring(5);
        return PRESET_PERIODS.contains(v) ? v : null;
    }

    /** The two halves of {@code a..b}, either blank (null); anything without {@code ..} is rejected. */
    private static String[] presetRange(String prefix, String value, String expected) {
        int at = value.indexOf("..");
        if (at < 0) throw new WizardArgumentException(prefix + ": expected " + expected + ", got '" + value + "'");
        String from = trimToNull(value.substring(0, at));
        String to = trimToNull(value.substring(at + 2));
        if (from == null && to == null) throw new WizardArgumentException(prefix + ": a range needs a from or a to");
        return new String[] {from, to};
    }

    /** An ISO day, or a date-time on a date-time column; a day on one is widened to its start or end,
     *  as the generated period picker does. */
    private static String isoDate(String prefix, String raw, boolean dateTime, boolean end) {
        try {
            if (raw.length() == 10) {
                java.time.LocalDate.parse(raw);
                return dateTime ? raw + (end ? "T23:59:59" : "T00:00:00") : raw;
            }
            if (!dateTime) throw new WizardArgumentException(prefix + ": expected a day (yyyy-MM-dd), got '" + raw + "'");
            java.time.LocalDateTime.parse(raw);
            return raw;
        } catch (java.time.format.DateTimeParseException e) {
            throw new WizardArgumentException(prefix + ": '" + raw + "' is not an ISO date"
                    + (dateTime ? "-time (yyyy-MM-dd or yyyy-MM-ddTHH:mm:ss)" : " (yyyy-MM-dd)"));
        }
    }

    static java.math.BigDecimal number(String prefix, String raw, String what) {
        try {
            return new java.math.BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new WizardArgumentException(prefix + ": " + what + " '" + raw + "' is not a number");
        }
    }

    /** A dashboard's period picker: absent (no picker), or the period it opens on. */
    static PageDefinition.DateRange parseDateRange(String id, String raw) {
        String r = trimToNull(raw);
        if (r == null) return null;
        for (PageDefinition.DateRange range : PageDefinition.DateRange.values()) {
            if (range.wire().equalsIgnoreCase(r)) return range;
        }
        throw new WizardArgumentException("Page '" + id + "': unknown dateRange '" + r
                + "' (expected all, 7d, 30d, 90d, ytd or 12m)");
    }
}
