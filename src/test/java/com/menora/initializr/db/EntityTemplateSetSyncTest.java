package com.menora.initializr.db;

import com.menora.initializr.CatalogTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.admin.AdminController;
import com.menora.initializr.db.entity.EntityTemplateFileEntity;
import com.menora.initializr.db.entity.EntityTemplateSetEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.FullstackExampleEntity;
import com.menora.initializr.db.repository.EntityTemplateFileRepository;
import com.menora.initializr.db.repository.EntityTemplateSetRepository;
import com.menora.initializr.db.repository.FullstackExampleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EntityTemplateSetSync}: a release's template and example changes reach an existing
 * database, and an admin's edits survive it. Every test rolls back, so the shared context keeps
 * the freshly seeded sets.
 */
@CatalogTest
@Transactional
class EntityTemplateSetSyncTest {

    private static final String SHIPPED_PATH = "src/main/java/{{packagePath}}/web/ApiExceptionHandler.java";

    @Autowired private EntityTemplateSetSync sync;
    @Autowired private EntityTemplateSetRepository setRepo;
    @Autowired private EntityTemplateFileRepository fileRepo;
    @Autowired private FullstackExampleRepository exampleRepo;
    @Autowired private DataSeeder dataSeeder;
    @Autowired private AdminController adminController;

    @Test
    void aFreshSeedStampsEveryRowWithItsContentHash() {
        assertThat(fileRepo.findAll()).isNotEmpty().allSatisfy(row ->
                assertThat(row.getSeedHash()).as(row.getPathTemplate())
                        .isEqualTo(EntityTemplateSetSync.hash(row.getContent())));
    }

    @Test
    void aSecondSyncChangesNothing() throws Exception {
        assertThat(sync.sync()).isEqualTo(new EntityTemplateSetSync.Result(0, 0, 0, 0));
    }

    @Test
    void anUntouchedRowFollowsTheRelease() throws Exception {
        EntityTemplateFileEntity row = aShippedRow();
        String shipped = row.getContent();
        row.setContent("// what an older release shipped\n");
        row.setSeedHash(EntityTemplateSetSync.hash(row.getContent()));
        row.setSortOrder(row.getSortOrder() + 7);
        fileRepo.save(row);

        EntityTemplateSetSync.Result result = sync.sync();

        assertThat(result).isEqualTo(new EntityTemplateSetSync.Result(0, 1, 0, 0));
        EntityTemplateFileEntity after = fileRepo.findById(row.getId()).orElseThrow();
        assertThat(after.getContent()).isEqualTo(shipped);
        assertThat(after.getSortOrder()).isEqualTo(row.getSortOrder());
        assertThat(after.getSeedHash()).isEqualTo(EntityTemplateSetSync.hash(shipped));
    }

    @Test
    void aRowFromBeforeTheHashIsReplacedOnceAndStamped() throws Exception {
        EntityTemplateFileEntity row = aShippedRow();
        String shipped = row.getContent();
        row.setContent("// edited or old — nothing can tell which\n");
        row.setSeedHash(null);
        fileRepo.saveAndFlush(row);

        assertThat(sync.sync().updated()).isEqualTo(1);

        EntityTemplateFileEntity after = fileRepo.findById(row.getId()).orElseThrow();
        assertThat(after.getContent()).isEqualTo(shipped);
        assertThat(after.getSeedHash()).isEqualTo(EntityTemplateSetSync.hash(shipped));
    }

    @Test
    void anAdminEditedRowIsKept() throws Exception {
        EntityTemplateFileEntity row = aShippedRow();
        row.setContent("// the team's own handler\n");
        fileRepo.save(row);

        assertThat(sync.sync()).isEqualTo(new EntityTemplateSetSync.Result(0, 0, 0, 1));
        assertThat(fileRepo.findById(row.getId()).orElseThrow().getContent())
                .isEqualTo("// the team's own handler\n");
    }

    @Test
    void lineEndingsAloneAreNotAnEdit() throws Exception {
        EntityTemplateFileEntity row = aShippedRow();
        // A browser's textarea hands an untouched CRLF template back with LF, and the reverse.
        row.setContent(row.getContent().contains("\r\n")
                ? row.getContent().replace("\r\n", "\n")
                : row.getContent().replace("\n", "\r\n"));
        fileRepo.save(row);

        assertThat(sync.sync().kept()).isZero();
    }

    @Test
    void theAdminApiKeepsTheHashSoAnEditIsRecognized() throws Exception {
        EntityTemplateFileEntity row = aShippedRow();
        // What the admin UI PUTs: the row's JSON, which never carries the hash.
        EntityTemplateFileEntity body = new EntityTemplateFileEntity();
        body.setSetId(row.getSetId());
        body.setPathTemplate(row.getPathTemplate());
        body.setContent("// edited in the admin UI\n");
        body.setSubstitutionType(row.getSubstitutionType());
        body.setFileType(row.getFileType());
        body.setPerEntity(row.isPerEntity());
        body.setPerPage(row.isPerPage());
        body.setSortOrder(row.getSortOrder());
        body.setGatedBy(row.getGatedBy());
        String stamped = row.getSeedHash();

        adminController.updateEntityTemplateFile(row.getId(), body);

        assertThat(fileRepo.findById(row.getId()).orElseThrow().getSeedHash()).isEqualTo(stamped);
        assertThat(sync.sync().kept()).isEqualTo(1);
        assertThat(fileRepo.findById(row.getId()).orElseThrow().getContent())
                .isEqualTo("// edited in the admin UI\n");
    }

