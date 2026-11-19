package com.menora.initializr;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /starter-fullstack.zip}: direction, palettes, the Menora Digital set, the dev user header and the chrome language. */
@IntegrationTest
@Tag("slow") // left out of mvn test -Pquick
class FullstackThemeGenerationTests extends FullstackEndpointTestSupport {

    @Test
    void fullstackEndpoint_rtlOptionSetsDirAndMirrorsShell() throws Exception {
        // opts.scaffold=[rtl] flips the frontend isRtl flag: index.html gets dir="rtl"/lang="he"
        // and the shell/shared-UI templates use Tailwind logical utilities so the layout mirrors.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("opts", Map.of("scaffold", List.of("rtl")));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        String indexHtml = entries.get("shop/frontend/index.html");
        assertThat(indexHtml).contains("dir=\"rtl\"").contains("lang=\"he\"");

        // The shell uses logical utilities (converted from physical border-l/ml/left/text-right),
        // and the Toaster flips to the RTL-appropriate corner.
        String app = entries.get("shop/frontend/src/app/App.tsx");
        assertThat(app).contains("border-s-2").doesNotContain("border-l-2");
        assertThat(app).contains("position={ 'top-left' }");
        assertThat(entries.get("shop/frontend/src/shared/ui/Table.tsx"))
                .contains("sticky end-0").doesNotContain("sticky right-0");
    }

