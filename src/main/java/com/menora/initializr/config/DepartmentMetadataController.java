package com.menora.initializr.config;

import com.menora.initializr.db.entity.DepartmentEntity;
import com.menora.initializr.db.repository.DepartmentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public department list for the generator screens. Kind-neutral: the backend, frontend and
 * fullstack screens all pick from the same list, sent back as the {@code department}
 * request parameter / body field.
 */
@RestController
public class DepartmentMetadataController {

    private final DepartmentRepository departmentRepo;

    public DepartmentMetadataController(DepartmentRepository departmentRepo) {
        this.departmentRepo = departmentRepo;
    }

    @GetMapping("/metadata/departments")
    public List<Map<String, Object>> departments() {
        return departmentRepo.findAllByOrderBySortOrderAsc().stream().map(this::toJson).toList();
    }

    private Map<String, Object> toJson(DepartmentEntity d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getDepartmentId());
        m.put("name", d.getName());
        m.put("isDefault", d.isDefault());
        m.put("sortOrder", d.getSortOrder());
        return m;
    }
}
