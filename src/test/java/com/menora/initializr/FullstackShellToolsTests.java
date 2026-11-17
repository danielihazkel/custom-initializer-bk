package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 10, phase 8: the header's tools — {@code nav.userMenu}, {@code nav.notifications} and
 * {@code nav.commandPalette}. Each is off unless asked for, and a layout that asks for none keeps
 * its bytes (the golden manifests say so for the examples). Built for real by
 * {@code GeneratedFrontendBuildSmokeTests.fullstackFrontend*ShellTools*}.
 */
@IntegrationTest
class FullstackShellToolsTests {

    private static final String FE = "support/frontend/src/";
    private static final String BE = "support/backend/src/main/java/com/menora/support/";

    @Autowired
    private TestRestTemplate rest;

    /** The Approvals example — a workflow with an assignee, an inbox page, record pages — with the tools given. */
    static Map<String, Object> approvals(String frontendSet, String... tools) {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("approvals", frontendSet);
        // The example's own options: its home page has an activity feed, which needs audit.
        body.put("opts", Map.of("scaffold", List.of("audit", "optimisticLocking", "csvExport", "seedData")));
        Map<String, Object> nav = new LinkedHashMap<>();
        for (String tool : tools) nav.put(tool, true);
        if (!nav.isEmpty()) body.put("nav", nav);
        return body;
    }

    /** Orders and their lines — no workflow anywhere — on a layout of two lists. */
    private static Map<String, Object> plain(String... tools) {
        Map<String, Object> body = FullstackExampleRequests.oneToManyBody("react-tailwind-crud");
        body.put("artifactId", "support");
        body.put("packageName", "com.menora.support");
        List<Map<String, Object>> pages = new ArrayList<>();
        pages.add(new LinkedHashMap<>(Map.of("id", "orders", "type", "entity-list", "entity", "Order")));
        pages.add(new LinkedHashMap<>(Map.of("id", "order", "type", "record", "entity", "Order")));
        body.put("pages", pages);
        Map<String, Object> nav = new LinkedHashMap<>();
        for (String tool : tools) nav.put(tool, true);
        if (!nav.isEmpty()) body.put("nav", nav);
        return body;
    }

