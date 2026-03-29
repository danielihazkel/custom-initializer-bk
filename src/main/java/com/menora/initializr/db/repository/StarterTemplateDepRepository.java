package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.StarterTemplateDepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StarterTemplateDepRepository extends JpaRepository<StarterTemplateDepEntity, Long> {
    List<StarterTemplateDepEntity> findAllByTemplateId(Long templateId);
    void deleteAllByTemplateId(Long templateId);
}
