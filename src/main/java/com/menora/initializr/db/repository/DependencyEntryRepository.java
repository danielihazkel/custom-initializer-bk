package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.ProjectKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DependencyEntryRepository extends JpaRepository<DependencyEntryEntity, Long> {
    Optional<DependencyEntryEntity> findByDepId(String depId);
    List<DependencyEntryEntity> findByGroupId(Long groupId);
    List<DependencyEntryEntity> findByDepIdIn(Iterable<String> depIds);
    List<DependencyEntryEntity> findByDepIdInAndProjectKind(Iterable<String> depIds, ProjectKind projectKind);
    List<DependencyEntryEntity> findAllByProjectKind(ProjectKind projectKind);
}
