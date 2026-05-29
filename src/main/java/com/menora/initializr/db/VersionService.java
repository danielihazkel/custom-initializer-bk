package com.menora.initializr.db;

import com.menora.initializr.db.entity.VersionDefinitionEntity;
import com.menora.initializr.db.entity.VersionKind;
import com.menora.initializr.db.repository.VersionDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DB-backed source of truth for Java / Spring Boot / React / Node /
 * package-manager version lists. Replaces the YAML-bound
 * {@code initializr.*-versions} and {@code frontend.*-versions} blocks.
 *
 * <p>Reads are cached per-kind and invalidated by {@link #refresh()}, which
 * is called from {@link com.menora.initializr.config.DatabaseInitializrMetadataProvider#refresh()}
 * so a single {@code POST /admin/refresh} picks up version changes alongside
 * the dependency catalog.
 */
@Service
public class VersionService {

    private static final Logger log = LoggerFactory.getLogger(VersionService.class);

    private final VersionDefinitionRepository repo;
    private volatile Map<VersionKind, List<VersionDefinitionEntity>> cache;

    public VersionService(VersionDefinitionRepository repo) {
        this.repo = repo;
    }

    /** Drops the cache; the next read repopulates from the DB. */
    public synchronized void refresh() {
        cache = null;
    }

    /** All enabled rows for a kind, in admin-defined sort order. */
    public List<VersionDefinitionEntity> listEnabled(VersionKind kind) {
        return loadByKind().getOrDefault(kind, List.of());
    }

    /**
     * The {@code versionId} of the default row for a kind. Falls back to the
     * first enabled row, then to a hardcoded sentinel so generation never
     * crashes on a freshly migrated DB before seeding has run.
     */
    public String defaultId(VersionKind kind) {
        List<VersionDefinitionEntity> rows = listEnabled(kind);
        return rows.stream().filter(VersionDefinitionEntity::isDefault)
                .map(VersionDefinitionEntity::getVersionId)
                .findFirst()
                .orElseGet(() -> rows.isEmpty() ? fallback(kind) : rows.get(0).getVersionId());
    }

    /** The {@code npm_semver} value for a React row (e.g. {@code "^18.3.1"}). */
    public Optional<String> reactSemver(String reactVersionId) {
        return lookupReact(reactVersionId).map(VersionDefinitionEntity::getNpmSemver);
    }

    /** The {@code types_semver} value for a React row (e.g. {@code "^18.3.3"}). */
    public Optional<String> reactTypesSemver(String reactVersionId) {
        return lookupReact(reactVersionId).map(VersionDefinitionEntity::getTypesSemver);
    }

    private Optional<VersionDefinitionEntity> lookupReact(String reactVersionId) {
        if (reactVersionId == null || reactVersionId.isBlank()) return Optional.empty();
        // Accept either the row's versionId ("18") or a fully-qualified version ("18.3.1")
        // by stripping to the major — matches the old FrontendMustacheContext behavior.
        String major = reactVersionId.trim();
        int dot = major.indexOf('.');
        if (dot > 0) major = major.substring(0, dot);
        for (VersionDefinitionEntity v : listEnabled(VersionKind.REACT)) {
            if (v.getVersionId().equals(major)) return Optional.of(v);
        }
        return Optional.empty();
    }

    private Map<VersionKind, List<VersionDefinitionEntity>> loadByKind() {
        Map<VersionKind, List<VersionDefinitionEntity>> snapshot = cache;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (cache != null) return cache;
            EnumMap<VersionKind, List<VersionDefinitionEntity>> loaded = new EnumMap<>(VersionKind.class);
            for (VersionKind k : VersionKind.values()) {
                loaded.put(k, repo.findByKindAndEnabledTrueOrderBySortOrderAscIdAsc(k));
            }
            cache = loaded;
            log.debug("VersionService cache rebuilt: {}",
                    loaded.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue().size())
                            .toList());
            return loaded;
        }
    }

    /** Sensible defaults if the DB has no rows yet (e.g. between migration and seed). */
    private static String fallback(VersionKind kind) {
        return switch (kind) {
            case JAVA -> "21";
            case BOOT -> "3.2.1";
            case REACT -> "18";
            case NODE -> "20";
            case PACKAGE_MANAGER -> "pnpm";
        };
    }
}
