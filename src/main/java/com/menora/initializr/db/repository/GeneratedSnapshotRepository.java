package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.GeneratedSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface GeneratedSnapshotRepository extends JpaRepository<GeneratedSnapshotEntity, String> {

    @Modifying
    @Transactional
    @Query("update GeneratedSnapshotEntity s set s.lastUsedAt = :at where s.id = :id")
    int touch(@Param("id") String id, @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query("delete from GeneratedSnapshotEntity s where s.lastUsedAt < :before")
    int deleteUnusedSince(@Param("before") Instant before);
}
