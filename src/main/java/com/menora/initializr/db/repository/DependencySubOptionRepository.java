package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DependencySubOptionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DependencySubOptionRepository extends JpaRepository<DependencySubOptionEntity, Long> {
    List<DependencySubOptionEntity> findAllByOrderByDependencyIdAscSortOrderAsc();
    List<DependencySubOptionEntity> findAllByProjectKindOrderByDependencyIdAscSortOrderAsc(ProjectKind projectKind);
    long countByDependencyId(String dependencyId);
    void deleteByDependencyId(String dependencyId);
}