    private Map<String, String> generate(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, body);
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Map<String, String> files = new LinkedHashMap<>();
        FullstackExampleRequests.unzip(response.getBody()).forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
        return files;
    }

    @Test
    void theHeaderCarriesTheToolsTheLayoutAskedFor() throws Exception {
        Map<String, String> files = generate(approvals("react-tailwind-crud", "userMenu", "notifications", "commandPalette"));
        assertThat(files).containsKeys(FE + "app/shell/ShellTools.tsx", FE + "app/shell/CommandPalette.tsx",
                FE + "app/shell/NotificationBell.tsx", FE + "app/shell/UserMenu.tsx", FE + "app/shell/dismiss.ts");
        // One import and one element in the shell: the tools wrap its own theme switch.
        assertThat(files.get(FE + "app/App.tsx"))
                .contains("import { ShellTools } from '@app/shell/ShellTools'")
                .contains("          <ShellTools onNavigate={(page, arg, query) => go(page as View, arg, query)}>\n          <button\n            onClick={toggle}")
                .contains("          </button>\n          </ShellTools>\n");
        String tools = files.get(FE + "app/shell/ShellTools.tsx");
        // The palette: the nav's pages and the form that starts a claim; a claim opens on its record page.
        assertThat(tools)
                .contains("  { id: 'my-tasks', label: ")
                .contains("  { id: 'new-claim', label: ")
                .contains("    path: '/api/expense-claims',")
                .contains("    recordPage: 'claim',")
                // Pages limited to roles are offered to those roles only.
                .doesNotContain("{{");
        // The bell: the claims' queues, opened on the inbox page's own tabs.
        assertThat(tools)
                .contains("const BELL: BellSource[] = [")
                .contains("    page: 'my-tasks',")
                .contains("    queues: ['mine', 'unassigned', 'open'],")
                .contains("<NotificationBell sources={BELL} onNavigate={onNavigate} />")
                .contains("<UserMenu palette />");
        assertThat(files.get(FE + "shared/i18n/strings.ts"))
                .contains("  paletteOpen: 'Go to anything',").contains("  bellMine: 'Assigned to you',").contains("  signedInAs: 'Signed in as',")
                .contains("  paletteOpen: 'מעבר מהיר',");

        // The Menora shell takes them the same way, around its own theme switch.
        Map<String, String> menora = generate(approvals("react-menora-digital-crud", "userMenu", "notifications", "commandPalette"));
        assertThat(menora.get(FE + "app/App.tsx"))
                .contains("import { ShellTools } from '@app/shell/ShellTools'")
                .contains("          <ShellTools onNavigate={(page, arg, query) => go(page as View, arg, query)}>\n          <ThemeToggle ")
                .contains("/>\n          </ShellTools>\n");
        assertThat(menora.get(FE + "app/shell/ShellTools.tsx")).isEqualTo(tools);
    }

    @Test
    void eachToolIsItsOwnSetting() throws Exception {
        Map<String, String> files = generate(approvals("react-tailwind-crud", "commandPalette"));
        assertThat(files).containsKeys(FE + "app/shell/ShellTools.tsx", FE + "app/shell/CommandPalette.tsx")
                .doesNotContainKeys(FE + "app/shell/NotificationBell.tsx", FE + "app/shell/UserMenu.tsx", FE + "app/shell/dismiss.ts");
        assertThat(files.get(FE + "app/shell/ShellTools.tsx")).doesNotContain("NotificationBell").doesNotContain("UserMenu");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).contains("paletteOpen").doesNotContain("bellMine").doesNotContain("signedInAs");

        // None asked for: no shell folder, and the shell does not know the word.
        Map<String, String> none = generate(approvals("react-tailwind-crud"));
        assertThat(none.keySet()).noneMatch(path -> path.startsWith(FE + "app/shell/"));
        assertThat(none.get(FE + "app/App.tsx")).doesNotContain("ShellTools");
        assertThat(none.get(FE + "shared/i18n/strings.ts")).doesNotContain("paletteOpen").doesNotContain("bellMine").doesNotContain("signedInAs");
    }

    @Test
    void aUserMenuBringsTheCurrentUserWithoutAWorkflow() throws Exception {
        // No workflow: nothing answered /api/me, and nothing asked.
        Map<String, String> without = generate(plain());
        assertThat(without).doesNotContainKeys(BE + "web/CurrentUser.java", BE + "web/CurrentUserController.java", FE + "shared/api/me.ts");

        Map<String, String> with = generate(plain("userMenu"));
        assertThat(with).containsKeys(BE + "web/CurrentUser.java", BE + "web/CurrentUserController.java", FE + "shared/api/me.ts",
                FE + "app/shell/UserMenu.tsx", FE + "app/shell/dismiss.ts");
        assertThat(with.get(BE + "web/CurrentUserController.java")).contains("@RequestMapping(\"/api/me\")");
        // The palette alone needs no user.
        assertThat(generate(plain("commandPalette"))).doesNotContainKeys(BE + "web/CurrentUser.java", FE + "shared/api/me.ts");
        // Every backend file but those two is what it was.
        for (Map.Entry<String, String> file : without.entrySet()) {
            if (file.getKey().startsWith("support/backend/") && !file.getKey().endsWith("menora-model.json")) {
                assertThat(with.get(file.getKey())).as(file.getKey()).isEqualTo(file.getValue());
            }
        }
    }

    @Test
    void thePaletteSearchesWhatHasAPageToOpen() throws Exception {
        String tools = generate(plain("commandPalette")).get(FE + "app/shell/ShellTools.tsx");
        // Orders have a list and a record page; nothing else in the model has a page of its own.
        assertThat(tools)
                .contains("    path: '/api/orders',")
                .contains("    recordPage: 'order',")
                .contains("    listPage: 'orders',")
                .contains("    listPage: 'orders',");
        assertThat(tools.split("    path: '/api/", -1)).hasSize(2);
        // Without an ldap-auth dependency no page asks for a role, so the palette offers every page.
        // (With one — the sets' default — the pages of an entity ask what reading it asks.)
        Map<String, Object> open = plain("commandPalette");
        open.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        assertThat(generate(open).get(FE + "app/shell/ShellTools.tsx"))
                .contains("<CommandPalette pages={ PAGES } entities={ENTITIES} onNavigate={onNavigate} />")
                .doesNotContain("useRoles");
    }

    @Test
    void theBellNeedsAWorkflowThatAssignsItsTasks() {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, plain("notifications"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .contains("nav.notifications needs an entity whose workflow has an assigneeField")
                .contains("\"path\":\"nav.notifications\"");
    }
}
