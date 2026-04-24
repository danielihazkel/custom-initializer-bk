package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.StarterTemplateDepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface StarterTemplateDepRepository extends JpaRepository<StarterTemplateDepEntity, Long> {
    List<StarterTemplateDepEntity> findAllByTemplateId(Long templateId);
    void deleteAllByTemplateId(Long templateId);
    long countByDepId(String depId);
    long countByDepIdIn(Collection<String> depIds);
    void deleteByDepId(String depId);
    long countByTemplateId(Long templateId);

    /**
     * Eagerly joins the parent template so {@code getTemplate().getTemplateId()}
     * doesn't trigger a per-row lazy load (used by the export service).
     */
    @Query("select td from StarterTemplateDepEntity td join fetch td.template")
    List<StarterTemplateDepEntity> findAllWithTemplate();
}
