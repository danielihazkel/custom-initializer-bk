package com.menora.initializr.observability;

import com.menora.initializr.config.GeneratedSnapshots;
import com.menora.initializr.db.entity.GeneratedSnapshotEntity;
import com.menora.initializr.db.repository.GeneratedSnapshotRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The files each download generated, so a later update of that project can start from them
 * ({@link GeneratedSnapshots}). Written off the request thread, like the audit, through a small
 * bounded queue — a snapshot is a whole project, and a burst of downloads must not hold memory or
 * wait on the database; one that does not fit is not kept (its update then falls back to
 * generating both sides). Kept while used: a snapshot nobody downloaded or updated from for
 * {@code menora.snapshots.retention-days} (730) goes.
 */
@Service
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    private final GeneratedSnapshotRepository repo;
    private final boolean enabled;
    private final int retentionDays;
    private final ExecutorService writer;

    public SnapshotStore(GeneratedSnapshotRepository repo,
                         @Value("${menora.snapshots.enabled:true}") boolean enabled,
                         @Value("${menora.snapshots.retention-days:730}") int retentionDays,
                         @Value("${menora.snapshots.async:${menora.audit.async:true}}") boolean async) {
        this.repo = repo;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.writer = async ? new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(20),
                runnable -> {
                    Thread thread = new Thread(runnable, "generated-snapshot-writer");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, executor) -> log.warn("Snapshot queue full: a generated project was not kept")) : null;
    }

    @PreDestroy
    void stop() throws InterruptedException {
        if (writer == null) return;
        writer.shutdown();
        writer.awaitTermination(10, TimeUnit.SECONDS);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Keeps what the current request generated, when it is a download or an update (a preview is
     * somebody looking): called as its zip is about to be written, so the project is kept before
     * anyone holding it can ask for an update of it.
     */
    public void keepCurrent(String uri) {
        AuditDetails details = AuditDetails.currentOrNull();
        if (details == null || details.snapshotId == null || uri == null) return;
        String endpoint = uri.startsWith("/") ? uri.substring(1) : uri;
        if (!GenerationAuditFilter.isGeneration(uri)
                || !GenerationAuditService.GENERATIONS.contains(GenerationAuditFilter.actionOf(endpoint))) {
            return;
        }
        String artifact = details.request == null ? null : details.request.getArtifactId();
        keep(details.snapshotId, GenerationAuditFilter.kindOf(endpoint, details), artifact, details.snapshotTree);
    }

    /** Keeps a project's files under their id; a project already kept is only marked as used. */
    public void keep(String id, String kind, String artifactId, Map<String, byte[]> tree) {
        if (!enabled || !GeneratedSnapshots.isId(id) || tree == null) return;
        Runnable save = () -> save(id, kind, artifactId, tree);
        if (writer == null) save.run();
        else writer.execute(save);
    }

    private void save(String id, String kind, String artifactId, Map<String, byte[]> tree) {
        try {
            Instant now = Instant.now();
            if (repo.existsById(id)) {
                repo.touch(id, now);
                return;
            }
            GeneratedSnapshotEntity row = new GeneratedSnapshotEntity();
            row.setId(id);
            row.setKind(kind);
            row.setArtifactId(artifactId == null ? null : artifactId.substring(0, Math.min(255, artifactId.length())));
            row.setCreatedAt(now);
            row.setLastUsedAt(now);
            row.setFileCount(tree.size());
            byte[] zip = GeneratedSnapshots.zip(tree);
            row.setSizeBytes(zip.length);
            row.setContent(zip);
            repo.save(row);
        } catch (Exception e) {
            // Two downloads of one project at once: the other one kept it.
            if (repo.existsById(id)) return;
            log.warn("Failed to keep generated snapshot {}", id, e);
        }
    }

    /** The files of a kept project (the model file aside), marked as used; empty when unknown or gone. */
    @Transactional
    public Optional<Map<String, byte[]>> find(String id) {
        if (!enabled || !GeneratedSnapshots.isId(id)) return Optional.empty();
        // A download a moment ago may still be on its way to the database.
        if (writer != null && !repo.existsById(id)) awaitIdle(Duration.ofSeconds(2));
        return repo.findById(id).map(row -> {
            row.setLastUsedAt(Instant.now());
            return GeneratedSnapshots.unzip(row.getContent());
        });
    }

    /** Waits (a little) for the snapshots already handed to the writer. For tests. */
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

    @Scheduled(cron = "${menora.snapshots.retention-cron:0 45 3 * * *}")
    public void applyRetention() {
        int removed = purgeUnusedFor(retentionDays);
        if (removed > 0) log.info("Generated snapshots: {} unused for {} days removed", removed, retentionDays);
    }

    public int purgeUnusedFor(int days) {
        if (days <= 0) return 0;
        return repo.deleteUnusedSince(Instant.now().minus(days, ChronoUnit.DAYS));
    }
}
