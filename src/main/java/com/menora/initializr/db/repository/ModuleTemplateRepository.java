package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.ModuleTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;

public interface ModuleTemplateRepository extends JpaRepository<ModuleTemplateEntity, Long> {
    List<ModuleTemplateEntity> findAllByOrderBySortOrderAsc();
    List<ModuleTemplateEntity> findByModuleIdInOrderBySortOrderAsc(Set<String> moduleIds);
}
