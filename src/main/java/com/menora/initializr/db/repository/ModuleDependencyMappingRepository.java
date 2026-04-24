package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.ModuleDependencyMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ModuleDependencyMappingRepository extends JpaRepository<ModuleDependencyMappingEntity, Long> {
    List<ModuleDependencyMappingEntity> findAllByOrderBySortOrderAsc();
    List<ModuleDependencyMappingEntity> findByDependencyIdIn(Set<String> depIds);
    long countByDependencyId(String dependencyId);
    long countByDependencyIdIn(Collection<String> dependencyIds);
    void deleteByDependencyId(String dependencyId);
    long countByModuleId(String moduleId);
    void deleteByModuleId(String moduleId);
}
