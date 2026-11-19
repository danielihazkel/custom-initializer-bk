package com.menora.initializr.fullstack;

import com.menora.initializr.config.WizardArgumentException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.menora.initializr.fullstack.FullstackPageValidator.*;
import static com.menora.initializr.fullstack.PagePresets.*;
import static com.menora.initializr.fullstack.DashboardWidgetRules.*;
import static com.menora.initializr.fullstack.ReportChartRules.*;
import static com.menora.initializr.fullstack.PageStructureRules.*;
import static com.menora.initializr.fullstack.PageChecks.*;

/** What a list page (or an embedded list) may show and how it opens: columns, sort, view, detail, page size, hidden actions. Part of {@link FullstackPageValidator}. */
final class PageListRules {

    private PageListRules() {}

    static boolean auditApplies(EntityDefinition entity, Set<String> scaffoldOpts) {
        if (entity.readOnly()) return false;
        Boolean override = entity.opts().get("audit");
        return override != null ? override : scaffoldOpts.contains("audit");
    }

    /** The columns a list can show — lower-cased → as declared: every field, every MANY_TO_ONE
     *  relation (by field name) and, with audit, {@code createdAt}/{@code updatedAt}. */
    private static Map<String, String> listColumns(EntityDefinition entity, boolean audit) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldDefinition f : entity.fields()) out.put(f.name().toLowerCase(Locale.ROOT), f.name());
        for (RelationDefinition r : entity.relations()) {
            if (r.type() == RelationType.MANY_TO_ONE) out.put(r.fieldName().toLowerCase(Locale.ROOT), r.fieldName());
        }
        if (audit) {
            out.put("createdat", "createdAt");
            out.put("updatedat", "updatedAt");
            out.put("createdby", "createdBy");
            out.put("updatedby", "updatedBy");
        }
        return out;
    }

    /** The columns the generated list endpoint sorts by (its SORTABLE whitelist): every field but
     *  long text and files and, with audit, the audit pair. Relations are shown but never sorted. */
    private static Map<String, String> sortableColumns(EntityDefinition entity, boolean audit) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldDefinition f : entity.fields()) {
            if (f.type().sortable()) out.put(f.name().toLowerCase(Locale.ROOT), f.name());
        }
        if (audit) {
            out.put("createdat", "createdAt");
            out.put("updatedat", "updatedAt");
            out.put("createdby", "createdBy");
            out.put("updatedby", "updatedBy");
        }
        return out;
    }

    private static boolean isAuditColumn(String name) {
        return name.equalsIgnoreCase("createdAt") || name.equalsIgnoreCase("updatedAt")
                || name.equalsIgnoreCase("createdBy") || name.equalsIgnoreCase("updatedBy");
    }

    /** The ordered subset of columns a list page shows: each a known column, none twice, canonicalized. */
    static List<String> columns(String prefix, EntityDefinition entity, List<String> raw, boolean audit) {
        if (raw == null) return List.of();
        if (raw.isEmpty()) throw new WizardArgumentException(prefix + ": 'columns' needs at least one column when given");
        Map<String, String> known = listColumns(entity, audit);
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>(raw.size());
        for (String name : raw) {
            String canonical = trimToNull(name) == null ? null : known.get(name.trim().toLowerCase(Locale.ROOT));
            if (canonical == null) {
                String hint = name != null && isAuditColumn(name.trim()) && !audit
                        ? " — the audit scaffold option is off, so " + entity.name() + " has no audit columns"
                        : " (expected one of " + new ArrayList<>(known.values()) + ")";
                throw new WizardArgumentException(prefix + " columns: '" + name + "' is not a column of "
                        + entity.name() + hint);
            }
            if (!seen.add(canonical)) {
                throw new WizardArgumentException(prefix + " columns: '" + canonical + "' is listed twice");
            }
            out.add(canonical);
        }
        return out;
    }

    /** The column a list page opens sorted by: a sortable one, {@code asc} (default) or {@code desc}. */
    static PageDefinition.ListSort sort(String prefix, EntityDefinition entity,
                                        FullstackStarterRequest.SortDto raw, boolean audit) {
        if (raw == null) return null;
        String field = trimToNull(raw.field());
        if (field == null) throw new WizardArgumentException(prefix + " sort: 'field' is required");
        Map<String, String> sortable = sortableColumns(entity, audit);
        String canonical = sortable.get(field.toLowerCase(Locale.ROOT));
        if (canonical == null) {
            String hint = isAuditColumn(field) && !audit
                    ? " — the audit scaffold option is off, so " + entity.name() + " has no audit columns"
                    : " (sortable: " + new ArrayList<>(sortable.values()) + ")";
            throw new WizardArgumentException(prefix + " sort: '" + field + "' is not sortable on " + entity.name() + hint);
        }
        String dir = trimToNull(raw.dir());
        boolean desc;
        if (dir == null || dir.equalsIgnoreCase("asc")) desc = false;
        else if (dir.equalsIgnoreCase("desc")) desc = true;
        else throw new WizardArgumentException(prefix + " sort: dir must be asc or desc, got '" + dir + "'");
        return new PageDefinition.ListSort(canonical, desc);
    }

    /** Where a list page opens a row: absent keeps the default (the record page when there is one,
     *  else the quick-look drawer). */
    static PageDefinition.Detail detail(String prefix, String raw) {
        String d = trimToNull(raw);
        if (d == null) return null;
        for (PageDefinition.Detail detail : PageDefinition.Detail.values()) {
            if (detail.wire().equalsIgnoreCase(d)) return detail;
        }
        throw new WizardArgumentException(prefix + ": unknown detail '" + d + "' (expected drawer, side or record)");
    }

    /** The view a list page opens in: one the entity's list actually offers (its enabled listViews,
     *  minus the ones its fields cannot support — see EntityScaffoldContext.emittedListViews). */
    static String view(String prefix, EntityDefinition entity, String raw) {
        String v = trimToNull(raw);
        if (v == null) return null;
        String lower = v.toLowerCase(Locale.ROOT);
        if (!LIST_VIEWS.contains(lower)) {
            throw new WizardArgumentException(prefix + ": unknown view '" + v + "' (expected table, cards, kanban or calendar)");
        }
        List<String> emitted = EntityViewModel.emittedListViews(entity);
        if (!emitted.contains(lower)) {
            throw new WizardArgumentException(prefix + ": view '" + lower + "' is not enabled on " + entity.name()
                    + " (its list views are " + emitted + "; enable it on the entity — kanban needs an enum or"
                    + " boolean field on a writable entity, calendar a date field)");
        }
        return lower;
    }

    /** A list page's hidden actions, canonicalized to {@link PageDefinition.ListAction} order; null for none. */
    static PageDefinition.ListSpec listSpec(String prefix, EntityDefinition entity, List<String> raw,
                                            Set<String> scaffoldOpts) {
        if (raw == null || raw.isEmpty()) return null;
        Boolean exportOverride = entity.opts().get("csvExport");
        Boolean sheetOverride = entity.opts().get("xlsxExport");
        // Either export is an action a list has: CSV, or the Excel workbook.
        boolean exports = (exportOverride != null ? exportOverride : scaffoldOpts.contains("csvExport"))
                || (sheetOverride != null ? sheetOverride : scaffoldOpts.contains("xlsxExport"));
        if (entity.readOnly() && !exports) {
            throw new WizardArgumentException(prefix + ": " + entity.name()
                    + " is read-only and exports nothing, so its list has no actions to hide");
        }
        Set<PageDefinition.ListAction> picked = new HashSet<>();
        for (String r : raw) {
            String name = trimToNull(r);
            PageDefinition.ListAction action = null;
            for (PageDefinition.ListAction a : PageDefinition.ListAction.values()) {
                if (a.wire().equalsIgnoreCase(name)) action = a;
            }
            if (action == null) {
                throw new WizardArgumentException(prefix + ": unknown hideActions entry '" + r
                        + "' (expected new, edit, delete, export, import or bulk)");
            }
            if (!picked.add(action)) {
                throw new WizardArgumentException(prefix + ": hideActions lists '" + action.wire() + "' twice");
            }
        }
        return new PageDefinition.ListSpec(java.util.Arrays.stream(PageDefinition.ListAction.values()).filter(picked::contains).toList());
    }

    /** The rows per page a list opens with: one of the pager's choices. */
    static Integer pageSize(String prefix, Integer raw) {
        if (raw == null) return null;
        if (!LIST_PAGE_SIZES.contains(raw)) {
            throw new WizardArgumentException(prefix + ": pageSize must be one of 10, 20, 50 or 100, got " + raw);
        }
        return raw;
    }
}
