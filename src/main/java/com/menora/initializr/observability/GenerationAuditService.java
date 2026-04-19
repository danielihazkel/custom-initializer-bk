package com.menora.initializr.observability;

import com.menora.initializr.db.entity.GenerationEventEntity;
import com.menora.initializr.db.entity.GenerationEventEntity.Status;
import com.menora.initializr.db.repository.GenerationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GenerationAuditService {

    private static final Logger log = LoggerFactory.getLogger(GenerationAuditService.class);

    private final GenerationEventRepository repo;
    private final GenerationMetrics metrics;

    public GenerationAuditService(GenerationEventRepository repo, GenerationMetrics metrics) {
        this.repo = repo;
        this.metrics = metrics;
    }

    public void record(GenerationEventEntity event) {
        metrics.record(event.getStatus(), event.getDurationMs());
        try {
            repo.save(event);
        } catch (Exception ex) {
            log.warn("Failed to persist generation event", ex);
        }
    }

    public List<GenerationEventEntity> recent(int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        return repo.findAllByOrderByEventTimestampDesc(PageRequest.of(0, capped));
    }

    public Map<String, Object> summary(int days) {
        int d = Math.max(1, Math.min(days, 365));
        Instant since = Instant.now().minus(d, ChronoUnit.DAYS);

        long total = repo.countByEventTimestampAfter(since);
        long success = repo.countByEventTimestampAfterAndStatus(since, Status.SUCCESS);
        long failure = repo.countByEventTimestampAfterAndStatus(since, Status.FAILURE);

        List<Long> durations = repo.findDurationsSince(since);
        long p50 = percentile(durations, 50);
        long p95 = percentile(durations, 95);
        long p99 = percentile(durations, 99);

        Map<String, Long> depCounts = new HashMap<>();
        for (String csv : repo.findDependencyIdsSince(since)) {
            if (csv == null || csv.isBlank()) continue;
            for (String id : csv.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    depCounts.merge(trimmed, 1L, Long::sum);
                }
            }
        }
        List<Map<String, Object>> topDeps = new ArrayList<>(depCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(e -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("depId", e.getKey());
                    row.put("count", e.getValue());
                    return row;
                })
                .toList());

        List<Map<String, Object>> topBootVersions = new ArrayList<>();
        for (Object[] row : repo.countByBootVersionSince(since)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("bootVersion", row[0]);
            entry.put("count", ((Number) row[1]).longValue());
            topBootVersions.add(entry);
        }

        double successRate = total == 0 ? 0.0 : (double) success / (double) total;

        Map<String, Object> result = new HashMap<>();
        result.put("days", d);
        result.put("totalCount", total);
        result.put("successCount", success);
        result.put("failureCount", failure);
        result.put("successRate", successRate);
        result.put("p50Ms", p50);
        result.put("p95Ms", p95);
        result.put("p99Ms", p99);
        result.put("topDependencies", topDeps);
        result.put("topBootVersions", topBootVersions);
        return result;
    }

    private static long percentile(List<Long> sortedAsc, int p) {
        if (sortedAsc == null || sortedAsc.isEmpty()) return 0L;
        List<Long> copy = new ArrayList<>(sortedAsc);
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
}
