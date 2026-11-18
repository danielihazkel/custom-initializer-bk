package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.BackendPresetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BackendPresetRepository extends JpaRepository<BackendPresetEntity, Long> {

    /** The list's columns only — a snapshot with wizard documents can be large. */
    interface Summary {
        Long getId();
        String getName();
        String getDescription();
        String getCoordinates();
        int getDependencyCount();
        int getModuleCount();
        String getCreatedBy();
        Instant getCreatedAt();
        Instant getUpdatedAt();
    }

    List<Summary> findAllSummariesByOrderByUpdatedAtDesc();

    Optional<BackendPresetEntity> findByNameIgnoreCase(String name);
}
