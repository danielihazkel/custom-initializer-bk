package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.GenerationEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface GenerationEventRepository extends JpaRepository<GenerationEventEntity, Long>,
        JpaSpecificationExecutor<GenerationEventEntity> {

    /** Every event of a period, oldest first: what the usage view counts. */
    List<GenerationEventEntity> findByEventTimestampAfterOrderByEventTimestampAsc(Instant since);

    /** Retention: the events older than {@code before}. */
    @Modifying
    @Transactional
    @Query("DELETE FROM GenerationEventEntity e WHERE e.eventTimestamp < :before")
    int deleteOlderThan(@Param("before") Instant before);

    List<GenerationEventEntity> findAllByOrderByEventTimestampDesc(Pageable pageable);

    long countByEventTimestampAfter(Instant since);

    long countByEventTimestampAfterAndStatus(Instant since, GenerationEventEntity.Status status);

    @Query("SELECT e.durationMs FROM GenerationEventEntity e WHERE e.eventTimestamp > :since ORDER BY e.durationMs ASC")
    List<Long> findDurationsSince(@Param("since") Instant since);

    @Query("SELECT e.dependencyIds FROM GenerationEventEntity e WHERE e.eventTimestamp > :since AND e.dependencyIds IS NOT NULL")
    List<String> findDependencyIdsSince(@Param("since") Instant since);

    /** Endpoint and dependency list of every event of one status, for the popular-dependencies list. */
    @Query("SELECT e.endpoint, e.dependencyIds FROM GenerationEventEntity e WHERE e.eventTimestamp > :since AND e.status = :status AND e.dependencyIds IS NOT NULL")
    List<Object[]> findEndpointAndDependencyIdsSince(@Param("since") Instant since,
                                                     @Param("status") GenerationEventEntity.Status status);

    @Query("SELECT e.bootVersion, COUNT(e) FROM GenerationEventEntity e WHERE e.eventTimestamp > :since AND e.bootVersion IS NOT NULL GROUP BY e.bootVersion ORDER BY COUNT(e) DESC")
    List<Object[]> countByBootVersionSince(@Param("since") Instant since);
}
