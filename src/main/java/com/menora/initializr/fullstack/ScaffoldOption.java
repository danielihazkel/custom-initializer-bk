package com.menora.initializr.fullstack;

import com.menora.initializr.config.ProjectOptionsContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fullstack generator's opt-in scaffolding, {@code opts.scaffold} of a request — the one list
 * every side reads: the backend render context, the frontend render context, the per-entity
 * overrides, the validator and (through {@code GET /metadata/fullstack/schema}) the editor.
 *
 * <p>The two render paths do not share a context, and they used to set these flags in two
 * hand-kept blocks plus a third map for the per-entity keys — an option added to one and
 * forgotten in another was honoured by the API but never rendered, or unreachable from the
 * editor. Adding one is now a constant here plus its template sections.
 *
 * <p>Declared per-entity options first, in the order the per-entity error message has always
 * listed them.
 */
public enum ScaffoldOption {

    AUDIT("audit", "optScaffoldAudit", true, true, true),
    SOFT_DELETE("softDelete", "optScaffoldSoftDelete", true, true, true),
    CSV_EXPORT("csvExport", "optScaffoldCsvExport", true, true, true),
    BULK_DELETE("bulkDelete", "optScaffoldBulkDelete", true, true, true),
    BULK_UPDATE("bulkUpdate", "optScaffoldBulkUpdate", true, true, true),
    TESTS("tests", "optScaffoldTests", true, true, true),
    CSV_IMPORT("csvImport", "optScaffoldCsvImport", true, true, true),
    OPTIMISTIC_LOCKING("optimisticLocking", "optScaffoldOptimisticLocking", true, true, true),
    /** Table cells edited in place — saved through the existing PUT, so the backend never reads it. */
    INLINE_EDIT("inlineEdit", "optScaffoldInlineEdit", false, true, true),
    /** The list as an Excel workbook, beside the CSV: written by the generated backend itself. */
    XLSX_EXPORT("xlsxExport", "optScaffoldXlsxExport", true, true, true),
    /** Named views of a list — its search, filters, sort and columns — kept on the server per user. */
    SAVED_VIEWS("savedViews", "optScaffoldSavedViews", true, true, true),
    /** Every change to a row kept with who made it, shown on the record and restorable by an admin. */
    HISTORY("history", "optScaffoldHistory", true, true, true),

    INVERSE_COLLECTIONS("inverseCollections", "optScaffoldInverse", true, true, false),
    /** springdoc annotations; the {@code openapi} dependency is force-added with it. */
    OPENAPI("openapi", "optScaffoldOpenApi", true, false, false),
    /**
     * No template reads it any more: it used to write commented-out {@code @RequiresPermission}
     * lines, and the checks are now live whenever an ldap-auth dependency is on the build, by each
     * entity's own {@code access} levels. Kept so a model that names it still validates.
     */
    SECURED("secured", "optScaffoldSecured", true, false, false),
    SEED_DATA("seedData", "optScaffoldSeedData", true, false, false),
    /** Not a template flag: sets {@code dir="rtl"} on the generated frontend's description. */
    RTL("rtl", null, false, false, false);

    /** The two render paths of a fullstack project. */
    public enum Side { BACKEND, FRONTEND }

    private final String key;
    private final String flag;
    private final boolean backend;
    private final boolean frontend;
    private final boolean perEntity;

    ScaffoldOption(String key, String flag, boolean backend, boolean frontend, boolean perEntity) {
        this.key = key;
        this.flag = flag;
        this.backend = backend;
        this.frontend = frontend;
        this.perEntity = perEntity;
    }

    /** The name in {@code opts.scaffold} and in an entity's {@code opts}. */
    public String key() {
        return key;
    }

    /** The context flag templates read ({@code optScaffold…}); null for an option no template reads. */
    public String flag() {
        return flag;
    }

    public boolean readBy(Side side) {
        return side == Side.BACKEND ? backend : frontend;
    }

    /** Whether one entity may turn it on or off for itself ({@code EntityDefinition.opts}). */
    public boolean perEntity() {
        return perEntity;
    }

    /** Every option name, in declaration order. */
    public static List<String> keys() {
        return Arrays.stream(values()).map(ScaffoldOption::key).toList();
    }

    public static boolean isKnown(String key) {
        return Arrays.stream(values()).anyMatch(o -> o.key.equals(key));
    }

    /** Per-entity option name → its context flag, in declaration order. */
    public static Map<String, String> perEntityFlags() {
        Map<String, String> flags = new LinkedHashMap<>();
        for (ScaffoldOption o : values()) if (o.perEntity) flags.put(o.key, o.flag);
        return Collections.unmodifiableMap(flags);
    }

    /**
     * Sets, on one render path's project context, the flag of every option that path reads —
     * true when the request's {@code opts.scaffold} names it.
     */
    public static void putFlags(Map<String, Object> projectCtx, ProjectOptionsContext options, Side side) {
        for (ScaffoldOption o : values()) {
            if (o.flag != null && o.readBy(side)) projectCtx.put(o.flag, options.hasOption("scaffold", o.key));
        }
    }
}
