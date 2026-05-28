package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.EntityTemplateSetDefaultDepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntityTemplateSetDefaultDepRepository
        extends JpaRepository<EntityTemplateSetDefaultDepEntity, Long> {
    List<EntityTemplateSetDefaultDepEntity> findBySetIdOrderBySortOrderAsc(Long setId);
    void deleteBySetId(Long setId);
    long countByDepId(String depId);
    void deleteByDepId(String depId);
}
