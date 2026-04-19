package com.menora.initializr.config;

import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencySubOptionEntity;
import com.menora.initializr.db.entity.ModuleDependencyMappingEntity;
import com.menora.initializr.db.entity.StarterTemplateDepEntity;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import com.menora.initializr.db.repository.DependencyEntryRepository;
import com.menora.initializr.db.repository.ModuleDependencyMappingRepository;
import com.menora.initializr.db.repository.ModuleTemplateRepository;
import com.menora.initializr.db.repository.StarterTemplateDepRepository;
import com.menora.initializr.db.repository.StarterTemplateRepository;
import com.menora.initializr.sql.SqlDialect;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
public class ExtensionMetadataController {

    private final DependencyConfigService configService;
    private final DependencyCompatibilityRepository compatibilityRepo;
    private final StarterTemplateRepository templateRepo;
    private final StarterTemplateDepRepository templateDepRepo;
    private final ModuleTemplateRepository moduleRepo;
    private final ModuleDependencyMappingRepository moduleMappingRepo;
    private final DependencyEntryRepository entryRepo;

    public ExtensionMetadataController(DependencyConfigService configService,
                                       DependencyCompatibilityRepository compatibilityRepo,
                                       StarterTemplateRepository templateRepo,
                                       StarterTemplateDepRepository templateDepRepo,
                                       ModuleTemplateRepository moduleRepo,
                                       ModuleDependencyMappingRepository moduleMappingRepo,
                                       DependencyEntryRepository entryRepo) {
        this.configService = configService;
        this.compatibilityRepo = compatibilityRepo;
        this.templateRepo = templateRepo;
        this.templateDepRepo = templateDepRepo;
        this.moduleRepo = moduleRepo;
        this.moduleMappingRepo = moduleMappingRepo;
        this.entryRepo = entryRepo;
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

    @GetMapping("/metadata/module-templates")
    public List<ModuleTemplateDto> moduleTemplates() {
        List<ModuleDependencyMappingEntity> allMappings = moduleMappingRepo.findAllByOrderBySortOrderAsc();
        Map<String, List<String>> mappingsByModule = allMappings.stream()
                .collect(Collectors.groupingBy(
                        ModuleDependencyMappingEntity::getModuleId,
                        Collectors.mapping(ModuleDependencyMappingEntity::getDependencyId, Collectors.toList())));

        return moduleRepo.findAllByOrderBySortOrderAsc().stream()
                .map(m -> new ModuleTemplateDto(
                        m.getModuleId(), m.getLabel(), m.getDescription(),
                        m.getSuffix(), m.getPackaging(), m.isHasMainClass(),
                        mappingsByModule.getOrDefault(m.getModuleId(), List.of())))
                .toList();
    }

    /**
     * Dep-id → dialect-enum-name for drivers the SQL wizard can handle AND that
     * currently exist in the dependency catalog. Empty map → UI hides the wizard.
     */
    @GetMapping("/metadata/sql-dialects")
    public Map<String, String> sqlDialects() {
        Set<String> candidateDepIds = Arrays.stream(SqlDialect.values())
                .map(SqlDialect::depId)
                .collect(Collectors.toSet());
        Set<String> present = entryRepo.findByDepIdIn(candidateDepIds).stream()
                .map(DependencyEntryEntity::getDepId)
                .collect(Collectors.toSet());
        Map<String, String> result = new LinkedHashMap<>();
        for (SqlDialect d : SqlDialect.values()) {
            if (present.contains(d.depId())) {
                result.put(d.depId(), d.name());
            }
        }
        return result;
    }

    public record SubOptionDto(String id, String label, String description) {}
    public record CompatibilityRuleDto(String sourceDepId, String targetDepId, String relationType, String description) {}
    public record TemplateDepDto(String depId, List<String> subOptions) {}
    public record StarterTemplateDto(
            String id, String name, String description,
            String icon, String color,
            String bootVersion, String javaVersion, String packaging,
            List<TemplateDepDto> dependencies) {}
    public record ModuleTemplateDto(
            String moduleId, String label, String description,
            String suffix, String packaging, boolean hasMainClass,
            List<String> dependencyIds) {}
}
