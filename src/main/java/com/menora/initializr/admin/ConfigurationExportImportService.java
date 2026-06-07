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
    private final EntityTemplateSetRepository entityTemplateSetRepo;
    private final EntityTemplateFileRepository entityTemplateFileRepo;
    private final EntityTemplateSetDefaultDepRepository entityTemplateSetDefaultDepRepo;
    private final ColorPaletteRepository colorPaletteRepo;
    private final VersionDefinitionRepository versionRepo;

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
                                             ModuleDependencyMappingRepository moduleMappingRepo,
                                             EntityTemplateSetRepository entityTemplateSetRepo,
                                             EntityTemplateFileRepository entityTemplateFileRepo,
                                             EntityTemplateSetDefaultDepRepository entityTemplateSetDefaultDepRepo,
                                             ColorPaletteRepository colorPaletteRepo,
                                             VersionDefinitionRepository versionRepo) {
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
        this.entityTemplateSetRepo = entityTemplateSetRepo;
        this.entityTemplateFileRepo = entityTemplateFileRepo;
        this.entityTemplateSetDefaultDepRepo = entityTemplateSetDefaultDepRepo;
        this.colorPaletteRepo = colorPaletteRepo;
        this.versionRepo = versionRepo;
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
                    ge.setProjectKind(g.getProjectKind().name());
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
                    ee.setProjectKind(e.getProjectKind().name());
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
                    fe.setProjectKind(f.getProjectKind().name());
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
                    be.setScope(b.getScope());
                    be.setSubOptionId(b.getSubOptionId());
                    be.setSortOrder(b.getSortOrder());
                    be.setProjectKind(b.getProjectKind().name());
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
                    se.setProjectKind(s.getProjectKind().name());
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
                    ce.setProjectKind(c.getProjectKind().name());
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
                    te.setProjectKind(t.getProjectKind().name());
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

        Map<Long, String> setIdToKey = new LinkedHashMap<>();
        export_.setEntityTemplateSets(
                entityTemplateSetRepo.findAllByOrderBySortOrderAsc().stream().map(s -> {
                    setIdToKey.put(s.getId(), s.getSetKey());
                    EntityTemplateSetExport ese = new EntityTemplateSetExport();
                    ese.setSetKey(s.getSetKey());
                    ese.setName(s.getName());
                    ese.setDescription(s.getDescription());
                    ese.setKind(s.getKind().name());
                    ese.setEnabled(s.isEnabled());
                    ese.setSortOrder(s.getSortOrder());
                    ese.setDesignSystem(s.getDesignSystem() == null ? null : s.getDesignSystem().name());
                    ese.setBootVersion(s.getBootVersion());
                    ese.setJavaVersion(s.getJavaVersion());
                    ese.setDefaultPaletteId(s.getDefaultPaletteId());
                    return ese;
                }).toList());

        export_.setEntityTemplateFiles(
                entityTemplateFileRepo.findAll().stream().map(f -> {
                    EntityTemplateFileExport efe = new EntityTemplateFileExport();
                    efe.setSetKey(setIdToKey.get(f.getSetId()));
                    efe.setPathTemplate(f.getPathTemplate());
                    efe.setContent(f.getContent());
                    efe.setSubstitutionType(f.getSubstitutionType() != null ? f.getSubstitutionType().name() : null);
                    efe.setFileType(f.getFileType() != null ? f.getFileType().name() : null);
                    efe.setPerEntity(f.isPerEntity());
                    efe.setSortOrder(f.getSortOrder());
                    efe.setGatedBy(f.getGatedBy());
                    return efe;
                }).filter(efe -> efe.getSetKey() != null).toList());

        export_.setEntityTemplateSetDefaultDeps(
                entityTemplateSetDefaultDepRepo.findAll().stream().map(d -> {
                    EntityTemplateSetDefaultDepExport dde = new EntityTemplateSetDefaultDepExport();
                    dde.setSetKey(setIdToKey.get(d.getSetId()));
                    dde.setDepId(d.getDepId());
                    dde.setSortOrder(d.getSortOrder());
                    return dde;
                }).filter(dde -> dde.getSetKey() != null).toList());

        export_.setColorPalettes(
                colorPaletteRepo.findAllByOrderBySortOrderAsc().stream().map(p -> {
                    ColorPaletteExport cpe = new ColorPaletteExport();
                    cpe.setPaletteId(p.getPaletteId());
                    cpe.setName(p.getName());
                    cpe.setDescription(p.getDescription());
                    cpe.setPrimary(p.getPrimary());
                    cpe.setSecondary(p.getSecondary());
                    cpe.setAccent(p.getAccent());
                    cpe.setError(p.getError());
                    cpe.setDefault(p.isDefault());
                    cpe.setSortOrder(p.getSortOrder());
                    return cpe;
                }).toList());

        export_.setVersionDefinitions(
                versionRepo.findAllByOrderByKindAscSortOrderAscIdAsc().stream().map(v -> {
                    VersionExport ve = new VersionExport();
                    ve.setKind(v.getKind().name());
                    ve.setVersionId(v.getVersionId());
                    ve.setDisplayName(v.getDisplayName());
                    ve.setDefault(v.isDefault());
                    ve.setSortOrder(v.getSortOrder());
                    ve.setEnabled(v.isEnabled());
                    ve.setNpmSemver(v.getNpmSemver());
                    ve.setTypesSemver(v.getTypesSemver());
                    return ve;
                }).toList());

        return export_;
    }

    // ── Import ───────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Integer> importAll(ConfigurationExport data) {
        validate(data);

        // Clear all tables in child-first order
        entityTemplateFileRepo.deleteAllInBatch();
        entityTemplateSetDefaultDepRepo.deleteAllInBatch();
        entityTemplateSetRepo.deleteAllInBatch();
        colorPaletteRepo.deleteAllInBatch();
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
            entity.setProjectKind(parseKind(g.getProjectKind()));
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
            entity.setProjectKind(parseKind(e.getProjectKind()));
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
            entity.setProjectKind(parseKind(f.getProjectKind()));
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
            entity.setScope(b.getScope());
            entity.setSubOptionId(b.getSubOptionId());
            entity.setSortOrder(b.getSortOrder());
            entity.setProjectKind(parseKind(b.getProjectKind()));
            buildCustomRepo.save(entity);
        }

        for (SubOptionExport s : safe(data.getSubOptions())) {
            DependencySubOptionEntity entity = new DependencySubOptionEntity();
            entity.setDependencyId(s.getDependencyId());
            entity.setOptionId(s.getOptionId());
            entity.setLabel(s.getLabel());
            entity.setDescription(s.getDescription());
            entity.setSortOrder(s.getSortOrder());
            entity.setProjectKind(parseKind(s.getProjectKind()));
            subOptionRepo.save(entity);
        }

        for (CompatibilityExport c : safe(data.getCompatibilityRules())) {
            DependencyCompatibilityEntity entity = new DependencyCompatibilityEntity();
            entity.setSourceDepId(c.getSourceDepId());
            entity.setTargetDepId(c.getTargetDepId());
            entity.setRelationType(DependencyCompatibilityEntity.RelationType.valueOf(c.getRelationType()));
            entity.setDescription(c.getDescription());
            entity.setSortOrder(c.getSortOrder());
            entity.setProjectKind(parseKind(c.getProjectKind()));
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
            entity.setProjectKind(parseKind(t.getProjectKind()));
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

        // Insert color palettes (referenced by entity template sets via defaultPaletteId)
        for (ColorPaletteExport p : safe(data.getColorPalettes())) {
            ColorPaletteEntity entity = new ColorPaletteEntity();
            entity.setPaletteId(p.getPaletteId());
            entity.setName(p.getName());
            entity.setDescription(p.getDescription());
            entity.setPrimary(p.getPrimary());
            entity.setSecondary(p.getSecondary());
            entity.setAccent(p.getAccent());
            entity.setError(p.getError());
            entity.setDefault(p.isDefault());
            entity.setSortOrder(p.getSortOrder());
            colorPaletteRepo.save(entity);
        }

        // Insert entity template sets, build setKey→entity map
        Map<String, EntityTemplateSetEntity> setMap = new LinkedHashMap<>();
        for (EntityTemplateSetExport s : safe(data.getEntityTemplateSets())) {
            EntityTemplateSetEntity entity = new EntityTemplateSetEntity();
            entity.setSetKey(s.getSetKey());
            entity.setName(s.getName());
            entity.setDescription(s.getDescription());
            entity.setKind(EntityTemplateSetEntity.Kind.valueOf(s.getKind()));
            entity.setEnabled(s.isEnabled());
            entity.setSortOrder(s.getSortOrder());
            if (s.getDesignSystem() != null && !s.getDesignSystem().isBlank()) {
                entity.setDesignSystem(EntityTemplateSetEntity.DesignSystem.valueOf(s.getDesignSystem()));
            }
            entity.setBootVersion(s.getBootVersion());
            entity.setJavaVersion(s.getJavaVersion());
            entity.setDefaultPaletteId(s.getDefaultPaletteId());
            setMap.put(s.getSetKey(), entityTemplateSetRepo.save(entity));
        }

        // Insert entity template files, resolve set FK by setKey
        for (EntityTemplateFileExport f : safe(data.getEntityTemplateFiles())) {
            EntityTemplateSetEntity set = setMap.get(f.getSetKey());
            if (set == null) {
                throw new IllegalArgumentException("Entity template file references unknown set: " + f.getSetKey());
            }
            EntityTemplateFileEntity entity = new EntityTemplateFileEntity();
            entity.setSetId(set.getId());
            entity.setPathTemplate(f.getPathTemplate());
            entity.setContent(f.getContent());
            entity.setSubstitutionType(f.getSubstitutionType() != null
                    ? FileContributionEntity.SubstitutionType.valueOf(f.getSubstitutionType()) : null);
            entity.setFileType(f.getFileType() != null
                    ? EntityTemplateFileEntity.FileType.valueOf(f.getFileType()) : null);
            entity.setPerEntity(f.isPerEntity());
            entity.setSortOrder(f.getSortOrder());
            entity.setGatedBy(f.getGatedBy());
            entityTemplateFileRepo.save(entity);
        }

        // Insert default deps, resolve set FK by setKey
        for (EntityTemplateSetDefaultDepExport d : safe(data.getEntityTemplateSetDefaultDeps())) {
            EntityTemplateSetEntity set = setMap.get(d.getSetKey());
            if (set == null) {
                throw new IllegalArgumentException(
                        "Entity template set default dep references unknown set: " + d.getSetKey());
            }
            EntityTemplateSetDefaultDepEntity entity = new EntityTemplateSetDefaultDepEntity();
            entity.setSetId(set.getId());
            entity.setDepId(d.getDepId());
            entity.setSortOrder(d.getSortOrder());
            entityTemplateSetDefaultDepRepo.save(entity);
        }

        // Version definitions. Backward-compatible: only touch the table when the export
        // carries them (older exports predate this field — leave existing versions intact).
        if (data.getVersionDefinitions() != null) {
            versionRepo.deleteAllInBatch();
            for (VersionExport v : data.getVersionDefinitions()) {
                VersionDefinitionEntity entity = new VersionDefinitionEntity();
                entity.setKind(VersionKind.valueOf(v.getKind()));
                entity.setVersionId(v.getVersionId());
                entity.setDisplayName(v.getDisplayName());
                entity.setDefault(v.isDefault());
                entity.setSortOrder(v.getSortOrder());
                entity.setEnabled(v.isEnabled());
                entity.setNpmSemver(v.getNpmSemver());
                entity.setTypesSemver(v.getTypesSemver());
                versionRepo.save(entity);
            }
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
        counts.put("entityTemplateSets", safe(data.getEntityTemplateSets()).size());
        counts.put("entityTemplateFiles", safe(data.getEntityTemplateFiles()).size());
        counts.put("entityTemplateSetDefaultDeps", safe(data.getEntityTemplateSetDefaultDeps()).size());
        counts.put("colorPalettes", safe(data.getColorPalettes()).size());
        counts.put("versionDefinitions", safe(data.getVersionDefinitions()).size());
        return counts;
    }

    /** Parse a project-kind string; null/blank → null (entity setters coerce to BACKEND). */
    private static ProjectKind parseKind(String kind) {
        return (kind == null || kind.isBlank()) ? null : ProjectKind.valueOf(kind);
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
