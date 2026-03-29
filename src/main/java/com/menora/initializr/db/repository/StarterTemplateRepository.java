package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.StarterTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StarterTemplateRepository extends JpaRepository<StarterTemplateEntity, Long> {
    List<StarterTemplateEntity> findAllByOrderBySortOrderAsc();
}
