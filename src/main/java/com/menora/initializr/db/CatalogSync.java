package com.menora.initializr.db;

import com.menora.initializr.db.CatalogSyncTables.Catalog;
import com.menora.initializr.db.CatalogSyncTables.Table;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.CatalogSeedEntity;
import com.menora.initializr.db.entity.CatalogSeedEntity.RowTable;
import com.menora.initializr.db.entity.DependencyCompatibilityEntity;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencyGroupEntity;
import com.menora.initializr.db.entity.DependencySubOptionEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.CatalogSeedRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the dependency catalog in step with the one this release ships under {@code catalog/} —
 * the counterpart, for the catalog, of {@link EntityTemplateSetSync}. Six tables
 * ({@link CatalogSyncTables}): dependency groups and entries, sub-options, compatibility rules,
 * file contributions and build customizations.
 *
 * <p>Those rows are edited in the admin UI, so the database is what a generation reads — and
 * they were seeded once, so a release that fixed a Dockerfile, added a dependency or reworded a
 * rule reached new databases only (or needed a hand-written data migration). What is remembered
 * about each shipped row ({@link CatalogSeedEntity}) is the fingerprint of the shipped version
 * last written to this database. On start, for each row the release ships:
 * <ul>
 *   <li>the database's row still has that fingerprint — nobody touched it: it is brought up to
 *       the release;</li>
 *   <li>the row was edited: it is kept, and reported;</li>
 *   <li>the row was deleted: it stays deleted;</li>
 *   <li>the release has not shipped this row to this database before: it is added — unless what
 *       it needs is not there (below);</li>
 *   <li>a row an older release shipped and this one does not is removed when nobody touched it
 *       and nothing still needs it.</li>
 * </ul>
 *
 * <p><b>The first start of an existing database (the baseline) — per table.</b> Nothing is known
 * about a table's rows the first time a release that syncs it looks: one that differs from the
 * shipped one may be an admin's edit or simply an older release's content, and one that is
 * missing may have been deleted or may never have been there. So that start changes nothing in
 * that table — it records every shipped row, the ones that match as in step and the rest as never
 * applied — and what differs or is missing is {@link #report() reported} for an admin to
 * {@link #apply apply} or {@link #keep keep}. From then on the list above holds. (Files and build
 * rows have been synced since Round 12; the other four tables since Round 14, so a database from
 * between the two takes its baseline for those four alone.)
 *
 * <p><b>What a row is.</b> A row is recognised by its identity — an entry by its id; an option by
 * kind, dependency and option id; a rule by kind, the two dependencies and the relation; a file
 * by kind, dependency, target path, sub-option and runtime version; a build row by kind,
 * dependency, type, coordinates, repository, sub-option and Boot range — so an admin who changes
 * one of those has made another row, and the shipped one counts as deleted. A <em>group</em> has
 * only its name, which an admin may well change: the record remembers which row stands for the
 * shipped group ({@code row_id}), so a renamed group is still that group — it differs and is
 * kept, and the entries a release ships for it land in it. Rows an admin created match nothing
 * shipped and are never touched.
 *
 * <p><b>What a row needs.</b> An entry needs its group; an option, a rule, a file and a build row
 * need their dependency ({@code __common__} always exists). A shipped row whose parent is not in
 * the catalog — an admin deleted the dependency — is neither added nor updated:
 * {@link Status#PARENT_GONE}. Tables are therefore written parents first (groups, entries,
 * options, rules, files, build rows) and rows no longer shipped are removed the other way; an
 * entry only when nothing names it any more, a group only when it is empty.
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
        DELETED,
        /** What the row needs — its dependency, or an entry's group — is not in the catalog. */
        PARENT_GONE
    }

    /** What a start did. {@code baselined}: the tables it looked at for the first time. */
    public record Result(Set<RowTable> baselined, int added, int updated, int removed, int differs, int missing,
                         int parentGone) {

        /** Whether any table was looked at for the first time (and so left as it was). */
        public boolean baseline() {
            return !baselined.isEmpty();
        }

        public boolean changedAnything() {
            return added + updated + removed > 0;
        }
    }

    /**
     * One shipped row and where it stands; {@code rowId} is the database row it was matched to,
     * {@code reason} what is missing for a row whose parent is gone.
     */
    public record Row(String key, RowTable table, ProjectKind kind, String dependencyId, String label,
                      Status status, Long rowId, String reason) {
    }

    public record Report(Map<Status, Integer> summary, List<Row> rows) {
    }

    /** A shipped row beside the database's, as the admin API serializes them. */
    public record Detail(Row row, Object shipped, Object current) {
    }

    /** What {@link #applyRows} did: the rows written, and the ones it could not write, with why. */
    public record Applied(int written, List<Row> skipped) {
    }

    /** One row this release ships. */
    private record Shipped<E>(Table<E> table, E entity, String key, String label) {
    }

    private final CatalogSyncTables tables;
    private final CatalogSeedRepository seedRepo;
    private final boolean enabled;

    public CatalogSync(CatalogSyncTables tables,
                       CatalogSeedRepository seedRepo,
                       @Value("${initializr.catalog.sync:auto}") String mode) {
        this.tables = tables;
        this.seedRepo = seedRepo;
        this.enabled = !"off".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public boolean enabled() {
        return enabled;
    }

    // ── What the release ships ───────────────────────────────────────────────

    /**
     * The dependency groups of one kind's manifest, unsaved, each with its entries: saving a group
     * saves them. How {@link DataSeeder} seeds a fresh database — from the same reading the sync
     * compares with.
     */
    public List<DependencyGroupEntity> shippedDependencyGroups(ProjectKind kind) throws IOException {
        return tables.shippedCatalog(kind);
    }

    public List<DependencySubOptionEntity> shippedSubOptions(ProjectKind kind) throws IOException {
        return tables.subOptions.shipped(kind);
    }

    public List<DependencyCompatibilityEntity> shippedCompatibilityRules(ProjectKind kind) throws IOException {
        return tables.rules.shipped(kind);
    }

    /** The file contributions of one kind's manifest, as unsaved entities, in the manifest's order. */
    public List<FileContributionEntity> shippedFileContributions(ProjectKind kind) throws IOException {
        return tables.files.shipped(kind);
    }

    /** The build customizations of one kind's manifest, as unsaved entities, in the manifest's order. */
    public List<BuildCustomizationEntity> shippedBuildCustomizations(ProjectKind kind) throws IOException {
        return tables.builds.shipped(kind);
    }

    static String keyOf(FileContributionEntity f) {
        return CatalogSyncTables.keyOf(f);
    }

    static String keyOf(BuildCustomizationEntity b) {
        return CatalogSyncTables.keyOf(b);
    }

    static String fingerprintOf(FileContributionEntity f) {
        return CatalogSyncTables.fingerprintOf(f);
    }

    static String fingerprintOf(BuildCustomizationEntity b) {
        return CatalogSyncTables.fingerprintOf(b);
    }

    /** The key of the shipped dependency with this id, as the report and the admin API name it. */
    static String entryKey(String dependencyId) {
        return CatalogSyncTables.hash(String.join("\u0001", "entry", CatalogSyncTables.text(dependencyId)));
    }

    /** The key of the shipped group of this kind and name. */
    static String groupKey(ProjectKind kind, String name) {
        return CatalogSyncTables.groupKey(kind, name);
    }

    /**
     * What the sync calls a stored row: its key and its fingerprint as it stands — for the tests,
     * which stage "an older release wrote this" by recording a row's present fingerprint as applied.
     * Null when the table has no row with that id.
     */
    @Transactional(readOnly = true)
    public String[] identityOf(RowTable table, Long rowId) {
        Pass pass = new Pass();
        for (Table<?> candidate : tables.all()) {
            if (candidate.id == table) return identityOf(candidate, rowId, pass);
        }
        return null;
    }

    private static <E> String[] identityOf(Table<E> table, Long rowId, Pass pass) {
        for (E row : table.stored()) {
            if (rowId.equals(table.rowId(row))) return new String[] {table.key(row, pass), table.fingerprint(row, pass)};
        }
        return null;
    }

    // ── One look at the catalog ──────────────────────────────────────────────

    /**
     * The catalog as one run of the sync sees it: what is recorded, which stored group stands for
     * which shipped one, which dependencies exist — kept current as the run writes.
     */
    private final class Pass implements Catalog {

        final Map<String, CatalogSeedEntity> states = new LinkedHashMap<>();
        /** The tables no record knows yet: looked at for the first time. */
        final Set<RowTable> baselined = EnumSet.allOf(RowTable.class);
        private final Set<String> dependencyIds = new HashSet<>();
        private final Map<Long, String> groupKeyById = new HashMap<>();
        private final Map<String, DependencyGroupEntity> groupByKey = new HashMap<>();
        private final Map<RowTable, Map<String, List<Object>>> stored = new EnumMap<>(RowTable.class);

        Pass() {
            for (CatalogSeedEntity state : seedRepo.findAll()) {
                states.put(state.getSeedKey(), state);
                baselined.remove(state.getRowTable());
            }
            dependencyIds.addAll(tables.dependencyIds());
            indexGroups();
        }

        /**
         * Which stored group is which: one a record points at ({@code row_id}) stands for that
         * shipped group whatever it is called now; any other is known by its name. Done again
         * whenever groups or their records change.
         */
        void indexGroups() {
            groupKeyById.clear();
            groupByKey.clear();
            stored.remove(RowTable.DEPENDENCY_GROUP);
            stored.remove(RowTable.DEPENDENCY_ENTRY);
            Map<Long, String> claimed = new HashMap<>();
            for (CatalogSeedEntity state : states.values()) {
                if (state.getRowTable() == RowTable.DEPENDENCY_GROUP && state.getRowId() != null) {
                    claimed.putIfAbsent(state.getRowId(), state.getSeedKey());
                }
            }
            List<DependencyGroupEntity> groups = new ArrayList<>(tables.storedGroups());
            groups.sort(Comparator.comparing(DependencyGroupEntity::getId));
            for (DependencyGroupEntity group : groups) {
                String key = claimed.get(group.getId());
                if (key == null) continue;
                groupKeyById.put(group.getId(), key);
                groupByKey.put(key, group);
            }
            for (DependencyGroupEntity group : groups) {
                if (groupKeyById.containsKey(group.getId())) continue;
                String key = CatalogSyncTables.groupKey(group.getProjectKind(), group.getName());
                groupKeyById.put(group.getId(), key);
                groupByKey.putIfAbsent(key, group);
            }
        }

        @Override
        public String groupKeyOf(DependencyGroupEntity group) {
            String key = groupKeyById.get(group.getId());
            return key != null ? key : CatalogSyncTables.groupKey(group.getProjectKind(), group.getName());
        }

        @Override
        public DependencyGroupEntity groupFor(String groupKey) {
            return groupByKey.get(groupKey);
        }

        @Override
        public boolean hasDependency(String dependencyId) {
            return CatalogSyncTables.COMMON.equals(dependencyId) || dependencyIds.contains(dependencyId);
        }

        /** A table's stored rows by key. Several rows may share one (an admin's copy). */
        @SuppressWarnings("unchecked")
        <E> List<E> candidates(Table<E> table, String key) {
            Map<String, List<Object>> byKey = stored.computeIfAbsent(table.id, id -> {
                Map<String, List<Object>> index = new LinkedHashMap<>();
                for (E row : table.stored()) index.computeIfAbsent(table.key(row, this), k -> new ArrayList<>()).add(row);
                return index;
            });
            return (List<E>) byKey.getOrDefault(key, List.of());
        }

        /**
         * The stored row a shipped one stands for: the one its record points at; else the one
         * equal to it when there are several; else the first.
         */
        <E> E match(Shipped<E> s) {
            List<E> candidates = candidates(s.table(), s.key());
            if (candidates.isEmpty()) return null;
            CatalogSeedEntity state = states.get(s.key());
            if (state != null && state.getRowId() != null) {
                for (E row : candidates) {
                    if (state.getRowId().equals(s.table().rowId(row))) return row;
                }
            }
            String fingerprint = s.table().fingerprint(s.entity(), this);
            for (E row : candidates) {
                if (s.table().fingerprint(row, this).equals(fingerprint)) return row;
            }
            return candidates.get(0);
        }

        /**
         * After a row was added, updated or removed: what the rest of the run must know of it. An
         * update changes nothing here — a row keeps its key — except for a group, whose entries
         * are found through it.
         */
        <E> void wrote(Table<E> table, CatalogSeedEntity state, E row, Change change) {
            Map<String, List<Object>> index = stored.get(table.id);
            if (index != null && change == Change.ADDED) {
                index.computeIfAbsent(table.key(row, this), k -> new ArrayList<>()).add(row);
            } else if (index != null && change == Change.REMOVED) {
                index.values().forEach(rows -> rows.remove(row));
            }
            if (row instanceof DependencyEntryEntity entry) {
                if (change == Change.REMOVED) dependencyIds.remove(entry.getDepId());
                else dependencyIds.add(entry.getDepId());
            }
            if (row instanceof DependencyGroupEntity group) {
                if (state != null && change != Change.REMOVED) state.setRowId(group.getId());
                indexGroups();
            }
        }
    }

    private enum Change { ADDED, UPDATED, REMOVED }

    private List<Shipped<?>> shipped(Pass pass) {
        try {
            List<Shipped<?>> out = new ArrayList<>();
            for (Table<?> table : tables.all()) addShipped(table, pass, out);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("The shipped catalog could not be read", e);
        }
    }

    private static <E> void addShipped(Table<E> table, Pass pass, List<Shipped<?>> out) throws IOException {
        for (ProjectKind kind : List.of(ProjectKind.BACKEND, ProjectKind.FRONTEND)) {
            for (E row : table.shipped(kind)) {
                out.add(new Shipped<>(table, row, table.key(row, pass), table.label(row)));
            }
        }
    }

    // ── The start-up pass ────────────────────────────────────────────────────

    /** The counts of one start. */
    private static final class Tally {
        int added, updated, removed, differs, missing, parentGone;
    }

    /**
     * Brings the rows nobody touched up to this release. Called by {@link DataSeeder} on every
     * start, after a fresh database has been seeded (every row is then in step and is recorded
     * as such) or on an existing one.
     */
    @Transactional
    public Result sync() {
        if (!enabled) {
            log.debug("initializr.catalog.sync is off — the catalog's rows are left as they are");
            return new Result(Set.of(), 0, 0, 0, 0, 0, 0);
        }
        Pass pass = new Pass();
        Set<RowTable> baselined = pass.baselined.isEmpty() ? EnumSet.noneOf(RowTable.class) : EnumSet.copyOf(pass.baselined);
        Tally tally = new Tally();

        Set<String> shippedKeys = new HashSet<>();
        for (Shipped<?> s : shipped(pass)) {
            shippedKeys.add(s.key());
            syncRow(s, pass, baselined.contains(s.table().id), tally);
        }

        // What an earlier release shipped and this one does not: gone when nobody touched it and
        // nothing needs it — children before what they belong to.
        List<Table<?>> reversed = new ArrayList<>(tables.all());
        java.util.Collections.reverse(reversed);
        for (Table<?> table : reversed) {
            for (CatalogSeedEntity state : new ArrayList<>(pass.states.values())) {
                if (state.getRowTable() != table.id || shippedKeys.contains(state.getSeedKey())) continue;
                removeUnshipped(table, state, pass, tally);
            }
        }

        Result result = new Result(baselined, tally.added, tally.updated, tally.removed, tally.differs, tally.missing,
                tally.parentGone);
        if (result.changedAnything()) {
            log.info("Catalog brought up to this release: {} rows added, {} updated, {} removed",
                    tally.added, tally.updated, tally.removed);
        }
        if (!baselined.isEmpty()) {
            log.info("Catalog: first look at {} — recorded, none of their rows changed", baselined);
        }
        if (tally.differs + tally.missing + tally.parentGone > 0) {
            log.warn("Catalog: {} rows differ from the ones this release ships, {} shipped rows are missing and {} "
                            + "belong to a dependency or group that is not in the catalog; they were left as they are. "
                            + "GET /admin/catalog-sync lists them, POST /admin/catalog-sync/apply takes the shipped version.",
                    tally.differs, tally.missing, tally.parentGone);
        }
        return result;
    }

    private <E> void syncRow(Shipped<E> s, Pass pass, boolean baseline, Tally tally) {
        Table<E> table = s.table();
        CatalogSeedEntity state = pass.states.get(s.key());
        E row = pass.match(s);
        String fingerprint = table.fingerprint(s.entity(), pass);
        boolean same = row != null && table.fingerprint(row, pass).equals(fingerprint);
        boolean parentGone = !same && table.missingParent(s.entity(), pass) != null;

        if (state == null) {
            state = new CatalogSeedEntity(s.key(), table.id, s.label());
            pass.states.put(s.key(), state);
            if (same) {
                state.setAppliedHash(fingerprint);
            } else if (parentGone) {
                tally.parentGone++;
            } else if (row == null && !baseline) {
                // New in this release.
                row = table.add(s.entity(), pass);
                state.setAppliedHash(fingerprint);
                tally.added++;
                log.info("Catalog: added {}", s.label());
                pass.wrote(table, state, row, Change.ADDED);
            } else if (row == null) {
                tally.missing++;
            } else {
                tally.differs++;
            }
        } else {
            if (!s.label().equals(state.getLabel())) state.setLabel(s.label());
            if (row == null) {
                if (state.getAppliedHash() == null && !state.isKept()) {
                    if (parentGone) tally.parentGone++;
                    else tally.missing++;
                }
            } else if (same) {
                state.setAppliedHash(fingerprint);
            } else if (state.isKept()) {
                // The admin's own row, by their choice.
            } else if (table.fingerprint(row, pass).equals(state.getAppliedHash())) {
                // Still what an earlier release wrote: nobody edited it.
                if (parentGone) {
                    tally.parentGone++;
                } else {
                    table.update(s.entity(), row, pass);
                    state.setAppliedHash(fingerprint);
                    tally.updated++;
                    log.info("Catalog: updated {}", s.label());
                    pass.wrote(table, state, row, Change.UPDATED);
                }
            } else {
                tally.differs++;
            }
        }
        remember(table, state, row, pass);
        seedRepo.save(state);
    }

    /** A group's record points at the row that stands for it, so a rename does not lose it. */
    private <E> void remember(Table<E> table, CatalogSeedEntity state, E row, Pass pass) {
        if (table.id != RowTable.DEPENDENCY_GROUP || row == null) return;
        Long id = table.rowId(row);
        if (id != null && !id.equals(state.getRowId())) {
            state.setRowId(id);
            pass.indexGroups();
        }
    }

    private <E> void removeUnshipped(Table<E> table, CatalogSeedEntity state, Pass pass, Tally tally) {
        for (E row : new ArrayList<>(pass.candidates(table, state.getSeedKey()))) {
            boolean untouched = !state.isKept() && table.fingerprint(row, pass).equals(state.getAppliedHash());
            if (!untouched) continue;
            if (!table.removable(row)) {
                log.info("Catalog: {} is no longer shipped and stays: something in the catalog still uses it", state.getLabel());
                continue;
            }
            table.delete(row);
            tally.removed++;
            log.info("Catalog: removed {}, no longer shipped", state.getLabel());
            pass.wrote(table, null, row, Change.REMOVED);
        }
        pass.states.remove(state.getSeedKey());
        seedRepo.delete(state);
        if (table.id == RowTable.DEPENDENCY_GROUP) pass.indexGroups();
    }

    // ── For the admin ────────────────────────────────────────────────────────

    /** Every shipped row and where it stands: table by table, each in its manifests' order. */
    @Transactional(readOnly = true)
    public Report report() {
        Pass pass = new Pass();
        Map<Status, Integer> summary = new EnumMap<>(Status.class);
        for (Status status : Status.values()) summary.put(status, 0);
        List<Row> rows = new ArrayList<>();
        for (Shipped<?> s : shipped(pass)) {
            Row row = rowOf(s, pass);
            summary.merge(row.status(), 1, Integer::sum);
            rows.add(row);
        }
        return new Report(summary, rows);
    }

    /** One shipped row beside the database's, or null when the release ships no such row. */
    @Transactional(readOnly = true)
    public Detail detail(String key) {
        Pass pass = new Pass();
        for (Shipped<?> s : shipped(pass)) {
            if (s.key().equals(key)) return detailOf(s, pass);
        }
        return null;
    }

    private <E> Detail detailOf(Shipped<E> s, Pass pass) {
        E current = pass.match(s);
        return new Detail(rowOf(s, pass), s.table().view(s.entity(), pass),
                current == null ? null : s.table().view(current, pass));
    }

    private <E> Row rowOf(Shipped<E> s, Pass pass) {
        Table<E> table = s.table();
        CatalogSeedEntity state = pass.states.get(s.key());
        E row = pass.match(s);
        String reason = null;
        Status status;
        if (row != null && table.fingerprint(row, pass).equals(table.fingerprint(s.entity(), pass))) {
            status = Status.IN_STEP;
        } else if (state != null && state.isKept()) {
            status = Status.KEPT;
        } else if ((reason = table.missingParent(s.entity(), pass)) != null) {
            status = Status.PARENT_GONE;
        } else if (row != null) {
            status = Status.DIFFERS;
        } else if (state != null && state.getAppliedHash() != null) {
            status = Status.DELETED;
        } else {
            status = Status.MISSING;
        }
        return new Row(s.key(), table.id, table.kind(s.entity()), table.dependencyId(s.entity()), s.label(), status,
                row == null ? null : table.rowId(row), reason);
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
        return applyRows(keys).written();
    }

    /**
     * {@link #apply} that also says what it left: a row whose dependency (or, for an entry, whose
     * group) is not in the catalog cannot be written, and comes back with why. Rows are taken
     * parents first, so a dependency and its options, rules and files can be named together.
     */
    @Transactional
    public Applied applyRows(Collection<String> keys) {
        Set<String> wanted = keys == null ? null : new HashSet<>(keys);
        Pass pass = new Pass();
        int written = 0;
        List<Row> skipped = new ArrayList<>();
        for (Shipped<?> s : shipped(pass)) {
            Row row = rowOf(s, pass);
            if (wanted == null) {
                if (row.status() != Status.DIFFERS && row.status() != Status.MISSING) continue;
            } else if (!wanted.contains(s.key())) {
                continue;
            }
            Boolean wrote = applyRow(s, pass);
            if (wrote == null) {
                skipped.add(skippedRow(s, pass));
            } else if (wrote) {
                written++;
            }
        }
        return new Applied(written, skipped);
    }

    /** A row that could not be written, with what it lacks — whatever else its status says. */
    private <E> Row skippedRow(Shipped<E> s, Pass pass) {
        Row row = rowOf(s, pass);
        return row.reason() != null ? row : new Row(row.key(), row.table(), row.kind(), row.dependencyId(), row.label(),
                row.status(), row.rowId(), s.table().missingParent(s.entity(), pass));
    }

    /** True: written. False: it was the shipped one already. Null: its parent is gone. */
    private <E> Boolean applyRow(Shipped<E> s, Pass pass) {
        Table<E> table = s.table();
        E row = pass.match(s);
        String fingerprint = table.fingerprint(s.entity(), pass);
        boolean same = row != null && table.fingerprint(row, pass).equals(fingerprint);
        if (!same && table.missingParent(s.entity(), pass) != null) return null;

        CatalogSeedEntity state = pass.states.get(s.key());
        if (state == null) {
            state = new CatalogSeedEntity(s.key(), table.id, s.label());
            pass.states.put(s.key(), state);
        }
        Change change = null;
        if (row == null) {
            row = table.add(s.entity(), pass);
            change = Change.ADDED;
        } else if (!same) {
            table.update(s.entity(), row, pass);
            change = Change.UPDATED;
        }
        boolean wrote = change != null;
        state.setAppliedHash(fingerprint);
        state.setKept(false);
        if (wrote) pass.wrote(table, state, row, change);
        remember(table, state, row, pass);
        seedRepo.save(state);
        log.info("Catalog: an admin took the shipped {}", s.label());
        return wrote;
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
        Pass pass = new Pass();
        int marked = 0;
        for (Shipped<?> s : shipped(pass)) {
            if (!wanted.contains(s.key())) continue;
            CatalogSeedEntity state = pass.states.get(s.key());
            if (state == null) state = new CatalogSeedEntity(s.key(), s.table().id, s.label());
            state.setKept(kept);
            seedRepo.save(state);
            marked++;
        }
        return marked;
    }
}
