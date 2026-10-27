package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.FullstackModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FullstackModelRepository extends JpaRepository<FullstackModelEntity, Long> {

    /** The list's columns only — a snapshot can be up to a megabyte and the list never shows it. */
    interface Summary {
        Long getId();
        String getName();
        String getDescription();
        int getEntityCount();
        String getCreatedBy();
        Instant getCreatedAt();
        Instant getUpdatedAt();
    }

    List<Summary> findAllSummariesByOrderByUpdatedAtDesc();

    Optional<FullstackModelEntity> findByNameIgnoreCase(String name);
}
