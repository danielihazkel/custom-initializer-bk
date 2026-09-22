package com.menora.initializr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.admin.FullstackExampleAdminController;
import com.menora.initializr.db.repository.FullstackExampleRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public list of the enabled "Start from → Examples" models for the Fullstack tab. Managed
 * under {@code /admin/fullstack-examples}; seeded from {@code catalog/fullstack-examples.json}.
 */
@RestController
public class FullstackExampleController {

    private final FullstackExampleRepository repo;
    private final ObjectMapper objectMapper;

    public FullstackExampleController(FullstackExampleRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** {@code id} is the example's slug, which is what the UI keys the cards on. */
    public record ExampleView(String id, String name, String description, String icon, JsonNode entities,
                              JsonNode pages, JsonNode settings) {
    }

    @GetMapping("/metadata/fullstack/examples")
    public List<ExampleView> list() {
        return repo.findByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .map(e -> new ExampleView(e.getExampleId(), e.getName(), e.getDescription(), e.getIcon(),
                        FullstackExampleAdminController.readEntities(e, objectMapper),
                        FullstackExampleAdminController.readJson(e.getPages(), e, objectMapper),
                        FullstackExampleAdminController.readJson(e.getSettings(), e, objectMapper)))
                .toList();
    }
}