    @Test
    void fullstackEndpoint_defaultsToLtrIndexHtml() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());
        String indexHtml = entries.get("shop/frontend/index.html");
        assertThat(indexHtml).contains("lang=\"en\"").doesNotContain("dir=\"rtl\"");
        // Logical utilities are always emitted (they behave identically in LTR).
        assertThat(entries.get("shop/frontend/src/app/App.tsx"))
                .contains("position={ 'top-right' }");
    }

    @Test
    void fullstackEndpoint_themesFrontendWithMenoraPaletteByDefault() throws Exception {
        // No colorPalette in the request → the isDefault palette (menora-default: navy + gold)
        // is injected into the Tailwind v4 @theme block, and components reference the brand tokens.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        String css = entries.get("shop/frontend/src/index.css");
        assertThat(css)
                .contains("@theme")
                .contains("--color-brand:")
                .contains("#9A83F7")   // Menora purple (palette primary → brand)
                .contains("#2B2F4C")   // fixed navy shell (ink)
                .contains("#FEDB41");  // gold highlight (palette secondary)

        // Components reference the brand tokens, not the old emerald defaults.
        String app = entries.get("shop/frontend/src/app/App.tsx");
        assertThat(app).contains("bg-app-shell").contains("text-gold").contains("border-gold");
        assertThat(app).doesNotContain("emerald");
        // Primary actions use the semantic action tokens (aliased to brand + white text in this set).
        assertThat(css).contains("--color-primary:").contains("--color-on-primary:   #ffffff");
        assertThat(ListPages.of(entries, "shop/frontend/src/pages/user/ui/UserPage.tsx")).contains("bg-primary").contains("text-on-primary");
        assertThat(entries.get("shop/frontend/src/shared/ui/FormDrawer.tsx")).contains("bg-primary");
        // The default set does not pull in the Menora Digital design-system dep.
        assertThat(entries).doesNotContainKey("shop/frontend/public/menora-mivtachim-logo.png");
        assertThat(entries.keySet()).noneMatch(k -> k.startsWith("shop/frontend/src/shared/ui/menora/"));

        // Brand logo asset shipped and referenced in the sidebar + favicon.
        assertThat(entries).containsKey("shop/frontend/public/logo.png");
        assertThat(app).contains("/logo.png").doesNotContain("Database");
        assertThat(entries.get("shop/frontend/index.html")).contains("/logo.png");
    }

    @Test
    void fullstackEndpoint_menoraDigitalSetReskinsTheFrontend() throws Exception {
        // frontendTemplateSet=react-menora-digital-crud: the overlay re-points the Tailwind theme to
        // the Menora Digital tokens (yellow action pills with ink labels, purple identity, #f8f8f8
        // ground, Assistant font), swaps the shell for the white top-bar layout, and — because the
        // set is tagged MENORA_DIGITAL — the substrate lays down the design-menora-digital dep's
        // tokens/components/logo. Every other file is borrowed from react-tailwind-crud.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("frontendTemplateSet", "react-menora-digital-crud");
        body.put("opts", Map.of("scaffold", List.of("rtl")));
        body.put("entities", List.of(
                Map.of("name", "User", "fields", List.of(pkField())),
                Map.of("name", "Order", "fields", List.of(pkField()))));
        Map<String, String> entries = generateZip(body);

        String css = entries.get("shop/frontend/src/index.css");
        assertThat(css)
                .contains("@import \"tailwindcss\"")
                .contains("@import './shared/ui/menora/tokens.css'")
                .contains("--color-primary:      #ffc700")
                .contains("--color-on-primary:   #37374e")
                .contains("--color-brand:        #684eed")
                .contains("--color-canvas:       #f8f8f8")
                .contains("--radius-2xl:         30px")
                .contains("'Assistant'")
                // dark mode re-points the same tokens to the design system's dark values
                .contains("--color-brand:        #a393ff").contains("--color-canvas:       #1e1e2f")
                .doesNotContain("#9A83F7").doesNotContain("#2B2F4C");

        assertThat(entries.get("shop/frontend/package.json"))
                .contains("@fontsource/assistant").doesNotContain("@fontsource/inter");
        assertThat(entries.get("shop/frontend/src/main.tsx")).contains("@fontsource/assistant/500.css");

        // Shell: the design system's NavLinks / ThemeToggle / Footer, and NO floating chat launcher —
        // that is the marketing site's contact control, not an admin app's.
        String app = entries.get("shop/frontend/src/app/App.tsx");
        assertThat(app)
                .contains("/menora-mivtachim-logo.png")
                .contains("<NavLinks").contains("<ThemeToggle").contains("<Footer")
                .contains("UserPage").contains("OrderPage")
                .contains("position={ 'top-left' }")
                .doesNotContain("<ChatLauncher").doesNotContain("bg-app-shell");

        // Dashboard: the Hero (yellow full stop, no CTA) and an ActionPanel with one yellow disc per
        // entity — not a single primary pill bound to the first entity.
        String dashboard = entries.get("shop/frontend/src/pages/dashboard/ui/DashboardPage.tsx");
        assertThat(dashboard)
                .contains("<Hero").contains("<ActionPanel").contains("<SectionHeader")
                .contains("label: 'Users'").contains("label: 'Orders'")
                .contains("t('welcomeTo', { name: 'shop' }) + '.'")
                .doesNotContain("bg-primary px-8");

        // Authored shared UI wraps the design-system ports; borrowed files are present and unchanged.
        assertThat(entries.get("shop/frontend/src/shared/ui/Table.tsx"))
                .contains("onSortChange").contains("MenoraTable").contains("caption");
        assertThat(entries).containsKey("shop/frontend/src/shared/api/useResource.ts");
        assertThat(ListPages.of(entries, "shop/frontend/src/pages/user/ui/UserPage.tsx"))
                .contains("<SectionHeader title=\"Users\"").contains("caption=\"Users\"").contains("variant=\"primary\"");
        assertThat(entries.get("shop/frontend/src/shared/ui/FormDrawer.tsx")).contains("mn-btn--primary");
        // vite.config.ts is borrowed as a mustache row (sourceSet) and rendered, not copied raw.
        assertThat(entries.get("shop/frontend/vite.config.ts"))
                .contains("base: '/'").contains("target: 'http://localhost:8080'").doesNotContain("{{");

        // Design-system substrate files + brand mark; the standalone landing page is still removed.
        assertThat(entries).containsKey("shop/frontend/src/shared/ui/menora/tokens.css");
        assertThat(entries).containsKey("shop/frontend/src/shared/ui/menora/ChatLauncher.tsx");
        assertThat(entries).containsKey("shop/frontend/public/menora-mivtachim-logo.png");
        assertThat(entries.keySet()).noneMatch(k -> k.startsWith("shop/frontend/src/pages/home/"));
        assertThat(entries.get("shop/frontend/index.html")).contains("dir=\"rtl\"");
    }

    @Test
    void fullstackEndpoint_appliesSelectedColorPalette() throws Exception {
        // An explicit colorPalette flows through to the generated theme (forest primary = #2e7d32).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("colorPalette", "forest");
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());
        assertThat(entries.get("shop/frontend/src/index.css"))
                .contains("#2e7d32")          // forest primary injected
                .doesNotContain("#9A83F7");   // not the Menora default purple
    }

    @Test
    void fullstackEndpoint_injectsDevUserinfoHeaderWhenLdapAuthSelected() throws Exception {
        // With the ldap-auth backend dep, the generated API client adds the `userinfo` header in
        // dev (read by the backend's @RequiresPermission aspect) and .env.development carries the
        // overridable default.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "secured");
        body.put("packageName", "com.menora.secured");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "ldap-auth"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        String client = entries.get("secured/frontend/src/shared/api/client.ts");
        assertThat(client)
                .contains("import.meta.env.DEV")
                .contains("headers['userinfo']")
                .contains("VITE_DEV_USERINFO");
        assertThat(entries.get("secured/frontend/.env.development")).contains("VITE_DEV_USERINFO=dev-user");

        // Backend got the LDAP authorization scaffold + deps.
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/security/PermissionAspect.java"));
        assertThat(entries.get("secured/backend/pom.xml")).contains("lts.ldap.util");
    }

    @Test
    void fullstackEndpoint_omitsDevUserinfoHeaderWithoutLdapAuth() throws Exception {
        // Deps without ldap-auth (explicit, since ldap-auth is now a set default) → the header
        // block is not emitted.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "plain");
        body.put("packageName", "com.menora.plain");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        assertThat(entries.get("plain/frontend/src/shared/api/client.ts"))
                .doesNotContain("userinfo");
        assertThat(entries.get("plain/frontend/.env.development")).doesNotContain("VITE_DEV_USERINFO");
    }

    // ── Chrome-string locale ──────────────────────────────────────────────────

    @Test
    void fullstackEndpoint_defaultsChromeStringsToEnglish() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Task", "fields", List.of(pkField(), Map.of("name", "title", "type", "String")))));
        Map<String, String> entries = generateZip(body);

        // One strings module ships both tables; the generated locale picks the English one.
        String strings = entries.get("shop/frontend/src/shared/i18n/strings.ts");
        assertThat(strings)
                .contains("export const LOCALE = 'en'")
                .contains("const S: Record<StringKey, string> = en")
                .contains("dashboard: 'Dashboard'")
                .contains("dashboard: 'לוח בקרה'")
                .contains("export function t(key: StringKey, params?: Record<string, string | number>): string");
        assertThat(entries.get("shop/frontend/src/shared/i18n/index.ts"))
                .contains("export { t, LOCALE, type StringKey } from './strings'");
        // Chrome text is read through t(), never inlined, so a locale flip touches one file.
        assertThat(entries.get("shop/frontend/src/app/App.tsx"))
                .contains("import { t } from '@shared/i18n'")
                .contains("label: t('dashboard')")
                .doesNotContain("'Dashboard'");
        assertThat(ListPages.of(entries, "shop/frontend/src/pages/task/ui/TaskPage.tsx"))
                .contains("t('newX', { x: 'Task' })")
                .contains("t(list.totalElements === 1 ? 'record' : 'records')")
                .doesNotContain("New Task");
        assertThat(entries.get("shop/frontend/src/shared/ui/Table.tsx"))
                .contains("import { t } from '../i18n'")
                .contains("placeholder={t('search')}");
        assertThat(entries.get("shop/frontend/src/shared/ui/CalendarView.tsx"))
                .contains("new Intl.DateTimeFormat(LOCALE, { weekday: 'short' })")
                .doesNotContain("'January'");
        // The form only imports the strings module when it actually reads a string (generated-PK
        // hint here); Task has no boolean/enum/relation so that hint is the sole use.
        assertThat(entries.get("shop/frontend/src/features/task-form/ui/TaskForm.tsx"))
                .contains("import { t } from '@shared/i18n'")
                .contains("hint={t('systemGenerated')}");
    }

    @Test
    void fullstackEndpoint_hebrewLocaleRendersHebrewChrome() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("locale", "he");
        body.put("frontendTemplateSet", "react-menora-digital-crud");
        body.put("entities", List.of(
                Map.of("name", "Task", "fields", List.of(pkField(), Map.of("name", "title", "type", "String")))));
        Map<String, String> entries = generateZip(body);

        String strings = entries.get("shop/frontend/src/shared/i18n/strings.ts");
        assertThat(strings)
                .contains("export const LOCALE = 'he'")
                .contains("const S: Record<StringKey, string> = he")
                .contains("dashboard: 'לוח בקרה'")
                .contains("newX: '{x} חדש'");
        // The Menora-authored shell and dashboard go through t() like the default set's.
        assertThat(entries.get("shop/frontend/src/app/App.tsx"))
                .contains("import { t } from '@shared/i18n'")
                .contains("ariaLabel={t('main')}")
                .contains("label: t('dashboard')");
        assertThat(entries.get("shop/frontend/src/pages/dashboard/ui/DashboardPage.tsx"))
                .contains("t('welcomeTo', { name: 'shop' })")
                .contains("title={t('collections')}");
        // `locale` is independent of `rtl`: no rtl opt → the document stays LTR.
        assertThat(entries.get("shop/frontend/index.html")).doesNotContain("dir=\"rtl\"");
    }

    @Test
    void fullstackEndpoint_rejectsUnknownLocale() {
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "demo");
            b.put("bootVersion", "3.2.1");
            b.put("locale", "fr");
            b.put("entities", List.of(Map.of("name", "Task", "fields", List.of(pkField()))));
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("locale 'fr' is not supported").contains("en, he");
    }
}
