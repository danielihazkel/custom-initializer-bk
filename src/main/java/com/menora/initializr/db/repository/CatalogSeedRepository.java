package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.CatalogSeedEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogSeedRepository extends JpaRepository<CatalogSeedEntity, String> {
}
