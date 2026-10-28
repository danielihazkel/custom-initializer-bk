package com.menora.initializr.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.DependencyEntryEntity;
import com.menora.initializr.db.entity.DependencyGroupEntity;
import com.menora.initializr.db.entity.ProjectKind;
import com.menora.initializr.db.repository.DependencyEntryRepository;
import com.menora.initializr.db.repository.DependencyGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reordering groups rebuilds the metadata inside the reorder's write transaction. The rebuild used
 * to drop a group's cross-kind entries with {@code removeIf} on the managed orphanRemoval
 * collection, so the commit deleted those rows from the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupReorderKeepsCrossKindEntriesTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private DependencyGroupRepository groupRepo;
    @Autowired private DependencyEntryRepository entryRepo;

    @Test
    void reorderingGroupsKeepsAnEntryOfTheOtherKind() throws Exception {
        DependencyGroupEntity group = groupRepo.findAllByProjectKindOrderBySortOrderAsc(ProjectKind.BACKEND).get(0);
        DependencyEntryEntity stray = new DependencyEntryEntity();
        stray.setGroup(group);
        stray.setDepId("reorder-test-frontend-entry");
        stray.setName("Reorder test");
        stray.setProjectKind(ProjectKind.FRONTEND);
        stray = entryRepo.save(stray);
        try {
            String token = json.readTree(mvc.perform(post("/admin/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"test\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("token").asText();

            MvcResult result = mvc.perform(put("/admin/dependency-groups/reorder")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(List.of(
                                    Map.of("id", group.getId(), "sortOrder", (long) group.getSortOrder())))))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(204);

            assertThat(entryRepo.findById(stray.getId())).isPresent();
        } finally {
            entryRepo.deleteById(stray.getId());
        }
    }
}
