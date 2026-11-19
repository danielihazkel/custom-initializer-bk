package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;
import com.menora.initializr.fullstack.FullstackStarterRequest.PageDefinitionDto;

import java.util.ArrayList;
import java.util.List;

import static com.menora.initializr.fullstack.FullstackPageValidator.*;
import static com.menora.initializr.fullstack.PageListRules.*;
import static com.menora.initializr.fullstack.PagePresets.*;
import static com.menora.initializr.fullstack.DashboardWidgetRules.*;
import static com.menora.initializr.fullstack.PageStructureRules.*;
import static com.menora.initializr.fullstack.PageChecks.*;

/** A report page's charts. Part of {@link FullstackPageValidator}. */
final class ReportChartRules {

    private ReportChartRules() {}

    /** A report's charts: {@code charts} (1–4), or the one-chart spelling {@code chart}. */
    static List<PageDefinition.Chart> charts(String prefix, EntityDefinition entity, PageDefinitionDto p) {
        boolean many = p.charts() != null && !p.charts().isEmpty();
        if (many && p.chart() != null) {
            throw new WizardArgumentException(prefix + ": give either 'chart' or 'charts', not both");
        }
        if (!many) return List.of(chart(prefix, entity, p.chart()));
        if (p.charts().size() > MAX_CHARTS) {
            throw new WizardArgumentException(prefix + ": at most " + MAX_CHARTS + " charts are allowed");
        }
        List<PageDefinition.Chart> out = new ArrayList<>();
        for (int i = 0; i < p.charts().size(); i++) {
            try {
                out.add(chart(prefix + " charts[" + i + "]", entity, p.charts().get(i)));
            } catch (WizardArgumentException problem) {
                throw problem.at("charts[" + i + "]");
            }
        }
        return out;
    }

