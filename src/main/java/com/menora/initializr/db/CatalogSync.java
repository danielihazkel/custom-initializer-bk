package com.menora.initializr.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.CatalogSeedEntity;
import com.menora.initializr.db.entity.CatalogSeedEntity.RowTable;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.BuildCustomizationRepository;
import com.menora.initializr.db.repository.CatalogSeedRepository;
import com.menora.initializr.db.repository.FileContributionRepository;
import com.menora.initializr.db.seed.CatalogManifests.BuildCustomDef;
import com.menora.initializr.db.seed.CatalogManifests.FileContribDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the catalog's file contributions and build customizations in step with the ones this
 * release ships under {@code catalog/} — the counterpart, for the dependency catalog, of
 * {@link EntityTemplateSetSync}.
 *
 * <p>Those rows are edited in the admin UI, so the database is what a generation reads — and
 * they were seeded once, so a release that fixed a Dockerfile, a datasource config or a BOM
 * version reached new databases only. What is remembered about each shipped row
 * ({@link CatalogSeedEntity}) is the fingerprint of the shipped version last written to this
 * database. On start, for each row the release ships:
 * <ul>
 *   <li>the database's row still has that fingerprint — nobody touched it: it is brought up to
 *       the release;</li>
 *   <li>the row was edited: it is kept, and reported;</li>
 *   <li>the row was deleted: it stays deleted;</li>
 *   <li>the release has not shipped this row to this database before: it is added;</li>
 *   <li>a row an older release shipped and this one does not is removed when nobody touched it.</li>
 * </ul>
 *
 * <p><b>The first start of an existing database (the baseline).</b> Nothing is known about its
 * rows: one that differs from the shipped one may be an admin's edit or simply an older release's
 * content, and one that is missing may have been deleted or may never have been there. So that
 * start changes nothing — it records every shipped row, the ones that match as in step and the
 * rest as never applied — and what differs or is missing is {@link #report() reported} for an
 * admin to {@link #apply apply} or {@link #keep keep}. From then on the list above holds.
 *
 * <p>A row is recognised by its identity — kind, dependency, target path, sub-option and
 * runtime version for a file; kind, dependency, type, coordinates, repository, sub-option and
 * Boot range for a build row — so an admin who changes one of those has made another row, and
 * the shipped one counts as deleted. Rows an admin created match nothing shipped and are never
 * touched.
 *
 * <p>{@code initializr.catalog.sync=off} leaves the catalog alone entirely.
 */
@Component
public class CatalogSync {

    private static final Logger log = LoggerFactory.getLogger(CatalogSync.class);

    /** Where a shipped row stands in this database. */
    public enum Status {
        /** The database's row is the shipped one. */
        IN_STEP,
        /** The database's row differs: an admin's edit, or content from before the baseline. */
        DIFFERS,
        /** No such row, and it was never applied here — an existing database's first look. */
        MISSING,
        /** An admin chose to keep their own row (or its absence). */
        KEPT,
        /** The row was applied here once and has been deleted since. */
        DELETED
    }

    /** What a start did. */
    public record Result(boolean baseline, int added, int updated, int removed, int differs, int missing) {
        public boolean changedAnything() {
            return added + updated + removed > 0;
        }
    }

    /** One shipped row and where it stands; {@code rowId} is the database row it was matched to. */
    public record Row(String key, RowTable table, ProjectKind kind, String dependencyId, String label,
                      Status status, Long rowId) {
    }

    public record Report(Map<Status, Integer> summary, List<Row> rows) {
    }

    /** A shipped row beside the database's: the two entities, as the admin API serializes them. */
    public record Detail(Row row, Object shipped, Object current) {
    }

    /** One row this release ships: exactly one of {@code file} and {@code build} is set. */
    private record Shipped(String key, RowTable table, String label, String fingerprint,
                           FileContributionEntity file, BuildCustomizationEntity build) {

        Object entity() {
            return file != null ? file : build;
        }

        ProjectKind kind() {
            return file != null ? file.getProjectKind() : build.getProjectKind();
        }

        String dependencyId() {
            return file != null ? file.getDependencyId() : build.getDependencyId();
        }
    }

    private final FileContributionRepository fileRepo;
    private final BuildCustomizationRepository buildRepo;
    private final CatalogSeedRepository seedRepo;
    private final boolean enabled;

    public CatalogSync(FileContributionRepository fileRepo,
                       BuildCustomizationRepository buildRepo,
                       CatalogSeedRepository seedRepo,
                       @Value("${initializr.catalog.sync:auto}") String mode) {
        this.fileRepo = fileRepo;
        this.buildRepo = buildRepo;
        this.seedRepo = seedRepo;
        this.enabled = !"off".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public boolean enabled() {
        return enabled;
    }

    // ── What the release ships ───────────────────────────────────────────────

    /** The file contributions of one kind's manifest, as unsaved entities, in the manifest's order. */
    public List<FileContributionEntity> shippedFileContributions(ProjectKind kind) throws IOException {
        List<FileContributionEntity> out = new ArrayList<>();
        for (FileContribDef d : readManifest(manifestDir(kind) + "file-contributions.json", FileContribDef.class)) {
            FileContributionEntity e = new FileContributionEntity();
            e.setDependencyId(d.depId());
            e.setFileType(FileContributionEntity.FileType.valueOf(d.fileType()));
            e.setContent(d.contentResource() != null ? readClasspath(d.contentResource()) : d.content());
            e.setTargetPath(d.targetPath());
            e.setSubstitutionType(d.substitutionType() != null
                    ? FileContributionEntity.SubstitutionType.valueOf(d.substitutionType()) : null);
            e.setJavaVersion(d.javaVersion());
            e.setNodeVersion(d.nodeVersion());
            e.setSubOptionId(d.subOptionId());
            e.setSortOrder(d.sortOrder());
            e.setProjectKind(kind);
            out.add(e);
        }
        return out;
    }

    /** The build customizations of one kind's manifest, as unsaved entities, in the manifest's order. */
    public List<BuildCustomizationEntity> shippedBuildCustomizations(ProjectKind kind) throws IOException {
        List<BuildCustomizationEntity> out = new ArrayList<>();
        for (BuildCustomDef d : readManifest(manifestDir(kind) + "build-customizations.json", BuildCustomDef.class)) {
            BuildCustomizationEntity e = new BuildCustomizationEntity();
            e.setDependencyId(d.depId());
            e.setCustomizationType(BuildCustomizationEntity.CustomizationType.valueOf(d.type()));
            e.setMavenGroupId(d.mavenGroupId());
            e.setMavenArtifactId(d.mavenArtifactId());
            e.setVersion(d.version());
            e.setExcludeFromGroupId(d.excludeFromGroupId());
            e.setExcludeFromArtifactId(d.excludeFromArtifactId());
            e.setRepoId(d.repoId());
            e.setRepoName(d.repoName());
            e.setRepoUrl(d.repoUrl());
            if (d.snapshotsEnabled() != null) e.setSnapshotsEnabled(d.snapshotsEnabled());
            e.setScope(d.scope());
            e.setSubOptionId(d.subOptionId());
            e.setCompatibilityRange(d.compatibilityRange());
            e.setSortOrder(d.sortOrder());
            e.setProjectKind(kind);
            out.add(e);
        }
        return out;
    }

    private static String manifestDir(ProjectKind kind) {
        return kind == ProjectKind.FRONTEND ? "catalog/frontend/" : "catalog/";
    }

    private List<Shipped> shipped() {
        try {
            List<Shipped> out = new ArrayList<>();
            for (ProjectKind kind : List.of(ProjectKind.BACKEND, ProjectKind.FRONTEND)) {
                for (FileContributionEntity f : shippedFileContributions(kind)) {
                    out.add(new Shipped(keyOf(f), RowTable.FILE_CONTRIBUTION, labelOf(f), fingerprintOf(f), f, null));
                }
                for (BuildCustomizationEntity b : shippedBuildCustomizations(kind)) {
                    out.add(new Shipped(keyOf(b), RowTable.BUILD_CUSTOMIZATION, labelOf(b), fingerprintOf(b), null, b));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("The shipped catalog could not be read", e);
        }
    }

    // ── Identity and fingerprint ─────────────────────────────────────────────

    /** What makes a file contribution the same row from one release to the next. */
    static String keyOf(FileContributionEntity f) {
        return hash(String.join("\u0001", "file", f.getProjectKind().name(), text(f.getDependencyId()),
                text(f.getTargetPath()), text(f.getSubOptionId()), text(f.getJavaVersion()), text(f.getNodeVersion())));
    }

    static String keyOf(BuildCustomizationEntity b) {
        return hash(String.join("\u0001", "build", b.getProjectKind().name(), text(b.getDependencyId()),
                String.valueOf(b.getCustomizationType()), text(b.getMavenGroupId()), text(b.getMavenArtifactId()),
                text(b.getRepoId()), text(b.getSubOptionId()), text(b.getCompatibilityRange())));
    }

    /**
     * Everything about a file contribution that is not its identity. The content is hashed with
     * its line endings normalized ({@link EntityTemplateSetSync#hash}): a Windows checkout ships
     * CRLF and the admin's textarea hands an untouched row back with LF — neither is an edit.
     * A missing substitution type is {@code NONE}, which is what the admin form saves for it.
     */
    static String fingerprintOf(FileContributionEntity f) {
        FileContributionEntity.SubstitutionType substitution = f.getSubstitutionType() == null
                ? FileContributionEntity.SubstitutionType.NONE : f.getSubstitutionType();
        return hash(String.join("\u0001", String.valueOf(f.getFileType()), substitution.name(),
                String.valueOf(f.getSortOrder()), EntityTemplateSetSync.hash(f.getContent())));
    }

    static String fingerprintOf(BuildCustomizationEntity b) {
        return hash(String.join("\u0001", text(b.getVersion()), text(b.getExcludeFromGroupId()),
                text(b.getExcludeFromArtifactId()), text(b.getRepoName()), text(b.getRepoUrl()),
                String.valueOf(b.isSnapshotsEnabled()), text(b.getScope()), String.valueOf(b.getSortOrder())));
    }

    private static String keyOf(Object row) {
        return row instanceof FileContributionEntity f ? keyOf(f) : keyOf((BuildCustomizationEntity) row);
    }

    private static String fingerprintOf(Object row) {
        return row instanceof FileContributionEntity f ? fingerprintOf(f) : fingerprintOf((BuildCustomizationEntity) row);
    }

    private static Long idOf(Object row) {
        return row instanceof FileContributionEntity f ? f.getId() : ((BuildCustomizationEntity) row).getId();
    }

    private static String labelOf(FileContributionEntity f) {
        StringBuilder label = new StringBuilder(f.getProjectKind().name().toLowerCase())
                .append(" file ").append(f.getDependencyId()).append(" → ").append(f.getTargetPath());
        if (f.getSubOptionId() != null) label.append(" (option ").append(f.getSubOptionId()).append(')');
        if (f.getJavaVersion() != null) label.append(" (Java ").append(f.getJavaVersion()).append(')');
        if (f.getNodeVersion() != null) label.append(" (Node ").append(f.getNodeVersion()).append(')');
        return label.toString();
    }

    private static String labelOf(BuildCustomizationEntity b) {
        StringBuilder label = new StringBuilder(b.getProjectKind().name().toLowerCase())
                .append(" build ").append(b.getDependencyId()).append(' ').append(b.getCustomizationType());
        if (!text(b.getMavenGroupId()).isEmpty() || !text(b.getMavenArtifactId()).isEmpty()) {
            label.append(' ').append(text(b.getMavenGroupId()));
            if (!text(b.getMavenGroupId()).isEmpty() && !text(b.getMavenArtifactId()).isEmpty()) label.append(':');
            label.append(text(b.getMavenArtifactId()));
        }
        if (!text(b.getRepoId()).isEmpty()) label.append(' ').append(b.getRepoId());
        if (b.getSubOptionId() != null) label.append(" (option ").append(b.getSubOptionId()).append(')');
        if (b.getCompatibilityRange() != null) label.append(" (Boot ").append(b.getCompatibilityRange()).append(')');
        return label.toString();
    }

    /** Null and blank are one value: the entities' setters turn one into the other on some columns only. */
    private static String text(String s) {
        return s == null || s.isBlank() ? "" : s;
    }

    private static String hash(String s) {
        return EntityTemplateSetSync.hash(s);
    }

    // ── The database's rows ──────────────────────────────────────────────────

    /** Every stored row of both tables by identity. Several rows may share one (an admin's copy). */
    private Map<String, List<Object>> storedByKey() {
        Map<String, List<Object>> byKey = new LinkedHashMap<>();
        for (FileContributionEntity f : fileRepo.findAll()) byKey.computeIfAbsent(keyOf(f), k -> new ArrayList<>()).add(f);
        for (BuildCustomizationEntity b : buildRepo.findAll()) byKey.computeIfAbsent(keyOf(b), k -> new ArrayList<>()).add(b);
        return byKey;
    }

    /** What is recorded about each shipped row, by its key. */
    private Map<String, CatalogSeedEntity> states() {
        Map<String, CatalogSeedEntity> states = new LinkedHashMap<>();
        for (CatalogSeedEntity state : seedRepo.findAll()) states.put(state.getSeedKey(), state);
        return states;
    }

    /** The stored row a shipped one stands for: the one equal to it when there are several, else the first. */
    private static Object match(Map<String, List<Object>> stored, String key, String fingerprint) {
        List<Object> candidates = stored.get(key);
        if (candidates == null || candidates.isEmpty()) return null;
        for (Object row : candidates) {
            if (fingerprintOf(row).equals(fingerprint)) return row;
        }
        return candidates.get(0);
    }

    private void save(Object row) {
        if (row instanceof FileContributionEntity f) fileRepo.save(f);
        else buildRepo.save((BuildCustomizationEntity) row);
    }

    private void delete(Object row) {
        if (row instanceof FileContributionEntity f) fileRepo.delete(f);
        else buildRepo.delete((BuildCustomizationEntity) row);
    }

    /** Makes the stored row say what the shipped one says; the identity is the same already. */
    private static void copy(Shipped shipped, Object row) {
        if (row instanceof FileContributionEntity target) {
            FileContributionEntity source = shipped.file();
            target.setContent(source.getContent());
            target.setFileType(source.getFileType());
            target.setSubstitutionType(source.getSubstitutionType());
            target.setSortOrder(source.getSortOrder());
        } else {
            BuildCustomizationEntity target = (BuildCustomizationEntity) row;
            BuildCustomizationEntity source = shipped.build();
            target.setVersion(source.getVersion());
            target.setExcludeFromGroupId(source.getExcludeFromGroupId());
            target.setExcludeFromArtifactId(source.getExcludeFromArtifactId());
            target.setRepoName(source.getRepoName());
            target.setRepoUrl(source.getRepoUrl());
            target.setSnapshotsEnabled(source.isSnapshotsEnabled());
            target.setScope(source.getScope());
            target.setSortOrder(source.getSortOrder());
        }
    }

    // ── The start-up pass ────────────────────────────────────────────────────

    /**
     * Brings the rows nobody touched up to this release. Called by {@link DataSeeder} on every
     * start, after a fresh database has been seeded (every row is then in step and is recorded
     * as such) or on an existing one.
     */
    @Transactional
    public Result sync() {
        if (!enabled) {
            log.debug("initializr.catalog.sync is off — the catalog's rows are left as they are");
            return new Result(false, 0, 0, 0, 0, 0);
        }
        List<Shipped> shipped = shipped();
        Map<String, CatalogSeedEntity> states = states();
        Map<String, List<Object>> stored = storedByKey();
        // Nothing recorded yet: this database has not been looked at by a release that syncs.
        boolean baseline = states.isEmpty();
        int added = 0, updated = 0, removed = 0, differs = 0, missing = 0;

        Set<String> shippedKeys = new HashSet<>();
        for (Shipped s : shipped) {
            shippedKeys.add(s.key());
            CatalogSeedEntity state = states.get(s.key());
            Object row = match(stored, s.key(), s.fingerprint());
            boolean same = row != null && fingerprintOf(row).equals(s.fingerprint());

            if (state == null) {
                state = new CatalogSeedEntity(s.key(), s.table(), s.label());
                if (same) {
                    state.setAppliedHash(s.fingerprint());
                } else if (row == null && !baseline) {
                    // New in this release.
                    save(s.entity());
                    state.setAppliedHash(s.fingerprint());
                    added++;
                    log.info("Catalog: added {}", s.label());
                } else if (row == null) {
                    missing++;
                } else {
                    differs++;
                }
                seedRepo.save(state);
                continue;
            }

            if (!s.label().equals(state.getLabel())) state.setLabel(s.label());
            if (row == null) {
                if (state.getAppliedHash() == null && !state.isKept()) missing++;
            } else if (same) {
                state.setAppliedHash(s.fingerprint());
            } else if (state.isKept()) {
                // The admin's own row, by their choice.
            } else if (fingerprintOf(row).equals(state.getAppliedHash())) {
                // Still what an earlier release wrote: nobody edited it.
                copy(s, row);
                save(row);
                state.setAppliedHash(s.fingerprint());
                updated++;
                log.info("Catalog: updated {}", s.label());
            } else {
                differs++;
            }
            seedRepo.save(state);
        }

        // What an earlier release shipped and this one does not: gone when nobody touched it.
        for (CatalogSeedEntity state : states.values()) {
            if (shippedKeys.contains(state.getSeedKey())) continue;
            List<Object> rows = stored.getOrDefault(state.getSeedKey(), List.of());
            for (Object row : rows) {
                if (!state.isKept() && fingerprintOf(row).equals(state.getAppliedHash())) {
                    delete(row);
                    removed++;
                    log.info("Catalog: removed {}, no longer shipped", state.getLabel());
                }
            }
            seedRepo.delete(state);
        }

        Result result = new Result(baseline, added, updated, removed, differs, missing);
        if (result.changedAnything()) {
            log.info("Catalog brought up to this release: {} rows added, {} updated, {} removed", added, updated, removed);
        }
        if (differs + missing > 0) {
            log.warn("Catalog: {} rows differ from the ones this release ships and {} shipped rows are missing; "
                    + "they were left as they are. GET /admin/catalog-sync lists them, "
                    + "POST /admin/catalog-sync/apply takes the shipped version.", differs, missing);
        }
        return result;
    }

    // ── For the admin ────────────────────────────────────────────────────────

    /** Every shipped row and where it stands, in the manifests' order. */
    @Transactional(readOnly = true)
    public Report report() {
        Map<String, CatalogSeedEntity> states = states();
        Map<String, List<Object>> stored = storedByKey();
        Map<Status, Integer> summary = new EnumMap<>(Status.class);
        for (Status status : Status.values()) summary.put(status, 0);
        List<Row> rows = new ArrayList<>();
        for (Shipped s : shipped()) {
            Row row = rowOf(s, states.get(s.key()), match(stored, s.key(), s.fingerprint()));
            summary.merge(row.status(), 1, Integer::sum);
            rows.add(row);
        }
        return new Report(summary, rows);
    }

    /** One shipped row beside the database's, or null when the release ships no such row. */
    @Transactional(readOnly = true)
    public Detail detail(String key) {
        for (Shipped s : shipped()) {
            if (!s.key().equals(key)) continue;
            Object current = match(storedByKey(), s.key(), s.fingerprint());
            return new Detail(rowOf(s, seedRepo.findById(key).orElse(null), current), s.entity(), current);
        }
        return null;
    }

    private static Row rowOf(Shipped s, CatalogSeedEntity state, Object row) {
        Status status;
        if (row != null && fingerprintOf(row).equals(s.fingerprint())) {
            status = Status.IN_STEP;
        } else if (state != null && state.isKept()) {
            status = Status.KEPT;
        } else if (row != null) {
            status = Status.DIFFERS;
        } else if (state != null && state.getAppliedHash() != null) {
            status = Status.DELETED;
        } else {
            status = Status.MISSING;
        }
        return new Row(s.key(), s.table(), s.kind(), s.dependencyId(), s.label(), status, row == null ? null : idOf(row));
    }

    /**
     * Takes the shipped version of the rows named — or, for {@code null}, of every row that
     * {@link Status#DIFFERS} or is {@link Status#MISSING} (never one an admin kept or deleted:
     * those are named to be taken). A row is rewritten in place, a missing one added.
     *
     * @return how many rows were written; a key the release does not ship is ignored
     */
    @Transactional
    public int apply(Collection<String> keys) {
        Set<String> wanted = keys == null ? null : new HashSet<>(keys);
        Map<String, List<Object>> stored = storedByKey();
        Map<String, CatalogSeedEntity> states = states();
        int written = 0;
        for (Shipped s : shipped()) {
            CatalogSeedEntity state = states.get(s.key());
            Object row = match(stored, s.key(), s.fingerprint());
            if (wanted == null) {
                Status status = rowOf(s, state, row).status();
                if (status != Status.DIFFERS && status != Status.MISSING) continue;
            } else if (!wanted.contains(s.key())) {
                continue;
            }
            if (row == null) {
                save(s.entity());
                written++;
            } else if (!fingerprintOf(row).equals(s.fingerprint())) {
                copy(s, row);
                save(row);
                written++;
            }
            if (state == null) state = new CatalogSeedEntity(s.key(), s.table(), s.label());
            state.setAppliedHash(s.fingerprint());
            state.setKept(false);
            seedRepo.save(state);
            log.info("Catalog: an admin took the shipped {}", s.label());
        }
        return written;
    }

    /**
     * Marks the rows named as the admin's own ({@code kept}), so they stop being reported and no
     * release replaces them — or, with {@code kept = false}, undoes that.
     *
     * @return how many shipped rows the keys named
     */
    @Transactional
    public int keep(Collection<String> keys, boolean kept) {
        Set<String> wanted = new HashSet<>(keys);
        Map<String, CatalogSeedEntity> states = states();
        int marked = 0;
        for (Shipped s : shipped()) {
            if (!wanted.contains(s.key())) continue;
            CatalogSeedEntity state = states.get(s.key());
            if (state == null) state = new CatalogSeedEntity(s.key(), s.table(), s.label());
            state.setKept(kept);
            seedRepo.save(state);
            marked++;
        }
        return marked;
    }

    // ── Reading the manifests ────────────────────────────────────────────────

    private static <T> List<T> readManifest(String path, Class<T> elementType) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(readClasspath(path),
                mapper.getTypeFactory().constructCollectionType(List.class, elementType));
    }

    private static String readClasspath(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) throw new IOException("Classpath resource not found: " + path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
