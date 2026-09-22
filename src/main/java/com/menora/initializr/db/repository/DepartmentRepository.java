package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DepartmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<DepartmentEntity, Long> {
    List<DepartmentEntity> findAllByOrderBySortOrderAsc();
    Optional<DepartmentEntity> findByDepartmentId(String departmentId);
    Optional<DepartmentEntity> findFirstByIsDefaultTrueOrderBySortOrderAsc();
}
