package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DesignKitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DesignKitRepository extends JpaRepository<DesignKitEntity, Long> {

    Optional<DesignKitEntity> findByKitKey(String kitKey);

    List<DesignKitEntity> findAllByOrderBySortOrderAscIdAsc();
}
