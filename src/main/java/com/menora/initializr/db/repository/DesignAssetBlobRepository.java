package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DesignAssetBlobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DesignAssetBlobRepository extends JpaRepository<DesignAssetBlobEntity, String> {
}
