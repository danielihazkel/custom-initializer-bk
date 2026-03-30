package com.menora.initializr.admin;

import com.menora.initializr.admin.dto.ConfigurationExport;
import com.menora.initializr.admin.dto.ConfigurationExport.*;
import com.menora.initializr.config.DatabaseInitializrMetadataProvider;
import com.menora.initializr.db.entity.*;
import com.menora.initializr.db.repository.*;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class ConfigurationExportImportService {

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

    public ConfigurationExportImportService(InitializrMetadataProvider metadataProvider,
                                             DependencyGroupRepository groupRepo,
                                             DependencyEntryRepository entryRepo,
                                             FileContributionRepository fileContribRepo,
                                             BuildCustomizationRepository buildCustomRepo,
                                             DependencySubOptionRepository subOptionRepo,
                                             DependencyCompatibilityRepository compatibilityRepo,
                                             StarterTemplateRepository templateRepo,
                                             StarterTemplateDepRepository templateDepRepo,
                                             ModuleTemplateRepository moduleRepo,
                                             ModuleDependencyMappingRepository moduleMappingRepo) {
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
    }

    // ── Export ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ConfigurationExport exportAll() {
        ConfigurationExport export_ = new ConfigurationExport();
        export_.setExportedAt(Instant.now().toString());

        export_.setDependencyGroups(
                groupRepo.findAllByOrderBySortOrderAsc().stream().map(g -> {
                    GroupExport ge = new GroupExport();
                    ge.setName(g.getName());
                    ge.setSortOrder(g.getSortOrder());
                    return ge;
                }).toList());

        export_.setDependencyEntries(
                entryRepo.findAll().stream().map(e -> {
                    EntryExport ee = new EntryExport();
                    ee.setGroupName(e.getGroup().getName());
                    ee.setDepId(e.getDepId());
                    ee.setName(e.getName());
                    ee.setDescription(e.getDescription());
                    ee.setMavenGroupId(e.getMavenGroupId());
                    ee.setMavenArtifactId(e.getMavenArtifactId());
                    ee.setVersion(e.getVersion());
                    ee.setScope(e.getScope());
                    ee.setRepository(e.getRepository());
                    ee.setSortOrder(e.getSortOrder());
                    ee.setCompatibilityRange(e.getCompatibilityRange());
                    return ee;
                }).toList());

        export_.setFileContributions(
                fileContribRepo.findAll().stream().map(f -> {
                    FileContribExport fe = new FileContribExport();
                    fe.setDependencyId(f.getDependencyId());
                    fe.setFileType(f.getFileType().name());
                    fe.setContent(f.getContent());
                    fe.setTargetPath(f.getTargetPath());
                    fe.setSubstitutionType(f.getSubstitutionType() != null ? f.getSubstitutionType().name() : null);
                    fe.setJavaVersion(f.getJavaVersion());
                    fe.setSubOptionId(f.getSubOptionId());
                    fe.setSortOrder(f.getSortOrder());
                    return fe;
                }).toList());

        export_.setBuildCustomizations(
                buildCustomRepo.findAll().stream().map(b -> {
                    BuildCustomExport be = new BuildCustomExport();
                    be.setDependencyId(b.getDependencyId());
                    be.setCustomizationType(b.getCustomizationType().name());
                    be.setMavenGroupId(b.getMavenGroupId());
                    be.setMavenArtifactId(b.getMavenArtifactId());
                    be.setVersion(b.getVersion());
                    be.setExcludeFromGroupId(b.getExcludeFromGroupId());
                    be.setExcludeFromArtifactId(b.getExcludeFromArtifactId());
                    be.setRepoId(b.getRepoId());
                    be.setRepoName(b.getRepoName());
                    be.setRepoUrl(b.getRepoUrl());
                    be.setSnapshotsEnabled(b.isSnapshotsEnabled());
                    be.setSortOrder(b.getSortOrder());
                    return be;
                }).toList());

        export_.setSubOptions(
                subOptionRepo.findAll().stream().map(s -> {
                    SubOptionExport se = new SubOptionExport();
                    se.setDependencyId(s.getDependencyId());
                    se.setOptionId(s.getOptionId());
                    se.setLabel(s.getLabel());
                    se.setDescription(s.getDescription());
                    se.setSortOrder(s.getSortOrder());
                    return se;
                }).toList());

        export_.setCompatibilityRules(
                compatibilityRepo.findAllByOrderBySortOrderAsc().stream().map(c -> {
                    CompatibilityExport ce = new CompatibilityExport();
                    ce.setSourceDepId(c.getSourceDepId());
                    ce.setTargetDepId(c.getTargetDepId());
                    ce.setRelationType(c.getRelationType().name());
                    ce.setDescription(c.getDescription());
                    ce.setSortOrder(c.getSortOrder());
                    return ce;
                }).toList());

        export_.setStarterTemplates(
                templateRepo.findAllByOrderBySortOrderAsc().stream().map(t -> {
                    TemplateExport te = new TemplateExport();
                    te.setTemplateId(t.getTemplateId());
                    te.setName(t.getName());
                    te.setDescription(t.getDescription());
                    te.setIcon(t.getIcon());
                    te.setColor(t.getColor());
                    te.setBootVersion(t.getBootVersion());
                    te.setJavaVersion(t.getJavaVersion());
                    te.setPackaging(t.getPackaging());
                    te.setSortOrder(t.getSortOrder());
                    return te;
                }).toList());

        export_.setStarterTemplateDeps(
                templateDepRepo.findAll().stream().map(td -> {
                    TemplateDepExport tde = new TemplateDepExport();
                    tde.setTemplateId(td.getTemplate().getTemplateId());
                    tde.setDepId(td.getDepId());
                    tde.setSubOptions(td.getSubOptions());
                    return tde;
                }).toList());

        export_.setModuleTemplates(
                moduleRepo.findAllByOrderBySortOrderAsc().stream().map(m -> {
                    ModuleExport me = new ModuleExport();
                    me.setModuleId(m.getModuleId());
                    me.setLabel(m.getLabel());
                    me.setDescription(m.getDescription());
                    me.setSuffix(m.getSuffix());
                    me.setPackaging(m.getPackaging());
                    me.setHasMainClass(m.isHasMainClass());
                    me.setSortOrder(m.getSortOrder());
                    return me;
                }).toList());

        export_.setModuleDependencyMappings(
                moduleMappingRepo.findAllByOrderBySortOrderAsc().stream().map(mm -> {
                    ModuleMappingExport mme = new ModuleMappingExport();
                    mme.setDependencyId(mm.getDependencyId());
                    mme.setModuleId(mm.getModuleId());
                    mme.setSortOrder(mm.getSortOrder());
                    return mme;
                }).toList());

        return export_;
    }

    // ── Import ───────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Integer> importAll(ConfigurationExport data) {
        validate(data);

        // Clear all tables in child-first order
        moduleMappingRepo.deleteAllInBatch();
        moduleRepo.deleteAllInBatch();
        templateDepRepo.deleteAllInBatch();
        templateRepo.deleteAllInBatch();
        compatibilityRepo.deleteAllInBatch();
        subOptionRepo.deleteAllInBatch();
        buildCustomRepo.deleteAllInBatch();
        fileContribRepo.deleteAllInBatch();
        entryRepo.deleteAllInBatch();
        groupRepo.deleteAllInBatch();

        // Insert groups, build name→entity map
        Map<String, DependencyGroupEntity> groupMap = new LinkedHashMap<>();
        for (GroupExport g : safe(data.getDependencyGroups())) {
            DependencyGroupEntity entity = new DependencyGroupEntity();
            entity.setName(g.getName());
            entity.setSortOrder(g.getSortOrder());
            groupMap.put(g.getName(), groupRepo.save(entity));
        }

        // Insert entries, resolve group FK
        for (EntryExport e : safe(data.getDependencyEntries())) {
            DependencyGroupEntity group = groupMap.get(e.getGroupName());
            if (group == null) throw new IllegalArgumentException("Unknown group: " + e.getGroupName());
            DependencyEntryEntity entity = new DependencyEntryEntity();
            entity.setGroup(group);
            entity.setDepId(e.getDepId());
            entity.setName(e.getName());
            entity.setDescription(e.getDescription());
            entity.setMavenGroupId(e.getMavenGroupId());
            entity.setMavenArtifactId(e.getMavenArtifactId());
            entity.setVersion(e.getVersion());
            entity.setScope(e.getScope());
            entity.setRepository(e.getRepository());
            entity.setSortOrder(e.getSortOrder());
            entity.setCompatibilityRange(e.getCompatibilityRange());
            entryRepo.save(entity);
        }

        // Insert leaf tables (string-based references, no FK resolution needed)
        for (FileContribExport f : safe(data.getFileContributions())) {
            FileContributionEntity entity = new FileContributionEntity();
            entity.setDependencyId(f.getDependencyId());
            entity.setFileType(FileContributionEntity.FileType.valueOf(f.getFileType()));
            entity.setContent(f.getContent());
            entity.setTargetPath(f.getTargetPath());
            entity.setSubstitutionType(f.getSubstitutionType() != null
                    ? FileContributionEntity.SubstitutionType.valueOf(f.getSubstitutionType()) : null);
            entity.setJavaVersion(f.getJavaVersion());
            entity.setSubOptionId(f.getSubOptionId());
            entity.setSortOrder(f.getSortOrder());
            fileContribRepo.save(entity);
        }

        for (BuildCustomExport b : safe(data.getBuildCustomizations())) {
            BuildCustomizationEntity entity = new BuildCustomizationEntity();
            entity.setDependencyId(b.getDependencyId());
            entity.setCustomizationType(BuildCustomizationEntity.CustomizationType.valueOf(b.getCustomizationType()));
            entity.setMavenGroupId(b.getMavenGroupId());
            entity.setMavenArtifactId(b.getMavenArtifactId());
            entity.setVersion(b.getVersion());
            entity.setExcludeFromGroupId(b.getExcludeFromGroupId());
            entity.setExcludeFromArtifactId(b.getExcludeFromArtifactId());
            entity.setRepoId(b.getRepoId());
            entity.setRepoName(b.getRepoName());
            entity.setRepoUrl(b.getRepoUrl());
            entity.setSnapshotsEnabled(b.isSnapshotsEnabled());
            entity.setSortOrder(b.getSortOrder());
            buildCustomRepo.save(entity);
        }

        for (SubOptionExport s : safe(data.getSubOptions())) {
            DependencySubOptionEntity entity = new DependencySubOptionEntity();
            entity.setDependencyId(s.getDependencyId());
            entity.setOptionId(s.getOptionId());
            entity.setLabel(s.getLabel());
            entity.setDescription(s.getDescription());
            entity.setSortOrder(s.getSortOrder());
            subOptionRepo.save(entity);
        }

        for (CompatibilityExport c : safe(data.getCompatibilityRules())) {
            DependencyCompatibilityEntity entity = new DependencyCompatibilityEntity();
            entity.setSourceDepId(c.getSourceDepId());
            entity.setTargetDepId(c.getTargetDepId());
            entity.setRelationType(DependencyCompatibilityEntity.RelationType.valueOf(c.getRelationType()));
            entity.setDescription(c.getDescription());
            entity.setSortOrder(c.getSortOrder());
            compatibilityRepo.save(entity);
        }

        // Insert starter templates, build templateId→entity map
        Map<String, StarterTemplateEntity> templateMap = new LinkedHashMap<>();
        for (TemplateExport t : safe(data.getStarterTemplates())) {
            StarterTemplateEntity entity = new StarterTemplateEntity();
            entity.setTemplateId(t.getTemplateId());
            entity.setName(t.getName());
            entity.setDescription(t.getDescription());
            entity.setIcon(t.getIcon());
            entity.setColor(t.getColor());
            entity.setBootVersion(t.getBootVersion());
            entity.setJavaVersion(t.getJavaVersion());
            entity.setPackaging(t.getPackaging());
            entity.setSortOrder(t.getSortOrder());
            templateMap.put(t.getTemplateId(), templateRepo.save(entity));
        }

        // Insert template deps, resolve template FK
        for (TemplateDepExport td : safe(data.getStarterTemplateDeps())) {
            StarterTemplateEntity template = templateMap.get(td.getTemplateId());
            if (template == null) throw new IllegalArgumentException("Unknown template: " + td.getTemplateId());
            StarterTemplateDepEntity entity = new StarterTemplateDepEntity();
            entity.setTemplate(template);
            entity.setDepId(td.getDepId());
            entity.setSubOptions(td.getSubOptions());
            templateDepRepo.save(entity);
        }

        // Insert module templates
        for (ModuleExport m : safe(data.getModuleTemplates())) {
            ModuleTemplateEntity entity = new ModuleTemplateEntity();
            entity.setModuleId(m.getModuleId());
            entity.setLabel(m.getLabel());
            entity.setDescription(m.getDescription());
            entity.setSuffix(m.getSuffix());
            entity.setPackaging(m.getPackaging());
            entity.setHasMainClass(m.isHasMainClass());
            entity.setSortOrder(m.getSortOrder());
            moduleRepo.save(entity);
        }

        // Insert module dependency mappings
        for (ModuleMappingExport mm : safe(data.getModuleDependencyMappings())) {
            ModuleDependencyMappingEntity entity = new ModuleDependencyMappingEntity();
            entity.setDependencyId(mm.getDependencyId());
            entity.setModuleId(mm.getModuleId());
            entity.setSortOrder(mm.getSortOrder());
            moduleMappingRepo.save(entity);
        }

        // Refresh metadata cache
        if (metadataProvider instanceof DatabaseInitializrMetadataProvider dbProvider) {
            dbProvider.refresh();
        }

        // Return counts
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("dependencyGroups", safe(data.getDependencyGroups()).size());
        counts.put("dependencyEntries", safe(data.getDependencyEntries()).size());
        counts.put("fileContributions", safe(data.getFileContributions()).size());
        counts.put("buildCustomizations", safe(data.getBuildCustomizations()).size());
        counts.put("subOptions", safe(data.getSubOptions()).size());
        counts.put("compatibilityRules", safe(data.getCompatibilityRules()).size());
        counts.put("starterTemplates", safe(data.getStarterTemplates()).size());
        counts.put("starterTemplateDeps", safe(data.getStarterTemplateDeps()).size());
        counts.put("moduleTemplates", safe(data.getModuleTemplates()).size());
        counts.put("moduleDependencyMappings", safe(data.getModuleDependencyMappings()).size());
        return counts;
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private void validate(ConfigurationExport data) {
        if (data.getVersion() != 1) {
            throw new IllegalArgumentException("Unsupported export version: " + data.getVersion());
        }

        // Check for duplicate group names
        Set<String> groupNames = new HashSet<>();
        for (GroupExport g : safe(data.getDependencyGroups())) {
            if (!groupNames.add(g.getName())) {
                throw new IllegalArgumentException("Duplicate group name: " + g.getName());
            }
        }

        // Check every entry references an existing group
        for (EntryExport e : safe(data.getDependencyEntries())) {
            if (!groupNames.contains(e.getGroupName())) {
                throw new IllegalArgumentException("Entry '" + e.getDepId()
                        + "' references unknown group: " + e.getGroupName());
            }
        }

        // Check for duplicate depIds
        Set<String> depIds = new HashSet<>();
        for (EntryExport e : safe(data.getDependencyEntries())) {
            if (!depIds.add(e.getDepId())) {
                throw new IllegalArgumentException("Duplicate depId: " + e.getDepId());
            }
        }

        // Check every template dep references an existing template
        Set<String> templateIds = new HashSet<>();
        for (TemplateExport t : safe(data.getStarterTemplates())) {
            if (!templateIds.add(t.getTemplateId())) {
                throw new IllegalArgumentException("Duplicate templateId: " + t.getTemplateId());
            }
        }
        for (TemplateDepExport td : safe(data.getStarterTemplateDeps())) {
            if (!templateIds.contains(td.getTemplateId())) {
                throw new IllegalArgumentException("Template dep references unknown template: " + td.getTemplateId());
            }
        }
    }

    private static <T> List<T> safe(List<T> list) {
        return list != null ? list : Collections.emptyList();
    }
}