    @Test
    void aMissingShippedFileIsAddedBack() throws Exception {
        EntityTemplateFileEntity row = aShippedRow();
        fileRepo.delete(row);
        fileRepo.flush();

        assertThat(sync.sync()).isEqualTo(new EntityTemplateSetSync.Result(1, 0, 0, 0));
        assertThat(rowsAt(row.getSetId(), SHIPPED_PATH)).hasSize(1);
    }

    @Test
    void aFileAnAdminAddedStaysAndOneNoLongerShippedGoes() throws Exception {
        Long setId = backendSet().getId();
        EntityTemplateFileEntity added = fileRepo.saveAndFlush(extraRow(setId, "docs/TEAM.md", null));
        assertThat(added.getSeedHash()).isEqualTo(EntityTemplateFileEntity.CUSTOM_SEED_HASH);
        EntityTemplateFileEntity dropped = extraRow(setId, "docs/DROPPED.md", "# shipped once\n");
        dropped.setSeedHash(EntityTemplateSetSync.hash(dropped.getContent()));
        fileRepo.saveAndFlush(dropped);
        EntityTemplateFileEntity droppedButEdited = extraRow(setId, "docs/KEPT.md", "# edited\n");
        droppedButEdited.setSeedHash(EntityTemplateSetSync.hash("# shipped once\n"));
        fileRepo.saveAndFlush(droppedButEdited);

        assertThat(sync.sync()).isEqualTo(new EntityTemplateSetSync.Result(0, 0, 1, 0));

        assertThat(rowsAt(setId, "docs/TEAM.md")).hasSize(1);
        assertThat(rowsAt(setId, "docs/DROPPED.md")).isEmpty();
        assertThat(rowsAt(setId, "docs/KEPT.md")).hasSize(1);
    }

    @Test
    void resetPutsTheShippedFileBackOverAnEdit() throws Exception {
        EntityTemplateFileEntity row = aShippedRow();
        String shipped = row.getContent();
        row.setContent("// the team's own handler\n");
        fileRepo.save(row);

        assertThat(sync.reset().updated()).isEqualTo(1);
        assertThat(fileRepo.findById(row.getId()).orElseThrow().getContent()).isEqualTo(shipped);
    }

    @Test
    void anUntouchedExampleFollowsTheReleaseAndAnEditedOneIsKept() {
        FullstackExampleEntity legacy = exampleRepo.findByExampleId("blog").orElseThrow();
        String shippedName = legacy.getName();
        legacy.setName("Blog, as an older release named it");
        legacy.setSeedHash(null);
        exampleRepo.save(legacy);
        FullstackExampleEntity edited = exampleRepo.findByExampleId("orders").orElseThrow();
        edited.setName("Our orders");
        exampleRepo.saveAndFlush(edited);

        dataSeeder.afterSingletonsInstantiated();

        FullstackExampleEntity blog = exampleRepo.findByExampleId("blog").orElseThrow();
        assertThat(blog.getName()).isEqualTo(shippedName);
        assertThat(blog.getSeedHash()).hasSize(64);
        assertThat(exampleRepo.findByExampleId("orders").orElseThrow().getName()).isEqualTo("Our orders");
    }

    @Test
    void aDeletedExampleIsNotAddedBack() {
        exampleRepo.delete(exampleRepo.findByExampleId("blog").orElseThrow());
        exampleRepo.flush();

        dataSeeder.afterSingletonsInstantiated();

        assertThat(exampleRepo.findByExampleId("blog")).isEmpty();
    }

    /**
     * A variant set lists every path of the set it borrows from: {@code sourceSet} copies content,
     * not rows, so a file added to a base manifest alone would be missing from the variant's output.
     */
    @Test
    void variantSetsListEveryPathOfTheirBaseSet() throws Exception {
        assertThat(manifestKeys("spring-jpa-crud-lombok")).isEqualTo(manifestKeys("spring-jpa-crud"));
        assertThat(manifestKeys("react-menora-digital-crud")).isEqualTo(manifestKeys("react-tailwind-crud"));
    }

    private static Set<String> manifestKeys(String set) throws Exception {
        JsonNode root = new ObjectMapper().readTree(new ClassPathResource(
                EntityTemplateSetSync.BASE + set + "/manifest.json").getContentAsString(StandardCharsets.UTF_8));
        Set<String> keys = new TreeSet<>();
        for (JsonNode f : root.get("files")) {
            keys.add(f.get("path").asText() + " | " + f.path("gatedBy").asText(""));
        }
        return keys;
    }

    private EntityTemplateSetEntity backendSet() {
        return setRepo.findBySetKey("spring-jpa-crud").orElseThrow();
    }

    private EntityTemplateFileEntity aShippedRow() {
        List<EntityTemplateFileEntity> rows = rowsAt(backendSet().getId(), SHIPPED_PATH);
        assertThat(rows).as("the fixture path is a shipped file").hasSize(1);
        return rows.get(0);
    }

    private List<EntityTemplateFileEntity> rowsAt(Long setId, String path) {
        return fileRepo.findBySetIdOrderBySortOrderAsc(setId).stream()
                .filter(r -> r.getPathTemplate().equals(path)).toList();
    }

    private static EntityTemplateFileEntity extraRow(Long setId, String path, String content) {
        EntityTemplateFileEntity row = new EntityTemplateFileEntity();
        row.setSetId(setId);
        row.setPathTemplate(path);
        row.setContent(content == null ? "# ours\n" : content);
        row.setSubstitutionType(FileContributionEntity.SubstitutionType.NONE);
        row.setFileType(EntityTemplateFileEntity.FileType.STATIC_COPY);
        return row;
    }
}
