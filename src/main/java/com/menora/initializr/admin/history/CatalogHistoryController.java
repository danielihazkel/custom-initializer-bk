package com.menora.initializr.admin.history;

import com.menora.initializr.db.entity.CatalogChangeEntity;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalog's change log, behind the admin login: the changes (filtered, paged), the requests
 * they were made in, one change with the row before and after it, and putting rows back —
 * {@code POST /admin/history/{id}/undo}, {@code /restore} (the version that change made) and
 * {@code /admin/history/requests/{requestId}/undo}. A refused undo is a 409 naming the rows that
 * changed since; {@code force: true} undoes them anyway.
 */
@RestController
@RequestMapping("/admin/history")
public class CatalogHistoryController {

    private final CatalogHistoryService history;

    public CatalogHistoryController(CatalogHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public Map<String, Object> changes(@RequestParam(required = false) String entityType,
                                       @RequestParam(required = false) String rowId,
                                       @RequestParam(required = false) String actor,
                                       @RequestParam(required = false) String source,
                                       @RequestParam(required = false) String requestId,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        Page<CatalogChangeEntity> changes = history.changes(new CatalogHistoryService.Filter(entityType, rowId, actor,
                source, requestId, day(from, false), day(to, true), q), page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", changes.getContent().stream().map(CatalogHistoryController::view).toList());
        body.put("totalElements", changes.getTotalElements());
        body.put("page", changes.getNumber());
        body.put("size", changes.getSize());
        return body;
    }

    @GetMapping("/requests")
    public Map<String, Object> requests(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        Page<CatalogHistoryService.RequestSummary> requests = history.requests(page, size);
        return Map.of("content", requests.getContent(), "totalElements", requests.getTotalElements(),
                "page", requests.getNumber(), "size", requests.getSize());
    }

    @GetMapping("/{id}")
    public Map<String, Object> change(@PathVariable long id) {
        return view(history.change(id));
    }

    public record Undo(Boolean force) {}

    @PostMapping("/{id}/undo")
    public Map<String, Object> undo(@PathVariable long id, @RequestBody(required = false) Undo body) {
        boolean force = body != null && Boolean.TRUE.equals(body.force());
        CatalogChangeContext.as("restore", () -> history.undo(id, force));
        return Map.of("undone", 1);
    }

    @PostMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable long id) {
        CatalogChangeContext.as("restore", () -> history.restoreVersion(id));
        return Map.of("restored", 1);
    }

    @PostMapping("/requests/{requestId}/undo")
    public Map<String, Object> undoRequest(@PathVariable String requestId, @RequestBody(required = false) Undo body) {
        boolean force = body != null && Boolean.TRUE.equals(body.force());
        int n = CatalogChangeContext.as("restore", () -> history.undoRequest(requestId, force));
        return Map.of("undone", n);
    }

    @ExceptionHandler(CatalogHistoryService.ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(CatalogHistoryService.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Changed since", "detail", e.getMessage(), "rows", e.rows()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request", "detail", String.valueOf(e.getMessage())));
    }

    static Map<String, Object> view(CatalogChangeEntity c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getId());
        row.put("changedAt", c.getChangedAt());
        row.put("actor", c.getActor());
        row.put("source", c.getSource());
        row.put("requestId", c.getRequestId());
        row.put("entityType", c.getEntityType());
        row.put("tableName", c.getTableName());
        row.put("rowId", c.getRowId());
        row.put("action", c.getAction());
        row.put("label", c.getLabel());
        row.put("before", CatalogHistoryService.parse(c.getBeforeJson()));
        row.put("after", CatalogHistoryService.parse(c.getAfterJson()));
        row.put("changedFields", c.getChangedFields() == null ? List.of() : Arrays.asList(c.getChangedFields().split(",")));
        return row;
    }

    private static Instant day(String value, boolean end) {
        if (value == null || value.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(value.trim());
            return (end ? date.plusDays(1) : date).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + value + "' is not a date (yyyy-MM-dd)");
        }
    }
}
