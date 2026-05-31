package com.menora.initializr.admin;

import com.menora.initializr.admin.dto.ConfigurationExport;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.BuildCustomizationRepository;
import com.menora.initializr.db.repository.ColorPaletteRepository;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import com.menora.initializr.db.repository.DependencyEntryRepository;
import com.menora.initializr.db.repository.DependencyGroupRepository;
import com.menora.initializr.db.repository.DependencySubOptionRepository;
import com.menora.initializr.db.repository.FileContributionRepository;
import com.menora.initializr.db.repository.ModuleDependencyMappingRepository;
import com.menora.initializr.db.repository.ModuleTemplateRepository;
import com.menora.initializr.db.repository.StarterTemplateRepository;
import com.menora.initializr.db.repository.VersionDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip test for {@link ConfigurationExportImportService}: export the seeded
 * catalog, import it back into a freshly-cleared DB, and assert the row counts and
 * representative content survive. This protects both the export/import feature and
 * (since export reflects seeded content) the {@link com.menora.initializr.db.DataSeeder}.
 *
 * <p>Each test runs in a transaction that rolls back, so the destructive
 * {@code importAll} (which clears every table) does not leak into other test classes
 * sharing the in-memory DB.
 *
 * <p>The export contract carries {@code projectKind} (so FRONTEND rows survive a round trip)
 * and the {@code version_definition} table.
 */
@SpringBootTest
@Transactional
class ConfigurationExportImportServiceTest {

    @Autowired private ConfigurationExportImportService service;
    @Autowired private DependencyGroupRepository groupRepo;
    @Autowired private DependencyEntryRepository entryRepo;
    @Autowired private FileContributionRepository fileContribRepo;
    @Autowired private BuildCustomizationRepository buildCustomRepo;
    @Autowired private DependencySubOptionRepository subOptionRepo;
    @Autowired private DependencyCompatibilityRepository compatibilityRepo;
    @Autowired private StarterTemplateRepository templateRepo;
    @Autowired private ModuleTemplateRepository moduleRepo;
    @Autowired private ModuleDependencyMappingRepository moduleMappingRepo;
    @Autowired private ColorPaletteRepository colorPaletteRepo;
    @Autowired private VersionDefinitionRepository versionRepo;

    @Test
    void roundTripPreservesRowCounts() {
        long groups = groupRepo.count();
        long entries = entryRepo.count();
        long fileContribs = fileContribRepo.count();
        long buildCustoms = buildCustomRepo.count();
        long subOptions = subOptionRepo.count();
        long compatibility = compatibilityRepo.count();
        long templates = templateRepo.count();
        long modules = moduleRepo.count();
        long moduleMappings = moduleMappingRepo.count();
        long palettes = colorPaletteRepo.count();
        long versions = versionRepo.count();

        ConfigurationExport export = service.exportAll();
        service.importAll(export);

        assertThat(groupRepo.count()).isEqualTo(groups);
        assertThat(entryRepo.count()).isEqualTo(entries);
        assertThat(fileContribRepo.count()).isEqualTo(fileContribs);
        assertThat(buildCustomRepo.count()).isEqualTo(buildCustoms);
        assertThat(subOptionRepo.count()).isEqualTo(subOptions);
        assertThat(compatibilityRepo.count()).isEqualTo(compatibility);
        assertThat(templateRepo.count()).isEqualTo(templates);
        assertThat(moduleRepo.count()).isEqualTo(modules);
        assertThat(moduleMappingRepo.count()).isEqualTo(moduleMappings);
        assertThat(colorPaletteRepo.count()).isEqualTo(palettes);
        assertThat(versionRepo.count()).isEqualTo(versions);
    }

    @Test
    void roundTripPreservesProjectKind() {
        long backendGroupsBefore = groupRepo.findAllByProjectKindOrderBySortOrderAsc(ProjectKind.BACKEND).size();
        long frontendGroupsBefore = groupRepo.findAllByProjectKindOrderBySortOrderAsc(ProjectKind.FRONTEND).size();
        long backendEntriesBefore = entryRepo.findAllByProjectKind(ProjectKind.BACKEND).size();
        long frontendEntriesBefore = entryRepo.findAllByProjectKind(ProjectKind.FRONTEND).size();
        assertThat(frontendGroupsBefore).isPositive();   // there is a FRONTEND catalog to preserve
        assertThat(frontendEntriesBefore).isPositive();

        service.importAll(service.exportAll());

        assertThat(groupRepo.findAllByProjectKindOrderBySortOrderAsc(ProjectKind.BACKEND)).hasSize((int) backendGroupsBefore);
        assertThat(groupRepo.findAllByProjectKindOrderBySortOrderAsc(ProjectKind.FRONTEND)).hasSize((int) frontendGroupsBefore);
        assertThat(entryRepo.findAllByProjectKind(ProjectKind.BACKEND)).hasSize((int) backendEntriesBefore);
        assertThat(entryRepo.findAllByProjectKind(ProjectKind.FRONTEND)).hasSize((int) frontendEntriesBefore);

        // A known FRONTEND entry keeps its kind (would regress to BACKEND before the fix).
        assertThat(entryRepo.findByDepId("router-react-router").orElseThrow().getProjectKind())
                .isEqualTo(ProjectKind.FRONTEND);
    }

    @Test
    void importReturnsCountsMatchingExport() {
        ConfigurationExport export = service.exportAll();
        Map<String, Integer> counts = service.importAll(export);

        assertThat(counts.get("dependencyGroups")).isEqualTo(export.getDependencyGroups().size());
        assertThat(counts.get("dependencyEntries")).isEqualTo(export.getDependencyEntries().size());
        assertThat(counts.get("fileContributions")).isEqualTo(export.getFileContributions().size());
        assertThat(counts.get("compatibilityRules")).isEqualTo(export.getCompatibilityRules().size());
        assertThat(counts.get("versionDefinitions")).isEqualTo(export.getVersionDefinitions().size());
    }

    @Test
    void roundTripPreservesRepresentativeContent() {
        service.importAll(service.exportAll());

        DependencyEntryEntity rqueue = entryRepo.findByDepId("rqueue").orElseThrow();
        assertThat(rqueue.getMavenGroupId()).isEqualTo("com.sonus");
        assertThat(rqueue.getMavenArtifactId()).isEqualTo("sonus-rqueue");
        assertThat(rqueue.getCompatibilityRange()).isEqualTo("[3.2.0,4.0.0)");
        assertThat(rqueue.getGroup().getName()).isEqualTo("Menora Standards");
    }

    @Test
    void importRejectsUnsupportedVersion() {
        ConfigurationExport export = service.exportAll();
        export.setVersion(2);
        assertThatThrownBy(() -> service.importAll(export))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported export version");
    }

    @Test
    void importRejectsEntryReferencingUnknownGroup() {
        ConfigurationExport export = service.exportAll();
        export.getDependencyEntries().get(0).setGroupName("No Such Group");
        assertThatThrownBy(() -> service.importAll(export))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown group");
    }
}
