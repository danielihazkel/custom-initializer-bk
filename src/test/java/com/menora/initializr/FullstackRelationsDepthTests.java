package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relations and rows depth (Round 14, phase 4): a many-to-many declared from both sides and
 * reached by bulk edit, cells edited in place, a wizard and the demo rows; a parent whose rules and
 * computed fields read the rows saved with it ({@code sum(lines.lineTotal)}, {@code count(lines)});
 * the version check a parent of rows always carries. Generated text here; the behaviour over a
 * real API is in {@code GeneratedBackendBuildSmokeTests}.
 */
@IntegrationTest
class FullstackRelationsDepthTests {

    @Autowired
    private TestRestTemplate rest;

    // ── A many-to-many from both sides ──────────────────────────────────────────────────────

    @Test
    void theOtherSideOfAManyToManyIsReadThereAndChangedWhereTheLinkIsHeld() throws Exception {
        Map<String, String> files = generate(FullstackExampleRequests.twoSidedManyToManyBody("react-tailwind-crud"));
        // Tag.posts maps nothing of its own: it is Post.tags, read from the other end.
        assertThat(file(files, "entity/Tag.java"))
                .contains("    @ManyToMany(mappedBy = \"tags\", fetch = FetchType.LAZY)\n"
                        + "    @org.hibernate.annotations.BatchSize(size = 100)\n"
                        + "    private java.util.Set<Post> posts = new java.util.LinkedHashSet<>();")
                .doesNotContain("@JoinTable");
        assertThat(file(files, "entity/Post.java")).contains("@JoinTable(name = \"post_tags\",");
        assertThat(file(files, "dto/TagDto.java")).contains("java.util.List<Long> postsIds").contains("java.util.List<String> postsLabels");

        String service = file(files, "service/TagService.java");
        assertThat(service)
                // A change made from this side is made in the posts' own sets, told apart by key.
                .contains("    private void linkPosts(Tag row, java.util.Collection<com.menora.press.entity.Post> wanted) {")
                .contains("owner.getTags().removeIf(mine -> java.util.Objects.equals(mine.getId(), row.getId()));")
                .contains("owner.getTags().add(row);")
                // On a create (once the row has its key), an update, and before a row is really deleted.
                .contains("        return linkedBack(repository.save(entity));")
                .contains("            linkPosts(existing, updated.getPosts());\n"
                        + "            existing.getPosts().clear();")
                .contains("        repository.delete(unlinked(findById(id)));")
                .contains("        rows.forEach(this::unlinked);")
                // The filter is the same subquery from either end.
                .contains("owner.join(\"posts\").get(\"id\").in(filters.postsId())");
        // The side that holds the link needs none of it.
        assertThat(file(files, "service/PostService.java"))
                .doesNotContain("linkedBack").doesNotContain("unlinked(").contains("existing.getTags().addAll(updated.getTags());");

        // One join table, the owner's.
        ResponseEntity<String> ddl = rest.exchange("/starter-fullstack.ddl", HttpMethod.POST,
                new HttpEntity<>(FullstackExampleRequests.twoSidedManyToManyBody("react-tailwind-crud"), json()), String.class);
        assertThat(ddl.getStatusCode()).as(ddl.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ddl.getBody()).contains("post_tags").doesNotContain("tag_posts");

        // The form picks the posts of a tag as it picks the tags of a post.
        assertThat(file(files, "tag-form/ui/TagForm.tsx")).contains("value={value.postsIds ?? []}");
        assertThat(file(files, "entities/tag/model/types.ts")).contains("postsIds?:");
    }

