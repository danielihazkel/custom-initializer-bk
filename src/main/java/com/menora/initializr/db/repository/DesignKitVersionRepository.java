package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DesignKitVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DesignKitVersionRepository extends JpaRepository<DesignKitVersionEntity, Long> {

    Optional<DesignKitVersionEntity> findByKitIdAndDraftMarker(Long kitId, Integer draftMarker);

    Optional<DesignKitVersionEntity> findByKitIdAndVersion(Long kitId, Integer version);

    /** Published versions, newest first. */
    List<DesignKitVersionEntity> findByKitIdAndStatusOrderByVersionDesc(Long kitId, DesignKitVersionEntity.Status status);

    Optional<DesignKitVersionEntity> findFirstByKitIdAndStatusOrderByVersionDesc(Long kitId, DesignKitVersionEntity.Status status);
}
