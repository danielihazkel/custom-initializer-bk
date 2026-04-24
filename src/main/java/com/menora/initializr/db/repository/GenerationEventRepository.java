package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.GenerationEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface GenerationEventRepository extends JpaRepository<GenerationEventEntity, Long> {

    List<GenerationEventEntity> findAllByOrderByEventTimestampDesc(Pageable pageable);

    long countByEventTimestampAfter(Instant since);

    long countByEventTimestampAfterAndStatus(Instant since, GenerationEventEntity.Status status);

    @Query("SELECT e.durationMs FROM GenerationEventEntity e WHERE e.eventTimestamp > :since ORDER BY e.durationMs ASC")
    List<Long> findDurationsSince(@Param("since") Instant since);

    @Query("SELECT e.dependencyIds FROM GenerationEventEntity e WHERE e.eventTimestamp > :since AND e.dependencyIds IS NOT NULL")
    List<String> findDependencyIdsSince(@Param("since") Instant since);

    @Query("SELECT e.bootVersion, COUNT(e) FROM GenerationEventEntity e WHERE e.eventTimestamp > :since AND e.bootVersion IS NOT NULL GROUP BY e.bootVersion ORDER BY COUNT(e) DESC")
    List<Object[]> countByBootVersionSince(@Param("since") Instant since);
}
