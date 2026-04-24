package com.menora.initializr.admin;

import com.menora.initializr.admin.dto.ConfigurationExport;
import com.menora.initializr.admin.dto.OrphanCheckResponse;
import com.menora.initializr.admin.validation.FileContributionContentValidator;
import com.menora.initializr.config.DatabaseInitializrMetadataProvider;
import com.menora.initializr.db.entity.*;
import com.menora.initializr.db.repository.*;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import org.springframework.transaction.annotation.Transactional;

/**
 * Admin REST API for managing dependency configuration in the database.
 *
 * POST /admin/refresh  — reloads the metadata cache from DB (no restart needed)
 * GET  /admin/dependency-groups     — list all groups
 * POST /admin/dependency-groups     — create a group
 * ...
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final InitializrMetadataProvider metadataProvider;
    private final DependencyGroupRepository groupRepo;
    private final DependencyEntryRepository entryRepo;
    private final FileContributionRepository fileContribRepo;
    private final BuildCustomizationRepository buildCustomRepo;
    private final DependencySubOptionRepository subOptionRepo;
    private final DependencyCompatibilityRepository compatibilityRepo;
    private final StarterTemplateRepository templateRepo;
    private final StarterTemplateDepRepository templateDepRepo;
    private final ModuleTemplateRepository moduleRepo;
    private final ModuleDependencyMappingRepository moduleMappingRepo;
    private final OrphanDetectionService orphanService;
    private final ConfigurationExportImportService exportImportService;
    private final FileContributionContentValidator contentValidator;

    public AdminController(InitializrMetadataProvider metadataProvider,
                           DependencyGroupRepository groupRepo,
                           DependencyEntryRepository entryRepo,
                           FileContributionRepository fileContribRepo,
                           BuildCustomizationRepository buildCustomRepo,
                           DependencySubOptionRepository subOptionRepo,
                           DependencyCompatibilityRepository compatibilityRepo,
                           StarterTemplateRepository templateRepo,
                           StarterTemplateDepRepository templateDepRepo,
                           ModuleTemplateRepository moduleRepo,
                           ModuleDependencyMappingRepository moduleMappingRepo,
                           OrphanDetectionService orphanService,
                           ConfigurationExportImportService exportImportService,
                           FileContributionContentValidator contentValidator) {
        this.metadataProvider = metadataProvider;
        this.groupRepo = groupRepo;
        this.entryRepo = entryRepo;
        this.fileContribRepo = fileContribRepo;
        this.buildCustomRepo = buildCustomRepo;
        this.subOptionRepo = subOptionRepo;
        this.compatibilityRepo = compatibilityRepo;
        this.templateRepo = templateRepo;
        this.templateDepRepo = templateDepRepo;
        this.moduleRepo = moduleRepo;
        this.moduleMappingRepo = moduleMappingRepo;
        this.orphanService = orphanService;
        this.exportImportService = exportImportService;
        this.contentValidator = contentValidator;
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @PostMapping("/refresh")
    public ResponseEntity<String> refresh() {
        if (metadataProvider instanceof DatabaseInitializrMetadataProvider dbProvider) {
            dbProvider.refresh();
            return ResponseEntity.ok("Metadata refreshed from database");
        }
        return ResponseEntity.badRequest().body("Metadata provider does not support refresh");
    }

    /** Invalidate the metadata cache after a catalog-affecting write. */
    private void refreshMetadata() {
        if (metadataProvider instanceof DatabaseInitializrMetadataProvider dbProvider) {
            dbProvider.refresh();
        }
    }

    // ── Dependency Groups ─────────────────────────────────────────────────────

    @GetMapping("/dependency-groups")
    public List<DependencyGroupEntity> listGroups() {
        return groupRepo.findAllByOrderBySortOrderAsc();
    }

    @PostMapping("/dependency-groups")
    public DependencyGroupEntity createGroup(@Valid @RequestBody DependencyGroupEntity group) {
        DependencyGroupEntity saved = groupRepo.save(group);
        refreshMetadata();
        return saved;
    }

    @PutMapping("/dependency-groups/reorder")
    @Transactional
    public ResponseEntity<Void> reorderGroups(@RequestBody List<Map<String, Long>> orderings) {
        for (Map<String, Long> entry : orderings) {
            Long id = entry.get("id");
            int sortOrder = entry.get("sortOrder").intValue();
            groupRepo.findById(id).ifPresent(group -> {
                group.setSortOrder(sortOrder);
                groupRepo.save(group);
            });
        }
        refreshMetadata();
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/dependency-groups/{id}")
    public DependencyGroupEntity updateGroup(@PathVariable Long id, @Valid @RequestBody DependencyGroupEntity group) {
        group.setId(id);
        DependencyGroupEntity saved = groupRepo.save(group);
        refreshMetadata();
        return saved;
    }

    @DeleteMapping("/dependency-groups/{id}")
    public ResponseEntity<?> deleteGroup(@PathVariable Long id,
                                         @RequestParam(defaultValue = "false") boolean force) {
        OrphanCheckResponse refs = orphanService.findReferencesForGroup(id);
        if (refs.hasReferences() && !force) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(refs);
        }
        if (force) orphanService.cascadeDeleteGroupReferences(id);
        groupRepo.deleteById(id);
        refreshMetadata();
        return ResponseEntity.noContent().build();
    }

    // ── Dependency Entries ────────────────────────────────────────────────────

    @GetMapping("/dependency-entries")
    public List<DependencyEntryEntity> listEntries() {
        return entryRepo.findAll();
    }

    @PostMapping("/dependency-entries")
    public DependencyEntryEntity createEntry(@Valid @RequestBody DependencyEntryEntity entry) {
        DependencyEntryEntity saved = entryRepo.save(entry);
        refreshMetadata();
        return saved;
    }

    @PutMapping("/dependency-entries/{id}")
    public DependencyEntryEntity updateEntry(@PathVariable Long id, @Valid @RequestBody DependencyEntryEntity entry) {
        entry.setId(id);
        DependencyEntryEntity saved = entryRepo.save(entry);
        refreshMetadata();
        return saved;
    }

    @DeleteMapping("/dependency-entries/{id}")
    public ResponseEntity<?> deleteEntry(@PathVariable Long id,
                                         @RequestParam(defaultValue = "false") boolean force) {
        DependencyEntryEntity entry = entryRepo.findById(id).orElse(null);
        if (entry == null) return ResponseEntity.noContent().build();
        OrphanCheckResponse refs = orphanService.findReferencesForDependency(entry.getDepId());
        if (refs.hasReferences() && !force) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(refs);
        }
        if (force) orphanService.cascadeDeleteDependencyReferences(entry.getDepId());
        entryRepo.deleteById(id);
        refreshMetadata();
        return ResponseEntity.noContent().build();
    }

    // ── File Contributions ────────────────────────────────────────────────────

    @GetMapping("/file-contributions")
    public List<FileContributionEntity> listFileContributions() {
        return fileContribRepo.findAll();
    }

    @PostMapping("/file-contributions")
    public FileContributionEntity createFileContribution(@Valid @RequestBody FileContributionEntity fc) {
        validateContent(fc);
        return fileContribRepo.save(fc);
    }

    @PutMapping("/file-contributions/{id}")
    public FileContributionEntity updateFileContribution(@PathVariable Long id, @Valid @RequestBody FileContributionEntity fc) {
        fc.setId(id);
        validateContent(fc);
        return fileContribRepo.save(fc);
    }

    private void validateContent(FileContributionEntity fc) {
        List<String> errors = contentValidator.validate(fc);
        if (errors.isEmpty()) return;
        String detail = errors.get(0);
        if (errors.size() > 1) detail += " (+" + (errors.size() - 1) + " more)";
        throw new IllegalArgumentException(
                "Syntax validation failed for " + fc.getTargetPath() + ": " + detail);
    }

    @DeleteMapping("/file-contributions/{id}")
    public ResponseEntity<Void> deleteFileContribution(@PathVariable Long id) {
        fileContribRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Build Customizations ──────────────────────────────────────────────────

    @GetMapping("/build-customizations")
    public List<BuildCustomizationEntity> listBuildCustomizations() {
        return buildCustomRepo.findAll();
    }

    @PostMapping("/build-customizations")
    public BuildCustomizationEntity createBuildCustomization(@Valid @RequestBody BuildCustomizationEntity bc) {
        return buildCustomRepo.save(bc);
    }

    @PutMapping("/build-customizations/{id}")
    public BuildCustomizationEntity updateBuildCustomization(@PathVariable Long id, @Valid @RequestBody BuildCustomizationEntity bc) {
        bc.setId(id);
        return buildCustomRepo.save(bc);
    }

    @DeleteMapping("/build-customizations/{id}")
    public ResponseEntity<Void> deleteBuildCustomization(@PathVariable Long id) {
        buildCustomRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Sub-Options ───────────────────────────────────────────────────────────

    @GetMapping("/sub-options")
    public List<DependencySubOptionEntity> listSubOptions() {
        return subOptionRepo.findAll();
    }

    @PostMapping("/sub-options")
    public DependencySubOptionEntity createSubOption(@Valid @RequestBody DependencySubOptionEntity so) {
        DependencySubOptionEntity saved = subOptionRepo.save(so);
        refreshMetadata();
        return saved;
    }

    @PutMapping("/sub-options/{id}")
    public DependencySubOptionEntity updateSubOption(@PathVariable Long id, @Valid @RequestBody DependencySubOptionEntity so) {
        so.setId(id);
        DependencySubOptionEntity saved = subOptionRepo.save(so);
        refreshMetadata();
        return saved;
    }

    @DeleteMapping("/sub-options/{id}")
    public ResponseEntity<Void> deleteSubOption(@PathVariable Long id) {
        subOptionRepo.deleteById(id);
        refreshMetadata();
        return ResponseEntity.noContent().build();
    }

    // ── Compatibility Rules ───────────────────────────────────────────────────

    @GetMapping("/compatibility")
    public List<DependencyCompatibilityEntity> listCompatibility() {
        return compatibilityRepo.findAllByOrderBySortOrderAsc();
    }

    @PostMapping("/compatibility")
    public DependencyCompatibilityEntity createCompatibility(@Valid @RequestBody DependencyCompatibilityEntity rule) {
        DependencyCompatibilityEntity saved = compatibilityRepo.save(rule);
        refreshMetadata();
        return saved;
    }

    @PutMapping("/compatibility/{id}")
    public DependencyCompatibilityEntity updateCompatibility(@PathVariable Long id, @Valid @RequestBody DependencyCompatibilityEntity rule) {
        rule.setId(id);
        DependencyCompatibilityEntity saved = compatibilityRepo.save(rule);
        refreshMetadata();
        return saved;
    }

    @DeleteMapping("/compatibility/{id}")
    public ResponseEntity<Void> deleteCompatibility(@PathVariable Long id) {
        compatibilityRepo.deleteById(id);
        refreshMetadata();
        return ResponseEntity.noContent().build();
    }

    // ── Starter Templates ──────────────────────────────────────────────────────

    @GetMapping("/starter-templates")
    public List<StarterTemplateEntity> listTemplates() {
        return templateRepo.findAllByOrderBySortOrderAsc();
    }

    @PostMapping("/starter-templates")
    public StarterTemplateEntity createTemplate(@Valid @RequestBody StarterTemplateEntity template) {
        return templateRepo.save(template);
    }

    @PutMapping("/starter-templates/{id}")
    public StarterTemplateEntity updateTemplate(@PathVariable Long id, @Valid @RequestBody StarterTemplateEntity template) {
        template.setId(id);
        return templateRepo.save(template);
    }

    @DeleteMapping("/starter-templates/{id}")
    public ResponseEntity<?> deleteTemplate(@PathVariable Long id,
                                            @RequestParam(defaultValue = "false") boolean force) {
        OrphanCheckResponse refs = orphanService.findReferencesForStarterTemplate(id);
        if (refs.hasReferences() && !force) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(refs);
        }
        if (force) orphanService.cascadeDeleteStarterTemplateReferences(id);
        templateRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Starter Template Dependencies ──────────────────────────────────────────

    @GetMapping("/starter-template-deps")
    public List<StarterTemplateDepEntity> listTemplateDeps(
            @RequestParam(required = false) Long templateId) {
        return templateId != null
                ? templateDepRepo.findAllByTemplateId(templateId)
                : templateDepRepo.findAll();
    }

    @PostMapping("/starter-template-deps")
    public StarterTemplateDepEntity createTemplateDep(@Valid @RequestBody StarterTemplateDepEntity dep) {
        return templateDepRepo.save(dep);
    }

    @PutMapping("/starter-template-deps/{id}")
    public StarterTemplateDepEntity updateTemplateDep(@PathVariable Long id, @Valid @RequestBody StarterTemplateDepEntity dep) {
        dep.setId(id);
        return templateDepRepo.save(dep);
    }

    @DeleteMapping("/starter-template-deps/{id}")
    public ResponseEntity<Void> deleteTemplateDep(@PathVariable Long id) {
        templateDepRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Module Templates ──────────────────────────────────────────────────────

    @GetMapping("/module-templates")
    public List<ModuleTemplateEntity> listModules() {
        return moduleRepo.findAllByOrderBySortOrderAsc();
    }

    @PostMapping("/module-templates")
    public ModuleTemplateEntity createModule(@Valid @RequestBody ModuleTemplateEntity module) {
        return moduleRepo.save(module);
    }

    @PutMapping("/module-templates/{id}")
    public ModuleTemplateEntity updateModule(@PathVariable Long id, @Valid @RequestBody ModuleTemplateEntity module) {
        module.setId(id);
        return moduleRepo.save(module);
    }

    @DeleteMapping("/module-templates/{id}")
    public ResponseEntity<?> deleteModule(@PathVariable Long id,
                                          @RequestParam(defaultValue = "false") boolean force) {
        ModuleTemplateEntity module = moduleRepo.findById(id).orElse(null);
        if (module == null) return ResponseEntity.noContent().build();
        OrphanCheckResponse refs = orphanService.findReferencesForModule(module.getModuleId());
        if (refs.hasReferences() && !force) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(refs);
        }
        if (force) orphanService.cascadeDeleteModuleReferences(module.getModuleId());
        moduleRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Module Dependency Mappings ─────────────────────────────────────────────

    @GetMapping("/module-dep-mappings")
    public List<ModuleDependencyMappingEntity> listModuleMappings() {
        return moduleMappingRepo.findAllByOrderBySortOrderAsc();
    }

    @PostMapping("/module-dep-mappings")
    public ModuleDependencyMappingEntity createModuleMapping(@Valid @RequestBody ModuleDependencyMappingEntity mapping) {
        return moduleMappingRepo.save(mapping);
    }

    @PutMapping("/module-dep-mappings/{id}")
    public ModuleDependencyMappingEntity updateModuleMapping(@PathVariable Long id, @Valid @RequestBody ModuleDependencyMappingEntity mapping) {
        mapping.setId(id);
        return moduleMappingRepo.save(mapping);
    }

    @DeleteMapping("/module-dep-mappings/{id}")
    public ResponseEntity<Void> deleteModuleMapping(@PathVariable Long id) {
        moduleMappingRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Export / Import ───────────────────────────────────────────────────────

    @GetMapping("/export")
    public ConfigurationExport exportConfiguration() {
        return exportImportService.exportAll();
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importConfiguration(@RequestBody ConfigurationExport data) {
        Map<String, Integer> counts = exportImportService.importAll(data);
        return ResponseEntity.ok(Map.of("imported", counts));
    }
}