    /**
     * A report's chart. {@code groupBy} decides its shape: an enum/boolean field draws a bar, a
     * temporal one draws a line bucketed by day/month/year. Omitted, it falls back to the entity's
     * first enum/boolean field, else its first date.
     */
    static PageDefinition.Chart chart(String prefix, EntityDefinition entity,
                                      FullstackStarterRequest.ChartDto raw) {
        if (raw == null) {
            throw new WizardArgumentException(prefix + ": a report needs a 'chart'");
        }
        String requested = trimToNull(raw.groupBy());
        FieldDefinition field = null;
        if (requested != null) {
            // A MANY_TO_ONE relation groups by the id it points at, like a top widget; bars are
            // named from the target's list. It has no bucket.
            for (RelationDefinition r : entity.relations()) {
                if (r.type() == RelationType.MANY_TO_ONE && r.fieldName().equalsIgnoreCase(requested)) {
                    if (trimToNull(raw.bucket()) != null) {
                        throw new WizardArgumentException(prefix + ": 'bucket' applies to a date groupBy, and '"
                                + r.fieldName() + "' is not one");
                    }
                    PageDefinition.Agg agg = parseAgg(prefix, raw.agg());
                    PageDefinition.ChartKind kind = chartKind(prefix, raw, false);
                    if (kind == PageDefinition.ChartKind.STACKED) {
                        throw new WizardArgumentException(prefix + ": a stacked chart splits an enum or boolean groupBy, and '"
                                + r.fieldName() + "' is a relation");
                    }
                    return new PageDefinition.Chart(r.fieldName(), null, agg,
                            aggField(prefix, entity, agg, trimToNull(raw.field())), chartTable(prefix, raw, kind), kind, null);
                }
            }
            field = fieldOf(prefix, entity, requested, "groupBy");
            if (field.primaryKey()
                    || !(field.type().isEnum() || field.type().isBoolean() || field.type().isTemporal())) {
                throw new WizardArgumentException(prefix + ": groupBy '" + requested
                        + "' must be a non-key enum, boolean or date field, or a relation");
            }
        } else {
            field = entity.fields().stream()
                    .filter(f -> !f.primaryKey() && (f.type().isEnum() || f.type().isBoolean()))
                    .findFirst()
                    .or(() -> entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isTemporal()).findFirst())
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " has no enum, boolean or date field to group by"));
        }
        boolean overTime = field.type().isTemporal();
        if (!overTime && trimToNull(raw.bucket()) != null) {
            throw new WizardArgumentException(prefix + ": 'bucket' applies to a date groupBy, and '"
                    + field.name() + "' is not one");
        }
        PageDefinition.Agg agg = parseAgg(prefix, raw.agg());
        PageDefinition.ChartKind kind = chartKind(prefix, raw, overTime);
        String series = kind == PageDefinition.ChartKind.STACKED
                ? series(prefix, entity, field.name(), trimToNull(raw.series())) : null;
        return new PageDefinition.Chart(field.name(), overTime ? parseBucket(prefix, raw.bucket()) : null,
                agg, aggField(prefix, entity, agg, trimToNull(raw.field())), chartTable(prefix, raw, kind), kind, series);
    }

    /**
     * A chart's {@code kind}, checked against its grouping: bar/donut/stacked/table for a group,
     * line/area/table over time. The default for its grouping (bar, line) comes back as null, so a
     * chart that names it renders exactly like one that does not. {@code series} belongs to a
     * stacked chart alone.
     */
    private static PageDefinition.ChartKind chartKind(String prefix, FullstackStarterRequest.ChartDto raw, boolean overTime) {
        String k = trimToNull(raw.kind());
        PageDefinition.ChartKind kind = null;
        if (k != null) {
            for (PageDefinition.ChartKind c : PageDefinition.ChartKind.values()) {
                if (c.wire().equalsIgnoreCase(k)) kind = c;
            }
            if (kind == null) {
                throw new WizardArgumentException(prefix + ": unknown chart kind '" + k
                        + "' (expected bar, donut, stacked, line, area or table)");
            }
            if (!kind.fits(overTime)) {
                throw new WizardArgumentException(prefix + ": a " + kind.wire() + " chart "
                        + (overTime ? "groups by an enum, boolean or relation, not a date (over time: line, area or table)"
                                : "draws a date over time (for this groupBy: bar, donut, stacked or table)"));
            }
        }
        if (kind != PageDefinition.ChartKind.STACKED && trimToNull(raw.series()) != null) {
            throw new WizardArgumentException(prefix + ": 'series' applies to a stacked chart");
        }
        boolean isDefault = kind == (overTime ? PageDefinition.ChartKind.LINE : PageDefinition.ChartKind.BAR);
        return isDefault ? null : kind;
    }

    /** A table chart is its totals table, so it cannot leave it out. */
    private static Boolean chartTable(String prefix, FullstackStarterRequest.ChartDto raw, PageDefinition.ChartKind kind) {
        if (kind == PageDefinition.ChartKind.TABLE && Boolean.FALSE.equals(raw.table())) {
            throw new WizardArgumentException(prefix + ": a table chart is its totals table, so 'table' cannot be false");
        }
        return kind == PageDefinition.ChartKind.TABLE ? Boolean.TRUE : raw.table();
    }

    /** A bar groups by a non-PK enum/boolean field — the first enum, else the first boolean, when omitted. */
    static String groupBy(String prefix, EntityDefinition entity, String requested) {
        if (requested == null) {
            return entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isEnum()).findFirst()
                    .or(() -> entity.fields().stream().filter(f -> !f.primaryKey() && f.type().isBoolean()).findFirst())
                    .map(FieldDefinition::name)
                    .orElseThrow(() -> new WizardArgumentException(prefix + ": " + entity.name()
                            + " has no enum or boolean field to group by"));
        }
        FieldDefinition field = fieldOf(prefix, entity, requested, "groupBy");
        if (field.primaryKey() || !(field.type().isEnum() || field.type().isBoolean())) {
            throw new WizardArgumentException(prefix + ": groupBy '" + requested + "' must be a non-key enum or boolean field");
        }
        return field.name();
    }
}
