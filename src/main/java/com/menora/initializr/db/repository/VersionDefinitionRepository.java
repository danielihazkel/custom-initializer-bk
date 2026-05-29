package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.VersionDefinitionEntity;
import com.menora.initializr.db.entity.VersionKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VersionDefinitionRepository extends JpaRepository<VersionDefinitionEntity, Long> {

    List<VersionDefinitionEntity> findAllByOrderByKindAscSortOrderAscIdAsc();

    List<VersionDefinitionEntity> findByKindOrderBySortOrderAscIdAsc(VersionKind kind);

    List<VersionDefinitionEntity> findByKindAndEnabledTrueOrderBySortOrderAscIdAsc(VersionKind kind);

    Optional<VersionDefinitionEntity> findByKindAndVersionId(VersionKind kind, String versionId);
}
