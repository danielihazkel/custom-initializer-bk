package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.EntityTemplateSetDefaultDepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntityTemplateSetDefaultDepRepository
        extends JpaRepository<EntityTemplateSetDefaultDepEntity, Long> {
    List<EntityTemplateSetDefaultDepEntity> findBySetIdOrderBySortOrderAsc(Long setId);

    List<EntityTemplateSetDefaultDepEntity> findAllByOrderBySetIdAscSortOrderAsc();

    /**
     * Deletes a set's default dependencies row by row and flushes at once. Row by row so the
     * catalog history records each (a bulk delete passes Hibernate's events by); flushed so the
     * DELETEs run now: the replace-default-deps PUT deletes then re-inserts within one
     * transaction, and with the IDENTITY-generated entity each {@code save(...)} INSERTs
     * immediately — a deferred delete would let a re-inserted {@code (set_id, dep_id)} pair hit
     * the unique constraint (surfaced as 409).
     */
    default void deleteBySetId(Long setId) {
        deleteAll(findBySetIdOrderBySortOrderAsc(setId));
        flush();
    }

    long countByDepId(String depId);
    void deleteByDepId(String depId);
}
