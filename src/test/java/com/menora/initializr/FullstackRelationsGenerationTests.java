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

/** {@code /starter-fullstack.zip}: many-to-one, many-to-many and one-to-many relations. */
@IntegrationTest
@Tag("slow") // left out of mvn test -Pquick
class FullstackRelationsGenerationTests extends FullstackEndpointTestSupport {

    @Test
    void fullstackEndpoint_rendersManyToOneRelationship() throws Exception {
        // Order has a required MANY_TO_ONE to Customer. The owning entity gets a @ManyToOne
        // + @JoinColumn, the DTO exposes the FK as customerId (with @NotNull since required),
        // the service copies the association on update, and the frontend type/form/page carry
        // the customerId field.
        Map<String, Object> customerName = Map.of("name", "name", "type", "String", "required", true);
        Map<String, Object> orderTotal = Map.of("name", "total", "type", "BigDecimal", "required", true);
        Map<String, Object> orderCustomerRel = Map.of(
                "type", "MANY_TO_ONE", "fieldName", "customer", "targetEntity", "Customer", "required", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Customer", "fields", List.of(pkField(), customerName)),
                Map.of("name", "Order",
                        "fields", List.of(pkField(), orderTotal),
                        "relations", List.of(orderCustomerRel))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        // Owning entity: JPA association + a @Formula label column (the target's first string
        // field) so list/detail views can show "Acme" instead of a raw FK id without an open session.
        String orderEntity = contentEndingWith(entries, "/entity/Order.java");
        assertThat(orderEntity)
                .contains("@ManyToOne(fetch = FetchType.LAZY, optional = false)")
                .contains("@JoinColumn(name = \"customer_id\", nullable = false)")
                .contains("private Customer customer;")
                .contains("public Customer getCustomer()")
                .contains("import org.hibernate.annotations.Formula;")
                .contains("@Formula(\"(select t.name from customers t where t.id = customer_id)\")")
                .contains("private String customerLabel;")
                .contains("public String getCustomerLabel()");

        // DTO: FK exposed as customerId (+ read-only customerLabel), imports the target entity,
        // @NotNull because required.
        String orderDto = contentEndingWith(entries, "/dto/OrderDto.java");
        assertThat(orderDto)
                .contains("import com.menora.shop.entity.Customer;")
                .contains("@NotNull Long customerId,")
                .contains("String customerLabel")
                .contains("entity.getCustomer() == null ? null : entity.getCustomer().getId(),")
                .contains("entity.getCustomerLabel()")
                .contains("Customer customer = new Customer();")
                .contains("customer.setId(this.customerId);")
                .contains("entity.setCustomer(customer);")
                // Comma-correctness: the label is the last record component, so it must NOT be
                // followed by a comma. CRLF-agnostic so it holds regardless of resource line endings.
                .contains("Long id,")
                .doesNotContain("customerLabel,")
                .doesNotContain(",,");

        // Service copies the association on update and filters by the relation's FK.
        assertThat(contentEndingWith(entries, "/service/OrderService.java"))
                .contains("existing.setCustomer(updated.getCustomer());")
                .contains("List<Long> customerId")
                .contains("root.get(\"customer\").get(\"id\").in(filters.customerId())");
        assertThat(contentEndingWith(entries, "/controller/OrderController.java"))
                .contains("@RequestParam(required = false) List<Long> customerId");

        // Frontend: type + form + page carry customerId (and the label).
        assertThat(entries.get("shop/frontend/src/entities/order/model/types.ts"))
                .contains("customerId: number | null")
                .contains("customerLabel?: string | null");
        // The FK is picked by typing: the target's label is searchable, so a RelationPicker
        // searches its list endpoint (a target without one keeps the useOptions <select>).
        assertThat(entries.get("shop/frontend/src/features/order-form/ui/OrderForm.tsx"))
                .contains("import { Field, inputClass, RelationPicker } from '@shared/ui'")
                .contains("path=\"/api/customers\"")
                .contains("label=\"Customer\" required")
                .contains("set('customerId'");
        // Table column shows the label (falling back to #id), and the filter bar gets a relation select.
        assertThat(ListPages.of(entries, "shop/frontend/src/pages/order/ui/OrderPage.tsx"))
                .contains("label: 'Customer', render: r => r.customerLabel ?? (r.customerId == null ? '—' : '#' + String(r.customerId))")
                .contains("kind: 'relation', optionsPath: '/api/customers', optionValue: 'id', optionLabel: 'name'");
        assertThat(entries.get("shop/frontend/src/features/order-form/ui/OrderDetail.tsx"))
                .contains("value.customerLabel ?? (");
        assertThat(entries.get("shop/frontend/src/shared/ui/FilterBar.tsx"))
                .contains("f.kind === 'relation'").contains("useOptions");

        // Customer (the target) is unaffected — no relations of its own.
        assertThat(contentEndingWith(entries, "/entity/Customer.java")).doesNotContain("@ManyToOne");
    }

    @Test
    void fullstackEndpoint_rejectsRelationToUnknownEntity() {
        Map<String, Object> rel = Map.of("type", "MANY_TO_ONE", "fieldName", "customer", "targetEntity", "Ghost");
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "shop");
            b.put("bootVersion", "3.2.1");
            b.put("entities", List.of(
                    Map.of("name", "Order", "fields", List.of(pkField()), "relations", List.of(rel))));
        });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("unknown entity");
    }

    @Test
    void fullstackEndpoint_inverseOptAddsOneToManyCollection() throws Exception {
        Map<String, Object> orderCustomerRel = Map.of(
                "type", "MANY_TO_ONE", "fieldName", "customer", "targetEntity", "Customer", "required", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("opts", Map.of("scaffold", List.of("inverseCollections")));
        body.put("entities", List.of(
                Map.of("name", "Customer", "fields", List.of(pkField(), Map.of("name", "name", "type", "String"))),
                Map.of("name", "Order",
                        "fields", List.of(pkField(), Map.of("name", "total", "type", "BigDecimal")),
                        "relations", List.of(orderCustomerRel))));

        Map<String, String> entries = generateZip(body);

        // The parent (Customer) gets the inverse collection; the DTO surfaces a read-only count.
        assertThat(contentEndingWith(entries, "/entity/Customer.java"))
                .contains("@OneToMany(mappedBy = \"customer\")")
                .contains("private List<Order> orders = new ArrayList<>();")
                // The count is a DB-computed @Formula column: the DTO is built outside the
                // service transaction (open-in-view off), so touching the lazy collection there
                // would throw LazyInitializationException on every GET.
                .contains("import org.hibernate.annotations.Formula;")
                .contains("@Formula(\"(select count(*) from orders c where c.customer_id = id)\")")
                .contains("private Long ordersCount;")
                .contains("public Long getOrdersCount()");
        assertThat(contentEndingWith(entries, "/dto/CustomerDto.java"))
                .contains("int ordersCount")
                .contains("entity.getOrdersCount() == null ? 0 : entity.getOrdersCount().intValue()")
                .doesNotContain("entity.getOrders().size()");
    }

    @Test
    void fullstackEndpoint_manyToManyRelation() throws Exception {
        Map<String, String> entries = generateZip(FullstackExampleRequests.manyToManyBody("react-tailwind-crud"));

        // The owner holds a set through a join table; the names are derived when none are given.
        assertThat(contentEndingWith(entries, "/entity/Post.java"))
                .contains("@ManyToMany(fetch = FetchType.LAZY)")
                .contains("@JoinTable(name = \"post_tags\",")
                .contains("joinColumns = @JoinColumn(name = \"post_id\"),")
                .contains("inverseJoinColumns = @JoinColumn(name = \"tag_id\"))")
                .contains("private java.util.Set<Tag> tags = new java.util.LinkedHashSet<>();")
                // The foreign key beside it is untouched by any of this.
                .contains("@ManyToOne(fetch = FetchType.LAZY)")
                .contains("private Author author;");
        // The DTO carries the linked rows' ids and labels, after everything it had.
        assertThat(contentEndingWith(entries, "/dto/PostDto.java"))
                .contains("Long authorId")
                .contains("java.util.List<Long> tagsIds,")
                .contains("java.util.List<String> tagsLabels) {")
                .contains("entity.getTags().stream().map(t -> t.getId()).toList()")
                .contains("map(t -> String.valueOf(t.getName())).toList()")
                // No list in the request leaves the links alone.
                .contains("if (this.tagsIds == null) {");
        assertThat(contentEndingWith(entries, "/service/PostService.java"))
                // Read while the session is open: the DTO is built after the transaction.
                .contains("org.hibernate.Hibernate.initialize(entity.getTags());")
                .contains("page.forEach(this::loaded);")
                // Ids become the rows they name, in one query; an unknown one is a 400.
                .contains("\"select t from Tag t where t.id in :ids\"")
                .contains("throw new IllegalArgumentException(\"tags: no Tag with one of the ids \" + ids);")
                .contains("existing.getTags().clear();")
                // The filter is a subquery on the set, not a join that would repeat rows.
                .contains("owner.join(\"tags\").get(\"id\").in(filters.tagsId())")
                .contains("return cb.exists(linked);")
                // The foreign key's filter is the plain one it always was.
                .contains("root.get(\"author\").get(\"id\").in(filters.authorId())");
        assertThat(contentEndingWith(entries, "/controller/PostController.java"))
                .contains("List<Long> tagsId")
                .contains("csv(String.join(\"; \", dto.tagsLabels())),");
        // The other side knows nothing of the link.
        assertThat(contentEndingWith(entries, "/entity/Tag.java")).doesNotContain("ManyToMany").doesNotContain("Post");

        // Frontend: the multi-pick control, the labels in the list and the detail, the filter.
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("frontend/src/shared/ui/MultiRelationPicker.tsx"));
        assertThat(contentEndingWith(entries, "/post/model/types.ts"))
                .contains("tagsIds?: number[] | null").contains("tagsLabels?: string[] | null");
        assertThat(contentEndingWith(entries, "/ui/PostForm.tsx"))
                .contains("import { MultiRelationPicker } from '@shared/ui/MultiRelationPicker'")
                .contains("path=\"/api/tags\"")
                .contains("labelKey=\"name\"")
                .contains("onChange={(ids, labels) => onChange({ ...value, tagsIds: ids as number[], tagsLabels: labels })}");
        assertThat(ListPages.of(entries, "/ui/PostPage.tsx"))
                .contains("label: 'Tags', render: r => r.tagsLabels == null || r.tagsLabels.length === 0 ? '—' : r.tagsLabels.join(', ')")
                .contains("name: 'tagsId'")
                // A new record is pre-filled from a filter on a foreign key, never from one on a set.
                .contains("record['authorId']")
                .doesNotContain("record['tagsId']");
        assertThat(contentEndingWith(entries, "/ui/PostDetail.tsx"))
                .contains("<Row label=\"Tags\">");
    }

    @Test
    void fullstackEndpoint_oneToManyRelation() throws Exception {
        Map<String, String> entries = generateZip(FullstackExampleRequests.oneToManyBody("react-tailwind-crud"));

        // The parent reads the link its child holds: a collection and a count the database computes.
        assertThat(contentEndingWith(entries, "/entity/Order.java"))
                .contains("@OneToMany(mappedBy = \"order\")")
                .contains("private List<OrderLine> lines = new ArrayList<>();")
                .contains("@Formula(\"(select count(*) from order_lines c where c.order_id = id)\")")
                .contains("private Long linesCount;")
                // Its own foreign key is what it was.
                .contains("private Customer customer;");
        assertThat(contentEndingWith(entries, "/dto/OrderDto.java")).contains("int linesCount");
        // The link named by mappedBy, under the name the model gave the list.
        assertThat(contentEndingWith(entries, "/entity/Customer.java"))
                .contains("@OneToMany(mappedBy = \"customer\")")
                .contains("private List<Order> orders = new ArrayList<>();");
        // Two lists over one child, each by its own link.
        assertThat(contentEndingWith(entries, "/entity/Account.java"))
                .contains("@OneToMany(mappedBy = \"source\")").contains("private List<Transfer> outgoing")
                .contains("@OneToMany(mappedBy = \"target\")").contains("private List<Transfer> incoming")
                .contains("where c.source_id = id").contains("where c.target_id = id");
        // An entity's own rows.
        assertThat(contentEndingWith(entries, "/entity/Employee.java"))
                .contains("@OneToMany(mappedBy = \"manager\")").contains("private List<Employee> reports");
        // Nothing is derived for a link nobody declared: inverseCollections is off.
        assertThat(contentEndingWith(entries, "/entity/Product.java")).doesNotContain("OneToMany");
        // The child's side is untouched.
        assertThat(contentEndingWith(entries, "/entity/OrderLine.java")).doesNotContain("OneToMany");

        // Frontend: the count on the type and the list, and the child rows as a table in the details.
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("frontend/src/shared/ui/RelatedRows.tsx"));
        assertThat(contentEndingWith(entries, "/order/model/types.ts")).contains("linesCount?: number | null");
        assertThat(ListPages.of(entries, "/ui/OrderPage.tsx")).contains("label: 'Lines', render: r => r.linesCount");
        assertThat(contentEndingWith(entries, "/ui/OrderDetail.tsx"))
                .contains("import { RelatedRows } from '@shared/ui/RelatedRows'")
                .contains("title={ 'Lines' }")
                .contains("path=\"/api/order-lines\"")
                .contains("param=\"orderId\"")
                .contains("id={value.id}")
                .contains("count={value.linesCount}")
                .contains("{ key: 'quantity', label: 'Quantity' },")
                // Another link of the child's by its label, else its id; the link back is left out.
                .contains("{ key: 'productLabel', label: 'Product', alt: 'productId' },")
                .doesNotContain("key: 'orderLabel'")
                // Long text has no place in a table cell, and the count is the table's heading.
                .doesNotContain("key: 'note'")
                .doesNotContain("<Row label=\"Lines\">");
        assertThat(contentEndingWith(entries, "/ui/CustomerDetail.tsx"))
                .contains("import { OrderStatusTypeLabels } from '@entities/order'")
                .contains("{ key: 'status', label: 'Status', labels: OrderStatusTypeLabels },")
                .contains("param=\"customerId\"");
        // Two tables over one child import its label map once.
        String account = contentEndingWith(entries, "/ui/AccountDetail.tsx");
        assertThat(account).containsOnlyOnce("import { TransferKindTypeLabels } from '@entities/transfer'")
                .contains("param=\"sourceId\"").contains("param=\"targetId\"")
                // The other link of a transfer is a column; the one the table is about is not.
                .contains("title={ 'Outgoing' }").contains("title={ 'Incoming' }");
        // A table of the entity's own rows reads the label map its details already import.
        assertThat(contentEndingWith(entries, "/ui/EmployeeDetail.tsx"))
                .containsOnlyOnce("import { EmployeeGradeTypeLabels")
                .contains("param=\"managerId\"")
                .contains("labels: EmployeeGradeTypeLabels");
        // A project without a ONE_TO_MANY ships no table.
        assertThat(generateZip(FullstackExampleRequests.manyToManyBody("react-tailwind-crud")).keySet())
                .noneMatch(p -> p.endsWith("RelatedRows.tsx"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullstackEndpoint_oneToManyNamesTheCollectionInverseCollectionsWouldDerive() throws Exception {
        Map<String, Object> body = FullstackExampleRequests.oneToManyBody("react-tailwind-crud");
        ((Map<String, Object>) body.get("opts")).put("scaffold", List.of("inverseCollections"));
        Map<String, String> entries = generateZip(body);
        // The declared list stands in for the derived one: one collection per link, not two.
        assertThat(contentEndingWith(entries, "/entity/Order.java"))
                .containsOnlyOnce("@OneToMany").contains("private List<OrderLine> lines").doesNotContain("orderLines");
        // A link nobody declared is still derived, and stays a count among the details.
        assertThat(contentEndingWith(entries, "/entity/Product.java")).contains("private List<OrderLine> orderLines");
        assertThat(contentEndingWith(entries, "/ui/ProductDetail.tsx"))
                .contains("<Row label=\"OrderLines\">").doesNotContain("RelatedRows");
        assertThat(contentEndingWith(entries, "/entity/Account.java"))
                .contains("outgoing").contains("incoming").doesNotContain("transfersBy");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullstackEndpoint_rejectsInvalidOneToMany() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // entity index, its relations, the message, the path.
        record Bad(int entity, List<Map<String, Object>> relations, String message, String path) {}
        Map<String, Object> order = Map.of("type", "MANY_TO_ONE", "fieldName", "order", "targetEntity", "Order", "required", true);
        for (Bad bad : List.of(
                new Bad(1, List.of(Map.of("type", "ONE_TO_MANY", "fieldName", "buyers", "targetEntity", "Customer")),
                        "which has no MANY_TO_ONE to 'Product'", "entities[1].relations[0].targetEntity"),
                new Bad(4, List.of(Map.of("type", "ONE_TO_MANY", "fieldName", "transfers", "targetEntity", "Transfer")),
                        "has several MANY_TO_ONEs to 'Account' (source, target) — name one with mappedBy", "entities[4].relations[0].mappedBy"),
                new Bad(4, List.of(Map.of("type", "ONE_TO_MANY", "fieldName", "transfers", "targetEntity", "Transfer", "mappedBy", "sender")),
                        "mappedBy 'sender' is not a MANY_TO_ONE of 'Transfer' to 'Account' (it has: source, target)", "entities[4].relations[0].mappedBy"),
                new Bad(4, List.of(Map.of("type", "ONE_TO_MANY", "fieldName", "outgoing", "targetEntity", "Transfer", "mappedBy", "source"),
                        Map.of("type", "ONE_TO_MANY", "fieldName", "sent", "targetEntity", "Transfer", "mappedBy", "SOURCE")),
                        "lists the same rows as another ONE_TO_MANY", "entities[4].relations[1].mappedBy"),
                new Bad(0, List.of(Map.of("type", "ONE_TO_MANY", "fieldName", "orders", "targetEntity", "Order", "required", true)),
                        "required does not apply to a ONE_TO_MANY", "entities[0].relations[0].required"),
                new Bad(0, List.of(Map.of("type", "ONE_TO_MANY", "fieldName", "orders", "targetEntity", "Order", "joinColumn", "customer_id")),
                        "joinColumn does not apply to a ONE_TO_MANY", "entities[0].relations[0].joinColumn"),
                new Bad(3, List.of(order, Map.of("type", "MANY_TO_ONE", "fieldName", "product", "targetEntity", "Product", "mappedBy", "lines")),
                        "mappedBy only applies to a ONE_TO_MANY", "entities[3].relations[1].mappedBy"))) {
            Map<String, Object> body = FullstackExampleRequests.oneToManyBody(null);
            ((List<Map<String, Object>>) body.get("entities")).get(bad.entity()).put("relations", bad.relations());
            ResponseEntity<String> response = restTemplate.exchange("/starter-fullstack.zip",
                    org.springframework.http.HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            assertThat(response.getStatusCode()).as(bad.message()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains(bad.message()).contains("\"path\":\"" + bad.path() + "\"");
        }

        // The count a list adds is a member like any other.
        Map<String, Object> body = FullstackExampleRequests.oneToManyBody(null);
        Map<String, Object> orderEntity = ((List<Map<String, Object>>) body.get("entities")).get(2);
        ((List<Map<String, Object>>) orderEntity.get("fields")).add(Map.of("name", "linesCount", "type", "INTEGER"));
        ResponseEntity<String> response = restTemplate.exchange("/starter-fullstack.zip",
                org.springframework.http.HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("field 'linesCount' clashes with 'linesCount'").contains("the count of relation 'lines'");
    }

    @Test
    void fullstackEndpoint_rejectsInvalidManyToMany() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        record Bad(Map<String, Object> relation, String message) {}
        for (Bad bad : List.of(
                new Bad(Map.of("type", "MANY_TO_MANY", "fieldName", "related", "targetEntity", "Post"), "MANY_TO_MANY to itself"),
                new Bad(Map.of("type", "MANY_TO_MANY", "fieldName", "tags", "targetEntity", "Tag", "required", true), "required does not apply"),
                new Bad(Map.of("type", "MANY_TO_MANY", "fieldName", "tags", "targetEntity", "Tag", "joinTable", "post tags;"), "not a valid"),
                new Bad(Map.of("type", "MANY_TO_ONE", "fieldName", "tag", "targetEntity", "Tag", "joinTable", "post_tag"), "only apply to a MANY_TO_MANY"),
                new Bad(Map.of("type", "ONE_TO_MANY", "fieldName", "tags", "targetEntity", "Tag"), "which has no MANY_TO_ONE to 'Post'"))) {
            Map<String, Object> body = FullstackExampleRequests.manyToManyBody(null);
            @SuppressWarnings("unchecked")
            Map<String, Object> post = ((List<Map<String, Object>>) body.get("entities")).get(2);
            post.put("relations", List.of(bad.relation()));
            ResponseEntity<String> response = restTemplate.exchange("/starter-fullstack.zip",
                    org.springframework.http.HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            assertThat(response.getStatusCode()).as(bad.message()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains(bad.message()).contains("entities[2].relations[0]");
        }
    }

    @Test
    void fullstackEndpoint_twoLinksToOneParentGetTwoInverseCollections() throws Exception {
        // Both links derived `transfers` on Account before, twice — a class that does not compile.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "bank");
        body.put("packageName", "com.menora.bank");
        body.put("bootVersion", "3.2.1");
        body.put("opts", Map.of("scaffold", List.of("inverseCollections")));
        body.put("entities", List.of(
                Map.of("name", "Account", "fields", List.of(pkField(), Map.of("name", "name", "type", "String"))),
                Map.of("name", "Transfer",
                        "fields", List.of(pkField(), Map.of("name", "amount", "type", "BigDecimal")),
                        "relations", List.of(
                                Map.of("type", "MANY_TO_ONE", "fieldName", "source", "targetEntity", "Account", "required", true),
                                Map.of("type", "MANY_TO_ONE", "fieldName", "target", "targetEntity", "Account", "required", true)))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/entity/Account.java"))
                .contains("@OneToMany(mappedBy = \"source\")")
                .contains("private List<Transfer> transfersBySource = new ArrayList<>();")
                .contains("@OneToMany(mappedBy = \"target\")")
                .contains("private List<Transfer> transfersByTarget = new ArrayList<>();")
                .contains("private Long transfersBySourceCount;")
                .contains("private Long transfersByTargetCount;")
                .doesNotContain("List<Transfer> transfers ");
        assertThat(contentEndingWith(entries, "/dto/AccountDto.java"))
                .contains("int transfersBySourceCount")
                .contains("int transfersByTargetCount");
    }

    @Test
    void fullstackEndpoint_picksASearchableLinkByTyping() throws Exception {
        // A link to an entity whose label field is searchable is picked by typing (RelationPicker,
        // server-side `q`), in the form and in the filter bar; a target without a searchable label
        // keeps the one-page <select>.
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "Customer");
        customer.put("fields", List.of(pkField(), Map.of("name", "name", "type", "String")));
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("name", "Region");
        region.put("fields", List.of(pkField(), Map.of("name", "code", "type", "Integer")));
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("name", "Order");
        order.put("fields", List.of(pkField(), Map.of("name", "ref", "type", "String")));
        order.put("relations", List.of(
                Map.of("type", "MANY_TO_ONE", "fieldName", "customer", "targetEntity", "Customer"),
                Map.of("type", "MANY_TO_ONE", "fieldName", "region", "targetEntity", "Region")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", List.of(customer, region, order));

        Map<String, String> entries = generateZip(body);

        assertThat(entries).containsKey("shop/frontend/src/shared/ui/RelationPicker.tsx");
        assertThat(entries.get("shop/frontend/src/shared/ui/index.ts")).contains("export { RelationPicker } from './RelationPicker'");
        assertThat(entries.get("shop/frontend/src/features/order-form/ui/OrderForm.tsx"))
                .contains("import { Field, inputClass, RelationPicker } from '@shared/ui'")
                .contains("import { useOptions } from '@shared/api'")
                .contains("path=\"/api/customers\"")
                .contains("labelKey=\"name\"")
                .contains("onChange={v => set('customerId', v == null ? null : Number(v))}")
                .contains("const regionOptions = useOptions<Record<string, unknown>>('/api/regions')")
                .doesNotContain("customerOptions");
        assertThat(ListPages.of(entries, "shop/frontend/src/pages/order/ui/OrderPage.tsx"))
                .contains("optionsPath: '/api/customers', optionValue: 'id', optionLabel: 'name', searchable: true }")
                .contains("optionsPath: '/api/regions', optionValue: 'id' }");
        assertThat(entries.get("shop/frontend/src/shared/ui/FilterBar.tsx"))
                .contains("f.searchable && f.optionLabel");
        // Every link searchable: the form needs no useOptions.
        Map<String, Object> onlyCustomer = new LinkedHashMap<>(order);
        onlyCustomer.put("relations", List.of(Map.of("type", "MANY_TO_ONE", "fieldName", "customer", "targetEntity", "Customer")));
        body.put("entities", List.of(customer, onlyCustomer));
        assertThat(generateZip(body).get("shop/frontend/src/features/order-form/ui/OrderForm.tsx"))
                .doesNotContain("useOptions");
    }

    @Test
    void fullstackEndpoint_relationsCarryTheirOwnLabel() throws Exception {
        // A relation used to be called by its field name in PascalCase everywhere ("Customer" in a
        // Hebrew app). It now takes a label like a field does; without one nothing changes.
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "Customer");
        customer.put("fields", List.of(pkField(), Map.of("name", "name", "type", "String")));
        customer.put("relations", List.of(
                Map.of("type", "ONE_TO_MANY", "fieldName", "orders", "targetEntity", "Order", "mappedBy", "customer",
                        "label", "הזמנות של \"לקוח\"")));
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("name", "Tag");
        tag.put("fields", List.of(pkField(), Map.of("name", "name", "type", "String")));
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("name", "Order");
        order.put("fields", List.of(pkField(), Map.of("name", "reference", "type", "String")));
        order.put("relations", List.of(
                Map.of("type", "MANY_TO_ONE", "fieldName", "customer", "targetEntity", "Customer", "required", true,
                        "label", "  לקוח מס' \"1\"  "),
                Map.of("type", "MANY_TO_ONE", "fieldName", "billTo", "targetEntity", "Customer"),
                Map.of("type", "MANY_TO_MANY", "fieldName", "tags", "targetEntity", "Tag", "label", "תגיות")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(customer, tag, order));
        body.put("opts", Map.of("scaffold", List.of("csvImport")));

        Map<String, String> entries = generateZip(body);

        String page = ListPages.of(entries, "shop/frontend/src/pages/order/ui/OrderPage.tsx");
        assertThat(page)
                // Column headings: the label (trimmed, escaped for a TS literal); unlabelled as before.
                .contains("label: 'לקוח מס\\' \"1\"', render: r =>")
                .contains("label: 'BillTo', render: r =>")
                .contains("label: 'תגיות', render: r =>")
                // The filter bar's controls.
                .contains("{ name: 'customerId', label: 'לקוח מס\\' \"1\"', kind: 'relation'")
                .contains("{ name: 'billToId', label: 'BillTo', kind: 'relation'")
                .contains("{ name: 'tagsId', label: 'תגיות', kind: 'relation'");
        assertThat(entries.get("shop/frontend/src/features/order-form/ui/OrderForm.tsx"))
                .contains("<Field label=\"לקוח מס' &quot;1&quot;\" required error={errors?.customerId}>")
                .contains("<Field label=\"BillTo\" error={errors?.billToId}>")
                .contains("<Field label=\"תגיות\" error={errors?.tagsIds}>");
        assertThat(contentEndingWith(entries, "/OrderDetail.tsx"))
                .contains("<Row label=\"לקוח מס' &quot;1&quot;\">")
                .contains("<Row label=\"תגיות\">");
        assertThat(contentEndingWith(entries, "order-form/model/importFields.ts"))
                .contains("{ name: 'customerId', label: 'לקוח מס\\' \"1\"', kind: 'relation'");
        // A ONE_TO_MANY's label names its count column and the table of child rows in the details.
        assertThat(ListPages.of(entries, "shop/frontend/src/pages/customer/ui/CustomerPage.tsx"))
                .contains("label: 'הזמנות של \"לקוח\"', render: r => r.ordersCount == null");
        assertThat(contentEndingWith(entries, "/CustomerDetail.tsx"))
                .contains("title={ 'הזמנות של \"לקוח\"' }");
        // Labels are a UI concern: the backend keeps the field names.
        assertThat(contentEndingWith(entries, "/dto/OrderDto.java")).doesNotContain("לקוח");

        // Over 80 characters is a 400 that says where.
        Map<String, Object> longLabel = new LinkedHashMap<>(order);
        longLabel.put("relations", List.of(Map.of("type", "MANY_TO_ONE", "fieldName", "customer",
                "targetEntity", "Customer", "label", "x".repeat(81))));
        body.put("entities", List.of(customer, tag, longLabel));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> tooLong = restTemplate.exchange("/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody()).contains("label is over 80 characters").contains("entities[2].relations[0].label");
    }
}
