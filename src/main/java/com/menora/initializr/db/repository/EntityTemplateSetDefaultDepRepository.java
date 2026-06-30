package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.EntityTemplateSetDefaultDepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EntityTemplateSetDefaultDepRepository
        extends JpaRepository<EntityTemplateSetDefaultDepEntity, Long> {
    List<EntityTemplateSetDefaultDepEntity> findBySetIdOrderBySortOrderAsc(Long setId);

    /**
     * Bulk DML delete (not a derived delete) so the DELETEs execute immediately rather than
     * being queued as deferred persistence-context removals. The replace-default-deps PUT
     * deletes then re-inserts within one transaction; with the IDENTITY-generated entity
     * each {@code save(...)} INSERTs immediately, so a deferred delete would let the INSERTs
     * run first and a re-inserted {@code (set_id, dep_id)} pair would hit the unique
     * constraint (surfaced as 409). Immediate DML avoids that ordering hazard.
     */
    @Modifying
    @Query("delete from EntityTemplateSetDefaultDepEntity e where e.setId = :setId")
    void deleteBySetId(@Param("setId") Long setId);

    long countByDepId(String depId);
    void deleteByDepId(String depId);
}
