package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.FullstackTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FullstackTemplateRepository extends JpaRepository<FullstackTemplateEntity, Long> {

    List<FullstackTemplateEntity> findAllByOrderByUpdatedAtDesc();

    List<FullstackTemplateEntity> findAllByKindOrderByUpdatedAtDesc(String kind);

    Optional<FullstackTemplateEntity> findByKindAndNameIgnoreCase(String kind, String name);
}
