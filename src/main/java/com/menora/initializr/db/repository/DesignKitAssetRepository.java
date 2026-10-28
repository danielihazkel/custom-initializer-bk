package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DesignKitAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DesignKitAssetRepository extends JpaRepository<DesignKitAssetEntity, Long> {

    List<DesignKitAssetEntity> findByVersionIdOrderByTargetPathAsc(Long versionId);

    Optional<DesignKitAssetEntity> findByVersionIdAndTargetPath(Long versionId, String targetPath);
}
