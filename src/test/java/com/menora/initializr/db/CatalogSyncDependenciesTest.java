package com.menora.initializr.db;

import com.menora.initializr.CatalogTest;
import com.menora.initializr.db.CatalogSync.Applied;
import com.menora.initializr.db.CatalogSync.Report;
import com.menora.initializr.db.CatalogSync.Result;
import com.menora.initializr.db.CatalogSync.Row;
import com.menora.initializr.db.CatalogSync.Status;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.CatalogSeedEntity;
import com.menora.initializr.db.entity.CatalogSeedEntity.RowTable;
import com.menora.initializr.db.entity.DependencyCompatibilityEntity;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencyGroupEntity;
import com.menora.initializr.db.entity.DependencySubOptionEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.BuildCustomizationRepository;
import com.menora.initializr.db.repository.CatalogSeedRepository;
import com.menora.initializr.db.repository.DependencyCompatibilityRepository;
import com.menora.initializr.db.repository.DependencyEntryRepository;
import com.menora.initializr.db.repository.DependencyGroupRepository;
import com.menora.initializr.db.repository.DependencySubOptionRepository;
import com.menora.initializr.db.repository.FileContributionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog sync over the four tables it gained in Round 14 — dependency groups, entries,
 * sub-options and compatibility rules — and the two things that came with them: a row is not
 * written without what it needs, and each table takes its own baseline. The rules shared with
 * file contributions and build rows are pinned by {@link CatalogSyncTest}; here each is run on
 * the new tables, with the cases only they have (an entry moved to another group, a renamed
 * group, a child of a deleted dependency).
 *
 * <p>As there, "the release moved on" is staged from the other side: a row is changed and its
 * record is told that this is what was last applied. {@code @Transactional}: everything rolls back.
 */
@CatalogTest
@Transactional
class CatalogSyncDependenciesTest {

    @Autowired private CatalogSync catalogSync;
    @Autowired private DependencyGroupRepository groupRepo;
    @Autowired private DependencyEntryRepository entryRepo;
    @Autowired private DependencySubOptionRepository subOptionRepo;
    @Autowired private DependencyCompatibilityRepository ruleRepo;
    @Autowired private FileContributionRepository fileRepo;
    @Autowired private BuildCustomizationRepository buildRepo;
    @Autowired private CatalogSeedRepository seedRepo;

    @PersistenceContext
    private EntityManager entityManager;

    private static final Set<RowTable> NEW_TABLES = EnumSet.of(RowTable.DEPENDENCY_GROUP, RowTable.DEPENDENCY_ENTRY,
            RowTable.SUB_OPTION, RowTable.COMPATIBILITY_RULE);

    @BeforeEach
    void inStep() {
        for (CatalogSeedEntity state : seedRepo.findAll()) {
            if (state.isKept()) {
                state.setKept(false);
                seedRepo.save(state);
            }
        }
        catalogSync.apply(null);
    }

    // ── The rows the tests stage ─────────────────────────────────────────────

    private DependencyEntryEntity entry(String id) {
        return entryRepo.findByDepId(id).orElse(null);
    }

    private DependencyGroupEntity group(String name) {
        return groupRepo.findAll().stream()
                .filter(g -> g.getProjectKind() == ProjectKind.BACKEND && name.equals(g.getName()))
                .findFirst().orElse(null);
    }

    private List<DependencySubOptionEntity> optionsOf(String dependencyId) {
        return subOptionRepo.findAll().stream().filter(o -> dependencyId.equals(o.getDependencyId())).toList();
    }

    private DependencyCompatibilityEntity rule(String source, String target) {
        return ruleRepo.findAll().stream()
                .filter(r -> source.equals(r.getSourceDepId()) && target.equals(r.getTargetDepId()))
                .findFirst().orElse(null);
    }

    private List<FileContributionEntity> filesOf(String dependencyId) {
        return fileRepo.findAll().stream().filter(f -> dependencyId.equals(f.getDependencyId())).toList();
    }

    private List<BuildCustomizationEntity> buildsOf(String dependencyId) {
        return buildRepo.findAll().stream().filter(b -> dependencyId.equals(b.getDependencyId())).toList();
    }

    private String keyOf(RowTable table, Long rowId) {
        return catalogSync.identityOf(table, rowId)[0];
    }

