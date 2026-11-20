package com.menora.initializr.admin;

import com.menora.initializr.CatalogTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.CatalogSync;
import com.menora.initializr.config.DatabaseInitializrMetadataProvider;
import com.menora.initializr.db.entity.CatalogSeedEntity;
import com.menora.initializr.db.entity.CatalogSeedEntity.RowTable;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.CatalogSeedRepository;
import com.menora.initializr.db.repository.DependencyEntryRepository;
import com.menora.initializr.db.repository.FileContributionRepository;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /admin/catalog-sync}: what an admin sees of a catalog row that differs from the shipped
 * one, and the two things they can do about it. The rules themselves are pinned by
 * {@code CatalogSyncTest}; this is the HTTP surface.
 *
 * <p>{@code @Transactional}: MockMvc runs on the test's thread, so what the endpoints write joins
 * the test's transaction and rolls back with it.
 */
@CatalogTest
@Transactional
class CatalogSyncAdminTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private CatalogSync catalogSync;
    @Autowired private FileContributionRepository fileRepo;
    @Autowired private CatalogSeedRepository seedRepo;
    @Autowired private DependencyEntryRepository entryRepo;
    @Autowired private InitializrMetadataProvider metadataProvider;

    /**
     * Taking a row refreshes the dependencies a generation offers — from inside this test's
     * transaction. Once that has rolled back, they are read again, so the context the other test
     * classes share is left with the catalog the database really has.
     */
    @AfterTransaction
    void rereadTheCatalog() {
        if (metadataProvider instanceof DatabaseInitializrMetadataProvider dbProvider) dbProvider.refresh();
    }

    @BeforeEach
    void inStep() {
        // Whatever the shared database's other tests left: start from the shipped catalog.
        for (CatalogSeedEntity state : seedRepo.findAll()) {
            if (state.isKept()) {
                state.setKept(false);
                seedRepo.save(state);
            }
        }
        catalogSync.apply(null);
    }

    private String bearer() throws Exception {
        MvcResult result = mvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private FileContributionEntity nginx() {
        return fileRepo.findByProjectKindOrderBySortOrderAsc(ProjectKind.FRONTEND).stream()
                .filter(f -> "__common__".equals(f.getDependencyId()) && "nginx/nginx.conf".equals(f.getTargetPath()))
                .findFirst().orElseThrow();
    }

    @Test
    void itIsBehindTheAdminLogin() throws Exception {
        mvc.perform(get("/admin/catalog-sync")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/catalog-sync/apply").contentType(MediaType.APPLICATION_JSON).content("{\"all\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminSeesWhatDiffersAndTakesTheShippedVersionOrKeepsTheirOwn() throws Exception {
        String bearer = bearer();
        FileContributionEntity row = nginx();
        String shipped = row.getContent();
        row.setContent(shipped + "\n# ours\n");
        fileRepo.save(row);
        String key = catalogSync.report().rows().stream()
                .filter(r -> r.status() == CatalogSync.Status.DIFFERS).findFirst().orElseThrow().key();

        // The report: counts of everything, rows narrowed by status.
        mvc.perform(get("/admin/catalog-sync").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.summary.DIFFERS").value(1))
                .andExpect(jsonPath("$.summary.MISSING").value(0))
                .andExpect(jsonPath("$.summary.IN_STEP").isNumber());
        JsonNode differing = json.readTree(mvc.perform(get("/admin/catalog-sync?status=DIFFERS,MISSING")
                        .header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(differing.get("rows")).hasSize(1);
        JsonNode reported = differing.get("rows").get(0);
        assertThat(reported.get("key").asText()).isEqualTo(key);
        assertThat(reported.get("table").asText()).isEqualTo("FILE_CONTRIBUTION");
        assertThat(reported.get("kind").asText()).isEqualTo("FRONTEND");
        assertThat(reported.get("dependencyId").asText()).isEqualTo("__common__");
        assertThat(reported.get("label").asText()).contains("nginx/nginx.conf");
        assertThat(reported.get("rowId").asLong()).isEqualTo(row.getId());

        // One row: the shipped entity beside the database's.
        mvc.perform(get("/admin/catalog-sync/" + key).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.status").value("DIFFERS"))
                .andExpect(jsonPath("$.shipped.content").value(shipped))
                .andExpect(jsonPath("$.shipped.targetPath").value("nginx/nginx.conf"))
                .andExpect(jsonPath("$.current.content").value(shipped + "\n# ours\n"))
                .andExpect(jsonPath("$.current.id").value(row.getId()));
        mvc.perform(get("/admin/catalog-sync/nope").header("Authorization", bearer))
                .andExpect(status().isNotFound());

        // Keep mine, then think again.
        mvc.perform(post("/admin/catalog-sync/keep").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"" + key + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(1))
                .andExpect(jsonPath("$.summary.KEPT").value(1))
                .andExpect(jsonPath("$.summary.DIFFERS").value(0));
        mvc.perform(post("/admin/catalog-sync/keep").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"" + key + "\"],\"kept\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.DIFFERS").value(1));

        // Take the shipped version.
        mvc.perform(post("/admin/catalog-sync/apply").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"" + key + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(1))
                .andExpect(jsonPath("$.summary.DIFFERS").value(0));
        assertThat(nginx().getContent()).isEqualTo(shipped);
    }

    private String keyOf(RowTable table, Long rowId) {
        return catalogSync.report().rows().stream()
                .filter(r -> r.table() == table && rowId.equals(r.rowId())).findFirst().orElseThrow().key();
    }

    private String offered() throws Exception {
        return mvc.perform(get("/metadata/client").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aDependencyTakenFromTheReleaseIsOfferedAtOnce() throws Exception {
        String bearer = bearer();
        DependencyEntryEntity kafka = entryRepo.findByDepId("kafka").orElseThrow();
        String shippedName = kafka.getName();
        String key = keyOf(RowTable.DEPENDENCY_ENTRY, kafka.getId());
        kafka.setName("Kafka (ours)");
        entryRepo.saveAndFlush(kafka);
        mvc.perform(post("/admin/refresh").header("Authorization", bearer)).andExpect(status().isOk());
        assertThat(offered()).contains("Kafka (ours)");

        // The row as a table of fields: the shipped entry beside the database's.
        mvc.perform(get("/admin/catalog-sync/" + key).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.table").value("DEPENDENCY_ENTRY"))
                .andExpect(jsonPath("$.row.dependencyId").value("kafka"))
                .andExpect(jsonPath("$.row.status").value("DIFFERS"))
                .andExpect(jsonPath("$.shipped.name").value(shippedName))
                .andExpect(jsonPath("$.shipped.group").value("Messaging"))
                .andExpect(jsonPath("$.current.name").value("Kafka (ours)"))
                .andExpect(jsonPath("$.current.group").value("Messaging"))
                .andExpect(jsonPath("$.current.id").value(kafka.getId()));

        // Taken: what a generation offers follows without a refresh by hand.
        mvc.perform(post("/admin/catalog-sync/apply").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"" + key + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(1))
                .andExpect(jsonPath("$.skipped").isEmpty());
        assertThat(offered()).contains(shippedName).doesNotContain("Kafka (ours)");
    }

    @Test
    void whatCannotBeWrittenComesBackWithTheReason() throws Exception {
        String bearer = bearer();
        // An admin deleted Redis; a release ships a file for it that this database never had.
        FileContributionEntity file = fileRepo.findAll().stream()
                .filter(f -> "redis".equals(f.getDependencyId())).findFirst().orElseThrow();
        String fileKey = keyOf(RowTable.FILE_CONTRIBUTION, file.getId());
        DependencyEntryEntity redis = entryRepo.findByDepId("redis").orElseThrow();
        String entryKey = keyOf(RowTable.DEPENDENCY_ENTRY, redis.getId());
        fileRepo.delete(file);
        seedRepo.deleteById(fileKey);
        entryRepo.delete(redis);
        entryRepo.flush();

        JsonNode report = json.readTree(mvc.perform(get("/admin/catalog-sync?status=PARENT_GONE")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.PARENT_GONE").value(1))
                .andExpect(jsonPath("$.summary.DELETED").value(1))
                .andReturn().getResponse().getContentAsString());
        assertThat(report.get("rows")).hasSize(1);
        assertThat(report.get("rows").get(0).get("reason").asText()).isEqualTo("the dependency 'redis' is not in the catalog");

        // Named alone it is skipped, and the answer says why.
        mvc.perform(post("/admin/catalog-sync/apply").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"" + fileKey + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(0))
                .andExpect(jsonPath("$.skipped[0].key").value(fileKey))
                .andExpect(jsonPath("$.skipped[0].label").value(org.hamcrest.Matchers.containsString("redis")))
                .andExpect(jsonPath("$.skipped[0].reason").value("the dependency 'redis' is not in the catalog"));
        assertThat(offered()).doesNotContain("\"id\":\"redis\"");

        // With its dependency, both are written — and the dependency is offered again.
        mvc.perform(post("/admin/catalog-sync/apply").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"" + fileKey + "\",\"" + entryKey + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(2))
                .andExpect(jsonPath("$.skipped").isEmpty())
                .andExpect(jsonPath("$.summary.PARENT_GONE").value(0));
        assertThat(offered()).contains("\"id\":\"redis\"");
    }

    @Test
    void applyAllTakesEveryRowThatDiffers() throws Exception {
        String bearer = bearer();
        FileContributionEntity row = nginx();
        String shipped = row.getContent();
        row.setContent("server {\n  listen 80;\n}\n");
        fileRepo.save(row);

        mvc.perform(post("/admin/catalog-sync/apply").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"all\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(1));
        assertThat(nginx().getContent()).isEqualTo(shipped);
    }

    @Test
    void aRequestThatNamesNothingIsRefused() throws Exception {
        String bearer = bearer();
        mvc.perform(post("/admin/catalog-sync/apply").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Nothing to apply"));
        mvc.perform(post("/admin/catalog-sync/keep").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
