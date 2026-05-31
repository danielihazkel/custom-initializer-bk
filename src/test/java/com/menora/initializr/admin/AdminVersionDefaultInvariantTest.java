package com.menora.initializr.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.VersionDefinitionEntity;
import com.menora.initializr.db.entity.VersionKind;
import com.menora.initializr.db.repository.VersionDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for the single-default invariant on version definitions.
 *
 * <p>Setting a new default for a {@link VersionKind} via {@code PUT /admin/versions/{id}}
 * must clear the default flag on the row that was previously the default of the same kind.
 * Before the fix, {@code AdminController.updateVersion} just saved the row, leaving two rows
 * flagged; {@code VersionService.defaultId()} then returned whichever sorted first
 * (e.g. {@code pnpm}, sort_order 0), so the admin change was silently ignored.
 *
 * <p>Runs {@code @Transactional} so the mutation to the seeded rows rolls back and the shared
 * in-memory DB is left untouched for sibling test classes. The fix flips the other rows with
 * an ordinary {@code save()} (no bulk update), so same-transaction re-reads observe it directly.
 *
 * <p>Auth mirrors {@link AdminApiIntegrationTests}: the admin gate is a custom bearer-token
 * filter (no Spring Security); log in with the test password to obtain the token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminVersionDefaultInvariantTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private VersionDefinitionRepository versionRepo;

    private String bearer() throws Exception {
        MvcResult result = mvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void settingNewDefaultClearsPreviousDefaultOfSameKind() throws Exception {
        String auth = bearer();

        var pms = versionRepo.findByKindOrderBySortOrderAscIdAsc(VersionKind.PACKAGE_MANAGER);
        var pnpm = pms.stream().filter(v -> v.getVersionId().equals("pnpm")).findFirst().orElseThrow();
        var npm = pms.stream().filter(v -> v.getVersionId().equals("npm")).findFirst().orElseThrow();

        // Sanity: seeded state has pnpm as the sole default.
        assertThat(pnpm.isDefault()).isTrue();
        assertThat(npm.isDefault()).isFalse();

        // Promote npm to default via the admin API.
        npm.setDefault(true);
        mvc.perform(put("/admin/versions/" + npm.getId()).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(npm)))
                .andExpect(status().isOk());

        // The previous default must have been flipped off, leaving exactly one default.
        assertThat(versionRepo.findById(npm.getId()).orElseThrow().isDefault()).isTrue();
        assertThat(versionRepo.findById(pnpm.getId()).orElseThrow().isDefault()).isFalse();

        long defaults = versionRepo.findByKindOrderBySortOrderAscIdAsc(VersionKind.PACKAGE_MANAGER)
                .stream().filter(VersionDefinitionEntity::isDefault).count();
        assertThat(defaults).isEqualTo(1);
    }
}
