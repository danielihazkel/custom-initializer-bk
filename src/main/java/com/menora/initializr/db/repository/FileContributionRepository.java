package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.FileContributionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;

public interface FileContributionRepository extends JpaRepository<FileContributionEntity, Long> {
    List<FileContributionEntity> findByDependencyIdInOrderBySortOrderAsc(Set<String> dependencyIds);
    long countByDependencyId(String dependencyId);
    void deleteByDependencyId(String dependencyId);
    List<FileContributionEntity> findAllByOrderBySortOrderAsc();
}
