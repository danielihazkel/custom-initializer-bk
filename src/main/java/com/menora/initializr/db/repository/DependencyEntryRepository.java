package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DependencyEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DependencyEntryRepository extends JpaRepository<DependencyEntryEntity, Long> {
    Optional<DependencyEntryEntity> findByDepId(String depId);
    List<DependencyEntryEntity> findByGroupId(Long groupId);
}
