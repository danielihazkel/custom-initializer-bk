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
            assertThat(slots).as(set).containsKeys("src/shared/ui/Table.tsx", "src/shared/ui/widgets.tsx", "src/shared/ui/Badge.tsx")
                    .doesNotContainKeys("src/shared/ui/stats.ts", "src/shared/ui/index.ts");
            assertThat(slots.get("src/shared/ui/widgets.tsx").exports()).contains("KpiTile", "ReportChart", "PeriodSelect");
            for (KitSourceValidator.Slot slot : slots.values()) {
                String source = service.baseSource(kitOn(set), slot.path());
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
        assertThat(KitSourceValidator.check("src/shared/ui/kit/tone.ts", TONE, slots)).isEmpty();
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
        String key = kitOn("react-menora-digital-crud");
        String auth = "Bearer " + login();
        mvc.perform(get("/admin/design/kits/" + key + "/components").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseSetKey").value("react-menora-digital-crud"))
                .andExpect(jsonPath("$.slots[?(@.path == 'src/shared/ui/Table.tsx')].replaced").value(false));
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

    /** A new kit with a draft open, on the set's built-in kit. */
    private String kitOn(String set) {
        String key = "comp-" + System.nanoTime() % 1000000;
        service.create(new DesignKitService.CreateKit(key, "Components", null,
                set.equals("react-tailwind-crud") ? "tailwind-default" : "menora-digital"), "test");
        return key;
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
