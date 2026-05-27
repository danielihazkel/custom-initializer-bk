package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.ColorPaletteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ColorPaletteRepository extends JpaRepository<ColorPaletteEntity, Long> {
    Optional<ColorPaletteEntity> findByPaletteId(String paletteId);
    List<ColorPaletteEntity> findAllByOrderBySortOrderAsc();
    long countByPaletteId(String paletteId);
}
