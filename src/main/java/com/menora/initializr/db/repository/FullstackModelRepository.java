package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.FullstackModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FullstackModelRepository extends JpaRepository<FullstackModelEntity, Long> {

    List<FullstackModelEntity> findAllByOrderByUpdatedAtDesc();

    Optional<FullstackModelEntity> findByNameIgnoreCase(String name);
}
