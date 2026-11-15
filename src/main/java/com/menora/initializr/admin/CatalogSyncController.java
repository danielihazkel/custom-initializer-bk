package com.menora.initializr.admin;

import com.menora.initializr.db.CatalogSync;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the catalog's file contributions and build customizations stand against the ones this
 * release ships, and the two things an admin can do about a row that differs: take the shipped
 * version, or keep their own. See {@link CatalogSync}.
 *
 * <ul>
 *   <li>{@code GET /admin/catalog-sync} — {@code {summary, rows}}; {@code ?status=DIFFERS,MISSING}
 *       narrows the rows (the summary always counts all).</li>
 *   <li>{@code GET /admin/catalog-sync/{key}} — one row: the shipped entity beside the database's.</li>
 *   <li>{@code POST /admin/catalog-sync/apply} — {@code {keys: [...]}} takes the shipped version of
 *       those rows; {@code {all: true}} of every row that differs or is missing.</li>
 *   <li>{@code POST /admin/catalog-sync/keep} — {@code {keys: [...], kept: true|false}} marks rows
 *       as the admin's own (no longer reported, never replaced), or undoes it.</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/catalog-sync")
public class CatalogSyncController {

    private final CatalogSync catalogSync;

    public CatalogSyncController(CatalogSync catalogSync) {
        this.catalogSync = catalogSync;
    }

    /** {@code keys}: the rows meant; {@code all}: every row that differs or is missing; {@code kept}: for keep. */
    public record Selection(List<String> keys, Boolean all, Boolean kept) {
    }

    @GetMapping
    public Map<String, Object> report(@RequestParam(required = false) List<CatalogSync.Status> status) {
        CatalogSync.Report report = catalogSync.report();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", catalogSync.enabled());
        body.put("summary", report.summary());
        body.put("rows", status == null || status.isEmpty() ? report.rows()
                : report.rows().stream().filter(row -> status.contains(row.status())).toList());
        return body;
    }

    @GetMapping("/{key}")
    public ResponseEntity<CatalogSync.Detail> detail(@PathVariable String key) {
        CatalogSync.Detail detail = catalogSync.detail(key);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody Selection selection) {
        boolean all = Boolean.TRUE.equals(selection.all());
        if (!all && (selection.keys() == null || selection.keys().isEmpty())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nothing to apply",
                    "detail", "Name the rows in 'keys', or send 'all': true for every row that differs or is missing"));
        }
        int written = catalogSync.apply(all ? null : selection.keys());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("written", written);
        body.put("summary", catalogSync.report().summary());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/keep")
    public ResponseEntity<Map<String, Object>> keep(@RequestBody Selection selection) {
        if (selection.keys() == null || selection.keys().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nothing to keep",
                    "detail", "Name the rows in 'keys'"));
        }
        int marked = catalogSync.keep(selection.keys(), !Boolean.FALSE.equals(selection.kept()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("marked", marked);
        body.put("summary", catalogSync.report().summary());
        return ResponseEntity.ok(body);
    }
}
