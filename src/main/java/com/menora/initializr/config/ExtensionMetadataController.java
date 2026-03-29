package com.menora.initializr.config;

import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.DependencySubOptionEntity;
import com.menora.initializr.db.entity.StarterTemplateDepEntity;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import com.menora.initializr.db.repository.StarterTemplateDepRepository;
import com.menora.initializr.db.repository.StarterTemplateRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class ExtensionMetadataController {

    private final DependencyConfigService configService;
    private final DependencyCompatibilityRepository compatibilityRepo;
    private final StarterTemplateRepository templateRepo;
    private final StarterTemplateDepRepository templateDepRepo;

    public ExtensionMetadataController(DependencyConfigService configService,
                                       DependencyCompatibilityRepository compatibilityRepo,
                                       StarterTemplateRepository templateRepo,
                                       StarterTemplateDepRepository templateDepRepo) {
        this.configService = configService;
        this.compatibilityRepo = compatibilityRepo;
        this.templateRepo = templateRepo;
        this.templateDepRepo = templateDepRepo;
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

    @GetMapping("/metadata/starter-templates")
    public List<StarterTemplateDto> starterTemplates() {
        return templateRepo.findAllByOrderBySortOrderAsc().stream()
                .map(t -> {
                    List<StarterTemplateDepEntity> deps = templateDepRepo.findAllByTemplateId(t.getId());
                    List<TemplateDepDto> depDtos = deps.stream()
                            .map(d -> new TemplateDepDto(
                                    d.getDepId(),
                                    d.getSubOptions() != null && !d.getSubOptions().isBlank()
                                            ? List.of(d.getSubOptions().split(","))
                                            : List.of()))
                            .toList();
                    return new StarterTemplateDto(
                            t.getTemplateId(), t.getName(), t.getDescription(),
                            t.getIcon(), t.getColor(),
                            t.getBootVersion(), t.getJavaVersion(), t.getPackaging(),
                            depDtos);
                })
                .toList();
    }

    public record SubOptionDto(String id, String label, String description) {}
    public record CompatibilityRuleDto(String sourceDepId, String targetDepId, String relationType, String description) {}
    public record TemplateDepDto(String depId, List<String> subOptions) {}
    public record StarterTemplateDto(
            String id, String name, String description,
            String icon, String color,
            String bootVersion, String javaVersion, String packaging,
            List<TemplateDepDto> dependencies) {}
}
