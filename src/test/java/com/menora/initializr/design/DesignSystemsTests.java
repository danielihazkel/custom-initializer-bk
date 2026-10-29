package com.menora.initializr.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.FullstackExampleRequests;
import com.menora.initializr.db.entity.FullstackModelEntity;
import com.menora.initializr.db.repository.FullstackModelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Whole design systems (P4): the house kit a request naming none generates with, the traits and
 * aliases the generator UI reads, deprecated versions, who uses a kit, and a kit as one
 * {@code .menora-kit.zip}.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class DesignSystemsTests {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private DesignKitService service;
    @Autowired
    private DesignKitUsage usage;
    @Autowired
    private FullstackModelRepository models;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    @Test
    void theBuiltInsDescribeThemselvesForTheGenerator() {
        DesignKitService.KitSummary house = service.get("tailwind-default");
        assertThat(house.defaultKit()).isTrue();
        assertThat(house.traits()).isNull();
        assertThat(house.aliases()).isEmpty();
        assertThat(house.swatch()).containsKeys("color-ink", "color-brand", "color-primary", "color-canvas")
                .containsEntry("color-brand", "{palette.primary}");
        DesignKitService.KitSummary menora = service.get("menora-digital");
        assertThat(menora.defaultKit()).isFalse();
        assertThat(menora.traits()).isEqualTo(new KitManifest.Traits("menora", false));
        assertThat(menora.aliases()).containsExactly("react-menora-digital-crud");
        assertThat(menora.swatch()).containsEntry("color-primary", "#ffc700");
        assertThat(TokenValidator.validate(service.version("menora-digital", "latest").tokens(),
                new KitManifest(List.of(), null, null, new KitManifest.Traits("bootstrap", null)), ""))
                .contains("manifest: traits.sketch must be one of tailwind, menora");
    }

    @Test
    void aRequestNamingNoKitGeneratesWithTheHouseKit() throws Exception {
        String key = publishedClone("#123abc");
        try {
            service.update(key, new DesignKitService.UpdateKit(null, null, null, null, null, true));
            assertThat(service.get("tailwind-default").defaultKit()).isFalse();
            assertThat(service.houseKitFor("react-tailwind-crud")).contains(key);
            assertThat(generate(Map.of()).get("frontend/src/index.css")).containsPattern("--color-brand:\\s+#123abc;");
            // A set that stands for another kit, or a kit named outright, is not overridden.
            assertThat(generate(Map.of("frontendTemplateSet", "react-menora-digital-crud")).get("frontend/src/index.css"))
                    .doesNotContain("#123abc").contains("menora/tokens.css");
            assertThat(generate(Map.of("designKit", "tailwind-default")).get("frontend/src/index.css")).doesNotContain("#123abc");
            // There is always one house kit, and it stays usable.
            assertThatThrownBy(() -> service.update(key, new DesignKitService.UpdateKit(null, null, false, null, null)))
                    .hasMessageContaining("house kit");
            assertThatThrownBy(() -> service.update(key, new DesignKitService.UpdateKit(null, null, null, null, null, false)))
                    .hasMessageContaining("always a house kit");
            assertThatThrownBy(() -> service.delete(key)).hasMessageContaining("house kit");
        } finally {
            service.update("tailwind-default", new DesignKitService.UpdateKit(null, null, null, null, null, true));
        }
        assertThat(service.get(key).defaultKit()).isFalse();
        assertThat(generate(Map.of()).get("frontend/src/index.css")).doesNotContain("#123abc");
        // A kit that is not published cannot be the house kit.
        String draftOnly = "sys-" + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        service.create(new DesignKitService.CreateKit(draftOnly, "Draft only", null, "tailwind-default"), "test");
        assertThatThrownBy(() -> service.update(draftOnly, new DesignKitService.UpdateKit(null, null, null, null, null, true)))
                .hasMessageContaining("Publish");
    }

    @Test
    void aDeprecatedVersionStillGeneratesButIsMarked() throws Exception {
        String key = publishedClone("#2255aa");
        service.openDraft(key, null, "test");
        service.saveDraft(key, new DesignKitService.SaveDraft(FullstackDesignKitTests.withBrand(service.draft(key).tokens(), "#3366bb"), null, null, null));
        service.publish(key, "second", "test");
        assertThatThrownBy(() -> service.deprecate(key, 2, new DesignKitService.Deprecate(true, null)))
                .hasMessageContaining("latest version");
        String auth = "Bearer " + login();
        mvc.perform(post("/admin/design/kits/" + key + "/versions/1/deprecate").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deprecated\":true,\"note\":\"Use v2: the new brand blue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecated").value(true))
                .andExpect(jsonPath("$.deprecationNote").value("Use v2: the new brand blue"));
        mvc.perform(get("/metadata/design/kits/" + key + "/versions"))
                .andExpect(jsonPath("$[?(@.version == 1)].deprecated").value(true))
                .andExpect(jsonPath("$[?(@.version == 2)].deprecated").value(false));
        assertThat(service.get(key).deprecated()).containsExactly(new DesignKitService.DeprecatedVersion(1, "Use v2: the new brand blue"));
        String checksum = service.versions(key).stream().filter(v -> v.version() == 1).findFirst().orElseThrow().checksum();
        assertThat(service.version(key, "1").checksum()).isEqualTo(checksum);
        assertThat(generate(Map.of("designKit", key + "@1")).get("frontend/src/index.css")).containsPattern("--color-brand:\\s+#2255aa;");
        service.deprecate(key, 1, new DesignKitService.Deprecate(false, "ignored"));
        assertThat(service.get(key).deprecated()).isEmpty();
        assertThat(service.versions(key)).allSatisfy(v -> {
            assertThat(v.deprecated()).isFalse();
            assertThat(v.deprecationNote()).isNull();
        });
    }

    @Test
    void usageListsWhoGeneratesWithAKit() throws Exception {
        String key = publishedClone("#445566");
        FullstackModelEntity pinned = model("Pinned to " + key, "{\"entities\":[],\"frontendSet\":\"react-tailwind-crud\",\"designKit\":{\"key\":\"" + key + "\",\"version\":1}}");
        FullstackModelEntity menora = model("Menora via set " + key, "{\"entities\":[],\"frontendSet\":\"react-menora-digital-crud\"}");
        try {
            DesignKitUsage.Usage mine = usage.of(key);
            assertThat(mine.houseKit()).isFalse();
            assertThat(mine.examples()).isEmpty();
            assertThat(mine.models()).extracting(DesignKitUsage.User::name, DesignKitUsage.User::version, DesignKitUsage.User::via)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(pinned.getName(), 1, "pinned"));
            DesignKitUsage.Usage house = usage.of("tailwind-default");
            assertThat(house.houseKit()).isTrue();
            assertThat(house.examples()).hasSize(FullstackExampleRequests.examples().size())
                    .allSatisfy(u -> assertThat(u.via()).isEqualTo("house"));
            assertThat(house.models()).extracting(DesignKitUsage.User::name).doesNotContain(pinned.getName(), menora.getName());
            assertThat(usage.of("menora-digital").models()).extracting(DesignKitUsage.User::name, DesignKitUsage.User::via)
                    .contains(org.assertj.core.groups.Tuple.tuple(menora.getName(), "set"));
            mvc.perform(get("/admin/design/kits/" + key + "/usage").header("Authorization", "Bearer " + login()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.models[0].via").value("pinned"));
        } finally {
            models.delete(pinned);
            models.delete(menora);
        }
    }

    @Test
    void aKitTravelsAsOneArchiveAndComesInAsADraft() throws Exception {
        String auth = "Bearer " + login();
        byte[] archive = mvc.perform(get("/admin/design/kits/menora-digital/archive").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, byte[]> entries = FullstackExampleRequests.unzip(archive);
        assertThat(entries).containsKeys(KitArchive.DESCRIPTOR, "kit.css", "files/src/shared/ui/Shell.tsx",
                "files/src/shared/ui/menora/Button.tsx", "assets/public/menora-mivtachim-logo.png");
        JsonNode descriptor = json.readTree(entries.get(KitArchive.DESCRIPTOR));
        assertThat(descriptor.get("format").asText()).isEqualTo(KitArchive.FORMAT);
        assertThat(descriptor.get("baseSetKey").asText()).isEqualTo("react-tailwind-crud");

        // Into a new kit: a draft with every file and asset, ready to publish.
        String key = "imported-" + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        mvc.perform(multipart("/admin/design/kits/import").file(new MockMultipartFile("file", "menora.menora-kit.zip", "application/zip", archive))
                        .param("key", key).header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.hasDraft").value(true))
                .andExpect(jsonPath("$.latestVersion").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.clonedFrom").value("menora-digital@" + service.get("menora-digital").latestVersion()));
        DesignKitService.VersionView draft = service.draft(key);
        DesignKitService.VersionView source = service.version("menora-digital", "latest");
        assertThat(draft.files()).extracting(DesignKitService.FileView::sha256)
                .containsExactlyElementsOf(source.files().stream().map(DesignKitService.FileView::sha256).toList());
        assertThat(draft.assets()).extracting(DesignKitService.AssetView::sha256)
                .containsExactlyElementsOf(source.assets().stream().map(DesignKitService.AssetView::sha256).toList());
        assertThat(draft.tokens()).isEqualTo(source.tokens());
        assertThat(service.publish(key, null, "test").checksum()).isEqualTo(source.checksum());

        // Into the same kit again: a new draft on top of its latest version.
        mvc.perform(multipart("/admin/design/kits/import").file(new MockMultipartFile("file", "again.zip", "application/zip", archive))
                        .param("key", key).header("Authorization", auth))
                .andExpect(status().isCreated());
        assertThat(service.draft(key).basedOnVersion()).isEqualTo(1);
        // …but not over a draft, nor into a built-in.
        mvc.perform(multipart("/admin/design/kits/import").file(new MockMultipartFile("file", "again.zip", "application/zip", archive))
                        .param("key", key).header("Authorization", auth))
                .andExpect(status().isConflict());
        mvc.perform(multipart("/admin/design/kits/import").file(new MockMultipartFile("file", "again.zip", "application/zip", archive))
                        .header("Authorization", auth))
                .andExpect(status().isConflict());
        // Not an archive, and an archive holding a component the rules refuse: nothing is created.
        mvc.perform(multipart("/admin/design/kits/import").file(new MockMultipartFile("file", "x.zip", "application/zip", "nope".getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());
        String refused = "refused-" + SEQ.incrementAndGet();
        Map<String, byte[]> bad = new TreeMap<>(entries);
        bad.put("files/src/shared/ui/Shell.tsx", (new String(entries.get("files/src/shared/ui/Shell.tsx"), StandardCharsets.UTF_8)
                + "\nexport const leak = () => fetch('/x')\n").getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/admin/design/kits/import").file(new MockMultipartFile("file", "bad.zip", "application/zip", zipOf(bad)))
                        .param("key", refused).header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.problems").isArray());
        assertThat(service.list(false)).extracting(DesignKitService.KitSummary::key).doesNotContain(refused);
        mvc.perform(get("/admin/design/kits/menora-digital/archive")).andExpect(status().isUnauthorized());
    }

    /** A kit nav icon: a lucide-shaped component, so the Shell's {@code LucideIcon} prop takes it. */
    public static final String NAV_ICONS = """
            import { createLucideIcon } from 'lucide-react'

            export const ShieldIcon = createLucideIcon('ShieldIcon', [
              ['path', { d: 'M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6z', key: 'shield' }],
            ])
            """;
    public static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @Test
    void aBlankKitStartsFromTheBaseSetsRequiredTokens() throws Exception {
        String key = "blank-" + SEQ.incrementAndGet();
        DesignKitService.KitSummary blank = service.create(new DesignKitService.CreateKit(key, "Blank", null, null, "react-tailwind-crud"), "test");
        assertThat(blank.baseSetKey()).isEqualTo("react-tailwind-crud");
        assertThat(blank.clonedFrom()).isNull();
        DesignKitService.VersionView draft = service.draft(key);
        assertThat(draft.tokens().tokens()).extracting(DesignTokens.Token::name)
                .containsExactlyElementsOf(TokenSchema.REQUIRED.stream().map(TokenSchema.Required::name).toList());
        assertThat(draft.tokens().tokens()).filteredOn(t -> t.name().equals("color-brand"))
                .extracting(DesignTokens.Token::value).containsExactly("{palette.primary}");
        assertThat(draft.css()).isEmpty();
        assertThat(draft.files()).isEmpty();
        assertThat(draft.assets()).isEmpty();
        assertThat(draft.manifest().fonts()).isEqualTo(service.version("tailwind-default", "latest").manifest().fonts());
        assertThat(service.validateDraft(key).ok()).isTrue();
        service.publish(key, "blank", "test");
        assertThat(generate(Map.of("designKit", key)).get("frontend/src/index.css")).contains("--color-canvas:");

        assertThatThrownBy(() -> service.create(new DesignKitService.CreateKit("blank-x", "X", null, null, "react-menora-digital-crud"), "test"))
                .hasMessageContaining("react-tailwind-crud");
        assertThatThrownBy(() -> service.create(new DesignKitService.CreateKit("blank-y", "Y", null, "tailwind-default", "react-tailwind-crud"), "test"))
                .hasMessageContaining("not both");
        assertThatThrownBy(() -> service.create(new DesignKitService.CreateKit("blank-z", "Z", null, null, null), "test"))
                .hasMessageContaining("baseSetKey");
    }

    @Test
    void theShellIsPaintedWithItsOwnTokensWhichAKitMayLeaveOut() throws Exception {
        // A blank kit names only the required tokens: the shell gets the colours it always had.
        String key = "shell-" + SEQ.incrementAndGet();
        service.create(new DesignKitService.CreateKit(key, "Shell", null, null, "react-tailwind-crud"), "test");
        service.publish(key, "blank", "test");
        Map<String, String> files = generate(Map.of("designKit", key));
        assertThat(files.get("frontend/src/index.css"))
                .contains("--color-shell:", "var(--color-brand);", "--color-shell-end:", "var(--color-gold);", "--color-on-shell-muted:");
        assertThat(files.get("frontend/src/shared/ui/Shell.tsx")).contains("from-shell", "to-shell-end", "text-on-shell");

        // A kit that sets one paints the shell with it.
        String own = publishedClone("#0f766e");
        service.openDraft(own, null, "test");
        List<DesignTokens.Token> tokens = new java.util.ArrayList<>(service.draft(own).tokens().tokens());
        tokens.replaceAll(t -> t.name().equals("color-shell") ? new DesignTokens.Token(t.name(), t.type(), "#123456", null, t.group()) : t);
        service.saveDraft(own, new DesignKitService.SaveDraft(new DesignTokens(DesignTokens.SCHEMA, tokens), null, null, null));
        service.publish(own, "shell", "test");
        assertThat(generate(Map.of("designKit", own)).get("frontend/src/index.css")).containsPattern("--color-shell:\\s+#123456;");

        // A kit with a Shell of its own paints it as it likes: no shell tokens are added for it.
        service.openDraft(key, null, "test");
        service.putComponentFile(key, TokenSchema.SHELL_SLOT, service.baseSource(key, TokenSchema.SHELL_SLOT).replace("from-shell", "from-brand"));
        service.publish(key, "own shell", "test");
        assertThat(generate(Map.of("designKit", key)).get("frontend/src/index.css")).doesNotContain("--color-shell");
    }

    @Test
    void aThumbnailPicturesTheKitButStaysOutOfTheApp() throws Exception {
        String key = publishedClone("#0f766e");
        service.openDraft(key, null, "test");
        assertThatThrownBy(() -> service.putAsset(key, "kit/thumbnail.png", "IMAGE", null, PNG)).hasMessageContaining("THUMBNAIL");
        assertThatThrownBy(() -> service.putAsset(key, "public/thumb.png", "THUMBNAIL", null, PNG)).hasMessageContaining("THUMBNAIL");
        DesignKitService.AssetView thumb = service.putAsset(key, "kit/thumbnail.png", "THUMBNAIL", null, PNG);
        assertThat(service.get(key).thumbnail()).isNull();
        service.publish(key, "with a picture", "test");
        assertThat(service.get(key).thumbnail()).isEqualTo("/metadata/design/assets/" + thumb.sha256());
        mvc.perform(get("/metadata/design/kits")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == '" + key + "')].thumbnail").value(thumb.url()));
        Map<String, String> files = generate(Map.of("designKit", key));
        assertThat(files.keySet()).noneMatch(p -> p.contains("thumbnail"));
    }

    @Test
    void aKitAddsNavIconsAPageMayName() throws Exception {
        String key = publishedClone("#7c3aed");
        service.openDraft(key, null, "test");
        KitManifest base = service.draft(key).manifest();
        KitManifest withIcons = new KitManifest(base.imports(), base.fonts(), base.defaults(), base.traits(),
                new KitManifest.NavIcons("src/shared/ui/acme/navIcons.tsx", List.of("ShieldIcon", "CoinIcon")));
        service.saveDraft(key, new DesignKitService.SaveDraft(null, withIcons, null, null));
        // The module must exist and export every name.
        assertThat(service.validateDraft(key).problems()).anyMatch(p -> p.contains("not one of the kit's files"));
        service.putComponentFile(key, "src/shared/ui/acme/navIcons.tsx", NAV_ICONS);
        assertThat(service.validateDraft(key).problems()).containsExactly("manifest: nav icon CoinIcon is not exported by src/shared/ui/acme/navIcons.tsx");
        service.saveDraft(key, new DesignKitService.SaveDraft(null, new KitManifest(base.imports(), base.fonts(), base.defaults(), base.traits(),
                new KitManifest.NavIcons("src/shared/ui/acme/navIcons.tsx", List.of("ShieldIcon"))), null, null));
        assertThat(service.validateDraft(key).ok()).isTrue();
        assertThat(TokenValidator.validate(service.draft(key).tokens(), new KitManifest(List.of(), null, null, null,
                new KitManifest.NavIcons("src/shared/ui/Shell.tsx", List.of("Users", "AIcon", "AIcon"))), ""))
                .contains("manifest: navIcons.module must be one of the kit's .tsx helper files (src/shared/ui/<kit>/navIcons.tsx)",
                        "manifest: nav icon 'Users' must be a component name ending in Icon (ShieldIcon)",
                        "manifest: nav icon 'AIcon' is listed twice");
        service.publish(key, "icons", "test");
        assertThat(service.get(key).navIcons()).containsExactly("ShieldIcon");

        List<Map<String, Object>> pages = List.of(
                Map.of("id", "notes", "type", "entity-list", "entity", "Note", "icon", "shieldicon"),
                Map.of("id", "home", "type", "dashboard", "widgets", List.of(Map.of("kind", "kpi", "entity", "Note"))));
        String app = generate(Map.of("designKit", key, "pages", pages)).get("frontend/src/app/App.tsx");
        assertThat(app).contains("import { LayoutDashboard } from 'lucide-react'\nimport { ShieldIcon } from '@shared/ui/acme/navIcons'\n")
                .contains("icon: ShieldIcon }");
        // Without the kit the name is unknown, and the error names the icons there are.
        Map<String, Object> body = new LinkedHashMap<>(Map.of("artifactId", "systems", "packageName", "com.menora.systems", "pages", pages,
                "entities", List.of(Map.of("name", "Note", "fields", List.of(Map.of("name", "id", "type", "Long", "primaryKey", true, "generated", true))))));
        mvc.perform(post("/starter-fullstack.zip").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("unknown icon 'shieldicon'")));
    }

    private String publishedClone(String brand) {
        String key = "sys-" + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        service.create(new DesignKitService.CreateKit(key, "Systems", null, "tailwind-default"), "test");
        service.saveDraft(key, new DesignKitService.SaveDraft(FullstackDesignKitTests.withBrand(service.draft(key).tokens(), brand), null, null, null));
        service.publish(key, "first", "test");
        return key;
    }

    private FullstackModelEntity model(String name, String snapshot) {
        FullstackModelEntity m = new FullstackModelEntity();
        m.setName(name);
        m.setSnapshot(snapshot);
        m.setCreatedBy("test");
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(m.getCreatedAt());
        return models.save(m);
    }

    private Map<String, String> generate(Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "systems");
        body.put("packageName", "com.menora.systems");
        body.put("bootVersion", "3.2.1");
        body.putAll(extra);
        body.put("entities", List.of(Map.of("name", "Note", "fields", List.of(
                Map.of("name", "id", "type", "Long", "primaryKey", true, "generated", true),
                Map.of("name", "title", "type", "String")))));
        byte[] zip = mvc.perform(post("/starter-fullstack.zip").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, String> files = new TreeMap<>();
        FullstackExampleRequests.unzip(zip).forEach((path, bytes) ->
                files.put(path.substring(path.indexOf('/') + 1), new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n")));
        return files;
    }

    private static byte[] zipOf(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }
}
