package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DesignKitFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DesignKitFileRepository extends JpaRepository<DesignKitFileEntity, Long> {

    List<DesignKitFileEntity> findByVersionIdOrderByPathAsc(Long versionId);

    Optional<DesignKitFileEntity> findByVersionIdAndPath(Long versionId, String path);
}
