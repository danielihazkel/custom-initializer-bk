package com.menora.initializr.admin;

import com.menora.initializr.admin.dto.OrphanCheckResponse;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OrphanDetectionService {

    private final DependencyEntryRepository entryRepo;
    private final FileContributionRepository fileContribRepo;
    private final BuildCustomizationRepository buildCustomRepo;
    private final DependencySubOptionRepository subOptionRepo;
    private final DependencyCompatibilityRepository compatibilityRepo;
    private final StarterTemplateDepRepository templateDepRepo;
    private final ModuleDependencyMappingRepository moduleMappingRepo;

    public OrphanDetectionService(DependencyEntryRepository entryRepo,
                                   FileContributionRepository fileContribRepo,
                                   BuildCustomizationRepository buildCustomRepo,
                                   DependencySubOptionRepository subOptionRepo,
                                   DependencyCompatibilityRepository compatibilityRepo,
                                   StarterTemplateDepRepository templateDepRepo,
                                   ModuleDependencyMappingRepository moduleMappingRepo) {
        this.entryRepo = entryRepo;
        this.fileContribRepo = fileContribRepo;
        this.buildCustomRepo = buildCustomRepo;
        this.subOptionRepo = subOptionRepo;
        this.compatibilityRepo = compatibilityRepo;
        this.templateDepRepo = templateDepRepo;
        this.moduleMappingRepo = moduleMappingRepo;
    }

    public OrphanCheckResponse findReferencesForDependency(String depId) {
        return new OrphanCheckResponse(OrphanCheckResponse.depRefsMap(
                fileContribRepo.countByDependencyId(depId),
                buildCustomRepo.countByDependencyId(depId),
                subOptionRepo.countByDependencyId(depId),
                compatibilityRepo.countBySourceDepIdOrTargetDepId(depId, depId),
                templateDepRepo.countByDepId(depId),
                moduleMappingRepo.countByDependencyId(depId)
        ));
    }

    public OrphanCheckResponse findReferencesForGroup(Long groupId) {
        List<DependencyEntryEntity> entries = entryRepo.findByGroupId(groupId);
        Set<String> depIds = entries.stream().map(DependencyEntryEntity::getDepId)
                .collect(java.util.stream.Collectors.toSet());
        if (depIds.isEmpty()) {
            return new OrphanCheckResponse(OrphanCheckResponse.depRefsMap(0, 0, 0, 0, 0, 0));
        }
        return new OrphanCheckResponse(OrphanCheckResponse.depRefsMap(
                fileContribRepo.countByDependencyIdIn(depIds),
                buildCustomRepo.countByDependencyIdIn(depIds),
                subOptionRepo.countByDependencyIdIn(depIds),
                compatibilityRepo.countBySourceDepIdInOrTargetDepIdIn(depIds, depIds),
                templateDepRepo.countByDepIdIn(depIds),
                moduleMappingRepo.countByDependencyIdIn(depIds)
        ));
    }

    public OrphanCheckResponse findReferencesForModule(String moduleId) {
        Map<String, Long> refs = new LinkedHashMap<>();
        refs.put("moduleDependencyMappings", moduleMappingRepo.countByModuleId(moduleId));
        return new OrphanCheckResponse(refs);
    }

    public OrphanCheckResponse findReferencesForStarterTemplate(Long templateId) {
        Map<String, Long> refs = new LinkedHashMap<>();
        refs.put("starterTemplateDeps", templateDepRepo.countByTemplateId(templateId));
        return new OrphanCheckResponse(refs);
    }

    @Transactional
    public void cascadeDeleteDependencyReferences(String depId) {
        fileContribRepo.deleteByDependencyId(depId);
        buildCustomRepo.deleteByDependencyId(depId);
        subOptionRepo.deleteByDependencyId(depId);
        compatibilityRepo.deleteBySourceDepIdOrTargetDepId(depId, depId);
        templateDepRepo.deleteByDepId(depId);
        moduleMappingRepo.deleteByDependencyId(depId);
    }

    @Transactional
    public void cascadeDeleteGroupReferences(Long groupId) {
        List<DependencyEntryEntity> entries = entryRepo.findByGroupId(groupId);
        for (DependencyEntryEntity entry : entries) {
            cascadeDeleteDependencyReferences(entry.getDepId());
        }
    }

    @Transactional
    public void cascadeDeleteModuleReferences(String moduleId) {
        moduleMappingRepo.deleteByModuleId(moduleId);
    }

    @Transactional
    public void cascadeDeleteStarterTemplateReferences(Long templateId) {
        templateDepRepo.deleteAllByTemplateId(templateId);
    }
}
