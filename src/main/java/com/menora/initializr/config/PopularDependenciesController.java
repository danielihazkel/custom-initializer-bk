package com.menora.initializr.config;

import com.menora.initializr.observability.GenerationAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /metadata/popular-deps?kind=BACKEND|FRONTEND} — the dependencies projects were most
 * often generated with in the last 90 days, most used first: {@code [{depId, count}]}, at most ten.
 *
 * <p>The same counts sit behind the admin login as part of {@code /admin/activity/summary}; this
 * is the public slice the dependency picker needs for its "Popular" row — ids and counts, no
 * users, artifacts or addresses. An id is returned as it was requested, so the picker shows only
 * those its catalog still has.
 */
@RestController
public class PopularDependenciesController {

    static final int DAYS = 90;
    static final int LIMIT = 10;

    private final GenerationAuditService auditService;

    public PopularDependenciesController(GenerationAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/metadata/popular-deps")
    public List<Map<String, Object>> popular(@RequestParam(defaultValue = "BACKEND") String kind) {
        return auditService.popularDependencies("FRONTEND".equalsIgnoreCase(kind), DAYS, LIMIT);
    }
}
