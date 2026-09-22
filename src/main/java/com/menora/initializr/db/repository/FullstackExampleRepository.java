package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.FullstackExampleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FullstackExampleRepository extends JpaRepository<FullstackExampleEntity, Long> {
    List<FullstackExampleEntity> findAllByOrderBySortOrderAscIdAsc();
    List<FullstackExampleEntity> findByEnabledTrueOrderBySortOrderAscIdAsc();
    Optional<FullstackExampleEntity> findByExampleId(String exampleId);
}
