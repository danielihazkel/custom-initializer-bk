package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DependencyCompatibilityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DependencyCompatibilityRepository extends JpaRepository<DependencyCompatibilityEntity, Long> {
    List<DependencyCompatibilityEntity> findAllByOrderBySortOrderAsc();
    long countBySourceDepIdOrTargetDepId(String sourceDepId, String targetDepId);
    long countBySourceDepIdInOrTargetDepIdIn(Collection<String> sourceDepIds, Collection<String> targetDepIds);
    void deleteBySourceDepIdOrTargetDepId(String sourceDepId, String targetDepId);
}
