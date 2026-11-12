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
 * Round 10, phase 10: the {@code gallery} list view — an entity's rows as tiles of their picture,
 * for an entity with an image field. Built for real by
 * {@code GeneratedFrontendBuildSmokeTests.fullstackFrontend*RichFields*}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class FullstackGalleryViewTests {

    private static final String FE = "rota/frontend/src/";

    @Autowired
    private TestRestTemplate rest;

    /** The field-types model (a Shift has a photo), its list offered in the given views. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withViews(String frontendSet, String... views) {
        Map<String, Object> body = FullstackExampleRequests.fieldTypesBody(frontendSet);
        Map<String, Object> shift = new LinkedHashMap<>(((List<Map<String, Object>>) body.get("entities")).get(0));
        shift.put("listViews", List.of(views));
        body.put("entities", List.of(shift));
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
    void anEntityWithAPictureCanBeListedAsAGallery() throws Exception {
        Map<String, String> files = generate(withViews("react-tailwind-crud", "table", "gallery"));
        assertThat(files).containsKey(FE + "shared/ui/GalleryView.tsx");
        assertThat(files.get(FE + "pages/shift/ui/ShiftPage.tsx"))
                .contains("import { GalleryView } from '@shared/ui/GalleryView'")
                // A switch between the two views, and the gallery drawn from the first image field.
                .contains("              onClick={() => setViewMode('gallery')}")
                .contains("viewMode === 'gallery' ? (\n"
                        + "        <GalleryView\n")
                .contains("          imageField=\"photo\"\n"
                        + "          imageLabel={'Photo'}\n")
                .contains("useState<'table' | 'gallery'>");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).contains("  galleryView: 'Gallery view',").contains("  galleryView: 'תצוגת גלריה',");

        // The Menora set lists it among its view tabs and borrows the view.
        Map<String, String> menora = generate(withViews("react-menora-digital-crud", "gallery", "table"));
        assertThat(menora.get(FE + "pages/shift/ui/ShiftPage.tsx"))
                .contains("{ id: 'gallery', label: t('galleryView') }")
                .contains("        <GalleryView\n");
        assertThat(menora.get(FE + "shared/ui/GalleryView.tsx")).isEqualTo(files.get(FE + "shared/ui/GalleryView.tsx"));
    }

    @Test
    void withoutAPictureThereIsNoGallery() throws Exception {
        // Asked for on an entity with no image field: dropped, like a calendar without a date.
        Map<String, Object> body = FullstackExampleRequests.oneToManyBody("react-tailwind-crud");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = new ArrayList<>();
        for (Object raw : (List<?>) body.get("entities")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entity = new LinkedHashMap<>((Map<String, Object>) raw);
            entity.put("listViews", List.of("table", "gallery"));
            entities.add(entity);
        }
        body.put("entities", entities);
        Map<String, String> files = generate(body);
        assertThat(files.keySet()).noneMatch(path -> path.endsWith("GalleryView.tsx"));
        assertThat(files.get("shop/frontend/src/pages/order/ui/OrderPage.tsx")).doesNotContain("gallery");
        assertThat(files.get("shop/frontend/src/shared/i18n/strings.ts")).doesNotContain("galleryView");

        // Not asked for: an entity with a picture keeps the page it had.
        Map<String, String> plain = generate(FullstackExampleRequests.fieldTypesBody("react-tailwind-crud"));
        assertThat(plain.keySet()).noneMatch(path -> path.endsWith("GalleryView.tsx"));
        assertThat(plain.get(FE + "pages/shift/ui/ShiftPage.tsx")).doesNotContain("gallery");
    }

    @Test
    void aListPageCanOpenOnTheGallery() throws Exception {
        Map<String, Object> body = withViews("react-tailwind-crud", "table", "gallery");
        Map<String, Object> page = new LinkedHashMap<>(Map.of("id", "shifts", "type", "entity-list", "entity", "Shift", "view", "gallery"));
        body.put("pages", List.of(page));
        assertThat(generate(body).get(FE + "app/screens/ShiftsScreen.tsx")).contains("initialView=\"gallery\"");

        // ...but only where the entity's list has one.
        Map<String, Object> without = FullstackExampleRequests.fieldTypesBody("react-tailwind-crud");
        without.put("pages", List.of(page));
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, without);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("gallery").contains("pages[0]");
    }
}
