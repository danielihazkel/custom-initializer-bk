package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.FileContributionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface FileContributionRepository extends JpaRepository<FileContributionEntity, Long> {
    List<FileContributionEntity> findByDependencyIdInOrderBySortOrderAsc(Set<String> dependencyIds);
    List<FileContributionEntity> findByDependencyIdInAndFileTypeOrderBySortOrderAsc(
            Set<String> dependencyIds, FileContributionEntity.FileType fileType);
    long countByDependencyId(String dependencyId);
    long countByDependencyIdIn(Collection<String> dependencyIds);
    void deleteByDependencyId(String dependencyId);
}
