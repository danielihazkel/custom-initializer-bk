package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DependencyGroupEntity;
import com.menora.initializr.db.entity.ProjectKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DependencyGroupRepository extends JpaRepository<DependencyGroupEntity, Long> {
    List<DependencyGroupEntity> findAllByOrderBySortOrderAsc();

    List<DependencyGroupEntity> findAllByProjectKindOrderBySortOrderAsc(ProjectKind projectKind);

    @Query("SELECT DISTINCT g FROM DependencyGroupEntity g LEFT JOIN FETCH g.entries e ORDER BY g.sortOrder ASC")
    List<DependencyGroupEntity> findAllWithEntriesSorted();

    @Query("SELECT DISTINCT g FROM DependencyGroupEntity g LEFT JOIN FETCH g.entries e " +
           "WHERE g.projectKind = :kind ORDER BY g.sortOrder ASC")
    List<DependencyGroupEntity> findAllWithEntriesSortedByKind(@Param("kind") ProjectKind kind);
}
