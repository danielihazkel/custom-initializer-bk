package com.menora.initializr.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.FullstackExampleRequests;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The preview runtime the layout designer and the Studio frame: the shipped build belongs to its
 * manifest and speaks this backend's protocol, it is served with its sandbox policy, and the
 * fullstack preview hands it the app's binaries when asked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DesignRuntimeTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private DesignKitService designKits;

    @Test
    void shippedRuntimeMatchesItsManifestAndThisProtocol() throws Exception {
        JsonNode manifest = json.readTree(new ClassPathResource("static/design-runtime/runtime-manifest.json").getInputStream());
        assertThat(manifest.get("protocolVersion").asInt())
                .as("runtime protocol — rebuild with `npm run publish:runtime` in design-studio, or bump DesignRuntimeFilter.PROTOCOL_VERSION")
                .isEqualTo(DesignRuntimeFilter.PROTOCOL_VERSION);
        byte[] js = new ClassPathResource("static/design-runtime/runtime.js").getInputStream().readAllBytes();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(js)).substring(0, 16);
        assertThat(hash).as("runtime.js was changed without its manifest (or line endings were rewritten)")
                .isEqualTo(manifest.get("buildHash").asText());
        for (JsonNode font : manifest.get("fonts")) {
            assertThat(new ClassPathResource("static/design-runtime/fonts/" + font.asText()).exists()).as(font.asText()).isTrue();
        }
        String index = new String(new ClassPathResource("static/design-runtime/index.html").getInputStream().readAllBytes());
        assertThat(index).contains("src=\"./runtime.js\"").contains("id=\"override\"");
        assertThat(DesignContract.describe()).containsEntry("runtimeProtocol", DesignRuntimeFilter.PROTOCOL_VERSION);
    }

    @Test
    void runtimeIsServedWithItsSandboxPolicy() throws Exception {
        mvc.perform(get("/design-runtime/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", DesignRuntimeFilter.CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/design-runtime/runtime.js")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", DesignRuntimeFilter.CSP));
        // An opaque origin fetches fonts with CORS.
        mvc.perform(get("/design-runtime/fonts/inter-latin-400-normal.woff2")).andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
        assertThat(DesignRuntimeFilter.CSP).contains("connect-src 'none'").contains("form-action 'none'");
        // Nothing else gets the runtime's headers.
        mvc.perform(get("/metadata/design/contract")).andExpect(header().doesNotExist("Content-Security-Policy"));
    }

    @Test
    void fullstackPreviewCarriesBinariesOnlyWhenAsked() throws Exception {
        Map<String, Object> tickets = FullstackExampleRequests.examples().stream()
                .filter(e -> "tickets".equals(e.get("exampleId"))).findFirst().orElseThrow();
        Map<String, Object> body = FullstackExampleRequests.body(tickets, "react-menora-digital-crud", "en", true);
        String request = json.writeValueAsString(body);

        JsonNode plain = json.readTree(mvc.perform(post("/starter-fullstack.preview").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode logo = fileOf(plain, "frontend/public/menora-mivtachim-logo.png");
        assertThat(logo.get("content").asText()).isEqualTo("[binary file]");
        assertThat(logo.has("base64")).isFalse();

        JsonNode withAssets = json.readTree(mvc.perform(post("/starter-fullstack.preview?assets=true").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        byte[] png = Base64.getDecoder().decode(fileOf(withAssets, "frontend/public/menora-mivtachim-logo.png").get("base64").asText());
        assertThat(png).startsWith(0x89, 'P', 'N', 'G');
        // Text files and binaries outside public/ and src/assets/ stay as they were.
        assertThat(fileOf(withAssets, "frontend/src/main.tsx").has("base64")).isFalse();
    }

    @Test
    void theStudioPreviewsADraftKitThatThePublicPreviewRefuses() throws Exception {
        String key = "draft-preview-" + System.nanoTime() % 100000;
        designKits.create(new DesignKitService.CreateKit(key, "Draft preview", null, "tailwind-default"), "test");
        DesignTokens tokens = designKits.draft(key).tokens();
        designKits.saveDraft(key, new DesignKitService.SaveDraft(new DesignTokens(DesignTokens.SCHEMA, tokens.tokens().stream()
                .map(t -> t.name().equals("color-brand") ? new DesignTokens.Token(t.name(), t.type(), "#0f766e", t.dark(), t.group()) : t)
                .toList()), null, null, null));
        Map<String, Object> orders = FullstackExampleRequests.examples().stream()
                .filter(e -> "orders".equals(e.get("exampleId"))).findFirst().orElseThrow();
        Map<String, Object> body = new java.util.LinkedHashMap<>(FullstackExampleRequests.body(orders, null, "en", true));
        body.put("designKit", key + "@draft");
        String request = json.writeValueAsString(body);

        mvc.perform(post("/starter-fullstack.preview").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/design/preview").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
        JsonNode preview = json.readTree(mvc.perform(post("/admin/design/preview").header("Authorization", "Bearer " + login())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(fileOf(preview, "frontend/src/index.css").get("content").asText()).contains("#0f766e");
        // Asked by the runtime, so the binaries come along by default.
        assertThat(fileOf(preview, "frontend/public/logo.png").has("base64")).isTrue();
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private static JsonNode fileOf(JsonNode preview, String suffix) {
        for (JsonNode f : preview.get("files")) {
            if (f.get("path").asText().endsWith(suffix)) return f;
        }
        throw new AssertionError("no " + suffix + " in the preview");
    }
}
