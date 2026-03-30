package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DependencySubOptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DependencySubOptionRepository extends JpaRepository<DependencySubOptionEntity, Long> {
    List<DependencySubOptionEntity> findAllByOrderByDependencyIdAscSortOrderAsc();
    long countByDependencyId(String dependencyId);
    void deleteByDependencyId(String dependencyId);
}