    @Test
    void aSetIsChangedInBulkInPlaceInAWizardAndFilledInTheDemoRows() throws Exception {
        Map<String, Object> body = FullstackExampleRequests.twoSidedManyToManyBody("react-tailwind-crud");
        body.put("pages", List.of(
                Map.of("id", "posts", "type", "entity-list", "entity", "Post"),
                Map.of("id", "tags", "type", "entity-list", "entity", "Tag"),
                Map.of("id", "authors", "type", "entity-list", "entity", "Author"),
                Map.of("id", "new-post", "type", "wizard", "entity", "Post", "steps", List.of(
                        Map.of("title", "The post", "fields", List.of("title", "author")),
                        Map.of("title", "Its tags", "fields", List.of("Tags"))))));
        Map<String, String> files = generate(body);

        // Bulk: the same rows on every picked record — resolved once, an unknown id a 400.
        assertThat(file(files, "service/PostService.java"))
                .contains("    private static java.util.Set<com.menora.press.entity.Tag> stubsTags(Object value) {")
                .contains("            stub.setId(Long.valueOf(String.valueOf(id)));")
                .contains("            case \"tags\" -> links.setTags(stubsTags(value));")
                .contains("        resolveLinks(links);")
                .contains("                case \"tags\" -> {\n"
                        + "                    entity.getTags().clear();\n"
                        + "                    entity.getTags().addAll(links.getTags());\n"
                        + "                }");
        // The other side is not set in bulk (its rows are changed where the link is held).
        assertThat(file(files, "service/TagService.java")).doesNotContain("stubsPosts");

        String list = file(files, "pages/post/model/usePostList.tsx");
        assertThat(list)
                .contains("'tags': { label: 'Tags', kind: 'links', picker: { path: '/api/tags', valueKey: 'id', labelKey: 'name', numeric: true } },")
                .contains("await updateMany(selected, bulkField, picker ? (picker.numeric ? ids.map(Number) : ids) : bulkValue === '' ? null : bulkValue)")
                // In place: the cell opens the picker and saves the ids with the row.
                .contains("export type PostColumn = Column<Post> & { links?: string }")
                .contains("  'tags': { ids: 'tagsIds', labels: 'tagsLabels', path: '/api/tags', valueKey: 'id', labelKey: 'name', numeric: true },")
                .contains("            onSave={ids => saveCell(r, set.ids, ids)}");
        assertThat(file(files, "pages/post/ui/PostPage.tsx"))
                .contains("links: 'tags', label: 'Tags'")
                .contains("<option value=\"tags\">Tags</option>")
                .contains("import { MultiRelationPicker } from '@shared/ui/MultiRelationPicker'");
        assertThat(files.keySet()).anyMatch(path -> path.endsWith("shared/ui/InlineLinks.tsx"));
        // The other side is edited in place too (its save goes through the same PUT).
        assertThat(file(files, "pages/tag/model/useTagList.tsx")).contains("'posts': { ids: 'postsIds', labels: 'postsLabels', path: '/api/posts',");

        // A wizard step asks for the set, and owns its message.
        assertThat(file(files, "screens/NewPostScreen.tsx"))
                .contains("{ title: 'Its tags', fields: ['tags'], keys: ['tagsIds'] },");

        // Demo rows: the tags exist before the posts, and each post gets two.
        String demo = file(files, "config/DemoDataLoader.java");
        assertThat(demo.indexOf("tagRows = tagRepository.saveAll(tagRows);"))
                .isPositive().isLessThan(demo.indexOf("List<Post> postRows"));
        assertThat(demo).contains("            if (!tagRows.isEmpty()) {\n"
                + "                row.getTags().add(tagRows.get((i - 1) % tagRows.size()));\n"
                + "                row.getTags().add(tagRows.get(i % tagRows.size()));\n"
                + "            }");
    }

    @Test
    void aWizardWithoutStepsAsksForTheSetsLast() throws Exception {
        Map<String, Object> body = FullstackExampleRequests.twoSidedManyToManyBody("react-tailwind-crud");
        body.put("pages", List.of(
                Map.of("id", "posts", "type", "entity-list", "entity", "Post"),
                Map.of("id", "new-post", "type", "wizard", "entity", "Post")));
        assertThat(file(generate(body), "screens/NewPostScreen.tsx"))
                .contains("fields: ['title', 'author', 'tags'], keys: ['title', 'authorId', 'tagsIds'] },");
    }

