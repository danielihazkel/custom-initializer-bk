package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 10, phase 10: the {@code tree} page type — an entity's rows as an outline, each under the
 * row its self-reference names. Built for real by
 * {@code GeneratedFrontendBuildSmokeTests.fullstackFrontend*TreePage*}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class FullstackTreePageTests {

    private static final String FE = "catalog/frontend/src/";

    @Autowired
    private TestRestTemplate rest;

    /** Categories that sit under one another, on a layout of a list and a tree of them. */
    static Map<String, Object> body(String frontendSet, boolean recordPage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "catalog");
        body.put("packageName", "com.menora.catalog");
        body.put("bootVersion", "3.2.1");
        body.put("frontendTemplateSet", frontendSet);
        body.put("entities", new ArrayList<>(List.of(category(relation("parent")))));
        List<Map<String, Object>> pages = new ArrayList<>();
        pages.add(page("categories", "entity-list", "Category"));
        pages.add(page("outline", "tree", "Category"));
        if (recordPage) pages.add(page("category", "record", "Category"));
        body.put("pages", pages);
        return body;
    }

    @SafeVarargs
    private static Map<String, Object> category(Map<String, Object>... relations) {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("name", "Category");
        category.put("fields", List.of(
                Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "name", "type", "STRING", "required", true)));
        category.put("relations", List.of(relations));
        return category;
    }

    private static Map<String, Object> relation(String fieldName) {
        return new LinkedHashMap<>(Map.of("fieldName", fieldName, "targetEntity", "Category", "type", "MANY_TO_ONE"));
    }

    private static Map<String, Object> page(String id, String type, String entity) {
        return new LinkedHashMap<>(Map.of("id", id, "type", type, "entity", entity));
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

    private String refusal(Map<String, Object> body) {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    @Test
    void theRowsAreDrawnUnderTheRowTheyBelongTo() throws Exception {
        Map<String, String> files = generate(body("react-tailwind-crud", false));
        String screen = files.get(FE + "app/screens/OutlineScreen.tsx");
        assertThat(screen)
                // The link is the entity's only relation to itself: the row carries the parent's id.
                .contains("    const up = node.row.parentId\n")
                // One call, in name order, so each branch lists its rows that way.
                .contains("`/api/categories?size=${LIMIT}&sort=name,asc`")
                .contains("const labelOf = (r: Category): string => r.name == null || r.name === '' ? '#' + String(r.id) : String(r.name)")
                // No record page: a row opens the quick look.
                .contains("import { CategoryDetail } from '@features/category-form'")
                .contains("const openRow = (row: Category) => setDetail(row)")
                .contains("export function OutlineScreen() {");
        assertThat(files.get(FE + "app/App.tsx")).contains("import { OutlineScreen } from '@app/screens/OutlineScreen'");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).contains("  expandAll: 'Expand all',").contains("  expandAll: 'הרחבת הכול',");

        // With a record page a row opens there instead.
        String linked = generate(body("react-tailwind-crud", true)).get(FE + "app/screens/OutlineScreen.tsx");
        assertThat(linked)
                .contains("const openRow = (row: Category) => onNavigate('category', keyOf(row))")
                .doesNotContain("DetailDrawer");
        // The Menora set borrows the screen.
        assertThat(generate(body("react-menora-digital-crud", false)).get(FE + "app/screens/OutlineScreen.tsx")).isEqualTo(screen);
    }

    @Test
    void aLayoutWithoutATreeHasNoneOfIt() throws Exception {
        Map<String, Object> body = body("react-tailwind-crud", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) body.get("pages");
        pages.removeIf(p -> "tree".equals(p.get("type")));
        Map<String, String> files = generate(body);
        assertThat(files.keySet()).noneMatch(path -> path.endsWith("OutlineScreen.tsx"));
        assertThat(files.get(FE + "shared/i18n/strings.ts")).doesNotContain("expandAll");
    }

    @Test
    void aTreeNeedsOneLinkOfTheEntityToItself() {
        // No relation to itself: there is nothing to draw the tree by.
        Map<String, Object> flat = body("react-tailwind-crud", false);
        flat.put("entities", List.of(category()));
        assertThat(refusal(flat)).contains("Category has no relation to itself").contains("pages[1].entity");

        // Two of them: the page has to say which.
        Map<String, Object> two = body("react-tailwind-crud", false);
        two.put("entities", List.of(category(relation("parent"), relation("replacedBy"))));
        assertThat(refusal(two)).contains("points at itself through parent and replacedBy").contains("pages[1].via");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) two.get("pages");
        pages.get(1).put("via", "ReplacedBy");
        ResponseEntity<byte[]> named = FullstackExampleRequests.post(rest, two);
        assertThat(named.getStatusCode()).isEqualTo(HttpStatus.OK);

        // A name that is not such a link is refused; so is 'via' on a page that has no use for it.
        pages.get(1).put("via", "name");
        assertThat(refusal(two)).contains("via 'name' is not a relation of Category to itself");
        pages.get(1).remove("via");
        pages.get(0).put("via", "parent");
        assertThat(refusal(two)).contains("does not take 'via'");
    }
}
