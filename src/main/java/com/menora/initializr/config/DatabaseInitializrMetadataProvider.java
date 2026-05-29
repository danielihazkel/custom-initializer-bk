package com.menora.initializr.config;

import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.VersionService;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencyGroupEntity;
import com.menora.initializr.db.entity.VersionDefinitionEntity;
import com.menora.initializr.db.entity.VersionKind;
import io.spring.initializr.metadata.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the framework's default metadata provider by loading the dependency
 * catalog and the Java / Boot version lists from the database instead of
 * application.yml.
 *
 * Other non-dependency metadata (packagings, types, languages, etc.) is still
 * read from application.yml via InitializrProperties.
 *
 * Call {@link #refresh()} after DB changes to pick up the new catalog and
 * versions without a restart. Registered as @Primary bean via
 * {@link MetadataProviderConfig}.
 */
public class DatabaseInitializrMetadataProvider implements InitializrMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializrMetadataProvider.class);

    private final InitializrProperties initializrProperties;
    private final DependencyConfigService configService;
    private final VersionService versionService;

    private volatile InitializrMetadata cachedMetadata;

    public DatabaseInitializrMetadataProvider(InitializrProperties initializrProperties,
                                               DependencyConfigService configService,
                                               VersionService versionService) {
        this.initializrProperties = initializrProperties;
        this.configService = configService;
        this.versionService = versionService;
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
        versionService.refresh();
        cachedMetadata = buildMetadata();
    }

    private InitializrMetadata buildMetadata() {
        // Inject DB-driven java/boot versions into InitializrProperties before
        // the framework's builder reads them — going through properties (rather
        // than mutating the built InitializrMetadata) avoids the unmodifiable
        // capability lists the framework wraps empty version lists in.
        applyVersionsToProperties(initializrProperties.getJavaVersions(),
                versionService.listEnabled(VersionKind.JAVA));
        applyVersionsToProperties(initializrProperties.getBootVersions(),
                versionService.listEnabled(VersionKind.BOOT));

        InitializrMetadata metadata = InitializrMetadataBuilder
                .fromInitializrProperties(initializrProperties)
                .build();

        // Replace the dependency catalog with DB data.
        List<DependencyGroup> dbGroups = loadGroupsFromDb();
        metadata.getDependencies().getContent().clear();
        metadata.getDependencies().getContent().addAll(dbGroups);
        metadata.getDependencies().validate();

        log.info("Loaded {} dependency groups, {} java versions, {} boot versions from database",
                dbGroups.size(),
                metadata.getJavaVersions().getContent().size(),
                metadata.getBootVersions().getContent().size());
        return metadata;
    }

    private static void applyVersionsToProperties(List<DefaultMetadataElement> target,
                                                  List<VersionDefinitionEntity> rows) {
        target.clear();
        for (VersionDefinitionEntity row : rows) {
            DefaultMetadataElement el = new DefaultMetadataElement();
            el.setId(row.getVersionId());
            el.setName(row.getDisplayName());
            el.setDefault(row.isDefault());
            target.add(el);
        }
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