    @Test
    void aSecondSideThatCannotWorkIsRefusedWithItsReason() {
        refusedSet(tag -> tag.put("relations", List.of(Map.of("type", "MANY_TO_MANY", "fieldName", "posts",
                        "targetEntity", "Post", "mappedBy", "labels"))),
                "mappedBy 'labels' is not a MANY_TO_MANY of 'Post' to 'Tag' that owns its join table (it has: tags)",
                "entities[1].relations[0].mappedBy");
        refusedSet(tag -> tag.put("relations", List.of(Map.of("type", "MANY_TO_MANY", "fieldName", "posts",
                        "targetEntity", "Post", "mappedBy", "tags", "joinTable", "tag_posts"))),
                "with mappedBy the join table is the one 'Post.tags' names", "entities[1].relations[0].joinTable");
        refusedSet(tag -> tag.put("relations", List.of(
                        Map.of("type", "MANY_TO_MANY", "fieldName", "posts", "targetEntity", "Post", "mappedBy", "tags"),
                        Map.of("type", "MANY_TO_MANY", "fieldName", "others", "targetEntity", "Post", "mappedBy", "tags"))),
                "is the other side of 'Post.tags', which another relation of this entity already is",
                "entities[1].relations[1].mappedBy");
        refusedSet(tag -> tag.put("relations", List.of(Map.of("type", "MANY_TO_ONE", "fieldName", "post",
                        "targetEntity", "Post", "mappedBy", "tags"))),
                "mappedBy only applies to a ONE_TO_MANY or a MANY_TO_MANY", "entities[1].relations[0].mappedBy");

        // The owner's rows belong to users: their links change hands with them, so only from there.
        Map<String, Object> owned = FullstackExampleRequests.twoSidedManyToManyBody("react-tailwind-crud");
        entity(owned, "Post").put("access", Map.of("read", "USER", "write", "USER", "owners", List.of("createdBy")));
        ResponseEntity<byte[]> answer = FullstackExampleRequests.post(rest, owned);
        assertThat(answer.getStatusCode()).as(text(answer)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text(answer)).contains("'Post' rows belong to users, so their links are changed from 'Post' only");
    }

    // ── A parent that reads its rows ────────────────────────────────────────────────────────

    /** The Orders example as it ships — an order's total is the sum of its lines, and it needs one — with tests and demo rows. */
    private static Map<String, Object> orders() {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("opts", Map.of("scaffold", List.of("tests", "seedData", "bulkUpdate", "bulkDelete", "softDelete", "csvImport")));
        return body;
    }

    @Test
    void aParentsRulesAndComputedFieldsReadItsRows() throws Exception {
        Map<String, String> files = generate(orders());
        assertThat(file(files, "rules/OrderRules.java"))
                .contains("        // total = sum(lines.lineTotal)\n"
                        + "        row.setTotal(Expr.sum(row.getLines(), each -> Expr.num(each.getLineTotal())));")
                .contains("        if (Expr.fails(Expr.gt(Expr.count(row.getLines()), new java.math.BigDecimal(\"0\")))) {\n"
                        // A rule over the rows alone shows its message on them.
                        + "            errors.putIfAbsent(\"lines\", \"An order needs at least one line\");");
        assertThat(file(files, "rules/Expr.java"))
                .contains("    public static BigDecimal count(java.util.Collection<?> rows) {")
                .contains("    public static <T> BigDecimal sum(java.util.Collection<T> rows, java.util.function.Function<T, BigDecimal> value) {")
                .contains("public static <T> BigDecimal avg(");

        // The form: the same, over the rows as they are edited — each kept as it will be saved.
        assertThat(file(files, "order-form/model/validate.ts"))
                .contains("  value = { ...value, total: rule.sum(value.lines, 'lineTotal') }")
                .contains("  if (!errors.lines && rule.fails(rule.gt(rule.count(value.lines), 0))) errors.lines = 'An order needs at least one line'");
        assertThat(file(files, "order-line-form/ui/OrderLineRows.tsx"))
                .contains("function derived(row: Partial<OrderLine>): Partial<OrderLine> {\n"
                        + "  row = { ...row, lineTotal: rule.mul(rule.num(row.quantity), rule.num(row.unitPrice)) }\n"
                        + "  return row\n"
                        + "}")
                .contains("      onChange={rows => onChange(rows.map(derived))}");
        assertThat(file(files, "order-form/ui/OrderForm.tsx"))
                .contains("errors?.lines && <p role=\"alert\" className=\"mt-1 text-xs text-danger\">{errors.lines}</p>}");
        assertThat(file(files, "shared/ui/rules.ts")).contains("export const sum = (rows: Rows, field: string): number | null =>");
    }

