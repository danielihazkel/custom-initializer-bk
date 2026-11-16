package com.menora.initializr.observability;

import com.menora.initializr.db.entity.GenerationEventEntity;
import com.menora.initializr.db.entity.GenerationEventEntity.Status;
import com.menora.initializr.db.repository.GenerationEventRepository;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The generation audit: one row per ZIP, patch or preview asked for ({@link GenerationAuditFilter}),
 * written off the request thread, kept for {@code menora.audit.retention-days}, and read back by
 * the admin's Activity tab — a summary of a period, and the events themselves, filtered, paged and
 * as CSV.
 */
@Service
public class GenerationAuditService {

    private static final Logger log = LoggerFactory.getLogger(GenerationAuditService.class);

    /** What a generation is: something downloaded. A preview is someone looking. */
    static final Set<String> GENERATIONS = Set.of("download", "patch");
    static final String PREVIEW = "preview";

    /** The kinds, in the order the view stacks them. */
    public static final List<String> KINDS = List.of("backend", "wizard", "multimodule", "fullstack", "frontend");

    private final GenerationEventRepository repo;
    private final GenerationMetrics metrics;
    private final int retentionDays;
    private final ZoneId zone;
    /** One writer, a bounded queue: a burst of generations never waits on the database. */
    private final ExecutorService writer;
    private final AtomicLong dropped = new AtomicLong();

