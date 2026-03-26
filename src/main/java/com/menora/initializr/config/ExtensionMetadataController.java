package com.menora.initializr.config;

import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.DependencySubOptionEntity;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class ExtensionMetadataController {

    private final DependencyConfigService configService;
    private final DependencyCompatibilityRepository compatibilityRepo;

    public ExtensionMetadataController(DependencyConfigService configService,
                                       DependencyCompatibilityRepository compatibilityRepo) {
        this.configService = configService;
        this.compatibilityRepo = compatibilityRepo;
    }

    @GetMapping("/metadata/extensions")
    public Map<String, List<SubOptionDto>> extensions() {
        return configService.getAllSubOptions().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .map(so -> new SubOptionDto(so.getOptionId(), so.getLabel(), so.getDescription()))
                                .collect(Collectors.toList())
                ));
    }

    @GetMapping("/metadata/compatibility")
    public List<CompatibilityRuleDto> compatibility() {
        return compatibilityRepo.findAllByOrderBySortOrderAsc().stream()
                .map(r -> new CompatibilityRuleDto(
                        r.getSourceDepId(),
                        r.getTargetDepId(),
                        r.getRelationType().name(),
                        r.getDescription()))
                .toList();
    }

    public record SubOptionDto(String id, String label, String description) {}
    public record CompatibilityRuleDto(String sourceDepId, String targetDepId, String relationType, String description) {}
}
