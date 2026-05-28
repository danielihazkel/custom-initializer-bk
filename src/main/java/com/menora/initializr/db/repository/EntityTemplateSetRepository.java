package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EntityTemplateSetRepository extends JpaRepository<EntityTemplateSetEntity, Long> {
    Optional<EntityTemplateSetEntity> findBySetKey(String setKey);
    List<EntityTemplateSetEntity> findAllByOrderBySortOrderAsc();
    List<EntityTemplateSetEntity> findAllByKindOrderBySortOrderAsc(EntityTemplateSetEntity.Kind kind);
}