    @Test
    void aRowChangedThroughItsOwnEndpointsRunsItsParentsRulesAgain() throws Exception {
        String service = file(generate(orders()), "service/OrderLineService.java");
        assertThat(service)
                .contains("    private void recheckParent(com.menora.support.entity.Order link) {")
                .contains("        entityManager.flush();")
                .contains("        entityManager.refresh(parent);")
                .contains("            com.menora.support.rules.OrderRules.check(parent);")
                // After a create, an update (the order it left too), a delete, a restore, an import and the bulk calls.
                .contains("        return rechecked(repository.save(entity));")
                .contains("        com.menora.support.entity.Order movedFrom = existing.getOrder();")
                .contains("        OrderLine saved = rechecked(repository.save(existing));")
                .contains("        repository.delete(existing);\n        recheckParent(existing.getOrder());")
                .contains("        return rechecked(findById(id));")
                .contains("        if (errors.isEmpty()) recheckParents(entities);")
                .contains("        repository.deleteAllById(ids);\n        recheckParents(rows);")
                .contains("        repository.saveAll(rows);\n        recheckParents(rows);");
        // An entity nobody adds up is as it was.
        assertThat(file(generate(orders()), "service/ProductService.java")).doesNotContain("recheck");
    }

    @Test
    void theGeneratedTestsAndDemoRowsMakeAParentWithItsRows() throws Exception {
        Map<String, String> files = generate(orders());
        // The API tests' order is made with a line (its rule asks for one), the line with its product.
        assertThat(file(files, "ApiTestSupport.java"))
                .contains("ObjectNode linesRow = row.putArray(\"lines\").addObject();")
                .contains("linesRow.set(\"productId\", create(\"Product\").get(\"id\"));")
                .doesNotContain("no row the tests can make passes its rule");
        assertThat(file(files, "order-form/model/validate.test.ts")).contains("lines: [{ quantity: ");

        // Demo rows: the lines are made first, two to an order, and saved with it.
        String demo = file(files, "config/DemoDataLoader.java");
        assertThat(demo)
                .contains("        // The rows of a Order: two to each, made here and saved with it.\n"
                        + "        for (int i = 1; i <= ROWS * 2; i++) {")
                .contains("            for (int n = (i - 1) * 2; n < Math.min(i * 2, orderLineRows.size()); n++) {\n"
                        + "                orderLineRows.get(n).setOrder(row);\n"
                        + "                row.getLines().add(orderLineRows.get(n));\n"
                        + "            }")
                .doesNotContain("orderLineRows = orderLineRepository.saveAll(orderLineRows);")
                .doesNotContain("row.setOrder(orderRows.get(");
        assertThat(demo.indexOf("List<OrderLine> orderLineRows")).isLessThan(demo.indexOf("List<Order> orderRows"));
        // A row that breaks the order's rule (none here does) would be left out, after its lines are on it.
        assertThat(demo.indexOf("row.getLines().add(orderLineRows.get(n));"))
                .isLessThan(demo.indexOf("if (!com.menora.support.rules.OrderRules.apply(row).isEmpty()) continue;"));
    }

