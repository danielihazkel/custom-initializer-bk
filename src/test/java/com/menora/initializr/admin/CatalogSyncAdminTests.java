package com.menora.initializr.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.CatalogSync;
import com.menora.initializr.db.entity.CatalogSeedEntity;
import com.menora.initializr.db.entity.FileContributionEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.CatalogSeedRepository;
import com.menora.initializr.db.repository.FileContributionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
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
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CatalogSyncAdminTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private CatalogSync catalogSync;
    @Autowired private FileContributionRepository fileRepo;
    @Autowired private CatalogSeedRepository seedRepo;

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
