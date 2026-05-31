package com.menora.initializr.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the admin API via {@link MockMvc}. The {@link AdminAuthFilter}
 * servlet filter (registered for {@code /admin/*}) is applied by the MockMvc
 * auto-configuration, so the auth gate is exercised end-to-end.
 *
 * <p>The configured admin password is {@code test} (see
 * {@code src/test/resources/application.properties}).
 *
 * <p>Tests are non-mutating — validation failures don't insert, and the orphan-conflict
 * (409) path doesn't delete — so no DB cleanup is needed across the shared in-memory DB.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminApiIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String bearer() throws Exception {
        return "Bearer " + login();
    }

    // ── Authentication ──────────────────────────────────────────────────────

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithCorrectPasswordReturnsToken() throws Exception {
        assertThat(login()).isNotBlank();
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/admin/dependency-groups"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedListGroupsReturnsSeededCatalog() throws Exception {
        mvc.perform(get("/admin/dependency-groups").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Menora Standards')]").exists());
    }

    // ── Validation / error handling (GlobalExceptionHandler) ────────────────

    @Test
    void createGroupWithBlankNameReturns400() throws Exception {
        mvc.perform(post("/admin/dependency-groups")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"sortOrder\":0,\"projectKind\":\"BACKEND\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
    }

    // ── Refresh ─────────────────────────────────────────────────────────────

    @Test
    void refreshReturnsOk() throws Exception {
        mvc.perform(post("/admin/refresh").header("Authorization", bearer()))
                .andExpect(status().isOk());
    }

    // ── Orphan detection (cascade-delete conflict) ──────────────────────────

    @Test
    void deletingGroupWithEntriesReturns409() throws Exception {
        String auth = bearer();
        MvcResult result = mvc.perform(get("/admin/dependency-groups").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();

        // Pick any seeded group that has entries — deleting it without force must conflict.
        JsonNode groups = json.readTree(result.getResponse().getContentAsString());
        long groupId = -1;
        for (JsonNode g : groups) {
            if (g.has("entries") && g.get("entries").size() > 0) {
                groupId = g.get("id").asLong();
                break;
            }
        }
        assertThat(groupId).as("expected a seeded group with entries").isGreaterThan(0);

        mvc.perform(delete("/admin/dependency-groups/" + groupId).header("Authorization", auth))
                .andExpect(status().isConflict());
    }
}
