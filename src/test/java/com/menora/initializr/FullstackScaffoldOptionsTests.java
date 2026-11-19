package com.menora.initializr;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /starter-fullstack.zip}: the scaffold options and what every generated list does. */
@IntegrationTest
@Tag("slow") // left out of mvn test -Pquick
class FullstackScaffoldOptionsTests extends FullstackEndpointTestSupport {

    @Test
    void fullstackEndpoint_seedDataOptGeneratesDemoLoaderInDependencyOrder() throws Exception {
        // Order is declared BEFORE Customer but references it, so the loader must seed customers
        // first and hand each order a customer. Every field type gets a deterministic expression.
        Map<String, Object> customerName = Map.of("name", "name", "type", "STRING", "required", true, "unique", true, "length", 40);
        Map<String, Object> customerEmail = Map.of("name", "email", "type", "STRING", "email", true);
        Map<String, Object> customerActive = Map.of("name", "active", "type", "BOOLEAN");
        Map<String, Object> orderStatus = Map.of("name", "status", "type", "ENUM", "enumValues", List.of("OPEN", "PAID"));
        Map<String, Object> orderPlaced = Map.of("name", "placedAt", "type", "LOCAL_DATE");
        Map<String, Object> orderTotal = Map.of("name", "total", "type", "BIG_DECIMAL", "min", 1, "max", 500);
        Map<String, Object> orderQty = Map.of("name", "qty", "type", "INTEGER");
        Map<String, Object> orderNotes = Map.of("name", "notes", "type", "TEXT");
        Map<String, Object> rel = Map.of(
                "type", "MANY_TO_ONE", "fieldName", "customer", "targetEntity", "Customer", "required", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("opts", Map.of("scaffold", List.of("seedData")));
        body.put("entities", List.of(
                Map.of("name", "Order",
                        "fields", List.of(pkField(), orderStatus, orderPlaced, orderTotal, orderQty, orderNotes),
                        "relations", List.of(rel)),
                Map.of("name", "Customer", "fields", List.of(pkField(), customerName, customerEmail, customerActive))));
        Map<String, String> entries = generateZip(body);

        String loader = entries.get("shop/backend/src/main/java/com/menora/shop/config/DemoDataLoader.java");
        assertThat(loader)
                .contains("@ConditionalOnProperty(prefix = \"app.demo-data\", name = \"enabled\", havingValue = \"true\", matchIfMissing = true)")
                .contains("implements CommandLineRunner")
                .contains("if (customerRepository.count() > 0 || orderRepository.count() > 0)")
                // Customer rows are created before Order rows.
                .contains("row.setName(label(\"Name\", i, 40));")
                .contains("row.setEmail(\"user\" + i + \"@example.com\");")
                .contains("row.setActive(i % 2 == 0);")
                .contains("row.setStatus(Order.OrderStatusType.values()[(i - 1) % Order.OrderStatusType.values().length]);")
                .contains("row.setPlacedAt(java.time.LocalDate.now().minusDays(i));")
                .contains("row.setTotal(java.math.BigDecimal.valueOf(bounded(i, 1L, 500L)));")
                .contains("row.setQty((int) bounded(i, null, null));")
                .contains("row.setNotes(text(\"Notes\", i));")
                .contains("row.setCustomer(customerRows.get((i - 1) % customerRows.size()));")
                .contains("customerRows = customerRepository.saveAll(customerRows);")
                .doesNotContain("row.setId(");
        assertThat(loader.indexOf("List<Customer> customerRows"))
                .isLessThan(loader.indexOf("List<Order> orderRows"));

        // Without the opt the loader is not emitted.
        body.remove("opts");
        assertThat(generateZip(body)).doesNotContainKey("shop/backend/src/main/java/com/menora/shop/config/DemoDataLoader.java");
    }

    @Test
    void fullstackEndpoint_generatesTheStatsRollupForChartableEntities() throws Exception {
        // Sale has all three shapes (enum / date / numeric); Note has none of them.
        Map<String, Object> region = Map.of("name", "region", "type", "Enum", "enumValues", List.of("NORTH", "SOUTH"));
        Map<String, Object> amount = Map.of("name", "amount", "type", "BigDecimal");
        Map<String, Object> soldOn = Map.of("name", "soldOn", "type", "LocalDate");
        Map<String, Object> text = Map.of("name", "text", "type", "String");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "sales");
        body.put("packageName", "com.menora.sales");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Sale", "fields", List.of(pkField(), region, amount, soldOn)),
                Map.of("name", "Note", "fields", List.of(pkField(), text))));

        Map<String, String> entries = generateZip(body);

        String saleService = contentEndingWith(entries, "/service/SaleService.java");
        assertThat(saleService)
                .contains("public java.util.List<StatsBucket> stats(")
                .contains("public record StatsBucket(String key, java.math.BigDecimal value, String series) {")
                .contains("Object seriesValue = split ? row.get(1) : null;")
                // Hibernate's builder, so the date parts become extract(<unit> from x) per dialect.
                .contains("HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder()")
                .contains("groups.add(cb.year(when))")
                .contains("if (!\"year\".equals(unit)) groups.add(cb.month(when))")
                // Whitelists, one case per chartable column.
                .contains("case \"region\" -> root.get(\"region\")")
                .contains("case \"soldOn\" -> root.<LocalDate>get(\"soldOn\")")
                .contains("case \"amount\" -> root.<BigDecimal>get(\"amount\")")
                .contains("return groupBy.equals(\"soldOn\") || false;")
                // The list and the rollup share one predicate, so they can never drift.
                .contains("Specification<Sale> spec = specOf(q, filters)")
                .contains("private Specification<Sale> specOf(String q, Filters filters)");
        assertThat(contentEndingWith(entries, "/controller/SaleController.java"))
                .contains("@GetMapping(\"/stats\")")
                .contains("public StatsResponse stats(")
                .contains("@RequestParam(required = false) String groupBy")
                .contains("service.stats(q, filters, groupBy, bucket, agg, field, top, series)")
                // An empty group (avg over no rows) adds nothing to the total rather than throwing.
                .contains("if (bucketed.value() != null) total = total == null ? bucketed.value() : total.add(bucketed.value());")
                .contains("public record StatsResponse(List<SaleService.StatsBucket> buckets, java.math.BigDecimal total) {}");

        // Nothing to group, bucket or reduce → no endpoint, and no EntityManager to inject.
        assertThat(contentEndingWith(entries, "/service/NoteService.java"))
                .doesNotContain("StatsBucket")
                .doesNotContain("EntityManager")
                .contains("public NoteService(NoteRepository repository) {");
        assertThat(contentEndingWith(entries, "/controller/NoteController.java"))
                .doesNotContain("/stats");
    }

    @Test
    void fullstackEndpoint_auditOptAddsAuditing() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("opts", Map.of("scaffold", List.of("audit")));
        body.put("entities", List.of(Map.of("name", "Widget",
                "fields", List.of(pkField(), Map.of("name", "label", "type", "String")))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/config/JpaAuditingConfig.java"))
                .contains("@EnableJpaAuditing");
        assertThat(contentEndingWith(entries, "/entity/Widget.java"))
                .contains("@EntityListeners(AuditingEntityListener.class)")
                .contains("@CreatedDate")
                .contains("private Instant createdAt;")
                .contains("@LastModifiedDate");
        assertThat(contentEndingWith(entries, "/dto/WidgetDto.java"))
                .contains("java.time.Instant createdAt")
                .contains("entity.getCreatedAt()");
        assertThat(entries.get("shop/frontend/src/entities/widget/model/types.ts"))
                .contains("createdAt?: string | null");
    }

    @Test
    void fullstackEndpoint_withoutAuditOptOmitsAuditing() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "Widget",
                "fields", List.of(pkField(), Map.of("name", "label", "type", "String")))));

        Map<String, String> entries = generateZip(body);

        assertThat(entries.keySet().stream().anyMatch(k -> k.endsWith("/config/JpaAuditingConfig.java")))
                .isFalse();
        assertThat(contentEndingWith(entries, "/entity/Widget.java"))
                .doesNotContain("@CreatedDate")
                .doesNotContain("createdAt");
    }

    @Test
    void fullstackEndpoint_softDeleteOptAddsHibernateAnnotationsButSkipsCompositePk() throws Exception {
        Map<String, Object> orderId = Map.of("name", "orderId", "type", "Long", "primaryKey", true);
        Map<String, Object> lineNo = Map.of("name", "lineNo", "type", "Integer", "primaryKey", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("opts", Map.of("scaffold", List.of("softDelete")));
        body.put("entities", List.of(
                Map.of("name", "Widget", "fields", List.of(pkField(), Map.of("name", "label", "type", "String"))),
                Map.of("name", "OrderLine", "fields", List.of(orderId, lineNo))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/entity/Widget.java"))
                .contains("@SQLDelete(sql = \"UPDATE widgets SET deleted = true WHERE id = ?\")")
                .contains("@SQLRestriction(\"deleted = false\")")
                .contains("private boolean deleted = false;");
        // Composite-PK entity skips soft-delete (single-column WHERE can't address a composite key).
        assertThat(contentEndingWith(entries, "/entity/OrderLine.java"))
                .doesNotContain("@SQLDelete");
        // The 'deleted' flag is never exposed on the DTO.
        assertThat(contentEndingWith(entries, "/dto/WidgetDto.java")).doesNotContain("deleted");

        // A soft-deleted row can be restored: native UPDATE (JPQL can't see the row behind
        // @SQLRestriction), a service method that 404s on an unknown id, POST /{id}/restore, and
        // the frontend's delete toast wires its Undo to it instead of re-creating the record.
        assertThat(contentEndingWith(entries, "/repository/WidgetRepository.java"))
                .contains("@Modifying")
                .contains("@Query(value = \"UPDATE widgets SET deleted = false WHERE id = :id\", nativeQuery = true)")
                .contains("int restore(@Param(\"id\") Long id);");
        assertThat(contentEndingWith(entries, "/service/WidgetService.java"))
                .contains("public Widget restore(Long id)")
                .contains("if (repository.restore(id) == 0)");
        assertThat(contentEndingWith(entries, "/controller/WidgetController.java"))
                .contains("@PostMapping(\"/{id}/restore\")")
                .contains("service.restore(id)");
        assertThat(ListPages.of(entries, "shop/frontend/src/pages/widget/ui/WidgetPage.tsx"))
                .contains("restore(removed.id as number | string)")
                .contains("label: t('undo')")
                .doesNotContain("create(removed");
        // Composite PK: no soft delete, hence no restore anywhere.
        assertThat(contentEndingWith(entries, "/repository/OrderLineRepository.java")).doesNotContain("restore");
        assertThat(contentEndingWith(entries, "/controller/OrderLineController.java")).doesNotContain("restore");

        // With a @Version, Hibernate binds the id and the version to a delete: the custom statement
        // must take both (with one placeholder every delete failed, answered as a 409). A restore
        // moves the version on — and the audit timestamp — like any other change.
        body.put("opts", Map.of("scaffold", List.of("softDelete", "optimisticLocking", "audit")));
        for (String set : List.of("spring-jpa-crud", "spring-jpa-crud-lombok")) {
            body.put("backendTemplateSet", set);
            Map<String, String> locked = generateZip(body);
            assertThat(contentEndingWith(locked, "/entity/Widget.java"))
                    .contains("@SQLDelete(sql = \"UPDATE widgets SET deleted = true WHERE id = ? AND version = ?\")");
            assertThat(contentEndingWith(locked, "/repository/WidgetRepository.java"))
                    .contains("@Query(value = \"UPDATE widgets SET deleted = false, version = coalesce(version, 0) + 1,"
                            + " updated_at = CURRENT_TIMESTAMP, updated_by = :user WHERE id = :id\", nativeQuery = true)");
        }
    }

    @Test
    void fullstackEndpoint_sortsByWhatSortsAndShipsNoDeadFiles() throws Exception {
        // The Round 9 field types: `notes` is Markdown, `contract` a file, `photo` an image.
        Map<String, Object> body = FullstackExampleRequests.fieldTypesBody("react-tailwind-crud");
        Map<String, String> classic = generateZip(body);

        // A list sorts by neither long text (a TEXT/CLOB column does not sort on every database)
        // nor a file (its value is an id): out of the API's whitelist, and no sort arrow on the column.
        String controller = contentEndingWith(classic, "/controller/ShiftController.java");
        int from = controller.indexOf("SORTABLE = List.of(");
        assertThat(controller.substring(from, controller.indexOf("});", from)))
                .contains("\"title\",").contains("\"startsAt\",").contains("\"createdAt\",")
                .doesNotContain("\"notes\"").doesNotContain("\"contract\"").doesNotContain("\"photo\"");
        assertThat(ListPages.of(classic, "rota/frontend/src/pages/shift/ui/ShiftPage.tsx"))
                .contains("label: 'Title', sortKey: 'title', render:")
                .contains("label: 'Notes', render:")
                .contains("label: 'Contract', render:")
                .doesNotContain("sortKey: 'notes'").doesNotContain("sortKey: 'photo'");
        body.put("frontendTemplateSet", "react-menora-digital-crud");
        assertThat(ListPages.of(generateZip(body), "rota/frontend/src/pages/shift/ui/ShiftPage.tsx"))
                .contains("label: 'Title', sortKey: 'title'").contains("label: 'Notes', render:").doesNotContain("sortKey: 'notes'");
        body.put("frontendTemplateSet", "react-tailwind-crud");

        // The kanban's "no value" lane key is written as an escape: the file held a raw NUL byte,
        // which made git and the line-ending pass treat it as binary.
        String kanban = classic.get("rota/frontend/src/shared/ui/KanbanBoard.tsx");
        assertThat(kanban).contains("const UNASSIGNED = '\\u0000__unassigned__'").doesNotContain("\u0000").doesNotContain("\r");

        // The classic dashboard page is the classic shell's: a layout draws its own screens and
        // never imported it, yet it was generated.
        String dashboard = "rota/frontend/src/pages/dashboard/ui/DashboardPage.tsx";
        String barrel = "rota/frontend/src/pages/dashboard/index.ts";
        assertThat(classic).containsKeys(dashboard, barrel);
        body.put("pages", List.of(Map.of("id", "shifts", "type", "entity-list", "entity", "Shift")));
        Map<String, String> layout = generateZip(body);
        assertThat(layout).doesNotContainKeys(dashboard, barrel);
        assertThat(layout.get("rota/frontend/src/app/App.tsx")).doesNotContain("@pages/dashboard");

        // A page or a recent list that asks to sort by one of them is told so.
        body.put("pages", List.of(Map.of("id", "shifts", "type", "entity-list", "entity", "Shift",
                "sort", Map.of("field", "notes", "dir", "asc"))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> badSort = restTemplate.exchange("/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(badSort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badSort.getBody()).contains("'notes' is not sortable on Shift");
        body.put("pages", List.of(
                Map.of("id", "shifts", "type", "entity-list", "entity", "Shift"),
                Map.of("id", "home", "type", "dashboard", "widgets", List.of(
                        Map.of("kind", "recent", "entity", "Shift", "sortBy", "contract")))));
        ResponseEntity<String> badRecent = restTemplate.exchange("/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(badRecent.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRecent.getBody()).contains("sortBy 'contract' is long text or a file, which a list cannot be sorted by");

        // Lombok entities: a lazy @ManyToOne stays out of equals/hashCode/toString (@Data put it
        // in all three — touching it there loads the row, or fails outside a session).
        Map<String, Object> posts = FullstackExampleRequests.manyToManyBody("react-tailwind-crud");
        posts.put("backendTemplateSet", "spring-jpa-crud-lombok");
        assertThat(contentEndingWith(generateZip(posts), "/entity/Post.java"))
                .contains("    @JoinColumn(name = \"author_id\")\n    @lombok.EqualsAndHashCode.Exclude\n    @lombok.ToString.Exclude\n    private Author author;");
    }

    @Test
    void fullstackEndpoint_csvGuardsFormulaCellsAndAnImportChecksItsLinks() throws Exception {
        Map<String, Object> body = FullstackExampleRequests.manyToManyBody("react-tailwind-crud");
        body.put("opts", Map.of("scaffold", List.of("csvExport", "csvImport")));
        Map<String, String> entries = generateZip(body);

        // Export: a text cell that starts like a formula would run in a spreadsheet.
        assertThat(contentEndingWith(entries, "/controller/PostController.java"))
                .contains("private static final String FORMULA_STARTS = \"=+-@\";")
                .contains("if (value instanceof CharSequence && !s.isEmpty() && FORMULA_STARTS.indexOf(s.charAt(0)) >= 0) {\n"
                        + "            s = \"'\" + s;");
        // …and the app's own import drops that apostrophe, so an exported file still imports as it is.
        assertThat(contentEndingWith(entries, "/shared/ui/csv.ts"))
                .contains("const text = raw.trim().replace(/^'(?=[=+@-])/, '')");

        // Import: the ids of a many-to-many are resolved per row, like a create's — an unknown id
        // is that row's problem, not a database error for the whole file.
        assertThat(contentEndingWith(entries, "/service/PostService.java"))
                .contains("            Post entity = dto.toEntity();\n"
                        + "            try {\n"
                        + "                resolveLinks(entity); // the ids of a many-to-many must name rows, like a create's\n"
                        + "            } catch (IllegalArgumentException unknown) {\n"
                        + "                errors.add(new ImportError(row, null, unknown.getMessage()));\n"
                        + "            }\n"
                        + "            if (entity.getTags() == null) entity.setTags(new java.util.LinkedHashSet<>());\n");
        // An entity without a many-to-many keeps its import as it was.
        assertThat(contentEndingWith(entries, "/service/TagService.java")).doesNotContain("resolveLinks");
    }

    @Test
    void fullstackEndpoint_bulkCallsKeepWhatASingleSaveKeeps() throws Exception {
        // A bulk edit used to skip everything a single save checks: it nulled a required field,
        // took a value the DTO's validation refuses, and both bulk calls silently skipped ids
        // that name no row.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("opts", Map.of("scaffold", List.of("bulkUpdate", "bulkDelete")));
        body.put("entities", List.of(Map.of("name", "Widget", "fields", List.of(pkField(),
                Map.of("name", "label", "type", "String", "required", true, "length", 40),
                Map.of("name", "code", "type", "String", "required", true),
                Map.of("name", "stock", "type", "Integer", "min", 0)))));

        String service = contentEndingWith(generateZip(body), "/service/WidgetService.java");
        assertThat(service)
                // Every id must name a row — checked before anything is changed.
                .contains("    public void deleteAll(java.util.List<Long> ids) {\n"
                        + "        java.util.List<Widget> rows = repository.findAllById(ids);\n"
                        + "        requireAll(ids, rows);\n"
                        + "        repository.deleteAllById(ids);\n")
                .contains("        java.util.List<Widget> rows = repository.findAllById(ids);\n"
                        + "        requireAll(ids, rows);\n"
                        + "        if (value == null && java.util.Set.of(\"label\", \"code\").contains(field)) {\n"
                        + "            throw new IllegalArgumentException(field + \" is required\");\n")
                .contains("private void requireAll(java.util.List<Long> ids, java.util.List<Widget> rows) {")
                .contains("throw new ResourceNotFoundException(\"Widget not found: \" + missing);")
                // The value is held to the DTO's own constraints (length, min…), then saved.
                .contains("private final jakarta.validation.Validator validator;")
                .contains(": validator.validateProperty(com.menora.shop.dto.WidgetDto.from(rows.get(0)), field)) {\n"
                        + "                throw new IllegalArgumentException(field + \" \" + violation.getMessage());");

        // Without the validation starter there is no Validator to ask; the other two checks stay.
        body.put("dependencies", List.of("web", "data-jpa", "h2"));
        assertThat(contentEndingWith(generateZip(body), "/service/WidgetService.java"))
                .contains("requireAll(ids, rows);")
                .contains("java.util.Set.of(\"label\", \"code\").contains(field)")
                .doesNotContain("Validator");

        // Bulk delete alone: the helper ships with it; an entity with neither has none of this.
        body.put("opts", Map.of("scaffold", List.of("bulkDelete")));
        assertThat(contentEndingWith(generateZip(body), "/service/WidgetService.java"))
                .contains("private void requireAll(").doesNotContain("updateMany");
        body.put("opts", Map.of("scaffold", List.of()));
        assertThat(contentEndingWith(generateZip(body), "/service/WidgetService.java")).doesNotContain("requireAll");
    }

    @Test
    void fullstackEndpoint_cardsViewDetailDrawerAndDashboardChart() throws Exception {
        // The legacy single listView="cards" maps to a cards-only page (no Table, no toggle bar);
        // every entity gets a read-only DetailDrawer (onView); an enum field yields a dashboard chart.
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("name", "status");
        status.put("type", "Enum");
        status.put("enumValues", List.of("AVAILABLE", "SOLD"));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "Product");
        product.put("listView", "cards");
        product.put("fields", List.of(pkField(), Map.of("name", "name", "type", "String"), status));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(product,
                Map.of("name", "Plain", "fields", List.of(pkField(), Map.of("name", "label", "type", "String")))));

        Map<String, String> entries = generateZip(body);

        // Shared view components shipped once.
        assertThat(entries).containsKey("shop/frontend/src/shared/ui/CardGrid.tsx");
        assertThat(entries).containsKey("shop/frontend/src/shared/ui/DetailDrawer.tsx");
        // vite/client types so import.meta.env typechecks under `tsc -b`.
        assertThat(entries.get("shop/frontend/src/vite-env.d.ts")).contains("vite/client");

        // Cards-only: the page generates the CardGrid and nothing else — no Table, no toggle bar.
        String productPage = ListPages.of(entries, "shop/frontend/src/pages/product/ui/ProductPage.tsx");
        assertThat(productPage)
                .contains("useState<'cards'>((prefs.view as 'cards' | undefined) ?? openingView)")
                .contains("<CardGrid")
                .doesNotContain("<Table")
                .doesNotContain("aria-pressed={list.viewMode === 'cards'}")   // no toggle button
                .contains("onView={list.setDetailRow}")
                .contains("<ProductDetail value={list.detailRow} />");
        assertThat(entries).containsKey("shop/frontend/src/features/product-form/ui/ProductDetail.tsx");
        // An entity with no listViews defaults to table-only (no Cards, no toggle).
        assertThat(ListPages.of(entries, "shop/frontend/src/pages/plain/ui/PlainPage.tsx"))
                .contains("useState<'table'>((prefs.view as 'table' | undefined) ?? openingView)")
                .doesNotContain("<CardGrid");

        // The enum field drives a dashboard breakdown chart; Plain (no enum/boolean) gets none.
        String dashboard = entries.get("shop/frontend/src/pages/dashboard/ui/DashboardPage.tsx");
        assertThat(dashboard)
                .contains("function BarChart")
                .contains("label: t('xByY', { x: 'Products', y: 'Status' })")
                .contains("field: 'status'")
                // Grouped by the backend's rollup, so the chart is exact rather than a sample.
                .contains("`${path}/stats?groupBy=${field}`")
                .doesNotContain("BREAKDOWN_SAMPLE")
                .doesNotContain("basedOnSample");
        assertThat(dashboard).doesNotContain("x: 'Plains'");
    }

    @Test
    void fullstackEndpoint_kanbanCalendarFiltersExportAndBulkDelete() throws Exception {
        // Task: enum status → kanban-applicable; enum/numeric/temporal/boolean fields → filter bar.
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("name", "status");
        status.put("type", "Enum");
        status.put("enumValues", List.of("OPEN", "DONE"));
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("name", "Task");
        // Multi-select: Table + Kanban only (no Cards/Calendar even though dueDate is calendar-capable).
        task.put("listViews", List.of("table", "kanban"));
        task.put("fields", List.of(pkField(),
                Map.of("name", "title", "type", "String"),
                status,
                Map.of("name", "priority", "type", "Integer"),
                Map.of("name", "dueDate", "type", "LocalDate"),
                Map.of("name", "done", "type", "Boolean")));

        // Event: calendar-only via listViews; a LocalDateTime field makes calendar applicable.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", "Event");
        event.put("listViews", List.of("calendar"));
        event.put("fields", List.of(pkField(),
                Map.of("name", "name", "type", "String"),
                Map.of("name", "startsAt", "type", "LocalDateTime")));

        // Plain: legacy single listView="kanban" but no enum/boolean → back-compat + down-grade to table.
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("name", "Plain");
        plain.put("listView", "kanban");
        plain.put("fields", List.of(pkField(), Map.of("name", "label", "type", "String")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "ops");
        body.put("packageName", "com.menora.ops");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(task, event, plain));
        body.put("opts", Map.of("scaffold", List.of("csvExport", "bulkDelete")));

        Map<String, String> entries = generateZip(body);

        // New shared FE components shipped once.
        assertThat(entries).containsKey("ops/frontend/src/shared/ui/KanbanBoard.tsx");
        assertThat(entries).containsKey("ops/frontend/src/shared/ui/CalendarView.tsx");
        assertThat(entries).containsKey("ops/frontend/src/shared/ui/FilterBar.tsx");

        // Task page: only the selected Table + Kanban views, a toggle across just those, plus the
        // (view-independent) filter bar, CSV export, and bulk-select table. No Cards/Calendar code.
        // CRLF templates on a Windows checkout render CRLF; the multi-line checks below expect LF.
        String taskPage = ListPages.of(entries, "ops/frontend/src/pages/task/ui/TaskPage.tsx").replace("\r\n", "\n");
        assertThat(taskPage)
                .contains("const [viewMode, setViewMode] = useState<'table' | 'kanban'>((prefs.view as 'table' | 'kanban' | undefined) ?? openingView)")
                .contains("const openingView = 'table'")  // initial = first selected (canonical order)
                .contains("KanbanBoard, FilterBar")
                .contains("<KanbanBoard")
                .contains("groupField=\"status\"")
                .contains("aria-label={t('tableView')}")            // toggle bar present (2 views)
                .contains("aria-label={t('boardView')}")
                .doesNotContain("aria-label={t('cardView')}")       // cards not selected
                .doesNotContain("<CardGrid")
                .doesNotContain("<CalendarView")
                .contains("<FilterBar filters={list.filterDescriptors}")
                .contains("options: [{ value: 'OPEN', label: 'Open' }, { value: 'DONE', label: 'Done' }]")
                .contains("exportCsv('tasks.csv')")
                // The selection bar is for those who may write (ldap-auth is a set default).
                .contains("selectable={list.can('bulk')}")
                .contains("isRowSelected={list.isRowSelected}")
                // A failed drag-and-drop is toasted and the board reloaded, never a dropped promise.
                .contains("async function onKanbanMove(row: Task, value: string)")
                // Dragging a card saves the row: for those who may write.
                .contains("onMove={list.can('edit') ? list.onKanbanMove : undefined}")
                .contains("await reload()")
                .contains(" reload,")
                // The board loads every matching row (not one page) and takes its lane totals from
                // /stats; a lane with more than it shows opens in the table, filtered to that lane.
                .contains("const WHOLE_VIEW_LIMIT = 500")
                .contains("const wholeView = viewMode === 'kanban'")
                .doesNotContain("calendarRange")
                .contains("useTask({ page: wholeView ? 0 : page, size: wholeView ? WHOLE_VIEW_LIMIT : size, sort,")
                .contains("countBy('status')")
                .contains("totals={list.laneTotals}")
                .contains("onShowLane={value => { list.setFilters(f => ({ ...f, status: value })); list.setViewMode('table') }}")
                // Hard delete: the confirm promises no undo.
                .contains("message={t('cannotUndo')}")
                .doesNotContain("undoHint")
                // Deleting the last row of the last page steps back a page.
                .contains("if (!loading && items.length === 0 && page > 0 && totalPages > 0) setPage(totalPages - 1)")
                // A new search or filter starts the bulk selection again.
                .contains("if (selectionKey !== selectionFor) {");
        assertThat(taskPage)
                // A filtered board says nothing matches; an unfiltered one offers the first record.
                .contains("          filtered={list.filtered}\n          emptyAction={list.emptyAction}\n")
                // A failed load can be retried in place.
                .contains("{t('retry')}</button>")
                // A failed export is told, and the button says it is busy meanwhile.
                .contains("  const download = useDownload()")
                .contains("onClick={() => list.download.run(() => list.exportCsv('tasks.csv'))}");
        assertThat(entries.get("ops/frontend/src/shared/ui/KanbanBoard.tsx"))
                .contains("if (loading && rows.length === 0) {")
                .contains("t('nMoreShowInList', { n: hidden })")
                .contains("? <EmptyState title={t('noMatchingRecords')} />");
        // A date-only value is placed on its own day, not read as UTC midnight (the day before, west of UTC).
        assertThat(entries.get("ops/frontend/src/shared/ui/CalendarView.tsx"))
                .contains("const day = /^(\\d{4}-\\d{2}-\\d{2})/.exec(String(raw))")
                .doesNotContain("new Date(String(raw))");
        // Escape in a relation picker closes the picker, not the drawer or dialog around it.
        for (String layer : List.of("FormDrawer", "DetailDrawer", "ConfirmDialog")) {
            assertThat(entries.get("ops/frontend/src/shared/ui/" + layer + ".tsx"))
                    .contains("if (e.key === 'Escape' && !e.defaultPrevented)");
        }
        assertThat(entries.get("ops/frontend/src/shared/api/useResource.ts"))
                .contains("export function useDownload()")
                .contains("toast.error(t('exportFailed',");
        assertThat(entries.get("ops/frontend/src/shared/api/useResource.ts"))
                .contains("export const RefreshTick = createContext(0)")
                .contains("}, [reload, tick])")
                .contains("`${basePath}/stats?groupBy=${encodeURIComponent(field)}${where ? `&${where}` : ''}`");

        // Event page: calendar-only — no toggle bar, no Table/CardGrid/KanbanBoard.
        String eventPage = ListPages.of(entries, "ops/frontend/src/pages/event/ui/EventPage.tsx");
        assertThat(eventPage)
                .contains("useState<'calendar'>((prefs.view as 'calendar' | undefined) ?? openingView)")
                .contains("<CalendarView")
                .contains("dateField=\"startsAt\"")
                // The calendar loads the month on screen through the date's own filter (whole days).
                .contains("startsAtFrom: from + 'T00:00:00',")
                .contains("startsAtTo: to + 'T23:59:59',")
                .contains("filters: listFilters })")
                .contains("onRangeChange={list.onCalendarRange}")
                .contains("const wholeView = (viewMode === 'calendar' && calendarRange != null)")
                .doesNotContain("<Table")
                .doesNotContain("aria-label={t('calendarView')}");   // single view → no toggle

        // Plain page: legacy listView="kanban" down-grades to table-only, no filter bar.
        String plainPage = ListPages.of(entries, "ops/frontend/src/pages/plain/ui/PlainPage.tsx");
        assertThat(plainPage)
                .contains("const [viewMode] = useState<'table'>((prefs.view as 'table' | undefined) ?? openingView)")   // no toggle → no unused setter
                .doesNotContain("KanbanBoard")
                .doesNotContain("onKanbanMove")
                .doesNotContain("WHOLE_VIEW_LIMIT")
                .contains("import { useEffect, useState } from 'react'")
                .doesNotContain("FilterBar");

        // Backend Task service: a Filters carrier + composed Specification + bulk delete.
        String taskService = contentEndingWith(entries, "/service/TaskService.java");
        assertThat(taskService)
                .contains("public record Filters(")
                .contains("List<TaskStatusType> status")
                .contains("Integer priorityMin, Integer priorityMax")
                .contains("LocalDate dueDateFrom, LocalDate dueDateTo")
                .contains("findAll(String q, Filters filters, Pageable pageable)")
                .contains("public void deleteAll(java.util.List<Long> ids)");

        // Backend Task controller: filter params, CSV export, bulk-delete endpoint.
        String taskController = contentEndingWith(entries, "/controller/TaskController.java");
        assertThat(taskController)
                .contains("@RequestParam(required = false) List<TaskStatusType> status")
                .contains("DateTimeFormat.ISO.DATE")
                .contains("@GetMapping(\"/export.csv\")")
                .contains("@DeleteMapping(\"/bulk\")")
                .contains("new TaskService.Filters(")
                // CSV export streams in fixed chunks rather than loading the whole table.
                .contains("ResponseEntity<StreamingResponseBody> exportCsv(")
                .contains("PageRequest.of(pageNo++, CSV_CHUNK_SIZE, sort)")
                .contains("while (chunk.hasNext())")
                .contains("w.write('\\uFEFF')")
                .doesNotContain("Integer.MAX_VALUE");

        // Plain (no filters) keeps the simple two-arg findAll and no Filters record.
        assertThat(contentEndingWith(entries, "/service/PlainService.java"))
                .contains("findAll(String q, Pageable pageable)")
                .doesNotContain("record Filters");
    }

    @Test
    void fullstackEndpoint_perEntityOptOverrides() throws Exception {
        // Project-wide opts.scaffold=[audit, csvExport]; entity B opts out of both via its own
        // `opts` map, so only A gets the audit columns + CSV export while the project-level
        // JpaAuditingConfig still ships (it is gated on the project flag, not per entity).
        Map<String, Object> invoice = new LinkedHashMap<>();
        invoice.put("name", "Invoice");
        invoice.put("fields", List.of(pkField(), Map.of("name", "number", "type", "String")));
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("name", "Note");
        note.put("fields", List.of(pkField(), Map.of("name", "body", "type", "String")));
        note.put("opts", Map.of("audit", false, "csvExport", false));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "books");
        body.put("packageName", "com.menora.books");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", List.of(invoice, note));
        body.put("opts", Map.of("scaffold", List.of("audit", "csvExport")));

        Map<String, String> entries = generateZip(body);

        // A inherits the project flags.
        assertThat(contentEndingWith(entries, "/entity/Invoice.java"))
                .contains("@CreatedDate")
                .contains("@EntityListeners(AuditingEntityListener.class)");
        assertThat(contentEndingWith(entries, "/controller/InvoiceController.java"))
                .contains("@GetMapping(\"/export.csv\")");
        assertThat(ListPages.of(entries, "books/frontend/src/pages/invoice/ui/InvoicePage.tsx"))
                .contains("exportCsv('invoices.csv')");
        assertThat(entries.get("books/frontend/src/entities/note/model/types.ts")).isNotNull();

        // B opted out of both — on the backend and the frontend.
        assertThat(contentEndingWith(entries, "/entity/Note.java"))
                .doesNotContain("@CreatedDate")
                .doesNotContain("AuditingEntityListener");
        assertThat(contentEndingWith(entries, "/controller/NoteController.java"))
                .doesNotContain("export.csv");
        assertThat(ListPages.of(entries, "books/frontend/src/pages/note/ui/NotePage.tsx"))
                .doesNotContain("exportCsv");
        assertThat(entries.get("books/frontend/src/entities/note/model/types.ts"))
                .doesNotContain("createdAt");
        assertThat(entries.get("books/frontend/src/entities/invoice/model/types.ts"))
                .contains("createdAt");

        // The project-level auditing config is still emitted (the project flag is untouched).
        String auditingConfig = "books/backend/src/main/java/com/menora/books/config/JpaAuditingConfig.java";
        assertThat(entries).containsKey(auditingConfig);

        // The other way round: the project option off and one entity opting in on its own. Its
        // @CreatedDate fields need @EnableJpaAuditing — the config follows any audited entity,
        // not the project option (it used to be left out, and the timestamps stayed null).
        Map<String, Object> audited = new LinkedHashMap<>(note);
        audited.put("opts", Map.of("audit", true));
        body.put("entities", List.of(invoice, audited));
        body.put("opts", Map.of("scaffold", List.of("csvExport")));
        Map<String, String> oneAudited = generateZip(body);
        assertThat(contentEndingWith(oneAudited, "/entity/Note.java")).contains("@CreatedDate");
        assertThat(contentEndingWith(oneAudited, "/entity/Invoice.java")).doesNotContain("@CreatedDate");
        assertThat(oneAudited).containsKey(auditingConfig);
        // Nobody audited: no config.
        body.put("entities", List.of(invoice, note));
        assertThat(generateZip(body)).doesNotContainKey(auditingConfig);
        // Every entity opted out of the project option: nothing to enable either.
        Map<String, Object> invoiceOut = new LinkedHashMap<>(invoice);
        invoiceOut.put("opts", Map.of("audit", false));
        body.put("entities", List.of(invoiceOut, note));
        body.put("opts", Map.of("scaffold", List.of("audit")));
        assertThat(generateZip(body)).doesNotContainKey(auditingConfig);

        // An unknown override key is a 400 naming the key and the entity.
        Map<String, Object> bogus = new LinkedHashMap<>(note);
        bogus.put("opts", Map.of("bogus", true));
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "books");
            b.put("bootVersion", "3.2.1");
            b.put("entities", List.of(invoice, bogus));
        });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Unknown scaffold option 'bogus' on entity Note");
    }

    @Test
    void fullstackEndpoint_optimisticLockingSavesRelationsToVersionedEntities() throws Exception {
        // A relation set from an id alone is a stub with no version: Hibernate took it for an unsaved
        // row once the target was versioned, so every create/update/import of the relation failed
        // (409, "A required value is missing"). The service now swaps it for a managed reference.
        Map<String, Object> body = FullstackWorkflowTests.claims(false);
        body.put("opts", Map.of("scaffold", List.of("optimisticLocking", "csvImport")));
        Map<String, String> files = generateZip(body);
        String service = files.get("claims/backend/src/main/java/com/menora/claims/service/ExpenseClaimService.java").replace("\r\n", "\n");
        assertThat(service)
                .contains("    private void withReferences(ExpenseClaim entity) {\n"
                        + "        if (entity.getCostCenter() != null) {\n"
                        + "            entity.setCostCenter(entityManager.getReference(com.menora.claims.entity.CostCenter.class, entity.getCostCenter().getId()));\n")
                .contains("        withReferences(entity);\n        return repository.save(entity);")
                .contains("        ExpenseClaim existing = findById(id);\n        withReferences(updated);\n")
                .contains("            ExpenseClaim entity = dto.toEntity();\n            withReferences(entity);\n");
        // Without locking on the target, the stub is fine and the service keeps its bytes.
        Map<String, Object> unlocked = FullstackWorkflowTests.claims(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> center = ((List<Map<String, Object>>) unlocked.get("entities")).get(0);
        center.put("opts", Map.of("optimisticLocking", false));
        unlocked.put("opts", Map.of("scaffold", List.of("optimisticLocking")));
        assertThat(generateZip(unlocked).get("claims/backend/src/main/java/com/menora/claims/service/ExpenseClaimService.java"))
                .doesNotContain("withReferences");
    }

    @Test
    void fullstackEndpoint_optimisticLockingVersionsWritableEntities() throws Exception {
        // Invoice inherits the project opt; Note opts out; Summary is a read-only view.
        Map<String, Object> invoice = new LinkedHashMap<>();
        invoice.put("name", "Invoice");
        invoice.put("fields", List.of(pkField(), Map.of("name", "number", "type", "String")));
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("name", "Note");
        note.put("fields", List.of(pkField(), Map.of("name", "body", "type", "String")));
        note.put("opts", Map.of("optimisticLocking", false));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", "Summary");
        summary.put("readOnly", true);
        summary.put("viewQuery", "SELECT id, number FROM invoice");
        summary.put("fields", List.of(Map.of("name", "id", "type", "Long", "primaryKey", true),
                Map.of("name", "number", "type", "String")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "books");
        body.put("packageName", "com.menora.books");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", List.of(invoice, note, summary));
        body.put("opts", Map.of("scaffold", List.of("optimisticLocking")));

        Map<String, String> entries = generateZip(body);

        String entity = contentEndingWith(entries, "/entity/Invoice.java").replace("\r\n", "\n");
        assertThat(entity).contains("    @Version\n    @Column(name = \"version\")\n    private Long version;")
                .contains("public void setVersion(Long version)");
        String dto = contentEndingWith(entries, "/dto/InvoiceDto.java");
        assertThat(dto).contains("Long version)").contains("entity.getVersion()").contains("entity.setVersion(this.version);");
        String service = contentEndingWith(entries, "/service/InvoiceService.java");
        assertThat(service)
                .contains("import org.springframework.orm.ObjectOptimisticLockingFailureException;")
                .contains("if (updated.getVersion() != null && !updated.getVersion().equals(existing.getVersion())) {")
                .contains("throw new ObjectOptimisticLockingFailureException(Invoice.class, id);")
                .contains("entity.setVersion(null);");
        String handler = contentEndingWith(entries, "/web/ApiExceptionHandler.java");
        assertThat(handler).contains("@ExceptionHandler(ObjectOptimisticLockingFailureException.class)")
                .contains("pd.setProperty(\"stale\", true);");

        String types = entries.get("books/frontend/src/entities/invoice/model/types.ts");
        assertThat(types).contains("version?: number | null");
        String page = ListPages.of(entries, "books/frontend/src/pages/invoice/ui/InvoicePage.tsx");
        assertThat(page).contains("import { StaleNotice } from '@shared/ui/StaleNotice'")
                .contains("const isStale = (e: unknown) =>")
                .contains("} else if (isStale(e)) {")
                .contains("{ ...editing, version: current.version }")
                .contains("<StaleNotice busy={list.saving} onReload={list.loadTheirs} onOverwrite={list.saveMineAnyway} />");
        assertThat(entries).containsKey("books/frontend/src/shared/ui/StaleNotice.tsx");
        assertThat(entries.get("books/frontend/src/shared/i18n/strings.ts")).contains("saveMineAnyway: 'Save mine anyway'");

        // Opted out, and the read-only view: no version anywhere.
        assertThat(contentEndingWith(entries, "/entity/Note.java")).doesNotContain("@Version");
        assertThat(contentEndingWith(entries, "/service/NoteService.java")).doesNotContain("ObjectOptimisticLockingFailureException");
        assertThat(ListPages.of(entries, "books/frontend/src/pages/note/ui/NotePage.tsx")).doesNotContain("StaleNotice").doesNotContain("isStale");
        assertThat(contentEndingWith(entries, "/entity/Summary.java")).doesNotContain("@Version");
        assertThat(entries.get("books/frontend/src/entities/summary/model/types.ts")).doesNotContain("version");

        // An entity can opt in on its own: the shared handler and notice still ship.
        Map<String, Object> lockedNote = new LinkedHashMap<>(note);
        lockedNote.put("opts", Map.of("optimisticLocking", true));
        Map<String, Object> soloBody = new LinkedHashMap<>(body);
        soloBody.put("entities", List.of(invoice, lockedNote));
        soloBody.remove("opts");
        Map<String, String> solo = generateZip(soloBody);
        assertThat(contentEndingWith(solo, "/entity/Note.java")).contains("@Version");
        assertThat(contentEndingWith(solo, "/entity/Invoice.java")).doesNotContain("@Version");
        assertThat(contentEndingWith(solo, "/web/ApiExceptionHandler.java")).contains("ObjectOptimisticLockingFailureException");
        assertThat(solo).containsKey("books/frontend/src/shared/ui/StaleNotice.tsx");

        // Without the opt nothing changes.
        Map<String, Object> plainBody = new LinkedHashMap<>(body);
        plainBody.remove("opts");
        plainBody.put("entities", List.of(invoice));
        Map<String, String> plain = generateZip(plainBody);
        assertThat(contentEndingWith(plain, "/web/ApiExceptionHandler.java")).doesNotContain("Optimistic");
        assertThat(plain).doesNotContainKey("books/frontend/src/shared/ui/StaleNotice.tsx");
        assertThat(plain.get("books/frontend/src/shared/i18n/strings.ts")).doesNotContain("staleTitle");
    }

    @Test
    void fullstackEndpoint_inlineEditMakesScalarCellsEditable() throws Exception {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("name", "Task");
        task.put("fields", List.of(pkField(),
                Map.of("name", "title", "type", "String", "required", true),
                Map.of("name", "status", "type", "Enum", "enumValues", List.of("OPEN", "DONE")),
                Map.of("name", "estimate", "type", "Integer")));
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("name", "Note");
        note.put("fields", List.of(pkField(), Map.of("name", "body", "type", "String")));
        note.put("opts", Map.of("inlineEdit", false));

        for (String set : List.of("react-tailwind-crud", "react-menora-digital-crud")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("artifactId", "ops");
            body.put("packageName", "com.menora.ops");
            body.put("bootVersion", "3.2.1");
            body.put("frontendTemplateSet", set);
            body.put("entities", List.of(task, note));
            body.put("opts", Map.of("scaffold", List.of("inlineEdit", "optimisticLocking")));
            Map<String, String> entries = generateZip(body);

            String page = ListPages.of(entries, "ops/frontend/src/pages/task/ui/TaskPage.tsx");
            assertThat(page).as(set)
                    .contains("import { InlineCell, type InlineKind } from '@shared/ui/InlineCell'")
                    .contains("  'title': { kind: 'text' },")
                    .contains("  'status': { kind: 'enum', options: [{ value: 'OPEN', label: 'Open' }, { value: 'DONE', label: 'Done' }] },")
                    .contains("  'estimate': { kind: 'number' },")
                    .contains("const problem = validateTask(next)[field]")
                    .contains("await update(row.id as number | string, next)")
                    // With locking on, a stale cell save reloads the list instead of keeping the editor.
                    .contains("if (isStale(e)) {\n        await reload()".replace("\n", page.contains("\r\n") ? "\r\n" : "\n"))
                    // In place for those who may write (ldap-auth is a set default); plain cells otherwise.
                    .contains("columns={list.can('edit') ? list.inline(list.columns) : list.columns}");
            assertThat(entries).containsKey("ops/frontend/src/shared/ui/InlineCell.tsx");
            assertThat(ListPages.of(entries, "ops/frontend/src/pages/note/ui/NotePage.tsx")).as(set)
                    .doesNotContain("InlineCell").contains("columns={list.columns}");
        }

        // Without the opt the page and the shared UI are as they were.
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("artifactId", "ops");
        plain.put("bootVersion", "3.2.1");
        plain.put("entities", List.of(task));
        Map<String, String> entries = generateZip(plain);
        assertThat(entries).doesNotContainKey("ops/frontend/src/shared/ui/InlineCell.tsx");
        assertThat(ListPages.of(entries, "ops/frontend/src/pages/task/ui/TaskPage.tsx")).doesNotContain("INLINE_FIELDS");
    }

    @Test
    void fullstackEndpoint_listsOpenRowsRememberTheirSettingsAndGuardUnsavedEdits() throws Exception {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("name", "Task");
        task.put("fields", List.of(pkField(),
                Map.of("name", "title", "type", "String"),
                Map.of("name", "status", "type", "Enum", "enumValues", List.of("OPEN", "DONE"))));
        task.put("listViews", List.of("table", "kanban"));

        for (String set : List.of("react-tailwind-crud", "react-menora-digital-crud")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("artifactId", "ops");
            body.put("bootVersion", "3.2.1");
            body.put("frontendTemplateSet", set);
            body.put("entities", List.of(task));
            body.put("pages", List.of(Map.of("id", "tasks", "type", "entity-list", "entity", "Task")));
            Map<String, String> entries = generateZip(body);

            String page = ListPages.of(entries, "ops/frontend/src/pages/task/ui/TaskPage.tsx");
            assertThat(page).as(set)
                    // The list opens with this browser's saved settings, keeps them as they change...
                    .contains("const [prefs] = useState(() => readListPrefs('task', columns.flatMap(c => c.sortKey ? [c.sortKey] : []), ['table', 'kanban']))")
                    .contains("const [size, setSize] = useState(listState?.size ?? prefs.size ?? openingSize)")
                    .contains("useState<'table' | 'kanban'>(routeView ?? (prefs.view as 'table' | 'kanban' | undefined) ?? openingView)")
                    .contains("writeListPrefs('task', {")
                    // ...and its table offers the Columns menu (hide columns, reset the saved view).
                    .contains("columnMenu={list.columnMenu}")
                    .contains("      setViewMode(openingView)");
            String table = entries.get("ops/frontend/src/shared/ui/Table.tsx");
            assertThat(table).as(set)
                    .contains("columnMenu?: { hidden: string[]; onChange: (hidden: string[]) => void; onReset?: () => void }")
                    .contains("useListShortcuts(rootRef, searchRef, emptyAction?.onClick)")
                    .contains("data-list-table");
            if (set.equals("react-tailwind-crud")) {
                // A row opens on click and on Enter; the arrows move between rows.
                assertThat(table).contains("onClick={onView ? e => { if (opensRow(e)) onView(row) } : undefined}")
                        .contains("onKeyDown={onView ? e => onRowKey(e, () => onView(row)) : undefined}");
            } else {
                assertThat(table).contains("onRowOpen={onView}");
                assertThat(entries.get("ops/frontend/src/shared/ui/menora/Table.tsx")).contains("onRowOpen?: (row: T) => void;");
            }
            assertThat(entries).containsKeys("ops/frontend/src/shared/ui/listPrefs.ts", "ops/frontend/src/shared/ui/ColumnMenu.tsx",
                    "ops/frontend/src/shared/ui/leaveGuard.ts");

            // Unsaved edits: the drawer holds the page while dirty, and moving away asks first.
            assertThat(entries.get("ops/frontend/src/shared/ui/FormDrawer.tsx")).as(set)
                    .contains("const release = holdLeave()")
                    .contains("window.addEventListener('beforeunload', onUnload)");
            assertThat(entries.get("ops/frontend/src/app/route.ts")).as(set)
                    .contains("import { mayLeave } from '@shared/ui/leaveGuard'")
                    .contains("if (!mayLeave()) return");
            // A dialog over the drawer takes the Escape for itself.
            assertThat(entries.get("ops/frontend/src/shared/ui/ConfirmDialog.tsx")).as(set)
                    .contains("e.preventDefault()")
                    .contains("{busy ? busyLabel : confirmLabel}");
        }
    }

    @Test
    void fullstackEndpoint_rejectsAVersionFieldUnderOptimisticLocking() {
        Map<String, Object> invoice = new LinkedHashMap<>();
        invoice.put("name", "Invoice");
        invoice.put("fields", List.of(pkField(), Map.of("name", "version", "type", "Integer")));
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "books");
            b.put("bootVersion", "3.2.1");
            b.put("entities", List.of(invoice));
            b.put("opts", Map.of("scaffold", List.of("optimisticLocking")));
        });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Entity 'Invoice' already has a 'version' field or column");

        // The same field is fine when the entity opts out.
        invoice.put("opts", Map.of("optimisticLocking", false));
        ResponseEntity<String> ok = postFullstack(b -> {
            b.put("artifactId", "books");
            b.put("bootVersion", "3.2.1");
            b.put("entities", List.of(invoice));
            b.put("opts", Map.of("scaffold", List.of("optimisticLocking")));
        });
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void fullstackEndpoint_listsOfferTheFirstRecordAndTheDetailFormatsValues() throws Exception {
        // An empty, unfiltered list offers "New <entity>"; a filtered one says nothing matches.
        // The detail view words booleans and formats dates, importing only the i18n names it uses.
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("name", "Ticket");
        ticket.put("fields", List.of(pkField(),
                Map.of("name", "title", "type", "String"),
                Map.of("name", "open", "type", "Boolean"),
                Map.of("name", "due", "type", "LocalDate"),
                Map.of("name", "closedAt", "type", "LocalDateTime")));
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("name", "Note");
        note.put("fields", List.of(pkField(), Map.of("name", "text", "type", "String")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "desk");
        body.put("packageName", "com.menora.desk");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", List.of(ticket, note));

        Map<String, String> entries = generateZip(body);

        assertThat(ListPages.of(entries, "desk/frontend/src/pages/ticket/ui/TicketPage.tsx"))
                .contains("const filtered = debouncedSearch !== '' || Object.values(filters).some(v => v !== '')")
                .contains("const emptyAction = { label: t('newX', { x: 'Ticket' }), onClick: openNew }")
                .contains("filtered={list.filtered}")
                .contains("emptyAction={list.emptyAction}");
        assertThat(entries.get("desk/frontend/src/shared/ui/Table.tsx"))
                .contains("<EmptyState title={t('noMatchingRecords')} />")
                .contains("action={emptyAction}");
        // A value reads the same wherever it is shown — one function (displayTs) writes the table
        // cell, the details and a card: a boolean in the app's words, a date for its locale. The
        // table used to print `true` and an ISO date where the details worded and formatted them.
        assertThat(entries.get("desk/frontend/src/features/ticket-form/ui/TicketDetail.tsx"))
                .contains("import { t } from '@shared/i18n'")
                .contains("import { formatDate, formatDateTime } from '@shared/ui/format'")
                .contains("(value.open ? t('trueLabel') : t('falseLabel'))")
                .contains("formatDate(value.due)")
                .contains("formatDateTime(value.closedAt)")
                .contains("String(value.title)");
        assertThat(ListPages.of(entries, "desk/frontend/src/pages/ticket/ui/TicketPage.tsx"))
                .contains("import { formatDate, formatDateTime } from '@shared/ui/format'")
                .contains("render: r => r.open == null ? '—' : (r.open ? t('trueLabel') : t('falseLabel')) }")
                .contains("render: r => r.due == null ? '—' : formatDate(r.due) }")
                .contains("render: r => r.closedAt == null ? '—' : formatDateTime(r.closedAt) }")
                .contains("render: r => r.title == null ? '—' : String(r.title) }");
        // The helpers ship with the first date (they used to need a money, instant or file field).
        assertThat(entries.get("desk/frontend/src/shared/ui/format.ts"))
                .contains("export function formatDate(value: string): string {")
                .contains("const at = new Date(`${String(value).slice(0, 10)}T00:00:00`)")
                .contains("export function formatDateTime(value: string): string {");
        // Nothing to word or format: no i18n or format import at all (the generated lint rejects unused ones).
        assertThat(entries.get("desk/frontend/src/features/note-form/ui/NoteDetail.tsx"))
                .doesNotContain("@shared/i18n").doesNotContain("@shared/ui/format");

        // Cards (a board's, an inbox's) go through the same function: money in its currency, a
        // date for the locale, a file by its name — a card used to print the file's id.
        Map<String, Object> shift = FullstackExampleRequests.fieldTypesBody("react-tailwind-crud");
        shift.put("pages", List.of(
                Map.of("id", "shifts", "type", "entity-list", "entity", "Shift"),
                Map.of("id", "board", "type", "board", "entity", "Shift",
                        "cardFields", List.of("title", "rate", "contract", "clockedIn"))));
        String board = generateZip(shift).get("rota/frontend/src/app/screens/BoardScreen.tsx");
        assertThat(board)
                .contains("import { formatInstant, formatMoney } from '@shared/ui/format'")
                .contains("r.rate == null ? '—' : formatMoney(r.rate, 'USD')")
                .contains("r.contract == null ? '—' : (r.contractName ?? '—')")
                .contains("r.clockedIn == null ? '—' : formatInstant(r.clockedIn)");
        // A tile over a money field writes its number in the field's currency.
        Map<String, Object> money = FullstackExampleRequests.fieldTypesBody("react-tailwind-crud");
        money.put("pages", List.of(
                Map.of("id", "shifts", "type", "entity-list", "entity", "Shift"),
                Map.of("id", "home", "type", "dashboard", "widgets", List.of(
                        Map.of("kind", "kpi", "entity", "Shift", "agg", "sum", "field", "rate"),
                        Map.of("kind", "kpi", "entity", "Shift")))));
        Map<String, String> tiles = generateZip(money);
        assertThat(tiles.get("rota/frontend/src/app/screens/HomeScreen.tsx").replace("\r\n", "\n"))
                .contains("          agg=\"sum\"\n          field=\"rate\"\n          currency=\"USD\"\n")
                .containsOnlyOnce("currency=");
        assertThat(tiles.get("rota/frontend/src/shared/ui/stats.ts"))
                .contains("export function formatStat(value: number | null, currency?: string): string {");
    }
}
