package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.BuildCustomizationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface BuildCustomizationRepository extends JpaRepository<BuildCustomizationEntity, Long> {
    List<BuildCustomizationEntity> findByDependencyIdInOrderBySortOrderAsc(Set<String> dependencyIds);
    long countByDependencyId(String dependencyId);
    long countByDependencyIdIn(Collection<String> dependencyIds);
    void deleteByDependencyId(String dependencyId);
}
