package com.menora.initializr.db.repository;

import com.menora.initializr.db.entity.CatalogChangeEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CatalogChangeRepository extends JpaRepository<CatalogChangeEntity, Long>,
        JpaSpecificationExecutor<CatalogChangeEntity> {

    /** A request's changes, last first: the order an undo walks them in. */
    List<CatalogChangeEntity> findByRequestIdOrderByIdDesc(String requestId);

    /** A row's changes, newest first. */
    List<CatalogChangeEntity> findByEntityTypeAndRowIdOrderByIdDesc(String entityType, String rowId);

    /** The requests, newest first: id, first change, actor, source, change count. */
    @Query(value = "SELECT c.requestId, MIN(c.changedAt), MIN(c.actor), MIN(c.source), COUNT(c), MAX(c.id) "
            + "FROM CatalogChangeEntity c GROUP BY c.requestId ORDER BY MAX(c.id) DESC",
            countQuery = "SELECT COUNT(DISTINCT c.requestId) FROM CatalogChangeEntity c")
    Page<Object[]> requests(Pageable pageable);
}