    /** Tells the record that the row's present content is what a release last wrote. */
    private void recordAsApplied(RowTable table, Long rowId) {
        String[] identity = catalogSync.identityOf(table, rowId);
        CatalogSeedEntity state = seedRepo.findById(identity[0]).orElseThrow();
        state.setAppliedHash(identity[1]);
        seedRepo.save(state);
    }

    /** A row an older release shipped and this one does not: recorded as applied, as it stands. */
    private void recordAsLegacy(RowTable table, Long rowId, String label) {
        String[] identity = catalogSync.identityOf(table, rowId);
        CatalogSeedEntity state = new CatalogSeedEntity(identity[0], table, label);
        state.setAppliedHash(identity[1]);
        if (table == RowTable.DEPENDENCY_GROUP) state.setRowId(rowId);
        seedRepo.save(state);
    }

    /** As if the release had never shipped this row to this database. */
    private void forget(String key) {
        seedRepo.deleteById(key);
        seedRepo.flush();
    }

    private Row rowOf(String key) {
        return catalogSync.report().rows().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    private Status statusOf(String key) {
        return rowOf(key).status();
    }

    // ── Every table ──────────────────────────────────────────────────────────

    @Test
    void aSeededCatalogIsInStepInEveryTable() throws Exception {
        Result result = catalogSync.sync();
        assertThat(result.baseline()).isFalse();
        assertThat(result.changedAnything()).isFalse();
        assertThat(result.differs() + result.missing() + result.parentGone()).isZero();

        Report report = catalogSync.report();
        assertThat(report.rows()).allSatisfy(row -> assertThat(row.status()).as(row.label()).isEqualTo(Status.IN_STEP));
        // What the seeder wrote is what the sync reads: one row reported per shipped row.
        long groups = 0, entries = 0, options = 0, rules = 0;
        for (ProjectKind kind : ProjectKind.values()) {
            List<DependencyGroupEntity> shipped = catalogSync.shippedDependencyGroups(kind);
            groups += shipped.size();
            entries += shipped.stream().mapToLong(g -> g.getEntries().size()).sum();
            options += catalogSync.shippedSubOptions(kind).size();
            rules += catalogSync.shippedCompatibilityRules(kind).size();
        }
        assertThat(report.rows().stream().filter(r -> r.table() == RowTable.DEPENDENCY_GROUP)).hasSize((int) groups);
        assertThat(report.rows().stream().filter(r -> r.table() == RowTable.DEPENDENCY_ENTRY)).hasSize((int) entries);
        assertThat(report.rows().stream().filter(r -> r.table() == RowTable.SUB_OPTION)).hasSize((int) options);
        assertThat(report.rows().stream().filter(r -> r.table() == RowTable.COMPATIBILITY_RULE)).hasSize((int) rules);
        // Parents first: a group before its entries, a dependency before what names it.
        List<RowTable> order = report.rows().stream().map(Row::table).distinct().toList();
        assertThat(order).containsExactly(RowTable.DEPENDENCY_GROUP, RowTable.DEPENDENCY_ENTRY, RowTable.SUB_OPTION,
                RowTable.COMPATIBILITY_RULE, RowTable.FILE_CONTRIBUTION, RowTable.BUILD_CUSTOMIZATION);
        // A group's record points at its row.
        Row kafka = rowOf(CatalogSync.entryKey("kafka"));
        assertThat(kafka.dependencyId()).isEqualTo("kafka");
        assertThat(kafka.label()).isEqualTo("backend dependency kafka");
        assertThat(seedRepo.findById(CatalogSync.groupKey(ProjectKind.BACKEND, "Messaging")).orElseThrow().getRowId())
                .isEqualTo(group("Messaging").getId());
    }

    @Test
    void rowsNobodyTouchedFollowTheReleaseInEachTable() {
        // An older release's words, in each of the four tables.
        DependencyGroupEntity data = group("Data");
        int shippedOrder = data.getSortOrder();
        data.setSortOrder(40);
        groupRepo.save(data);
        recordAsApplied(RowTable.DEPENDENCY_GROUP, data.getId());

        DependencyEntryEntity kafka = entry("kafka");
        String shippedDescription = kafka.getDescription();
        String shippedArtifact = kafka.getMavenArtifactId();
        kafka.setDescription("Kafka, as an older release described it");
        kafka.setMavenArtifactId("spring-kafka-old");
        kafka.setCompatibilityRange("[3.0.0,3.2.0)");
        entryRepo.save(kafka);
        recordAsApplied(RowTable.DEPENDENCY_ENTRY, kafka.getId());

        DependencySubOptionEntity option = optionsOf("kafka").get(0);
        String shippedLabel = option.getLabel();
        option.setLabel("An older label");
        subOptionRepo.save(option);
        recordAsApplied(RowTable.SUB_OPTION, option.getId());

        DependencyCompatibilityEntity rule = rule("tracing", "actuator");
        String shippedReason = rule.getDescription();
        rule.setDescription("An older reason");
        ruleRepo.save(rule);
        recordAsApplied(RowTable.COMPATIBILITY_RULE, rule.getId());

        Result result = catalogSync.sync();

        assertThat(result.updated()).isEqualTo(4);
        assertThat(result.differs()).isZero();
        assertThat(group("Data").getSortOrder()).isEqualTo(shippedOrder);
        assertThat(entry("kafka").getDescription()).isEqualTo(shippedDescription);
        assertThat(entry("kafka").getMavenArtifactId()).isEqualTo(shippedArtifact);
        assertThat(entry("kafka").getCompatibilityRange()).isNull();
        assertThat(subOptionRepo.findById(option.getId()).orElseThrow().getLabel()).isEqualTo(shippedLabel);
        assertThat(ruleRepo.findById(rule.getId()).orElseThrow().getDescription()).isEqualTo(shippedReason);
        assertThat(catalogSync.sync().changedAnything()).isFalse();
    }

    @Test
    void anEditedRowIsKeptAndADeletedOneStaysDeleted() {
        DependencyEntryEntity kafka = entry("kafka");
        kafka.setName("Kafka (ours)");
        entryRepo.save(kafka);
        DependencySubOptionEntity edited = optionsOf("kafka").get(0);
        edited.setDescription("our words");
        subOptionRepo.save(edited);
        DependencySubOptionEntity deleted = optionsOf("kafka").get(1);
        String deletedKey = keyOf(RowTable.SUB_OPTION, deleted.getId());
        subOptionRepo.delete(deleted);
        DependencyCompatibilityEntity rule = rule("tracing", "actuator");
        String ruleKey = keyOf(RowTable.COMPATIBILITY_RULE, rule.getId());
        ruleRepo.delete(rule);

        Result result = catalogSync.sync();

        assertThat(result.changedAnything()).isFalse();
        assertThat(result.differs()).isEqualTo(2);
        assertThat(entry("kafka").getName()).isEqualTo("Kafka (ours)");
        assertThat(statusOf(CatalogSync.entryKey("kafka"))).isEqualTo(Status.DIFFERS);
        assertThat(statusOf(keyOf(RowTable.SUB_OPTION, edited.getId()))).isEqualTo(Status.DIFFERS);
        assertThat(statusOf(deletedKey)).isEqualTo(Status.DELETED);
        assertThat(statusOf(ruleKey)).isEqualTo(Status.DELETED);
        assertThat(optionsOf("kafka")).hasSize(2);
        assertThat(rule("tracing", "actuator")).isNull();

        // Kept: no longer reported. Taken: the shipped row again, a deleted one put back by name.
        assertThat(catalogSync.keep(List.of(CatalogSync.entryKey("kafka")), true)).isEqualTo(1);
        assertThat(statusOf(CatalogSync.entryKey("kafka"))).isEqualTo(Status.KEPT);
        assertThat(catalogSync.sync().differs()).isEqualTo(1);
        // Every row that differs — never one an admin kept or deleted.
        assertThat(catalogSync.apply(null)).isEqualTo(1);
        assertThat(entry("kafka").getName()).isEqualTo("Kafka (ours)");
        assertThat(rule("tracing", "actuator")).isNull();
        assertThat(catalogSync.apply(List.of(ruleKey, deletedKey))).isEqualTo(2);
        assertThat(rule("tracing", "actuator")).isNotNull();
        assertThat(optionsOf("kafka")).hasSize(3);
    }

    // ── Groups and the entries in them ───────────────────────────────────────

    @Test
    void anEntryTheReleaseMovedFollowsAndOneAnAdminMovedStays() {
        // An older release had Kafka under Web; nobody touched it since.
        DependencyEntryEntity kafka = entry("kafka");
        kafka.setGroup(group("Web"));
        entryRepo.save(kafka);
        recordAsApplied(RowTable.DEPENDENCY_ENTRY, kafka.getId());
        // An admin moved Redis to Web.
        DependencyEntryEntity redis = entry("redis");
        redis.setGroup(group("Web"));
        entryRepo.save(redis);

        Result result = catalogSync.sync();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.differs()).isEqualTo(1);
        assertThat(entry("kafka").getGroup().getName()).isEqualTo("Messaging");
        assertThat(entry("redis").getGroup().getName()).isEqualTo("Web");
        assertThat(statusOf(CatalogSync.entryKey("redis"))).isEqualTo(Status.DIFFERS);

        // The admin sees which group the release has it in, and can take that.
        @SuppressWarnings("unchecked")
        Map<String, Object> shipped = (Map<String, Object>) catalogSync.detail(CatalogSync.entryKey("redis")).shipped();
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) catalogSync.detail(CatalogSync.entryKey("redis")).current();
        assertThat(shipped).containsEntry("group", "Data").containsEntry("depId", "redis");
        assertThat(current).containsEntry("group", "Web");
        assertThat(catalogSync.apply(List.of(CatalogSync.entryKey("redis")))).isEqualTo(1);
        assertThat(entry("redis").getGroup().getName()).isEqualTo("Data");
    }

    @Test
    void aRenamedGroupIsStillTheShippedGroup() {
        DependencyGroupEntity messaging = group("Messaging");
        Long id = messaging.getId();
        String key = CatalogSync.groupKey(ProjectKind.BACKEND, "Messaging");
        messaging.setName("Queues and streams");
        groupRepo.save(messaging);

        Result result = catalogSync.sync();

        // The group differs and is kept as it is; its entries are where the release has them.
        assertThat(result.changedAnything()).isFalse();
        assertThat(result.differs()).isEqualTo(1);
        assertThat(rowOf(key).status()).isEqualTo(Status.DIFFERS);
        assertThat(rowOf(key).rowId()).isEqualTo(id);
        assertThat(statusOf(CatalogSync.entryKey("kafka"))).isEqualTo(Status.IN_STEP);
        assertThat(groupRepo.findById(id).orElseThrow().getName()).isEqualTo("Queues and streams");
        // Shown as the same group on both sides, under the name the database has for it.
        @SuppressWarnings("unchecked")
        Map<String, Object> shipped = (Map<String, Object>) catalogSync.detail(CatalogSync.entryKey("kafka")).shipped();
        assertThat(shipped).containsEntry("group", "Queues and streams");

        // An entry a release ships for that group lands in it — no second "Messaging".
        entryRepo.delete(entry("kafka"));
        forget(CatalogSync.entryKey("kafka"));
        result = catalogSync.sync();
        assertThat(result.added()).isEqualTo(1);
        assertThat(entry("kafka").getGroup().getId()).isEqualTo(id);
        assertThat(group("Messaging")).isNull();

        // The admin's name is theirs to keep.
        catalogSync.keep(List.of(key), true);
        assertThat(statusOf(key)).isEqualTo(Status.KEPT);
        assertThat(catalogSync.sync().differs()).isZero();
    }

    @Test
    void aGroupWhoseRowIdIsNoLongerThereIsFoundByItsName() {
        // After a configuration import the rows have new ids: the record points at nothing.
        String key = CatalogSync.groupKey(ProjectKind.BACKEND, "Data");
        CatalogSeedEntity state = seedRepo.findById(key).orElseThrow();
        state.setRowId(987_654_321L);
        seedRepo.save(state);

        assertThat(statusOf(key)).isEqualTo(Status.IN_STEP);
        assertThat(statusOf(CatalogSync.entryKey("redis"))).isEqualTo(Status.IN_STEP);
        assertThat(catalogSync.sync().changedAnything()).isFalse();
        assertThat(seedRepo.findById(key).orElseThrow().getRowId()).isEqualTo(group("Data").getId());
    }

    // ── A row and what it needs ──────────────────────────────────────────────

    @Test
    void aDependencyTheReleaseShipsForTheFirstTimeArrivesWithEverythingOfIts() {
        // Tracing — an entry, a rule, a file and a build row — as a release this database has not seen.
        DependencyCompatibilityEntity rule = rule("tracing", "actuator");
        forget(keyOf(RowTable.COMPATIBILITY_RULE, rule.getId()));
        ruleRepo.delete(rule);
        for (FileContributionEntity file : filesOf("tracing")) {
            forget(CatalogSync.keyOf(file));
            fileRepo.delete(file);
        }
        for (BuildCustomizationEntity build : buildsOf("tracing")) {
            forget(CatalogSync.keyOf(build));
            buildRepo.delete(build);
        }
        entryRepo.delete(entry("tracing"));
        forget(CatalogSync.entryKey("tracing"));

        Result result = catalogSync.sync();

        assertThat(result.baseline()).isFalse();
        assertThat(result.added()).isEqualTo(4);
        assertThat(result.parentGone()).isZero();
        assertThat(entry("tracing").getGroup().getName()).isEqualTo("Observability");
        assertThat(rule("tracing", "actuator")).isNotNull();
        assertThat(filesOf("tracing")).hasSize(1);
        assertThat(buildsOf("tracing")).hasSize(1);
        assertThat(catalogSync.report().summary().get(Status.IN_STEP)).isEqualTo(catalogSync.report().rows().size());
    }

    @Test
    void whatBelongsToADeletedDependencyIsNotAdded() {
        // An admin deleted Redis, Kafka and Tracing. A later release ships a new file for Redis,
        // a new option for Kafka and a new rule for Tracing.
        FileContributionEntity file = filesOf("redis").get(0);
        String fileKey = CatalogSync.keyOf(file);
        forget(fileKey);
        fileRepo.delete(file);
        DependencySubOptionEntity option = optionsOf("kafka").get(0);
        String optionKey = keyOf(RowTable.SUB_OPTION, option.getId());
        forget(optionKey);
        subOptionRepo.delete(option);
        DependencyCompatibilityEntity rule = rule("tracing", "actuator");
        String ruleKey = keyOf(RowTable.COMPATIBILITY_RULE, rule.getId());
        forget(ruleKey);
        ruleRepo.delete(rule);
        for (String id : List.of("redis", "kafka", "tracing")) entryRepo.delete(entry(id));

        Result result = catalogSync.sync();

        assertThat(result.added()).isZero();
        assertThat(result.parentGone()).isEqualTo(3);
        assertThat(filesOf("redis")).hasSize(1);
        assertThat(optionsOf("kafka")).hasSize(2);
        assertThat(rule("tracing", "actuator")).isNull();
        // The dependency was there once: deleted. What is its and is not there: its parent is gone.
        assertThat(statusOf(CatalogSync.entryKey("redis"))).isEqualTo(Status.DELETED);
        assertThat(rowOf(fileKey).status()).isEqualTo(Status.PARENT_GONE);
        assertThat(rowOf(fileKey).reason()).isEqualTo("the dependency 'redis' is not in the catalog");
        assertThat(rowOf(optionKey).status()).isEqualTo(Status.PARENT_GONE);
        assertThat(rowOf(ruleKey).reason()).isEqualTo("the dependency 'tracing' is not in the catalog");
        // The rows it still has are what the release ships: nothing to say about them.
        assertThat(statusOf(CatalogSync.keyOf(filesOf("redis").get(0)))).isEqualTo(Status.IN_STEP);
        assertThat(catalogSync.sync().added()).isZero();

        // Taking everything that differs or is missing does not bring them either.
        assertThat(catalogSync.apply(null)).isZero();
        // Named on its own, such a row is refused with the reason…
        Applied alone = catalogSync.applyRows(List.of(fileKey));
        assertThat(alone.written()).isZero();
        assertThat(alone.skipped()).singleElement().satisfies(skipped -> {
            assertThat(skipped.key()).isEqualTo(fileKey);
            assertThat(skipped.reason()).contains("'redis' is not in the catalog");
        });
        // …and named with its dependency, both are written: the dependency first.
        Applied together = catalogSync.applyRows(List.of(fileKey, CatalogSync.entryKey("redis")));
        assertThat(together.written()).isEqualTo(2);
        assertThat(together.skipped()).isEmpty();
        assertThat(entry("redis")).isNotNull();
        assertThat(filesOf("redis")).hasSize(2);
        assertThat(statusOf(fileKey)).isEqualTo(Status.IN_STEP);
    }

    @Test
    void anEntryWhoseGroupIsGoneIsNotAddedEither() {
        // The admin deleted the Messaging group, and Kafka with it.
        DependencyGroupEntity messaging = group("Messaging");
        String groupKey = CatalogSync.groupKey(ProjectKind.BACKEND, "Messaging");
        entryRepo.delete(entry("kafka"));
        entityManager.flush();
        entityManager.refresh(messaging);
        groupRepo.delete(messaging);
        // …and a release ships Kafka as if for the first time.
        forget(CatalogSync.entryKey("kafka"));

        Result result = catalogSync.sync();

        assertThat(result.added()).isZero();
        assertThat(entry("kafka")).isNull();
        assertThat(statusOf(groupKey)).isEqualTo(Status.DELETED);
        Row kafka = rowOf(CatalogSync.entryKey("kafka"));
        assertThat(kafka.status()).isEqualTo(Status.PARENT_GONE);
        assertThat(kafka.reason()).isEqualTo("its group 'Messaging' is not in the catalog");

        // The group taken back, the entry can be too.
        assertThat(catalogSync.applyRows(List.of(CatalogSync.entryKey("kafka"), groupKey)).written()).isEqualTo(2);
        assertThat(entry("kafka").getGroup().getName()).isEqualTo("Messaging");
    }

    // ── The first look, per table ────────────────────────────────────────────

    @Test
    void aDatabaseThatSyncedFilesBeforeTakesItsBaselineForTheNewTablesAlone() {
        // The catalog sync has run here since Round 12 (files and build rows are recorded), and
        // this is the first release that looks at the other four tables.
        DependencyEntryEntity kafka = entry("kafka");
        kafka.setDescription("Kafka, as an older release described it");
        entryRepo.save(kafka);
        DependencySubOptionEntity option = optionsOf("kafka").get(0);
        String optionKey = keyOf(RowTable.SUB_OPTION, option.getId());
        subOptionRepo.delete(option);
        DependencyCompatibilityEntity rule = rule("tracing", "actuator");
        rule.setDescription("An older reason");
        ruleRepo.save(rule);
        seedRepo.deleteAll(seedRepo.findAll().stream().filter(s -> NEW_TABLES.contains(s.getRowTable())).toList());
        seedRepo.flush();
        // A file an older release wrote, untouched: that table is past its baseline and follows.
        FileContributionEntity file = filesOf("redis").get(0);
        String shippedContent = file.getContent();
        file.setContent("# an older release's file\n");
        fileRepo.save(file);
        CatalogSeedEntity fileState = seedRepo.findById(CatalogSync.keyOf(file)).orElseThrow();
        fileState.setAppliedHash(CatalogSync.fingerprintOf(file));
        seedRepo.save(fileState);

        Result result = catalogSync.sync();

        assertThat(result.baselined()).containsExactlyInAnyOrderElementsOf(NEW_TABLES);
        assertThat(result.added()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.differs()).isEqualTo(2);
        assertThat(result.missing()).isEqualTo(1);
        assertThat(fileRepo.findById(file.getId()).orElseThrow().getContent()).isEqualTo(shippedContent);
        assertThat(entry("kafka").getDescription()).isEqualTo("Kafka, as an older release described it");
        assertThat(optionsOf("kafka")).hasSize(2);
        assertThat(statusOf(CatalogSync.entryKey("kafka"))).isEqualTo(Status.DIFFERS);
        assertThat(statusOf(optionKey)).isEqualTo(Status.MISSING);
        // Every shipped row of the four tables is recorded now.
        assertThat(seedRepo.count()).isEqualTo(catalogSync.report().rows().size());

        // The next start is no baseline, and still leaves the three alone.
        Result again = catalogSync.sync();
        assertThat(again.baseline()).isFalse();
        assertThat(again.changedAnything()).isFalse();
        assertThat(again.differs()).isEqualTo(2);
        assertThat(again.missing()).isEqualTo(1);

        // The admin takes the shipped version of what differs or is missing.
        assertThat(catalogSync.apply(null)).isEqualTo(3);
        assertThat(optionsOf("kafka")).hasSize(3);
        assertThat(catalogSync.report().summary().get(Status.IN_STEP)).isEqualTo(catalogSync.report().rows().size());
    }

    // ── What a release stops shipping ────────────────────────────────────────

    @Test
    void aRowNoLongerShippedGoesWhenNobodyTouchedItAndNothingNeedsIt() {
        // A dependency an older release shipped, with an option, in a group of its own.
        DependencyGroupEntity legacyGroup = new DependencyGroupEntity();
        legacyGroup.setName("Legacy");
        legacyGroup.setSortOrder(90);
        legacyGroup = groupRepo.save(legacyGroup);
        DependencyEntryEntity legacy = legacyEntry("legacy-dep", legacyGroup);
        DependencySubOptionEntity legacyOption = new DependencySubOptionEntity();
        legacyOption.setDependencyId("legacy-dep");
        legacyOption.setOptionId("legacy-option");
        legacyOption.setLabel("Legacy option");
        legacyOption = subOptionRepo.save(legacyOption);
        recordAsLegacy(RowTable.DEPENDENCY_GROUP, legacyGroup.getId(), "backend group Legacy");
        recordAsLegacy(RowTable.DEPENDENCY_ENTRY, legacy.getId(), "backend dependency legacy-dep");
        recordAsLegacy(RowTable.SUB_OPTION, legacyOption.getId(), "backend option legacy-dep / legacy-option");

        // Another one, which an admin's own file still names — and its group, which is not empty then.
        DependencyGroupEntity usedGroup = new DependencyGroupEntity();
        usedGroup.setName("Legacy in use");
        usedGroup.setSortOrder(91);
        usedGroup = groupRepo.save(usedGroup);
        DependencyEntryEntity used = legacyEntry("legacy-used", usedGroup);
        FileContributionEntity own = new FileContributionEntity();
        own.setDependencyId("legacy-used");
        own.setTargetPath("ours/own.txt");
        own.setFileType(FileContributionEntity.FileType.STATIC_COPY);
        own.setContent("ours");
        fileRepo.save(own);
        recordAsLegacy(RowTable.DEPENDENCY_GROUP, usedGroup.getId(), "backend group Legacy in use");
        recordAsLegacy(RowTable.DEPENDENCY_ENTRY, used.getId(), "backend dependency legacy-used");
        long recorded = seedRepo.count();

        Result result = catalogSync.sync();

        // The option, then its dependency, then the group that is empty by then.
        assertThat(result.removed()).isEqualTo(3);
        assertThat(subOptionRepo.findById(legacyOption.getId())).isEmpty();
        assertThat(entry("legacy-dep")).isNull();
        assertThat(groupRepo.findById(legacyGroup.getId())).isEmpty();
        // What something still needs stays, and is the admin's from here on.
        assertThat(entry("legacy-used")).isNotNull();
        assertThat(groupRepo.findById(usedGroup.getId())).isPresent();
        assertThat(seedRepo.count()).isEqualTo(recorded - 5);
        assertThat(catalogSync.sync().changedAnything()).isFalse();
    }

    private DependencyEntryEntity legacyEntry(String id, DependencyGroupEntity group) {
        DependencyEntryEntity entry = new DependencyEntryEntity();
        entry.setDepId(id);
        entry.setName(id);
        entry.setGroup(group);
        return entryRepo.save(entry);
    }

    @Test
    void anEditedRowNoLongerShippedStays() {
        DependencyEntryEntity legacy = legacyEntry("legacy-edited", group("Web"));
        recordAsLegacy(RowTable.DEPENDENCY_ENTRY, legacy.getId(), "backend dependency legacy-edited");
        legacy.setDescription("an admin's words");
        entryRepo.save(legacy);

        assertThat(catalogSync.sync().removed()).isZero();
        assertThat(entry("legacy-edited")).isNotNull();
        assertThat(seedRepo.findById(CatalogSync.entryKey("legacy-edited"))).isEmpty();
    }
}
