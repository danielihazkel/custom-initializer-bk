package com.menora.initializr.config;

import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencyGroupEntity;
import io.spring.initializr.metadata.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the framework's default metadata provider by loading the dependency
 * catalog from the database instead of application.yml.
 *
 * Non-dependency metadata (Java versions, Boot versions, packaging, types, etc.)
 * is still read from application.yml via InitializrProperties.
 *
 * Call {@link #refresh()} after DB changes to pick up a new catalog without restart.
 * Registered as @Primary bean via {@link MetadataProviderConfig}.
 */
public class DatabaseInitializrMetadataProvider implements InitializrMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializrMetadataProvider.class);

    private final InitializrProperties initializrProperties;
    private final DependencyConfigService configService;

    private volatile InitializrMetadata cachedMetadata;

    public DatabaseInitializrMetadataProvider(InitializrProperties initializrProperties,
                                               DependencyConfigService configService) {
        this.initializrProperties = initializrProperties;
        this.configService = configService;
    }

    @Override
    public InitializrMetadata get() {
        if (cachedMetadata == null) {
            synchronized (this) {
                if (cachedMetadata == null) {
                    cachedMetadata = buildMetadata();
                }
            }
        }
        return cachedMetadata;
    }

    /** Reload the metadata from the database. Call via POST /admin/refresh. */
    public synchronized void refresh() {
        log.info("Refreshing dependency metadata from database");
        cachedMetadata = buildMetadata();
    }

    private InitializrMetadata buildMetadata() {
        // Start from the YAML-based properties (Java versions, Boot versions, etc.)
        InitializrMetadata metadata = InitializrMetadataBuilder
                .fromInitializrProperties(initializrProperties)
                .build();

        // Replace the dependency catalog with DB data
        List<DependencyGroup> dbGroups = loadGroupsFromDb();
        metadata.getDependencies().getContent().clear();
        metadata.getDependencies().getContent().addAll(dbGroups);
        metadata.getDependencies().validate();

        log.info("Loaded {} dependency groups from database, {} types from properties",
                dbGroups.size(), metadata.getTypes().getContent().size());
        return metadata;
    }

    private List<DependencyGroup> loadGroupsFromDb() {
        List<DependencyGroupEntity> entities = configService.getAllGroupsWithEntries();
        List<DependencyGroup> groups = new ArrayList<>();

        for (DependencyGroupEntity ge : entities) {
            DependencyGroup group = DependencyGroup.create(ge.getName());

            for (DependencyEntryEntity ee : ge.getEntries()) {
                try {
                    Dependency dep = new Dependency();
                    dep.setId(ee.getDepId());
                    dep.setName(ee.getName());
                    dep.setDescription(ee.getDescription());
                    dep.setStarter(ee.isStarter());

                    if (ee.getMavenGroupId() != null && !ee.getMavenGroupId().isBlank()) {
                        dep.setGroupId(ee.getMavenGroupId());
                    }
                    if (ee.getMavenArtifactId() != null && !ee.getMavenArtifactId().isBlank()) {
                        dep.setArtifactId(ee.getMavenArtifactId());
                    }
                    if (ee.getVersion() != null && !ee.getVersion().isBlank()) {
                        dep.setVersion(ee.getVersion());
                    }
                    if (ee.getScope() != null && !ee.getScope().isBlank()) {
                        dep.setScope(ee.getScope());
                    }
                    if (ee.getRepository() != null && !ee.getRepository().isBlank()) {
                        dep.setRepository(ee.getRepository());
                    }
                    if (ee.getCompatibilityRange() != null && !ee.getCompatibilityRange().isBlank()) {
                        dep.setCompatibilityRange(ee.getCompatibilityRange());
                    }

                    dep.resolve();
                    group.getContent().add(dep);
                    log.info("  [metadata] loaded dep '{}' ({}) into group '{}'",
                            ee.getDepId(), ee.getName(), ge.getName());
                } catch (Exception ex) {
                    log.error("  [metadata] failed to load dep id='{}' name='{}' in group '{}': {}",
                            ee.getDepId(), ee.getName(), ge.getName(), ex.toString());
                }
            }

            log.info("[metadata] group '{}' — {} entries", ge.getName(), group.getContent().size());
            groups.add(group);
        }

        return groups;
    }
}
