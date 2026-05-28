package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.EntityTemplateFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntityTemplateFileRepository extends JpaRepository<EntityTemplateFileEntity, Long> {
    List<EntityTemplateFileEntity> findBySetIdOrderBySortOrderAsc(Long setId);
    void deleteBySetId(Long setId);
}
