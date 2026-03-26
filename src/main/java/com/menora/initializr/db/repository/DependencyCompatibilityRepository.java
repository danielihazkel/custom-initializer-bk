package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DependencyCompatibilityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DependencyCompatibilityRepository extends JpaRepository<DependencyCompatibilityEntity, Long> {
    List<DependencyCompatibilityEntity> findAllByOrderBySortOrderAsc();
}