    public GenerationAuditService(GenerationEventRepository repo, GenerationMetrics metrics,
                                  @Value("${menora.audit.retention-days:365}") int retentionDays,
                                  @Value("${menora.audit.async:true}") boolean async) {
        this.repo = repo;
        this.metrics = metrics;
        this.retentionDays = retentionDays;
        this.zone = ZoneId.systemDefault();
        this.writer = async ? new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1000),
                runnable -> {
                    Thread thread = new Thread(runnable, "generation-audit-writer");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, executor) -> {
                    long n = dropped.incrementAndGet();
                    if (n == 1 || n % 100 == 0) log.warn("Generation audit queue full: {} events not recorded", n);
                }) : null;
    }

    @PreDestroy
    void stop() throws InterruptedException {
        if (writer == null) return;
        writer.shutdown();
        writer.awaitTermination(5, TimeUnit.SECONDS);
    }

    public void record(GenerationEventEntity event) {
        metrics.record(event.getStatus(), event.getDurationMs());
        if (writer == null) {
            save(event);
        } else {
            writer.execute(() -> save(event));
        }
    }

    private void save(GenerationEventEntity event) {
        try {
            repo.save(event);
        } catch (Exception ex) {
            log.warn("Failed to persist generation event", ex);
        }
    }

    /**
     * Waits (a little) for the events already handed to the writer: a read right after a
     * generation then sees it. Returns false when they are still being written.
     */
    public boolean awaitIdle(Duration timeout) {
        if (writer == null) return true;
        try {
            Future<?> marker = writer.submit(() -> { });
            marker.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Events the full queue made the audit drop since the start. */
    public long droppedCount() {
        return dropped.get();
    }

    // ── Retention ───────────────────────────────────────────────────────────

    /** Nightly: the events older than the retention period go. 0 keeps everything. */
    @Scheduled(cron = "${menora.audit.retention-cron:0 30 3 * * *}")
    public void applyRetention() {
        int removed = purgeOlderThan(retentionDays);
        if (removed > 0) log.info("Generation audit retention: {} events older than {} days removed", removed, retentionDays);
    }

    public int purgeOlderThan(int days) {
        if (days <= 0) return 0;
        return repo.deleteOlderThan(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    public int retentionDays() {
        return retentionDays;
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    public List<GenerationEventEntity> recent(int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        return repo.findAllByOrderByEventTimestampDesc(PageRequest.of(0, capped));
    }

    /** The action of an event: its column, or — for a row without one — what its endpoint says. */
    static String actionOf(GenerationEventEntity e) {
        if (e.getAction() != null) return e.getAction();
        String endpoint = e.getEndpoint() == null ? "" : e.getEndpoint();
        if (endpoint.endsWith(".zip")) return "download";
        if (endpoint.endsWith(".patch")) return "patch";
        if (endpoint.endsWith(".preview")) return PREVIEW;
        return "other";
    }

    static String kindOf(GenerationEventEntity e) {
        return e.getKind() != null ? e.getKind() : GenerationAuditFilter.kindOf(e.getEndpoint(), null);
    }

    /**
     * A period's usage. Generations (downloads and patches) are counted apart from previews: the
     * totals, the success rate, the durations and the top lists are the generations'; the per-day
     * series, the kinds and the failures count both, each saying which.
     */
    public Map<String, Object> summary(int days) {
        int d = Math.max(1, Math.min(days, 365));
        Instant since = Instant.now().minus(d, ChronoUnit.DAYS);
        List<GenerationEventEntity> events = repo.findByEventTimestampAfterOrderByEventTimestampAsc(since);

        long total = 0, success = 0, failure = 0, previews = 0;
        List<Long> durations = new ArrayList<>();
        Map<String, Long> depCounts = new HashMap<>();
        Map<String, Long> bootCounts = new HashMap<>();
        Map<String, long[]> byKind = new LinkedHashMap<>();
        for (String kind : KINDS) byKind.put(kind, new long[2]);
        Map<String, Long> byDepartment = new HashMap<>();
        Map<String, Long> byUser = new HashMap<>();
        Map<String, Long> byEndpoint = new HashMap<>();
        // Per day, from the first day of the period to today, every day there even when empty.
        LocalDate today = LocalDate.now(zone);
        LocalDate first = LocalDate.ofInstant(since, zone);
        Map<LocalDate, Map<String, Long>> perDay = new TreeMap<>();
        Map<LocalDate, Long> previewsPerDay = new TreeMap<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            perDay.put(day, new LinkedHashMap<>());
            previewsPerDay.put(day, 0L);
        }
        Map<String, Map<String, Object>> failures = new LinkedHashMap<>();

        for (GenerationEventEntity e : events) {
            String action = actionOf(e);
            if (action.equals("other")) continue;
            boolean generation = GENERATIONS.contains(action);
            String kind = kindOf(e);
            LocalDate day = LocalDate.ofInstant(e.getEventTimestamp(), zone);
            byKind.computeIfAbsent(kind, k -> new long[2])[generation ? 0 : 1]++;
            byEndpoint.merge(e.getEndpoint(), 1L, Long::sum);
            if (generation) {
                total++;
                if (e.getStatus() == Status.SUCCESS) success++;
                else failure++;
                durations.add(e.getDurationMs());
                if (e.getDependencyIds() != null) {
                    for (String id : e.getDependencyIds().split(",")) {
                        String trimmed = id.trim();
                        if (!trimmed.isEmpty()) depCounts.merge(trimmed, 1L, Long::sum);
                    }
                }
                if (e.getBootVersion() != null) bootCounts.merge(e.getBootVersion(), 1L, Long::sum);
                byDepartment.merge(e.getDepartment() == null || e.getDepartment().isBlank() ? "(none)" : e.getDepartment(), 1L, Long::sum);
                if (e.getUsername() != null && !e.getUsername().isBlank()) byUser.merge(e.getUsername(), 1L, Long::sum);
                perDay.computeIfAbsent(day, k -> new LinkedHashMap<>()).merge(kind, 1L, Long::sum);
            } else {
                previews++;
                previewsPerDay.merge(day, 1L, Long::sum);
            }
            if (e.getStatus() == Status.FAILURE) {
                String message = e.getErrorMessage() == null || e.getErrorMessage().isBlank() ? "(no message)" : e.getErrorMessage();
                Map<String, Object> group = failures.computeIfAbsent(message, m -> {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("message", m);
                    g.put("count", 0L);
                    return g;
                });
                group.put("count", (Long) group.get("count") + 1);
                // Events come oldest first: the last one seen is the latest.
                group.put("lastAt", e.getEventTimestamp());
                group.put("lastEndpoint", e.getEndpoint());
                group.put("lastArtifactId", e.getArtifactId());
                group.put("lastUsername", e.getUsername());
                group.put("lastEventId", e.getId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", d);
        result.put("totalCount", total);
        result.put("successCount", success);
        result.put("failureCount", failure);
        result.put("successRate", total == 0 ? 0.0 : (double) success / (double) total);
        result.put("previewCount", previews);
        result.put("p50Ms", percentile(durations, 50));
        result.put("p95Ms", percentile(durations, 95));
        result.put("p99Ms", percentile(durations, 99));
        result.put("topDependencies", top(depCounts, 10, "depId"));
        result.put("topBootVersions", top(bootCounts, 10, "bootVersion"));
        List<Map<String, Object>> kinds = new ArrayList<>();
        byKind.forEach((kind, counts) -> {
            if (counts[0] == 0 && counts[1] == 0 && !KINDS.contains(kind)) return;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", kind);
            row.put("generations", counts[0]);
            row.put("previews", counts[1]);
            kinds.add(row);
        });
        result.put("byKind", kinds);
        result.put("byEndpoint", top(byEndpoint, 20, "endpoint"));
        result.put("byDepartment", top(byDepartment, 20, "department"));
        result.put("topUsers", top(byUser, 10, "username"));
        List<Map<String, Object>> series = new ArrayList<>();
        perDay.forEach((day, counts) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.toString());
            row.put("byKind", counts);
            row.put("generations", counts.values().stream().mapToLong(Long::longValue).sum());
            row.put("previews", previewsPerDay.getOrDefault(day, 0L));
            series.add(row);
        });
        result.put("perDay", series);
        List<Map<String, Object>> failureList = new ArrayList<>(failures.values());
        failureList.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));
        result.put("failures", failureList.size() > 20 ? failureList.subList(0, 20) : failureList);
        result.put("retentionDays", retentionDays);
        return result;
    }

    private static List<Map<String, Object>> top(Map<String, Long> counts, int limit, String key) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(key, e.getKey());
                    row.put("count", e.getValue());
                    return row;
                })
                .toList();
    }

    /** What the events list is narrowed by; a null field narrows nothing. */
    public record EventFilter(Instant from, Instant to, String kind, String action, Status status,
                              String department, String username, String q) {}

    /** The events matching {@code filter}, newest first, one page. */
    public Page<GenerationEventEntity> events(EventFilter filter, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, 200));
        return repo.findAll(specification(filter),
                PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "eventTimestamp").and(Sort.by(Sort.Direction.DESC, "id"))));
    }

    /** The CSV export's rows: the newest {@code max} matching events. */
    public List<GenerationEventEntity> export(EventFilter filter, int max) {
        return repo.findAll(specification(filter),
                PageRequest.of(0, Math.max(1, max), Sort.by(Sort.Direction.DESC, "eventTimestamp").and(Sort.by(Sort.Direction.DESC, "id"))))
                .getContent();
    }

    private static Specification<GenerationEventEntity> specification(EventFilter f) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (f.from() != null) where.add(cb.greaterThanOrEqualTo(root.get("eventTimestamp"), f.from()));
            if (f.to() != null) where.add(cb.lessThan(root.get("eventTimestamp"), f.to()));
            if (blank(f.kind()) == null && blank(f.action()) == null) {
                // The detection calls recorded before V30 are not generations.
                where.add(cb.or(cb.isNull(root.get("action")), cb.notEqual(root.get("action"), "other")));
            }
            if (blank(f.kind()) != null) where.add(cb.equal(root.get("kind"), f.kind().trim().toLowerCase(Locale.ROOT)));
            if (blank(f.action()) != null) {
                String action = f.action().trim().toLowerCase(Locale.ROOT);
                if (action.equals("generation")) where.add(root.get("action").in(GENERATIONS));
                else where.add(cb.equal(root.get("action"), action));
            }
            if (f.status() != null) where.add(cb.equal(root.get("status"), f.status()));
            if (blank(f.department()) != null) where.add(cb.equal(root.get("department"), f.department().trim()));
            if (blank(f.username()) != null) where.add(cb.equal(root.get("username"), f.username().trim()));
            if (blank(f.q()) != null) {
                String like = "%" + f.q().trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("artifactId")), like, '\\'),
                        cb.like(cb.lower(root.get("username")), like, '\\'),
                        cb.like(cb.lower(root.get("errorMessage")), like, '\\'),
                        cb.like(cb.lower(root.get("endpoint")), like, '\\')));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * The dependencies most often generated with, for the picker's "Popular" row: counted over
     * successful ZIP generations only (a preview is someone looking, a failure is nobody's
     * choice), of one kind — the frontend generator's endpoints are under {@code frontend/}.
     * Wizard, multi-module and fullstack ZIPs count too: their controllers say what the JSON
     * request resolved to ({@link AuditDetails}).
     */
    public List<Map<String, Object>> popularDependencies(boolean frontend, int days, int limit) {
        int d = Math.max(1, Math.min(days, 365));
        int n = Math.max(1, Math.min(limit, 50));
        Instant since = Instant.now().minus(d, ChronoUnit.DAYS);

        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : repo.findEndpointAndDependencyIdsSince(since, Status.SUCCESS)) {
            String endpoint = (String) row[0];
            String csv = (String) row[1];
            if (endpoint == null || csv == null || !endpoint.endsWith(".zip")) continue;
            if (endpoint.startsWith("frontend/") != frontend) continue;
            for (String id : csv.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) counts.merge(trimmed, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                // Most used first; ties by id, so the list does not reshuffle between calls.
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(n)
                .map(e -> {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("depId", e.getKey());
                    entry.put("count", e.getValue());
                    return entry;
                })
                .toList();
    }

    private static long percentile(List<Long> values, int p) {
        if (values == null || values.isEmpty()) return 0L;
        List<Long> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int idx = (int) Math.ceil(p / 100.0 * copy.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= copy.size()) idx = copy.size() - 1;
        return copy.get(idx);
    }

    // Helper for building event instances without exposing JPA setters everywhere
    public static GenerationEventEntity newEvent(String endpoint, Instant start, long durationMs, Status status) {
        GenerationEventEntity e = new GenerationEventEntity();
        e.setEventTimestamp(start);
        e.setEndpoint(endpoint);
        e.setDurationMs(durationMs);
        e.setStatus(status);
        return e;
    }

    public static String csv(String[] values) {
        if (values == null || values.length == 0) return null;
        return String.join(",", Arrays.stream(values).filter(v -> v != null && !v.isBlank()).toList());
    }

    public static String csv(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        return csv(values.toArray(String[]::new));
    }
}
