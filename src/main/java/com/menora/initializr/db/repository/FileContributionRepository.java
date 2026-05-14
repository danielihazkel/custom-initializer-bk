package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;

public interface FileContributionRepository extends JpaRepository<FileContributionEntity, Long> {
    List<FileContributionEntity> findByDependencyIdInOrderBySortOrderAsc(Set<String> dependencyIds);
    List<FileContributionEntity> findByDependencyIdInAndProjectKindOrderBySortOrderAsc(
            Set<String> dependencyIds, ProjectKind projectKind);
    List<FileContributionEntity> findByProjectKindOrderBySortOrderAsc(ProjectKind projectKind);
    long countByDependencyId(String dependencyId);
    void deleteByDependencyId(String dependencyId);
}
