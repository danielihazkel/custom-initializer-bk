package com.menora.initializr.admin;

import com.menora.initializr.db.entity.GenerationEventEntity;
import com.menora.initializr.observability.GenerationAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/activity")
public class ActivityController {

    private final GenerationAuditService auditService;

    public ActivityController(GenerationAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/recent")
    public List<GenerationEventEntity> recent(@RequestParam(defaultValue = "50") int limit) {
        return auditService.recent(limit);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "30") int days) {
        return auditService.summary(days);
    }
}
