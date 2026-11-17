package com.menora.initializr.db;

import com.menora.initializr.CatalogTest;
import com.menora.initializr.db.CatalogSync.Report;
import com.menora.initializr.db.CatalogSync.Result;
import com.menora.initializr.db.CatalogSync.Row;
import com.menora.initializr.db.CatalogSync.Status;
import com.menora.initializr.db.entity.BuildCustomizationEntity;
import com.menora.initializr.db.entity.CatalogSeedEntity;
import com.menora.initializr.db.entity.CatalogSeedEntity.RowTable;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.BuildCustomizationRepository;
import com.menora.initializr.db.repository.CatalogSeedRepository;
import com.menora.initializr.db.repository.FileContributionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules by which the catalog's file contributions and build customizations follow a release
 * ({@link CatalogSync}).
 *
 * <p>A test cannot ship a different release, so "the release moved on" is staged from the other
 * side: a row is given other content and its record is told that content is what was last
 * applied — which is exactly the state of a database seeded by an older release.
 *
 * <p>{@code @Transactional}: every change rolls back, since the in-memory database is shared with
 * the other test classes. For the same reason nothing here assumes what they left behind —
 * {@link #inStep()} first takes the shipped version of everything, inside the transaction.
 */
@CatalogTest
@Transactional
class CatalogSyncTest {

    @Autowired private CatalogSync catalogSync;
    @Autowired private FileContributionRepository fileRepo;
    @Autowired private BuildCustomizationRepository buildRepo;
    @Autowired private CatalogSeedRepository seedRepo;

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

    /** The backend Dockerfile for Java 21: a `__common__` file every backend gets. */
    private FileContributionEntity dockerfile() {
        return fileRepo.findByProjectKindOrderBySortOrderAsc(ProjectKind.BACKEND).stream()
                .filter(f -> "__common__".equals(f.getDependencyId()) && "Dockerfile".equals(f.getTargetPath())
                        && "21".equals(f.getJavaVersion()))
                .findFirst().orElse(null);
    }

    /** The generated frontend's nginx configuration — the row Round 12 changed. */
    private FileContributionEntity nginx() {
        return fileRepo.findByProjectKindOrderBySortOrderAsc(ProjectKind.FRONTEND).stream()
                .filter(f -> "__common__".equals(f.getDependencyId()) && "nginx/nginx.conf".equals(f.getTargetPath()))
                .findFirst().orElse(null);
    }

    /** The spring-cloud BOM for Boot 3.2/3.3. */
    private BuildCustomizationEntity cloudBom() {
        return buildRepo.findByProjectKindOrderBySortOrderAsc(ProjectKind.BACKEND).stream()
                .filter(b -> b.getCustomizationType() == BuildCustomizationEntity.CustomizationType.ADD_BOM
                        && "spring-cloud-dependencies".equals(b.getMavenArtifactId())
                        && "[3.2.0,3.4.0)".equals(b.getCompatibilityRange()))
                .findFirst().orElse(null);
    }

    private Status statusOf(String key) {
        return rowOf(key).status();
    }

    private Row rowOf(String key) {
        return catalogSync.report().rows().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    /** Tells the record that the row's present content is what a release last wrote. */
    private void recordAsApplied(String key, String fingerprint) {
        CatalogSeedEntity state = seedRepo.findById(key).orElseThrow();
        state.setAppliedHash(fingerprint);
        seedRepo.save(state);
    }

    // ── Rules ────────────────────────────────────────────────────────────────

    @Test
    void aCatalogInStepIsLeftAlone() {
        Result result = catalogSync.sync();
        assertThat(result.baseline()).isFalse();
        assertThat(result.changedAnything()).isFalse();
        assertThat(result.differs()).isZero();
        assertThat(result.missing()).isZero();

        Report report = catalogSync.report();
        assertThat(report.summary().get(Status.DIFFERS)).isZero();
        assertThat(report.summary().get(Status.MISSING)).isZero();
        assertThat(report.rows()).hasSizeGreaterThan(300);
        // Every shipped row has an identity of its own: two sharing one could not be told apart.
        assertThat(report.rows().stream().map(Row::key).distinct().count()).isEqualTo(report.rows().size());
        assertThat(statusOf(CatalogSync.keyOf(dockerfile()))).isEqualTo(Status.IN_STEP);
    }

    @Test
    void lineEndingsAreNotAnEdit() {
        // A Windows checkout ships CRLF; the admin's textarea hands an untouched row back with LF.
        FileContributionEntity row = dockerfile();
        String key = CatalogSync.keyOf(row);
        row.setContent(row.getContent().replace("\r\n", "\n").replace("\n", "\r\n"));
        fileRepo.save(row);
        assertThat(statusOf(key)).isEqualTo(Status.IN_STEP);
        row.setContent(row.getContent().replace("\r\n", "\n"));
        fileRepo.save(row);
        assertThat(statusOf(key)).isEqualTo(Status.IN_STEP);
    }

    @Test
    void aRowNobodyTouchedFollowsTheRelease() {
        FileContributionEntity file = nginx();
        String shippedContent = file.getContent();
        int shippedOrder = file.getSortOrder();
        file.setContent("server {\n  listen 80;\n}\n");
        file.setSortOrder(shippedOrder + 7);
        fileRepo.save(file);
        recordAsApplied(CatalogSync.keyOf(file), CatalogSync.fingerprintOf(file));

        BuildCustomizationEntity bom = cloudBom();
        String shippedVersion = bom.getVersion();
        bom.setVersion("2023.0.1");
        buildRepo.save(bom);
        recordAsApplied(CatalogSync.keyOf(bom), CatalogSync.fingerprintOf(bom));

        Result result = catalogSync.sync();

        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.added()).isZero();
        assertThat(result.differs()).isZero();
        assertThat(nginx().getContent()).isEqualTo(shippedContent);
        assertThat(nginx().getSortOrder()).isEqualTo(shippedOrder);
        assertThat(cloudBom().getVersion()).isEqualTo(shippedVersion);
        assertThat(statusOf(CatalogSync.keyOf(file))).isEqualTo(Status.IN_STEP);
        // And it keeps following: nothing left to do on the next start.
        assertThat(catalogSync.sync().changedAnything()).isFalse();
    }

    @Test
    void anEditedRowIsKeptAndReported() {
        FileContributionEntity file = nginx();
        String key = CatalogSync.keyOf(file);
        String shippedContent = file.getContent();
        file.setContent(shippedContent + "\n# ours: an extra location block\n");
        fileRepo.save(file);

        Result result = catalogSync.sync();

        assertThat(result.updated()).isZero();
        assertThat(result.differs()).isEqualTo(1);
        assertThat(nginx().getContent()).contains("# ours");
        Row row = rowOf(key);
        assertThat(row.status()).isEqualTo(Status.DIFFERS);
        assertThat(row.table()).isEqualTo(RowTable.FILE_CONTRIBUTION);
        assertThat(row.kind()).isEqualTo(ProjectKind.FRONTEND);
        assertThat(row.rowId()).isEqualTo(file.getId());
        assertThat(row.label()).isEqualTo("frontend file __common__ → nginx/nginx.conf");

        // The two sides, for a diff.
        CatalogSync.Detail detail = catalogSync.detail(key);
        assertThat(((FileContributionEntity) detail.shipped()).getContent()).isEqualTo(shippedContent);
        assertThat(((FileContributionEntity) detail.current()).getContent()).contains("# ours");
        assertThat(catalogSync.detail("no-such-key")).isNull();

        // Keep mine: no longer reported, and no start replaces it.
        assertThat(catalogSync.keep(List.of(key), true)).isEqualTo(1);
        assertThat(statusOf(key)).isEqualTo(Status.KEPT);
        assertThat(catalogSync.sync().differs()).isZero();
        assertThat(nginx().getContent()).contains("# ours");
        // "Apply all" does not take a kept row either.
        catalogSync.apply(null);
        assertThat(nginx().getContent()).contains("# ours");

        // Changed their mind: reported again, and taking the shipped version puts it back.
        catalogSync.keep(List.of(key), false);
        assertThat(statusOf(key)).isEqualTo(Status.DIFFERS);
        assertThat(catalogSync.apply(List.of(key))).isEqualTo(1);
        assertThat(nginx().getContent()).isEqualTo(shippedContent);
        assertThat(statusOf(key)).isEqualTo(Status.IN_STEP);
    }

    @Test
    void aDeletedRowStaysDeleted() {
        FileContributionEntity file = dockerfile();
        String key = CatalogSync.keyOf(file);
        fileRepo.delete(file);

        Result result = catalogSync.sync();

        assertThat(result.added()).isZero();
        assertThat(result.missing()).isZero();
        assertThat(dockerfile()).isNull();
        assertThat(statusOf(key)).isEqualTo(Status.DELETED);
        // "Apply all" is for what differs or was never there — not for what an admin removed.
        catalogSync.apply(null);
        assertThat(dockerfile()).isNull();
        // Named, it comes back.
        assertThat(catalogSync.apply(List.of(key))).isEqualTo(1);
        assertThat(dockerfile()).isNotNull();
        assertThat(statusOf(key)).isEqualTo(Status.IN_STEP);
    }

    @Test
    void aRowWhoseIdentityAnAdminChangedIsTheirsAndTheShippedOneCountsAsDeleted() {
        FileContributionEntity file = dockerfile();
        String key = CatalogSync.keyOf(file);
        file.setTargetPath("docker/Dockerfile");
        fileRepo.save(file);

        assertThat(catalogSync.sync().changedAnything()).isFalse();
        assertThat(statusOf(key)).isEqualTo(Status.DELETED);
        assertThat(fileRepo.findById(file.getId()).orElseThrow().getTargetPath()).isEqualTo("docker/Dockerfile");
    }

    @Test
    void theFirstStartOfAnExistingDatabaseChangesNothingAndReportsWhatDiffers() {
        // An existing database before this release: rows, and nothing recorded about them.
        FileContributionEntity stale = nginx();
        String staleKey = CatalogSync.keyOf(stale);
        String shippedContent = stale.getContent();
        stale.setContent("server {\n  listen 80;\n}\n");
        fileRepo.save(stale);
        FileContributionEntity absent = dockerfile();
        String absentKey = CatalogSync.keyOf(absent);
        fileRepo.delete(absent);
        BuildCustomizationEntity bom = cloudBom();
        String bomKey = CatalogSync.keyOf(bom);
        String shippedVersion = bom.getVersion();
        bom.setVersion("2023.0.1");
        buildRepo.save(bom);
        seedRepo.deleteAll();
        seedRepo.flush();

        Result result = catalogSync.sync();

        assertThat(result.baseline()).isTrue();
        assertThat(result.changedAnything()).isFalse();
        assertThat(result.differs()).isEqualTo(2);
        assertThat(result.missing()).isEqualTo(1);
        assertThat(nginx().getContent()).contains("listen 80;");
        assertThat(dockerfile()).isNull();
        assertThat(cloudBom().getVersion()).isEqualTo("2023.0.1");
        assertThat(statusOf(staleKey)).isEqualTo(Status.DIFFERS);
        assertThat(statusOf(absentKey)).isEqualTo(Status.MISSING);
        assertThat(statusOf(bomKey)).isEqualTo(Status.DIFFERS);
        // Everything else was found in step and is recorded as such.
        assertThat(seedRepo.count()).isEqualTo(catalogSync.report().rows().size());

        // A second start is no baseline, and still touches none of the three.
        Result again = catalogSync.sync();
        assertThat(again.baseline()).isFalse();
        assertThat(again.changedAnything()).isFalse();
        assertThat(again.differs()).isEqualTo(2);
        assertThat(again.missing()).isEqualTo(1);

        // The admin takes the shipped version of everything that differs or is missing.
        assertThat(catalogSync.apply(null)).isEqualTo(3);
        assertThat(nginx().getContent()).isEqualTo(shippedContent);
        assertThat(dockerfile()).isNotNull();
        assertThat(cloudBom().getVersion()).isEqualTo(shippedVersion);
        Report report = catalogSync.report();
        assertThat(report.summary().get(Status.DIFFERS)).isZero();
        assertThat(report.summary().get(Status.MISSING)).isZero();
    }

    @Test
    void aRowTheReleaseShipsForTheFirstTimeIsAdded() {
        // Known to this database: everything but the Dockerfile row, which a new release brings.
        FileContributionEntity file = dockerfile();
        String key = CatalogSync.keyOf(file);
        String content = file.getContent();
        fileRepo.delete(file);
        seedRepo.deleteById(key);
        seedRepo.flush();

        Result result = catalogSync.sync();

        assertThat(result.baseline()).isFalse();
        assertThat(result.added()).isEqualTo(1);
        assertThat(dockerfile()).isNotNull();
        assertThat(dockerfile().getContent()).isEqualTo(content);
        assertThat(statusOf(key)).isEqualTo(Status.IN_STEP);
    }

    @Test
    void aRowNoLongerShippedIsRemovedUnlessItWasEdited() throws IOException {
        FileContributionEntity untouched = legacyRow("legacy/untouched.txt");
        FileContributionEntity edited = legacyRow("legacy/edited.txt");
        edited.setContent("an admin's words");
        fileRepo.save(edited);
        // An admin's own row matches nothing shipped and nothing recorded.
        FileContributionEntity own = new FileContributionEntity();
        own.setDependencyId("__common__");
        own.setTargetPath("ours/own.txt");
        own.setFileType(FileContributionEntity.FileType.STATIC_COPY);
        own.setContent("ours");
        own = fileRepo.save(own);

        Result result = catalogSync.sync();

        assertThat(result.removed()).isEqualTo(1);
        assertThat(fileRepo.findById(untouched.getId())).isEmpty();
        assertThat(fileRepo.findById(edited.getId())).isPresent();
        assertThat(fileRepo.findById(own.getId())).isPresent();
        // The edited one is the admin's from here on: nothing is remembered about it.
        assertThat(seedRepo.findById(CatalogSync.keyOf(untouched))).isEmpty();
        assertThat(seedRepo.findById(CatalogSync.keyOf(edited))).isEmpty();
    }

    /** A row an older release shipped: stored, and recorded as applied with its present content. */
    private FileContributionEntity legacyRow(String targetPath) {
        FileContributionEntity row = new FileContributionEntity();
        row.setDependencyId("__common__");
        row.setTargetPath(targetPath);
        row.setFileType(FileContributionEntity.FileType.STATIC_COPY);
        row.setContent("shipped by an older release");
        row = fileRepo.save(row);
        CatalogSeedEntity state = new CatalogSeedEntity(CatalogSync.keyOf(row), RowTable.FILE_CONTRIBUTION, targetPath);
        state.setAppliedHash(CatalogSync.fingerprintOf(row));
        seedRepo.save(state);
        return row;
    }

    @Test
    void switchedOffItLeavesTheCatalogAlone() {
        FileContributionEntity file = nginx();
        file.setContent("server {\n  listen 80;\n}\n");
        fileRepo.save(file);
        recordAsApplied(CatalogSync.keyOf(file), CatalogSync.fingerprintOf(file));

        CatalogSync off = new CatalogSync(fileRepo, buildRepo, seedRepo, "off");
        assertThat(off.enabled()).isFalse();
        assertThat(off.sync().changedAnything()).isFalse();
        assertThat(nginx().getContent()).contains("listen 80;");
    }

    @Test
    void theManifestsAndTheSeededRowsAgree() throws IOException {
        // What a fresh database is seeded with is what the sync compares against: the same lists.
        for (ProjectKind kind : ProjectKind.values()) {
            List<String> shipped = catalogSync.shippedFileContributions(kind).stream().map(CatalogSync::keyOf).toList();
            List<String> stored = fileRepo.findByProjectKindOrderBySortOrderAsc(kind).stream().map(CatalogSync::keyOf).toList();
            assertThat(stored).as(kind + " files").containsAll(shipped);
            List<String> shippedBuilds = catalogSync.shippedBuildCustomizations(kind).stream().map(CatalogSync::keyOf).toList();
            List<String> storedBuilds = buildRepo.findByProjectKindOrderBySortOrderAsc(kind).stream().map(CatalogSync::keyOf).toList();
            assertThat(storedBuilds).as(kind + " build rows").containsAll(shippedBuilds);
            assertThat(shipped.stream().filter(Objects::nonNull).distinct().count()).isEqualTo(shipped.size());
        }
    }
}
