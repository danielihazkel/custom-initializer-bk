package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DependencyGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DependencyGroupRepository extends JpaRepository<DependencyGroupEntity, Long> {
    List<DependencyGroupEntity> findAllByOrderBySortOrderAsc();

    @Query("SELECT DISTINCT g FROM DependencyGroupEntity g LEFT JOIN FETCH g.entries e ORDER BY g.sortOrder ASC")
    List<DependencyGroupEntity> findAllWithEntriesSorted();
}
