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
 * Round 10, phase 7: a Markdown field is written in an editor with a preview, a picture shows on a
 * card, a file can be dropped onto its field, and a record page can be printed. Each is gated: a
 * project that uses none of it keeps its bytes (the golden manifests say so for the examples).
 * Built for real by {@code GeneratedFrontendBuildSmokeTests.fullstackFrontend*RichFields*}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class FullstackRichFieldsTests {

    private static final String FE = "rota/frontend/src/";

    @Autowired
    private TestRestTemplate rest;

    /**
     * The field-types model on a layout that shows its picture on cards and prints its record:
     * a list, a board whose cards carry the photo, an inbox-free record page with Print.
     */
    static Map<String, Object> layout(String frontendSet) {
        // ...and its list offered as a table and as a gallery of the shifts' photos.
        Map<String, Object> body = FullstackGalleryViewTests.withViews(frontendSet, "table", "gallery");
        List<Map<String, Object>> pages = new ArrayList<>();
        pages.add(page("shifts", "entity-list", Map.of("entity", "Shift")));
        pages.add(page("board", "board", Map.of("entity", "Shift", "laneField", "status", "cardFields", List.of("title", "photo"))));
        pages.add(page("shift", "record", Map.of("entity", "Shift", "printable", true)));
        body.put("pages", pages);
        return body;
    }

    private static Map<String, Object> page(String id, String type, Map<String, Object> more) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("id", id);
        page.put("type", type);
        page.putAll(more);
        return page;
    }

    private ResponseEntity<byte[]> post(Map<String, Object> body) {
        return FullstackExampleRequests.post(rest, body);
    }

    private Map<String, String> generate(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = post(body);
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Map<String, String> files = new LinkedHashMap<>();
        FullstackExampleRequests.unzip(response.getBody()).forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
        return files;
    }

    @Test
    void aMarkdownFieldIsWrittenInAnEditorWithAPreview() throws Exception {
        Map<String, String> files = generate(FullstackExampleRequests.fieldTypesBody("react-tailwind-crud"));
        assertThat(files.get(FE + "features/shift-form/ui/ShiftForm.tsx"))
                .contains("import { MarkdownEditor } from '@shared/ui/MarkdownEditor'")
                .contains("        <MarkdownEditor\n"
                        + "          label=\"Notes\"\n"
                        + "          value={value.notes}\n"
                        + "          onChange={next => set('notes', next)}\n"
                        + "          disabled={false}\n"
                        + "        />")
                // Nothing else in this form is long text, so no bare textarea is left.
                .doesNotContain("<textarea");
        // The preview is the component the details show the value with.
        assertThat(files.get(FE + "shared/ui/MarkdownEditor.tsx"))
                .contains("import { Markdown } from './Markdown'")
                .contains("<Markdown source={text} />")
                .doesNotContain("dangerouslySetInnerHTML");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).contains("  mdPreview: 'Preview',").contains("  mdPreview: 'תצוגה מקדימה',");

        // The Menora set borrows it.
        Map<String, String> menora = generate(FullstackExampleRequests.fieldTypesBody("react-menora-digital-crud"));
        assertThat(menora.get(FE + "shared/ui/MarkdownEditor.tsx")).isEqualTo(files.get(FE + "shared/ui/MarkdownEditor.tsx"));

        // A model without a Markdown field has none of it, and long text stays a textarea.
        Map<String, Object> plain = FullstackExampleRequests.fieldTypesBody("react-tailwind-crud");
        retype(plain, "notes", "TEXT");
        Map<String, String> without = generate(plain);
        assertThat(without).doesNotContainKey(FE + "shared/ui/MarkdownEditor.tsx");
        assertThat(without.get(FE + "features/shift-form/ui/ShiftForm.tsx")).doesNotContain("MarkdownEditor").contains("<textarea");
        assertThat(without.get(FE + "shared/i18n/strings.ts")).doesNotContain("mdPreview");
    }

    @Test
    void aPictureShowsOnACardAndAFileCanBeDroppedOntoItsField() throws Exception {
        Map<String, String> files = generate(layout("react-tailwind-crud"));
        assertThat(files.get(FE + "app/screens/BoardScreen.tsx"))
                .contains("import { attachmentUrl } from '@shared/ui/format'")
                .contains("r.photo == null ? '—' : <img src={attachmentUrl(r.photo)} alt={r.photoName ?? ''} className=\"h-10 w-10 rounded object-cover\" />");
        assertThat(files.get(FE + "shared/ui/AttachmentField.tsx"))
                .contains("onDrop={onDrop}")
                .contains("void pick(e.dataTransfer.files[0])")
                // The picked picture is shown from the browser's copy while it is sent.
                .contains("URL.createObjectURL(file)")
                .contains("{open && <span className=\"text-xs text-muted\">{t('dropHere')}</span>}");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).contains("  dropHere: 'or drop it here',").contains("  dropHere: 'או לגרור לכאן',");
    }

    @Test
    void aRecordPageCanBePrinted() throws Exception {
        Map<String, String> files = generate(layout("react-tailwind-crud"));
        assertThat(files.get(FE + "app/screens/ShiftScreen.tsx"))
                .contains("Printer, ")
                .contains("            onClick={() => window.print()}")
                .contains("            {t('print')}")
                // The page's own buttons stay off the paper.
                .contains("<div className=\"flex items-center gap-2 print:hidden\">");
        assertThat(files.get(FE + "index.css"))
                .contains("@media print {")
                .contains("  aside, header, footer, nav, [role='dialog'], [role='status'] {\n    display: none !important;\n  }");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).contains("  print: 'Print',").contains("  print: 'הדפסה',");
        // The Menora shell has its own stylesheet: the same rules.
        assertThat(generate(layout("react-menora-digital-crud")).get(FE + "index.css")).contains("@media print {");

        // Not asked for — absent or false — the page, the stylesheet and the strings are what they were.
        Map<String, Object> plain = layout("react-tailwind-crud");
        setOnPage(plain, "shift", "printable", null);
        Map<String, String> without = generate(plain);
        assertThat(without.get(FE + "app/screens/ShiftScreen.tsx")).doesNotContain("print");
        assertThat(without.get(FE + "index.css")).doesNotContain("@media print").endsWith("  scrollbar-color: var(--color-border) transparent;\n}\n");
        assertThat(without.get(FE + "shared/i18n/strings.ts")).doesNotContain("  print:");
        Map<String, Object> off = layout("react-tailwind-crud");
        setOnPage(off, "shift", "printable", false);
        Map<String, String> withFalse = generate(off);
        assertThat(withFalse.get(FE + "app/screens/ShiftScreen.tsx")).isEqualTo(without.get(FE + "app/screens/ShiftScreen.tsx"));
        assertThat(withFalse.get(FE + "index.css")).isEqualTo(without.get(FE + "index.css"));
    }

    @Test
    void onlyARecordPageTakesPrintable() {
        Map<String, Object> body = layout("react-tailwind-crud");
        setOnPage(body, "shifts", "printable", true);
        ResponseEntity<byte[]> response = post(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("does not take 'printable'").contains("pages[0]");
    }

    @SuppressWarnings("unchecked")
    private static void retype(Map<String, Object> body, String field, String type) {
        Map<String, Object> entity = new LinkedHashMap<>(((List<Map<String, Object>>) body.get("entities")).get(0));
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) entity.get("fields")) {
            Map<String, Object> copy = new LinkedHashMap<>(f);
            if (field.equals(copy.get("name"))) copy.put("type", type);
            fields.add(copy);
        }
        entity.put("fields", fields);
        body.put("entities", List.of(entity));
    }

    @SuppressWarnings("unchecked")
    private static void setOnPage(Map<String, Object> body, String pageId, String key, Object value) {
        for (Map<String, Object> page : (List<Map<String, Object>>) body.get("pages")) {
            if (!pageId.equals(page.get("id"))) continue;
            if (value == null) page.remove(key);
            else page.put(key, value);
        }
    }
}