    @Test
    void aParentOfRowsCarriesTheVersionCheckUnlessItSaysNot() throws Exception {
        // The project never asked for optimistic locking: the order, whose lines are saved with it, has it.
        Map<String, String> files = generate(orders());
        assertThat(file(files, "entity/Order.java")).contains("@Version");
        assertThat(file(files, "entity/Customer.java")).doesNotContain("@Version");
        assertThat(file(files, "service/OrderService.java"))
                .contains("throw new ObjectOptimisticLockingFailureException(Order.class, id);")
                .contains("entityManager.lock(existing, jakarta.persistence.LockModeType.OPTIMISTIC_FORCE_INCREMENT);");
        assertThat(files.keySet()).anyMatch(path -> path.endsWith("shared/ui/StaleNotice.tsx"));

        Map<String, Object> off = orders();
        entity(off, "Order").put("opts", Map.of("optimisticLocking", false));
        assertThat(file(generate(off), "entity/Order.java")).doesNotContain("@Version");

        // The version is the generator's column there: a field of that name is in the way.
        Map<String, Object> clash = orders();
        List<Map<String, Object>> fields = new ArrayList<>(fieldsOf(clash, "Order"));
        fields.add(Map.of("name", "version", "type", "STRING"));
        entity(clash, "Order").put("fields", fields);
        ResponseEntity<byte[]> answer = FullstackExampleRequests.post(rest, clash);
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text(answer)).contains("already has a 'version' field or column").contains("\"entities[2]\"");
    }

    @Test
    void rowsAreReadThroughTheirFunctionsOnly() {
        refusedRule("lines.lineTotal > 0", "'lines.lineTotal' is one value in each of the rows: read them with sum, min, max or avg");
        refusedRule("lines == 0", "'lines' is the rows saved with this entity: count(lines), or sum, min, max or avg over one of their fields");
        refusedRule("sum(lines.product) > 0", "'product' is not a field of OrderLine");
        refusedRule("count(customer) > 0", "count takes the rows saved with this entity — here: lines");
        refusedRule("sum(total) > 0", "sum takes a field of the rows saved with this entity, like sum(lines.total) — here: lines");
        refusedRule("avg(lines.linetotal) > 0", "'linetotal' is not a field of OrderLine (did you mean 'lineTotal'?)");
    }

    @Test
    void relatedRowsLoadTheRestAndRowsSavedWithARecordAreShownWhole() throws Exception {
        Map<String, String> files = generate(orders());
        assertThat(file(files, "shared/ui/RelatedRows.tsx"))
                .contains("{loadingMore ? t('loading') : t('loadMoreN', { n: total - rows.length })}")
                .contains("const page = await api.get<Page>(`${asked}&page=${pages}`)");
        assertThat(file(files, "order-form/ui/OrderDetail.tsx"))
                .contains("      count={value.linesCount}\n      rows={value.lines}\n      whole\n");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private void refusedSet(Consumer<Map<String, Object>> change, String message, String path) {
        Map<String, Object> body = FullstackExampleRequests.twoSidedManyToManyBody("react-tailwind-crud");
        change.accept(entity(body, "Tag"));
        ResponseEntity<byte[]> answer = FullstackExampleRequests.post(rest, body);
        assertThat(answer.getStatusCode()).as(message).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text(answer)).contains(message).contains("\"" + path + "\"");
    }

    private void refusedRule(String check, String message) {
        Map<String, Object> body = orders();
        entity(body, "Order").put("rules", List.of(Map.of("check", check, "message", "No", "field", "reference")));
        ResponseEntity<byte[]> answer = FullstackExampleRequests.post(rest, body);
        assertThat(answer.getStatusCode()).as(check).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text(answer)).contains(message).contains("\"entities[2].rules[0].check\"");
    }

    /** A copy of one entity of the body, put back in its place, to change. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> entity(Map<String, Object> body, String name) {
        List<Map<String, Object>> entities = new ArrayList<>((List<Map<String, Object>>) body.get("entities"));
        for (int i = 0; i < entities.size(); i++) {
            if (name.equals(entities.get(i).get("name"))) {
                Map<String, Object> copy = new LinkedHashMap<>(entities.get(i));
                entities.set(i, copy);
                body.put("entities", entities);
                return copy;
            }
        }
        throw new IllegalArgumentException("No entity " + name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fieldsOf(Map<String, Object> body, String name) {
        return (List<Map<String, Object>>) entity(body, name).get("fields");
    }

    private static HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, String> generate(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, body);
        assertThat(response.getStatusCode()).as(text(response)).isEqualTo(HttpStatus.OK);
        Map<String, String> files = new TreeMap<>();
        FullstackExampleRequests.unzip(response.getBody())
                .forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
        return files;
    }

    private static String text(ResponseEntity<byte[]> response) {
        return response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8);
    }

    private static String file(Map<String, String> files, String suffix) {
        return files.entrySet().stream().filter(e -> e.getKey().endsWith(suffix)).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }
}
