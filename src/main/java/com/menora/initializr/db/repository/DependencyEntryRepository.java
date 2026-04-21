package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.DependencyEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DependencyEntryRepository extends JpaRepository<DependencyEntryEntity, Long> {
    Optional<DependencyEntryEntity> findByDepId(String depId);
    List<DependencyEntryEntity> findByGroupId(Long groupId);
    List<DependencyEntryEntity> findByDepIdIn(Iterable<String> depIds);

    @Query("select e.depId from DependencyEntryEntity e where e.depId in :ids and e.starter = false")
    List<String> findFileOnlyDepIdsIn(@Param("ids") Iterable<String> ids);
}
