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
 * The {@code xlsxExport} scaffold option (Round 10): the list as an Excel workbook, written by the
 * generated backend itself. What is generated, and for which entities. The workbook is opened for
 * real by {@code GeneratedBackendBuildSmokeTests.liveApiOfABackendWithEveryOptBehaves}.
 */
@IntegrationTest
class FullstackXlsxExportTests {

    private static final String BE = "shop/backend/src/main/java/com/menora/shop/";
    private static final String FE = "shop/frontend/src/";

    @Autowired
    private TestRestTemplate rest;

    private static Map<String, Object> body(String frontendSet, String... scaffold) {
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
    void theBackendWritesTheWorkbookItself() throws Exception {
        Map<String, String> files = generate(body("react-tailwind-crud", "xlsxExport", "audit"));
        // No library: one class of the project's own.
        assertThat(files.get(BE + "web/XlsxWriter.java"))
                .contains("package com.menora.shop.web;")
                .contains("public final class XlsxWriter implements Closeable {")
                .contains("zip.putNextEntry(new ZipEntry(\"xl/worksheets/sheet1.xml\"));");
        assertThat(files.get("shop/backend/pom.xml")).doesNotContain("poi");

        assertThat(files.get(BE + "controller/OrderController.java"))
                .contains("    @GetMapping(\"/export.xlsx\")\n"
                        + "    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> exportXlsx(\n"
                        + "            @RequestParam(required = false) String q,")
                .contains("new com.menora.shop.web.XlsxWriter(out, \"Orders\")")
                // The columns under the names the UI gives them; a link by the row it points at.
                .contains("                sheet.header(\n"
                        + "                        \"Id\",\n"
                        + "                        \"Reference\",\n"
                        + "                        \"Status\",\n"
                        + "                        \"Customer\",\n"
                        + "                        \"Created\",\n"
                        + "                        \"Updated\",\n"
                        + "                        \"Created by\",\n"
                        + "                        \"Updated by\"\n"
                        + "                );")
                .contains("                        sheet.row(\n"
                        + "                                dto.id(),\n"
                        + "                                dto.reference(),\n"
                        + "                                dto.status(),\n"
                        + "                                dto.customerLabel(),\n"
                        + "                                dto.createdAt(),\n"
                        + "                                dto.updatedAt(),\n"
                        + "                                dto.createdBy(),\n"
                        + "                                dto.updatedBy()\n"
                        + "                        );")
                .contains("filename=\\\"orders.xlsx\\\"")
                .contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                // It is not the CSV export: that one was not asked for.
                .doesNotContain("export.csv");
    }

    @Test
    void theListPageOffersIt() throws Exception {
        Map<String, String> files = generate(body("react-tailwind-crud", "xlsxExport"));
        assertThat(ListPages.of(files, FE + "pages/order/ui/OrderPage.tsx"))
                .contains("import { useDownload } from '@shared/api'")
                .contains("import { api as sheetApi } from '@shared/api'")
                .contains("    if (sort) params.set('sort', `${sort.field},${sort.direction}`)\n"
                        + "    return sheetApi.download(`/api/orders/export.xlsx?${params}`, 'orders.xlsx')")
                .contains("            onClick={() => list.download.run(list.exportXlsx)}")
                .contains("<FileSpreadsheet className=\"h-4 w-4\" />")
                .doesNotContain("exportCsv(");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).contains("  exportXlsx: 'Export to Excel',").contains("  exportXlsx: 'ייצוא ל-Excel',");

        Map<String, String> menora = generate(body("react-menora-digital-crud", "xlsxExport", "csvExport"));
        assertThat(ListPages.of(menora, FE + "pages/order/ui/OrderPage.tsx"))
                .contains("label={t('excel')} onClick={() => list.download.run(list.exportXlsx)} />")
                .contains("exportCsv('orders.csv')");
    }

    @Test
    void anEntityTakesOrLeavesItOnItsOwn() throws Exception {
        // On for the project, off for one entity.
        Map<String, Object> off = body("react-tailwind-crud", "xlsxExport");
        opt(off, "Product", "xlsxExport", false);
        Map<String, String> files = generate(off);
        assertThat(files.get(BE + "controller/ProductController.java")).doesNotContain("export.xlsx");
        assertThat(ListPages.of(files, FE + "pages/product/ui/ProductPage.tsx")).doesNotContain("exportXlsx").doesNotContain("useDownload");
        assertThat(files.get(BE + "controller/OrderController.java")).contains("export.xlsx");

        // Off for the project, on for one entity: the writer still ships.
        Map<String, Object> on = body("react-tailwind-crud");
        opt(on, "Product", "xlsxExport", true);
        files = generate(on);
        assertThat(files).containsKey(BE + "web/XlsxWriter.java");
        assertThat(files.get(BE + "controller/ProductController.java")).contains("export.xlsx");
        assertThat(files.get(BE + "controller/OrderController.java")).doesNotContain("export.xlsx");

        // Not asked for at all: nothing of it.
        files = generate(body("react-tailwind-crud", "csvExport"));
        assertThat(files).doesNotContainKey(BE + "web/XlsxWriter.java");
        assertThat(files.get(FE + "shared/i18n/strings.ts")).doesNotContain("exportXlsx");
        assertThat(ListPages.of(files, FE + "pages/order/ui/OrderPage.tsx")).doesNotContain("sheetApi");
    }
}
