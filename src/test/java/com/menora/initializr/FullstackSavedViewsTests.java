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
 * Round 10, phase 9: the {@code savedViews} scaffold option — named views of a list, kept on the
 * server per user. What is generated, and for which entities. The endpoints are driven for real
 * by {@code GeneratedBackendBuildSmokeTests.liveApiOfABackendWithEveryOptBehaves}; the menu is
 * built by {@code GeneratedFrontendBuildSmokeTests.fullstackFrontend*SavedViews*}.
 */
@IntegrationTest
class FullstackSavedViewsTests {

    private static final String BE = "shop/backend/src/main/java/com/menora/shop/";
    private static final String FE = "shop/frontend/src/";

    @Autowired
    private TestRestTemplate rest;

    static Map<String, Object> body(String frontendSet, String... scaffold) {
        Map<String, Object> body = FullstackExampleRequests.oneToManyBody(frontendSet);
        body.put("opts", Map.of("scaffold", List.of(scaffold)));
        return body;
    }

    @SuppressWarnings("unchecked")
    private static void opt(Map<String, Object> body, String entityName, String key, boolean value) {
        List<Map<String, Object>> entities = new ArrayList<>();
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            Map<String, Object> copy = new LinkedHashMap<>(entity);
            if (entityName.equals(copy.get("name"))) copy.put("opts", Map.of(key, value));
            entities.add(copy);
        }
        body.put("entities", entities);
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
    void theBackendKeepsViewsPerUser() throws Exception {
        Map<String, String> files = generate(body("react-tailwind-crud", "savedViews"));
        assertThat(files).containsKeys(BE + "views/SavedView.java", BE + "views/SavedViewRepository.java",
                BE + "views/SavedViewService.java", BE + "views/SavedViewController.java");
        assertThat(files.get(BE + "views/SavedView.java"))
                .contains("package com.menora.shop.views;")
                .contains("@Table(name = \"app_saved_view\",")
                // Its name is its owner's alone, per list.
                .contains("columnNames = {\"list_name\", \"owner_id\", \"view_name\"}");
        // A view can only be of a list that was generated with the option.
        assertThat(files.get(BE + "views/SavedViewService.java"))
                .contains("private static final Set<String> LISTS = Set.of(\"customer\", \"product\", \"order\", \"order-line\", \"account\", \"transfer\", \"employee\");")
                .contains("repository.findByIdAndOwner(id, owner)");
        assertThat(files.get(BE + "views/SavedViewController.java"))
                .contains("@RequestMapping(\"/api/views\")")
                .contains("@RequestHeader(value = \"userinfo\", required = false) String header");
        // The Lombok set borrows the package.
        Map<String, Object> lombok = body("react-tailwind-crud", "savedViews");
        lombok.put("backendTemplateSet", "spring-jpa-crud-lombok");
        assertThat(generate(lombok).get(BE + "views/SavedViewService.java")).isEqualTo(files.get(BE + "views/SavedViewService.java"));
    }

    @Test
    void theListPageOffersItsViews() throws Exception {
        Map<String, String> files = generate(body("react-tailwind-crud", "savedViews"));
        assertThat(files).containsKeys(FE + "shared/ui/savedViews.ts", FE + "shared/ui/ViewsMenu.tsx");
        assertThat(ListPages.of(files, FE + "pages/order/ui/OrderPage.tsx"))
                .contains("import { ViewsMenu } from '@shared/ui/ViewsMenu'")
                .contains("import type { ViewState } from '@shared/ui/savedViews'")
                .contains("  const applyView = (state: ViewState) => {")
                .contains("<ViewsMenu list=\"order\" current={list.viewState} ")
                // The classic shell's list always opens plain: the user's default view applies.
                .contains("opensPlain={ true } />");
        // The dev user travels with the calls, so a view has an owner in development too.
        assertThat(files.get(FE + "shared/i18n/strings.ts")).contains("  views: 'Views',").contains("  views: 'תצוגות',");

        Map<String, String> menora = generate(body("react-menora-digital-crud", "savedViews", "csvExport"));
        assertThat(ListPages.of(menora, FE + "pages/order/ui/OrderPage.tsx"))
                .contains("<ViewsMenu list=\"order\" current={list.viewState} ")
                .contains("exportCsv('orders.csv')");
        assertThat(menora.get(FE + "shared/ui/ViewsMenu.tsx")).isEqualTo(files.get(FE + "shared/ui/ViewsMenu.tsx"));
    }

    @Test
    void anEntityTakesOrLeavesItOnItsOwn() throws Exception {
        Map<String, Object> off = body("react-tailwind-crud", "savedViews");
        opt(off, "Product", "savedViews", false);
        Map<String, String> files = generate(off);
        assertThat(ListPages.of(files, FE + "pages/product/ui/ProductPage.tsx")).doesNotContain("ViewsMenu").doesNotContain("applyView");
        assertThat(files.get(BE + "views/SavedViewService.java")).contains("Set.of(\"customer\", \"order\", \"order-line\", \"account\", \"transfer\", \"employee\");");

        Map<String, Object> on = body("react-tailwind-crud");
        opt(on, "Product", "savedViews", true);
        files = generate(on);
        assertThat(files.get(BE + "views/SavedViewService.java")).contains("Set.of(\"product\");");
        assertThat(ListPages.of(files, FE + "pages/product/ui/ProductPage.tsx")).contains("ViewsMenu");
        assertThat(ListPages.of(files, FE + "pages/order/ui/OrderPage.tsx")).doesNotContain("ViewsMenu");

        // Not asked for at all: nothing of it.
        files = generate(body("react-tailwind-crud", "csvExport"));
        assertThat(files.keySet()).noneMatch(path -> path.contains("/views/") || path.endsWith("savedViews.ts") || path.endsWith("ViewsMenu.tsx"));
        assertThat(files.get(FE + "shared/i18n/strings.ts")).doesNotContain("viewName");
    }

    @Test
    void aHandWrittenDatasourceConfigScansTheViewsPackage() throws Exception {
        // Every database but the default H2 names the packages it scans.
        Map<String, Object> body = body("react-tailwind-crud", "savedViews");
        body.put("dependencies", List.of("web", "data-jpa", "postgresql", "validation"));
        Map<String, String> files = generate(body);
        String config = files.entrySet().stream()
                .filter(e -> e.getKey().startsWith("shop/backend/") && e.getKey().endsWith("Config.java") && e.getValue().contains("EnableJpaRepositories"))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
        assertThat(config).contains("com.menora.shop.views");
    }
}
