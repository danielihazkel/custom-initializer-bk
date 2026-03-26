package com.menora.initializr.admin;

import com.menora.initializr.config.DatabaseInitializrMetadataProvider;
import com.menora.initializr.db.entity.*;
import com.menora.initializr.db.repository.*;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    public AdminController(InitializrMetadataProvider metadataProvider,
                           DependencyGroupRepository groupRepo,
                           DependencyEntryRepository entryRepo,
                           FileContributionRepository fileContribRepo,
                           BuildCustomizationRepository buildCustomRepo,
                           DependencySubOptionRepository subOptionRepo,
                           DependencyCompatibilityRepository compatibilityRepo) {
        this.metadataProvider = metadataProvider;
        this.groupRepo = groupRepo;
        this.entryRepo = entryRepo;
        this.fileContribRepo = fileContribRepo;
        this.buildCustomRepo = buildCustomRepo;
        this.subOptionRepo = subOptionRepo;
        this.compatibilityRepo = compatibilityRepo;
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

    // ── Dependency Groups ─────────────────────────────────────────────────────

    @GetMapping("/dependency-groups")
    public List<DependencyGroupEntity> listGroups() {
        return groupRepo.findAllByOrderBySortOrderAsc();
    }

    @PostMapping("/dependency-groups")
    public DependencyGroupEntity createGroup(@RequestBody DependencyGroupEntity group) {
        return groupRepo.save(group);
    }

    @PutMapping("/dependency-groups/{id}")
    public DependencyGroupEntity updateGroup(@PathVariable Long id, @RequestBody DependencyGroupEntity group) {
        group.setId(id);
        return groupRepo.save(group);
    }

    @DeleteMapping("/dependency-groups/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        groupRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Dependency Entries ────────────────────────────────────────────────────

    @GetMapping("/dependency-entries")
    public List<DependencyEntryEntity> listEntries() {
        return entryRepo.findAll();
    }

    @PostMapping("/dependency-entries")
    public DependencyEntryEntity createEntry(@RequestBody DependencyEntryEntity entry) {
        return entryRepo.save(entry);
    }

    @PutMapping("/dependency-entries/{id}")
    public DependencyEntryEntity updateEntry(@PathVariable Long id, @RequestBody DependencyEntryEntity entry) {
        entry.setId(id);
        return entryRepo.save(entry);
    }

    @DeleteMapping("/dependency-entries/{id}")
    public ResponseEntity<Void> deleteEntry(@PathVariable Long id) {
        entryRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── File Contributions ────────────────────────────────────────────────────

    @GetMapping("/file-contributions")
    public List<FileContributionEntity> listFileContributions() {
        return fileContribRepo.findAll();
    }

    @PostMapping("/file-contributions")
    public FileContributionEntity createFileContribution(@RequestBody FileContributionEntity fc) {
        return fileContribRepo.save(fc);
    }

    @PutMapping("/file-contributions/{id}")
    public FileContributionEntity updateFileContribution(@PathVariable Long id, @RequestBody FileContributionEntity fc) {
        fc.setId(id);
        return fileContribRepo.save(fc);
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
    public BuildCustomizationEntity createBuildCustomization(@RequestBody BuildCustomizationEntity bc) {
        return buildCustomRepo.save(bc);
    }

    @PutMapping("/build-customizations/{id}")
    public BuildCustomizationEntity updateBuildCustomization(@PathVariable Long id, @RequestBody BuildCustomizationEntity bc) {
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
    public DependencySubOptionEntity createSubOption(@RequestBody DependencySubOptionEntity so) {
        return subOptionRepo.save(so);
    }

    @PutMapping("/sub-options/{id}")
    public DependencySubOptionEntity updateSubOption(@PathVariable Long id, @RequestBody DependencySubOptionEntity so) {
        so.setId(id);
        return subOptionRepo.save(so);
    }

    @DeleteMapping("/sub-options/{id}")
    public ResponseEntity<Void> deleteSubOption(@PathVariable Long id) {
        subOptionRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Compatibility Rules ───────────────────────────────────────────────────

    @GetMapping("/compatibility")
    public List<DependencyCompatibilityEntity> listCompatibility() {
        return compatibilityRepo.findAllByOrderBySortOrderAsc();
    }

    @PostMapping("/compatibility")
    public DependencyCompatibilityEntity createCompatibility(@RequestBody DependencyCompatibilityEntity rule) {
        return compatibilityRepo.save(rule);
    }

    @PutMapping("/compatibility/{id}")
    public DependencyCompatibilityEntity updateCompatibility(@PathVariable Long id, @RequestBody DependencyCompatibilityEntity rule) {
        rule.setId(id);
        return compatibilityRepo.save(rule);
    }

    @DeleteMapping("/compatibility/{id}")
    public ResponseEntity<Void> deleteCompatibility(@PathVariable Long id) {
        compatibilityRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
