package com.menora.initializr.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Design Studio API over HTTP: the admin gate, the draft → publish flow, uploads, public reads. */
@SpringBootTest
@AutoConfigureMockMvc
class DesignKitAdminApiTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    @Test
    void theStudioApiNeedsTheAdminToken() throws Exception {
        mvc.perform(get("/admin/design/kits")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/design/kits").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        // The generator's reads are public.
        mvc.perform(get("/metadata/design/kits")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", hasItem("menora-digital")));
    }

    @Test
    void cloneUploadPublishAndRead() throws Exception {
        String auth = "Bearer " + login();
        String key = "api-kit-" + System.nanoTime() % 100000;
        mvc.perform(post("/admin/design/kits").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"Api kit\",\"cloneFrom\":\"menora-digital\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasDraft").value(true))
                .andExpect(jsonPath("$.baseSetKey").value("react-tailwind-crud"));
        mvc.perform(post("/admin/design/kits").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"Again\",\"cloneFrom\":\"menora-digital\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A kit with key '" + key + "' already exists"));

        MockMultipartFile svg = new MockMultipartFile("file", "logo.svg", "image/svg+xml", FullstackDesignKitTests.SVG);
        mvc.perform(multipart("/admin/design/kits/" + key + "/draft/assets").file(svg).header("Authorization", auth)
                        .param("targetPath", "public/brand-logo.svg").param("role", "LOGO"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/svg+xml"));
        MockMultipartFile fake = new MockMultipartFile("file", "logo.png", "image/png", "<script>".getBytes());
        mvc.perform(multipart("/admin/design/kits/" + key + "/draft/assets").file(fake).header("Authorization", auth)
                        .param("targetPath", "public/x.png").param("role", "LOGO"))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/admin/design/kits/" + key + "/draft").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokens\":{\"$schema\":\"menora-design-tokens/1\",\"tokens\":[]}}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/design/kits/" + key + "/draft/publish").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.problems").isArray());

        // Restore the tokens from the kit it was cloned from, then publish.
        String source = mvc.perform(get("/metadata/design/kits/menora-digital/versions/latest"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String tokens = json.writeValueAsString(json.readTree(source).get("tokens"));
        mvc.perform(put("/admin/design/kits/" + key + "/draft").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokens\":" + tokens + "}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/design/kits/" + key + "/draft/publish").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"logo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.notes").value("logo"));

        String v1 = mvc.perform(get("/metadata/design/kits/" + key + "/versions/1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")))
                .andReturn().getResponse().getContentAsString();
        String url = json.readTree(v1).get("assets").get(0).get("url").asText();
        byte[] bytes = mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", "image/svg+xml"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(bytes).isEqualTo(FullstackDesignKitTests.SVG);

        mvc.perform(get("/metadata/design/kits/" + key + "/versions/draft")).andExpect(status().isNotFound());
        mvc.perform(get("/admin/design/kits/" + key + "/versions").header("Authorization", auth))
                .andExpect(jsonPath("$[0].version").value(1));
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }
}
