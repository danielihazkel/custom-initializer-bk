package com.menora.initializr.admin;

import com.menora.initializr.config.WizardArgumentException;
import com.menora.initializr.db.entity.GenerationEventEntity;
import com.menora.initializr.observability.GenerationAuditService;
import com.menora.initializr.observability.GenerationAuditService.EventFilter;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The generation audit, for the admin's Activity tab (behind the admin login): a period's
 * summary, the events filtered and paged, and the same events as CSV.
 */
@RestController
@RequestMapping("/admin/activity")
public class ActivityController {

    /** The most rows one CSV export holds. */
    static final int CSV_LIMIT = 10_000;
    /** A read waits this long for events still being written, so a generation just made shows. */
    private static final Duration WRITE_WAIT = Duration.ofSeconds(2);

    private final GenerationAuditService auditService;

    public ActivityController(GenerationAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/recent")
    public List<GenerationEventEntity> recent(@RequestParam(defaultValue = "50") int limit) {
        auditService.awaitIdle(WRITE_WAIT);
        return auditService.recent(limit);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "30") int days) {
        auditService.awaitIdle(WRITE_WAIT);
        return auditService.summary(days);
    }

    /**
     * The events, newest first, one page. {@code from}/{@code to} are dates (the server's time
     * zone, {@code to} included); {@code action} is {@code download}, {@code patch},
     * {@code preview} or {@code generation} (the first two); {@code q} looks in the artifact,
     * the user, the endpoint and the error message.
     */
    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(required = false) String kind,
                                      @RequestParam(required = false) String action,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String department,
                                      @RequestParam(required = false) String username,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        auditService.awaitIdle(WRITE_WAIT);
        Page<GenerationEventEntity> events = auditService.events(
                filter(from, to, kind, action, status, department, username, q), page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", events.getContent());
        body.put("totalElements", events.getTotalElements());
        body.put("page", events.getNumber());
        body.put("size", events.getSize());
        return body;
    }

    /** The same filter as {@link #events}, as CSV: the newest {@value #CSV_LIMIT} rows at most. */
    @GetMapping("/events.csv")
    public ResponseEntity<byte[]> eventsCsv(@RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(required = false) String kind,
                                            @RequestParam(required = false) String action,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String department,
                                            @RequestParam(required = false) String username,
                                            @RequestParam(required = false) String q) {
        auditService.awaitIdle(WRITE_WAIT);
        List<GenerationEventEntity> rows = auditService.export(
                filter(from, to, kind, action, status, department, username, q), CSV_LIMIT);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("timestamp,endpoint,kind,action,status,artifactId,groupId,bootVersion,javaVersion,packaging,")
                .append("dependencies,wizards,entityCount,department,username,durationMs,errorMessage\r\n");
        for (GenerationEventEntity e : rows) {
            csv.append(String.join(",",
                    cell(e.getEventTimestamp() == null ? null : e.getEventTimestamp().toString()),
                    cell(e.getEndpoint()), cell(e.getKind()), cell(e.getAction()),
                    cell(e.getStatus() == null ? null : e.getStatus().name()),
                    cell(e.getArtifactId()), cell(e.getGroupId()), cell(e.getBootVersion()), cell(e.getJavaVersion()),
                    cell(e.getPackaging()), cell(e.getDependencyIds()), cell(e.getWizards()),
                    cell(e.getEntityCount() == null ? null : e.getEntityCount().toString()),
                    cell(e.getDepartment()), cell(e.getUsername()), cell(String.valueOf(e.getDurationMs())),
                    cell(e.getErrorMessage()))).append("\r\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"generation-activity.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @ExceptionHandler(WizardArgumentException.class)
    public ResponseEntity<Map<String, Object>> badFilter(WizardArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request", "detail", ex.getMessage()));
    }

    private static EventFilter filter(String from, String to, String kind, String action, String status,
                                      String department, String username, String q) {
        GenerationEventEntity.Status parsedStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                parsedStatus = GenerationEventEntity.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new WizardArgumentException("Unknown status '" + status + "'. Valid values: SUCCESS, FAILURE", "status");
            }
        }
        return new EventFilter(day(from, "from", false), day(to, "to", true), kind, action, parsedStatus,
                department, username, q);
    }

    /** A date as the start of that day, or of the next one for an inclusive end. */
    private static Instant day(String value, String field, boolean end) {
        if (value == null || value.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(value.trim());
            return (end ? date.plusDays(1) : date).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            throw new WizardArgumentException("'" + value + "' is not a date (yyyy-MM-dd)", field);
        }
    }

    /**
     * One CSV cell: quoted when it holds a comma, a quote or a line break, and a value a
     * spreadsheet would run as a formula (= + - @, a tab or a carriage return first) led by a quote.
     */
    public static String cell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
