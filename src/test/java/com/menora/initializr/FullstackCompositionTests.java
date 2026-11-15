package com.menora.initializr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Composition (Round 10): a ONE_TO_MANY whose rows are part of their parent — edited in the
 * parent's form and saved, and removed, with it. What the request may ask for, and what is
 * generated on both sides. The generated backend is driven for real by
 * {@code GeneratedBackendBuildSmokeTests.liveApiOfAComposedParentBehaves}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class FullstackCompositionTests {

    private static final String BE = "shop/backend/src/main/java/com/menora/shop/";
    private static final String FE = "shop/frontend/src/";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    /** Customers, products, and orders whose lines are composed into them. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> composed(String frontendSet) {
        Map<String, Object> body = FullstackExampleRequests.oneToManyBody(frontendSet);
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        edit(body, "Order", order -> order.put("relations", relations(order, "lines", r -> r.put("composition", true))));
        return body;
    }

    @SuppressWarnings("unchecked")
    private static void edit(Map<String, Object> body, String entityName, Consumer<Map<String, Object>> change) {
        List<Map<String, Object>> entities = new ArrayList<>();
        for (Map<String, Object> entity : (List<Map<String, Object>>) body.get("entities")) {
            Map<String, Object> copy = new LinkedHashMap<>(entity);
            if (entityName.equals(copy.get("name"))) change.accept(copy);
            entities.add(copy);
        }
        body.put("entities", entities);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> relations(Map<String, Object> entity, String fieldName,
                                                       Consumer<Map<String, Object>> change) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> relation : (List<Map<String, Object>>) entity.getOrDefault("relations", List.of())) {
            Map<String, Object> copy = new LinkedHashMap<>(relation);
            if (fieldName.equals(copy.get("fieldName"))) change.accept(copy);
            out.add(copy);
        }
        return out;
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

    private JsonNode refused(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return mapper.readTree(response.getBody());
    }

    @Test
    void theBackendSavesTheRowsWithTheirParent() throws Exception {
        Map<String, String> files = generate(composed("react-tailwind-crud"));

        // The parent owns the rows: they are saved with it — and deleted with it, since nothing here is soft-deleted.
        assertThat(files.get(BE + "entity/Order.java"))
                .contains("@OneToMany(mappedBy = \"order\", cascade = {jakarta.persistence.CascadeType.PERSIST, "
                        + "jakarta.persistence.CascadeType.MERGE, jakarta.persistence.CascadeType.REMOVE})")
                .contains("public void setLines(List<OrderLine> lines) {");
        // A row takes another's values, but for its key and its parent.
        assertThat(files.get(BE + "entity/OrderLine.java"))
                .contains("    public void copyFrom(OrderLine other) {\n"
                        + "        this.quantity = other.quantity;\n"
                        + "        this.note = other.note;\n"
                        + "        this.product = other.product;\n"
                        + "    }");
        assertThat(files.get(BE + "entity/Order.java")).doesNotContain("copyFrom");

        assertThat(files.get(BE + "dto/OrderDto.java"))
                .contains("        @jakarta.validation.Valid java.util.List<OrderLineDto> lines) {")
                .contains("org.hibernate.Hibernate.isInitialized(entity.getLines())\n"
                        + "                        ? entity.getLines().stream().map(OrderLineDto::from).toList() : null")
                .contains("        if (this.lines == null) {\n            entity.setLines(null);");
        // Inside its parent a row need not say whose it is, so the DTO no longer requires the link…
        assertThat(files.get(BE + "dto/OrderLineDto.java"))
                .contains("        Long orderId,").doesNotContain("@NotNull Long orderId")
                .contains("@NotNull Integer quantity");
        // …and on its own the service asks for it.
        assertThat(files.get(BE + "service/OrderLineService.java"))
                .contains("        if (entity.getOrder() == null) {\n            throw new IllegalArgumentException(\"orderId is required\");");

        assertThat(files.get(BE + "service/OrderService.java"))
                .contains("    private static final int MAX_ROWS = 200;")
                .contains("        return withRows(repository.findById(id).orElseThrow(() ->")
                .contains("    private void adoptLines(Order parent, com.menora.shop.entity.OrderLine row) {\n"
                        + "        row.setOrder(parent);\n"
                        + "        if (row.getProduct() != null) {\n"
                        + "            row.setProduct(entityManager.getReference(com.menora.shop.entity.Product.class, row.getProduct().getId()));")
                .contains("    private void mergeLines(Order parent, java.util.List<com.menora.shop.entity.OrderLine> rows) {")
                .contains("                kept.copyFrom(row);")
                .contains("        for (com.menora.shop.entity.OrderLine gone : had.values()) entityManager.remove(gone);")
                .contains("        if (updated.getLines() != null) {\n            mergeLines(existing, updated.getLines());")
                .contains("private final EntityManager entityManager;");
        // The customer's orders are an ordinary list: read-only, nothing cascaded.
        assertThat(files.get(BE + "entity/Customer.java")).contains("@OneToMany(mappedBy = \"customer\")\n");
        assertThat(files.get(BE + "dto/CustomerDto.java")).doesNotContain("OrderDto");
    }

    @Test
    void aSoftDeletedParentKeepsItsRowsAndAVersionedOneMovesWithThem() throws Exception {
        Map<String, Object> body = composed("react-tailwind-crud");
        body.put("opts", Map.of("scaffold", List.of("softDelete", "optimisticLocking")));
        Map<String, String> files = generate(body);
        // It can be restored, so its rows are not deleted with it.
        assertThat(files.get(BE + "entity/Order.java"))
                .contains("cascade = {jakarta.persistence.CascadeType.PERSIST, jakarta.persistence.CascadeType.MERGE})");
        assertThat(files.get(BE + "service/OrderService.java"))
                .contains("            entityManager.lock(existing, jakarta.persistence.LockModeType.OPTIMISTIC_FORCE_INCREMENT);");
    }

    @Test
    void theFormEditsTheRows() throws Exception {
        for (String set : List.of("react-tailwind-crud", "react-menora-digital-crud")) {
            Map<String, String> files = generate(composed(set));
            assertThat(files).as(set).containsKeys(FE + "shared/ui/RowsEditor.tsx", FE + "features/order-line-form/ui/OrderLineRows.tsx");
            assertThat(files.get(FE + "features/order-line-form/ui/OrderLineRows.tsx")).as(set)
                    // A column per value and per link — but not the link to the order itself.
                    .contains("  { key: 'quantity', label: 'Quantity', required: true },\n  { key: 'note', label: 'Note' },\n"
                            + "  { key: 'productId', label: 'Product' },\n]")
                    .doesNotContain("orderId")
                    .contains("errorOf={(index, column) => errors?.[`${name}[${index}].${column}`]}")
                    .contains("onChange={e => set({ quantity: e.target.value === '' ? null : Number(e.target.value) })}");
            assertThat(files.get(FE + "features/order-line-form/index.ts")).as(set)
                    .contains("export { OrderLineRows } from './ui/OrderLineRows'");
            assertThat(files.get(FE + "features/order-form/ui/OrderForm.tsx")).as(set)
                    .contains("import { OrderLineRows } from '@features/order-line-form'")
                    .contains("<OrderLineRows\n        label=\"Lines\"\n        name=\"lines\"\n        value={value.lines ?? []}\n"
                            + "        onChange={rows => onChange({ ...value, lines: rows })}\n        errors={errors}\n      />");
            assertThat(files.get(FE + "entities/order/model/types.ts")).as(set)
                    .startsWith("import type { OrderLine } from '@entities/order-line'\n")
                    .contains("  lines?: Partial<OrderLine>[] | null\n");
            // Each row is checked by its own rules, and need not name its order there.
            assertThat(files.get(FE + "features/order-form/model/validate.ts")).as(set)
                    .contains("import { validateOrderLine } from '@features/order-line-form'")
                    .contains("for (const [field, message] of Object.entries(validateOrderLine(row, true))) {\n"
                            + "      errors[`lines[${index}].${field}`] = message");
            assertThat(files.get(FE + "features/order-line-form/model/validate.ts")).as(set)
                    .contains("export function validateOrderLine(value: Partial<OrderLine>, nested = false): Record<string, string> {")
                    .contains("  if (!nested && blank(value.orderId)) errors.orderId = t('required')");
            // A row of the list has no rows of its own: the page reads the record before editing them.
            assertThat(files.get(FE + "pages/order/ui/OrderPage.tsx")).as(set)
                    .contains("import { api as rowsApi } from '@shared/api'")
                    .contains("rowsApi.get<Order>(`/api/orders/${encodeURIComponent(String(row.id))}`)")
                    .contains("const rows = { lines: full.lines, }");
            assertThat(files.get(FE + "shared/i18n/strings.ts")).as(set).contains("  addRow: 'Add row',").contains("  noRows: 'אין שורות עדיין.',");
            // The customer's page has no rows to fetch.
            assertThat(files.get(FE + "pages/customer/ui/CustomerPage.tsx")).as(set).doesNotContain("rowsApi");
        }
        // Without a composition none of it is there.
        Map<String, String> plain = generate(FullstackExampleRequests.oneToManyBody("react-tailwind-crud"));
        assertThat(plain).doesNotContainKeys(FE + "shared/ui/RowsEditor.tsx", FE + "features/order-line-form/ui/OrderLineRows.tsx");
        assertThat(plain.get(FE + "shared/i18n/strings.ts")).doesNotContain("addRow");
        assertThat(plain.get(FE + "features/order-line-form/model/validate.ts")).doesNotContain("nested");
    }

    @Test
    void aCompositionThatCannotWorkIsRefusedWithItsReason() throws Exception {
        // Not a ONE_TO_MANY.
        Map<String, Object> body = composed(null);
        edit(body, "OrderLine", line -> line.put("relations", relations(line, "product", r -> r.put("composition", true))));
        assertThat(refused(body).path("detail").asText()).contains("composition only applies to a ONE_TO_MANY");

        // The link to the parent has to be required.
        body = composed(null);
        edit(body, "OrderLine", line -> line.put("relations", relations(line, "order", r -> r.put("required", false))));
        JsonNode optional = refused(body);
        assertThat(optional.path("detail").asText())
                .contains("entity 'Order' relation 'lines': composition").contains("'order' on 'OrderLine' must be required");
        assertThat(optional.path("path").asText()).isEqualTo("entities[2].relations[1].composition");

        // A key of the client's choosing: a new row could not be told from a kept one.
        body = composed(null);
        edit(body, "OrderLine", line -> line.put("fields", List.of(
                Map.of("name", "code", "type", "STRING", "primaryKey", true),
                Map.of("name", "quantity", "type", "INTEGER", "required", true))));
        assertThat(refused(body).path("detail").asText()).contains("needs a single generated key");

        // Read-only rows; rows with a workflow, an attachment or lists of their own.
        body = composed(null);
        edit(body, "OrderLine", line -> line.put("readOnly", true));
        assertThat(refused(body).path("detail").asText()).contains("'OrderLine' is read-only");
        body = composed(null);
        edit(body, "OrderLine", line -> line.put("fields", List.of(
                Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "scan", "type", "FILE"))));
        assertThat(refused(body).path("detail").asText()).contains("has an attachment field ('scan')");
        body = composed(null);
        edit(body, "OrderLine", line -> {
            List<Map<String, Object>> relations = relations(line, "none", r -> { });
            relations.add(Map.of("type", "MANY_TO_MANY", "fieldName", "extras", "targetEntity", "Product"));
            line.put("relations", relations);
        });
        assertThat(refused(body).path("detail").asText()).contains("has lists of its own");

        // One owner: the customer cannot compose the order's lines too — nor its own orders, which point elsewhere.
        body = composed(null);
        edit(body, "OrderLine", line -> {
            List<Map<String, Object>> relations = relations(line, "none", r -> { });
            relations.add(Map.of("type", "MANY_TO_ONE", "fieldName", "customer", "targetEntity", "Customer", "required", true));
            line.put("relations", relations);
        });
        edit(body, "Customer", customer -> {
            List<Map<String, Object>> relations = relations(customer, "none", r -> { });
            relations.add(Map.of("type", "ONE_TO_MANY", "fieldName", "lines", "targetEntity", "OrderLine", "composition", true));
            customer.put("relations", relations);
        });
        assertThat(refused(body).path("detail").asText()).contains("'OrderLine' is already composed by");
    }
}
