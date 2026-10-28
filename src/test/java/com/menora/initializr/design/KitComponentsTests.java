package com.menora.initializr.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.FullstackExampleRequests;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A kit's own component files (P3): the base sets' components are the slots and pass their own
 * rules, a draft may replace one (keeping its exports) or add a helper, and a zip generated with
 * the kit carries them — a slot only where the set wrote it.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class KitComponentsTests {

    public static final String BADGE = """
            import { clsx } from 'clsx'
            import type { ReactNode } from 'react'
            import { tone } from './kit/tone'

            // A pill with the kit's own look.
            export function Badge({ children, className }: { children: ReactNode; tone?: string; className?: string }) {
              return <span data-kit-badge className={clsx(tone(), className)} style={{ letterSpacing: '0.02em' }}>{children}</span>
            }
            """;
    public static final String TONE = "export function tone(): string {\n  return 'rounded-full px-2 text-xs font-semibold'\n}\n";

    /** Keeps the keys of the kits a run creates apart (nanoTime alone repeats on a coarse clock). */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired
    private DesignKitService service;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    @Test
    void everyComponentOfBothSetsPassesTheRulesAsItIs() {
        for (String set : List.of("react-tailwind-crud", "react-menora-digital-crud")) {
            Map<String, KitSourceValidator.Slot> slots = service.slots(set);
            String kit = kitOn(set);
            assertThat(slots).as(set).containsKeys("src/shared/ui/Table.tsx", "src/shared/ui/widgets.tsx", "src/shared/ui/Badge.tsx")
                    .doesNotContainKeys("src/shared/ui/stats.ts", "src/shared/ui/index.ts");
            assertThat(slots.get("src/shared/ui/widgets.tsx").exports()).contains("KpiTile", "ReportChart", "PeriodSelect");
            for (KitSourceValidator.Slot slot : slots.values()) {
                String source = service.baseSource(kit, slot.path());
                assertThat(KitSourceValidator.check(slot.path(), source, slots)).as(set + " " + slot.path()).isEmpty();
            }
        }
    }

    @Test
    void theRulesRefuseWhatAKitMayNotDo() {
        Map<String, KitSourceValidator.Slot> slots = service.slots("react-tailwind-crud");
        String path = "src/shared/ui/Badge.tsx";
        assertThat(KitSourceValidator.check("src/app/App.tsx", "export default 1", slots).get(0)).contains("may replace a component");
        assertThat(KitSourceValidator.check("src/shared/ui/kit/x.js", "export const a = 1", slots)).isNotEmpty();
        assertThat(KitSourceValidator.check(path, BADGE.replace("export function Badge", "export function Pill"), slots))
                .anySatisfy(p -> assertThat(p).contains("import Badge from it"))
                .anySatisfy(p -> assertThat(p).contains("exports Pill"));
        assertThat(KitSourceValidator.check(path, BADGE + "\nexport const load = () => fetch('/api/x')\n", slots))
                .anySatisfy(p -> assertThat(p).contains("calls fetch()"));
        assertThat(String.join("\n", KitSourceValidator.check(path, BADGE.replace("return <span", "return <div dangerouslySetInnerHTML={{ __html: x }} /> || <span"), slots)))
                .contains("writes raw HTML");
        assertThat(String.join("\n", KitSourceValidator.check(path, "import axios from 'axios'\nimport { App } from '../../app/App'\n" + BADGE, slots)))
                .contains("imports 'axios'").contains("imports '../../app/App'");
        assertThat(KitSourceValidator.check(path, BADGE.replace("import { clsx }", "const f = () => import('./x')\nimport { clsx }"), slots))
                .anySatisfy(p -> assertThat(p).contains("import()"));
        // Words in comments are not code; strings are kept whole.
        assertThat(KitSourceValidator.check(path, "// no fetch() here, no eval()\n" + BADGE, slots)).isEmpty();
        // A string that says "import" is not an import.
        assertThat(KitSourceValidator.check(path, BADGE.replace("{children}</span>", "{children}{String(['import', \"import\"])}</span>"), slots)).isEmpty();
        assertThat(KitSourceValidator.check("src/shared/ui/kit/tone.ts", TONE, slots)).isEmpty();
        // Helpers live in a folder of their own (any lower-case name), stylesheets included.
        assertThat(KitSourceValidator.check("src/shared/ui/brand/tone.ts", TONE, slots)).isEmpty();
        assertThat(KitSourceValidator.check("src/shared/ui/brand/brand.css", ".x { color: red }", slots)).isEmpty();
        assertThat(KitSourceValidator.check("src/shared/ui/brand/brand.css", "@import 'x.css';", slots))
                .anySatisfy(p -> assertThat(p).contains("may not @import"));
        assertThat(KitSourceValidator.check("src/shared/ui/brand/brand.css", ".x { background: url(https://cdn.example/x.png) }", slots))
                .anySatisfy(p -> assertThat(p).contains("remote URLs"));
        assertThat(KitSourceValidator.check("src/shared/ui/Brand/tone.ts", TONE, slots)).isNotEmpty();
        assertThat(KitSourceValidator.check("src/shared/ui/tone.ts", TONE, slots)).isNotEmpty();
        assertThat(KitSourceValidator.importAllowed("src/shared/ui/kit/a/b.ts", "../../../api")).isTrue();
        assertThat(KitSourceValidator.importAllowed("src/shared/ui/Badge.tsx", "@shared/i18n")).isTrue();
    }

    @Test
    void aDraftReplacesASlotAndAddsAHelperAndTheZipCarriesThem() throws Exception {
        String key = kitOn("react-tailwind-crud");
        assertThatThrownBy(() -> service.putComponentFile(key, "src/shared/ui/Badge.tsx", "export const x = 1"))
                .isInstanceOf(DesignKitException.class);
        service.putComponentFile(key, "src/shared/ui/kit/tone.ts", TONE);
        service.putComponentFile(key, "src/shared/ui/Badge.tsx", BADGE);
        // InlineCell is written only with the inlineEdit opt: replaced, it stays out without it.
        String inline = service.baseSource(key, "src/shared/ui/InlineCell.tsx");
        service.putComponentFile(key, "src/shared/ui/InlineCell.tsx", "// kit\n" + inline);

        DesignKitService.Components c = service.components(key);
        assertThat(c.slots()).filteredOn(DesignKitService.SlotView::replaced).extracting(DesignKitService.SlotView::path)
                .containsExactly("src/shared/ui/Badge.tsx", "src/shared/ui/InlineCell.tsx");
        assertThat(c.helpers()).extracting(DesignKitService.FileView::path).containsExactly("src/shared/ui/kit/tone.ts");
        assertThat(service.draft(key).files()).hasSize(3);
        service.publish(key, "components", "test");
        assertThat(service.diff(key, "1", "1").files()).isEmpty();

        Map<String, String> files = generate(key, List.of());
        assertThat(files.get("frontend/src/shared/ui/Badge.tsx")).isEqualTo(BADGE);
        assertThat(files.get("frontend/src/shared/ui/kit/tone.ts")).isEqualTo(TONE);
        assertThat(files).doesNotContainKey("frontend/src/shared/ui/InlineCell.tsx");
        assertThat(generate(key, List.of("inlineEdit")).get("frontend/src/shared/ui/InlineCell.tsx")).startsWith("// kit\n");

        // Reset to the base: the next version writes the set's own Badge again.
        service.openDraft(key, null, "test");
        service.deleteComponentFile(key, "src/shared/ui/Badge.tsx");
        service.publish(key, "back to the base Badge", "test");
        assertThat(generate(key, List.of()).get("frontend/src/shared/ui/Badge.tsx")).isEqualTo(service.baseSource(key, "src/shared/ui/Badge.tsx").replace("\r\n", "\n"));
        assertThat(service.diff(key, "1", "2").files()).containsExactly(Map.of("path", "src/shared/ui/Badge.tsx", "change", "removed"));
    }

    @Test
    void theStudioApiListsReadsWritesAndResetsComponents() throws Exception {
        String key = kitOn("react-tailwind-crud");
        String auth = "Bearer " + login();
        mvc.perform(get("/admin/design/kits/" + key + "/components").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseSetKey").value("react-tailwind-crud"))
                .andExpect(jsonPath("$.slots[?(@.path == 'src/shared/ui/Table.tsx')].replaced").value(false));
        // A clone of the Menora kit starts with the Menora components as its own.
        String menora = "comp-mn-" + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        service.create(new DesignKitService.CreateKit(menora, "Menora clone", null, "menora-digital"), "test");
        mvc.perform(get("/admin/design/kits/" + menora + "/components").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[?(@.path == 'src/shared/ui/Table.tsx')].replaced").value(true))
                .andExpect(jsonPath("$.helpers.length()").value(22));
        String base = json.readTree(mvc.perform(get("/admin/design/kits/" + key + "/components/base").param("path", "src/shared/ui/Badge.tsx").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content").asText();
        assertThat(base).contains("export function Badge");

        // A refused file answers with its problems.
        mvc.perform(put("/admin/design/kits/" + key + "/draft/files").param("path", "src/shared/ui/Badge.tsx").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("content", "export const nope = fetch('/x')"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.problems.length()").value(org.hamcrest.Matchers.greaterThan(1)));
        mvc.perform(put("/admin/design/kits/" + key + "/draft/files").param("path", "src/shared/ui/Badge.tsx").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("content", "// mine\n" + base))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SLOT"));
        JsonNode file = json.readTree(mvc.perform(get("/admin/design/kits/" + key + "/files").param("path", "src/shared/ui/Badge.tsx").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(file.get("content").asText()).startsWith("// mine\n");
        mvc.perform(delete("/admin/design/kits/" + key + "/draft/files").param("path", "src/shared/ui/Badge.tsx").header("Authorization", auth))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/design/kits/" + key + "/files").param("path", "src/shared/ui/Badge.tsx").header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/design/kits/" + key + "/components")).andExpect(status().isUnauthorized());
    }

    @Test
    void theMenoraKitIsTheDefaultSetWithItsOwnComponents() throws Exception {
        DesignKitService.Components c = service.components("menora-digital");
        assertThat(c.baseSetKey()).isEqualTo("react-tailwind-crud");
        assertThat(c.slots()).filteredOn(DesignKitService.SlotView::replaced).extracting(DesignKitService.SlotView::path)
                .contains("src/shared/ui/Shell.tsx", "src/shared/ui/ListHeader.tsx", "src/shared/ui/Table.tsx", "src/shared/ui/links.tsx", "src/shared/ui/ScreenHeader.tsx")
                .hasSize(16);
        assertThat(c.helpers()).extracting(DesignKitService.FileView::path)
                .contains("src/shared/ui/menora/index.ts", "src/shared/ui/menora/Button.tsx", "src/shared/ui/menora/tokens.css")
                .hasSize(22);
        // The set that stands for the kit generates as the kit — no design-system dependency in
        // the substrate: the kit brings the ports, the stylesheets, the font and the logo.
        Map<String, Object> body = new LinkedHashMap<>(Map.of("artifactId", "kitted", "packageName", "com.menora.kitted",
                "bootVersion", "3.2.1", "frontendTemplateSet", "react-menora-digital-crud"));
        body.put("entities", List.of(Map.of("name", "Note", "fields", List.of(
                Map.of("name", "id", "type", "Long", "primaryKey", true, "generated", true),
                Map.of("name", "title", "type", "String")))));
        Map<String, String> bySet = zip(body);
        body.remove("frontendTemplateSet");
        body.put("designKit", "menora-digital");
        Map<String, String> byKit = zip(body);
        assertThat(bySet.keySet()).isEqualTo(byKit.keySet());
        bySet.forEach((path, content) -> {
            if (!path.endsWith("menora-model.json")) assertThat(byKit.get(path)).as(path).isEqualTo(content);
        });
        assertThat(bySet.get("frontend/src/shared/ui/Shell.tsx")).contains("useMenoraTheme");
        assertThat(bySet.get("frontend/src/index.css")).contains("@import './shared/ui/menora/tokens.css';");
        assertThat(bySet).containsKeys("frontend/src/shared/ui/menora/components.css", "frontend/public/menora-mivtachim-logo.png");
        // …and a custom kit on the same base set may be named with it.
        String key = kitOn("react-tailwind-crud");
        service.publish(key, "first", "test");
        body.put("designKit", key);
        body.put("frontendTemplateSet", "react-menora-digital-crud");
        assertThat(zip(body).get("frontend/src/shared/ui/Shell.tsx")).doesNotContain("useMenoraTheme");
    }

    @Test
    void tryGeneratesEveryExampleWithTheKit() throws Exception {
        String auth = "Bearer " + login();
        String key = kitOn("react-tailwind-crud");
        service.putComponentFile(key, "src/shared/ui/kit/tone.ts", TONE);
        service.putComponentFile(key, "src/shared/ui/Badge.tsx", BADGE);
        JsonNode report = json.readTree(mvc.perform(post("/admin/design/try").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"designKit\":\"" + key + "@draft\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(report.get("designKit").asText()).isEqualTo(key + "@draft");
        assertThat(report.get("baseSetKey").asText()).isEqualTo("react-tailwind-crud");
        JsonNode results = report.get("results");
        int examples = FullstackExampleRequests.examples().size();
        int layouts = (int) FullstackExampleRequests.examples().stream().filter(FullstackExampleRequests::hasPages).count();
        assertThat(results).hasSize(examples + layouts);
        for (JsonNode r : results) {
            assertThat(r.get("ok").asBoolean()).as(r.get("exampleId").asText() + " " + r.get("error")).isTrue();
            assertThat(r.get("files").asInt()).isGreaterThan(50);
            assertThat(r.get("kitFiles").toString()).contains("src/shared/ui/Badge.tsx").contains("src/shared/ui/kit/tone.ts");
        }
        // The Menora kit tries in Hebrew, right to left, writing its own shell everywhere.
        JsonNode menora = json.readTree(mvc.perform(post("/admin/design/try").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"designKit\":\"menora-digital\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode r : menora.get("results")) {
            assertThat(r.get("ok").asBoolean()).as(r.toString()).isTrue();
            assertThat(r.get("kitFiles").toString()).contains("src/shared/ui/Shell.tsx").contains("src/shared/ui/menora/index.ts");
        }
        mvc.perform(post("/admin/design/try").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"designKit\":\"nope\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/design/try").contentType(MediaType.APPLICATION_JSON).content("{\"designKit\":\"menora-digital\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** A new kit with a draft open, on the set's built-in kit. */
    private String kitOn(String set) {
        String key = "comp-" + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        service.create(new DesignKitService.CreateKit(key, "Components", null,
                set.equals("react-tailwind-crud") ? "tailwind-default" : "menora-digital"), "test");
        return key;
    }

    /** The zip of a fullstack request, by path below the artifact folder (line endings as LF). */
    private Map<String, String> zip(Map<String, Object> body) throws Exception {
        byte[] zip = mvc.perform(post("/starter-fullstack.zip").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, String> files = new TreeMap<>();
        FullstackExampleRequests.unzip(zip).forEach((path, bytes) ->
                files.put(path.substring(path.indexOf('/') + 1), new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n")));
        return files;
    }

    private Map<String, String> generate(String kit, List<String> scaffold) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "kitted");
        body.put("packageName", "com.menora.kitted");
        body.put("bootVersion", "3.2.1");
        body.put("designKit", kit);
        body.put("opts", Map.of("scaffold", scaffold));
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

    private String login() throws Exception {
        String body = mvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }
}
