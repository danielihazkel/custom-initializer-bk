package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.SchemaSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchemaSourceRepository extends JpaRepository<SchemaSourceEntity, Long> {
    List<SchemaSourceEntity> findAllByOrderBySortOrderAscNameAsc();
    Optional<SchemaSourceEntity> findByNameIgnoreCase(String name);
}
