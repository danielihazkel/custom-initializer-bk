package com.menora.initializr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Fullstack requests built from the seeded examples, the way the editor builds them, and their zips. */
public final class FullstackExampleRequests {

    private FullstackExampleRequests() {
    }

    public static List<Map<String, Object>> examples() throws IOException {
        try (InputStream in = new ClassPathResource("catalog/fullstack-examples.json").getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<>() {});
        }
    }

    public static boolean hasPages(Map<String, Object> example) {
        return example.get("pages") instanceof List<?> p && !p.isEmpty();
    }

    /**
     * An example's entities, its pages (unless classic) and its settings, with the given frontend
     * set (null: none named), locale and — for Hebrew — RTL.
     */
    public static Map<String, Object> body(Map<String, Object> example, String set, String locale, boolean layout) {
        return body(example, set, locale, layout, "3.2.1");
    }

    /** The same request on another Spring Boot version. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> body(Map<String, Object> example, String set, String locale, boolean layout,
                                           String bootVersion) {
        Map<String, Object> settings = example.get("settings") instanceof Map<?, ?> s
                ? (Map<String, Object>) s : Map.of();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "golden-" + example.get("exampleId"));
        body.put("packageName", "com.menora.golden");
        body.put("bootVersion", bootVersion);
        if (set != null) body.put("frontendTemplateSet", set);
        body.put("locale", locale);
        for (String key : List.of("dashboardTitle", "dashboardOverview", "colorPalette")) {
            if (settings.get(key) != null) body.put(key, settings.get(key));
        }
        List<String> scaffold = new ArrayList<>(settings.get("scaffold") instanceof List<?> l
                ? (List<String>) l : List.of());
        if ("he".equals(locale)) scaffold.add("rtl");
        body.put("opts", Map.of("scaffold", scaffold));
        body.put("entities", example.get("entities"));
        if (layout) {
            body.put("pages", example.get("pages"));
            if (settings.get("nav") != null) body.put("nav", settings.get("nav"));
        }
        return body;
    }

    /**
     * A seeded example with every display text made hostile to the code it is spliced into: each
     * entity and field label carries an apostrophe, a double quote, a backslash, braces and angle
     * brackets (Hebrew abbreviations — מס', מע"מ — carry the first two), and so do the dashboard
     * heading and blurb. Built for real by the frontend smoke tests: the proof that a label can
     * be any text.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> quotedLabelsBody(String exampleId, String set, String locale, boolean layout)
            throws IOException {
        Map<String, Object> example = examples().stream()
                .filter(e -> exampleId.equals(e.get("exampleId"))).findFirst().orElseThrow();
        Map<String, Object> body = body(example, set, locale, layout);
        List<Map<String, Object>> entities = new ArrayList<>();
        for (Map<String, Object> source : (List<Map<String, Object>>) example.get("entities")) {
            Map<String, Object> entity = new LinkedHashMap<>(source);
            entity.put("label", "מס' \"" + source.get("name") + "\" \\ {1} <b>");
            entity.put("labelPlural", "מע\"מ '" + source.get("name") + "' \\ {n} <i> &amp;");
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Map<String, Object> f : (List<Map<String, Object>>) source.get("fields")) {
                Map<String, Object> field = new LinkedHashMap<>(f);
                field.put("label", "ת\"ז '" + f.get("name") + "' \\ {x} <u> &lt;");
                fields.add(field);
            }
            entity.put("fields", fields);
            entities.add(entity);
        }
        body.put("entities", entities);
        body.put("dashboardTitle", "לוח \"בקרה\" של מס' {2026} <b>");
        body.put("dashboardOverview", "it's a \"test\" \\ {x} <i> &amp;");
        List<String> scaffold = new ArrayList<>(((Map<String, List<String>>) body.get("opts")).get("scaffold"));
        for (String opt : List.of("bulkUpdate", "csvImport", "csvExport")) {
            if (!scaffold.contains(opt)) scaffold.add(opt);
        }
        body.put("opts", Map.of("scaffold", scaffold));
        return body;
    }

    /**
     * One entity with every Round 9 field type — a time of day, an instant, money in two
     * currencies, Markdown — beside the old ones, and the options whose code touches a field by
     * its type (bulk and inline edit, CSV both ways, demo data, the controller test). The same
     * model is asserted as text, compiled ({@code -Dsmoke.be}) and built ({@code -Dsmoke.fe}).
     */
    public static Map<String, Object> fieldTypesBody(String frontendSet) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "rota");
        body.put("packageName", "com.menora.rota");
        body.put("bootVersion", "3.2.1");
        if (frontendSet != null) body.put("frontendTemplateSet", frontendSet);
        body.put("opts", Map.of("scaffold", List.of("audit", "csvExport", "csvImport", "bulkUpdate",
                "inlineEdit", "seedData", "tests")));
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true));
        fields.add(Map.of("name", "title", "type", "STRING", "required", true));
        fields.add(Map.of("name", "startsAt", "type", "LOCAL_TIME", "defaultValue", "09:00"));
        fields.add(Map.of("name", "clockedIn", "type", "INSTANT"));
        fields.add(Map.of("name", "rate", "type", "MONEY", "currency", "usd", "required", true, "min", 0));
        fields.add(Map.of("name", "bonus", "type", "MONEY", "defaultValue", "12.5"));
        fields.add(Map.of("name", "notes", "type", "MARKDOWN"));
        fields.add(Map.of("name", "contract", "type", "FILE"));
        fields.add(Map.of("name", "photo", "type", "IMAGE", "required", true));
        fields.add(Map.of("name", "status", "type", "ENUM", "enumValues", List.of("PLANNED", "DONE")));
        Map<String, Object> shift = new LinkedHashMap<>();
        shift.put("name", "Shift");
        shift.put("fields", fields);
        body.put("entities", List.of(shift));
        return body;
    }

    /**
     * Posts and tags: a MANY_TO_MANY ({@code Post.tags}) beside a MANY_TO_ONE ({@code Post.author}),
     * with the options that read a row through its DTO (CSV export, the controller test, demo
     * data). Asserted as text, compiled and built like {@link #fieldTypesBody}.
     */
    public static Map<String, Object> manyToManyBody(String frontendSet) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "press");
        body.put("packageName", "com.menora.press");
        body.put("bootVersion", "3.2.1");
        if (frontendSet != null) body.put("frontendTemplateSet", frontendSet);
        body.put("opts", Map.of("scaffold", List.of("csvExport", "seedData", "tests", "audit")));
        Map<String, Object> id = Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true);
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("name", "Author");
        author.put("fields", List.of(id, Map.of("name", "name", "type", "STRING", "required", true)));
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("name", "Tag");
        tag.put("fields", List.of(id, Map.of("name", "name", "type", "STRING", "required", true)));
        Map<String, Object> post = new LinkedHashMap<>();
        post.put("name", "Post");
        post.put("fields", List.of(id, Map.of("name", "title", "type", "STRING", "required", true)));
        post.put("relations", List.of(
                Map.of("type", "MANY_TO_ONE", "fieldName", "author", "targetEntity", "Author"),
                Map.of("type", "MANY_TO_MANY", "fieldName", "tags", "targetEntity", "Tag")));
        body.put("entities", List.of(author, tag, post));
        return body;
    }

    /**
     * A shop with ONE_TO_MANYs of every shape: one whose link is inferred ({@code Order.lines}),
     * one that names it ({@code Customer.orders}), two over the same child
     * ({@code Account.outgoing}/{@code incoming}, by {@code Transfer.source}/{@code target}) and
     * one of an entity's own rows ({@code Employee.reports}). {@code inverseCollections} is off,
     * so every collection here is one the model declared. Every list is mutable.
     */
    public static Map<String, Object> oneToManyBody(String frontendSet) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        if (frontendSet != null) body.put("frontendTemplateSet", frontendSet);
        body.put("opts", new LinkedHashMap<>(Map.of("scaffold", List.of("csvExport", "seedData", "tests", "audit"))));
        Map<String, Object> id = Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true);
        Map<String, Object> name = Map.of("name", "name", "type", "STRING", "required", true);
        List<Map<String, Object>> entities = new java.util.ArrayList<>();
        entities.add(entity("Customer", List.of(id, name),
                Map.of("type", "ONE_TO_MANY", "fieldName", "orders", "targetEntity", "Order", "mappedBy", "customer")));
        entities.add(entity("Product", List.of(id, name)));
        entities.add(entity("Order", List.of(id,
                        Map.of("name", "reference", "type", "STRING", "required", true),
                        Map.of("name", "status", "type", "ENUM", "enumValues", List.of("OPEN", "PAID"), "defaultValue", "OPEN")),
                Map.of("type", "MANY_TO_ONE", "fieldName", "customer", "targetEntity", "Customer", "required", true),
                Map.of("type", "ONE_TO_MANY", "fieldName", "lines", "targetEntity", "OrderLine")));
        entities.add(entity("OrderLine", List.of(id,
                        Map.of("name", "quantity", "type", "INTEGER", "required", true),
                        Map.of("name", "note", "type", "TEXT")),
                Map.of("type", "MANY_TO_ONE", "fieldName", "order", "targetEntity", "Order", "required", true),
                Map.of("type", "MANY_TO_ONE", "fieldName", "product", "targetEntity", "Product")));
        entities.add(entity("Account", List.of(id, name),
                Map.of("type", "ONE_TO_MANY", "fieldName", "outgoing", "targetEntity", "Transfer", "mappedBy", "source"),
                Map.of("type", "ONE_TO_MANY", "fieldName", "incoming", "targetEntity", "Transfer", "mappedBy", "target")));
        entities.add(entity("Transfer", List.of(id,
                        Map.of("name", "amount", "type", "BIG_DECIMAL", "required", true),
                        Map.of("name", "kind", "type", "ENUM", "enumValues", List.of("WIRE", "CASH"))),
                Map.of("type", "MANY_TO_ONE", "fieldName", "source", "targetEntity", "Account", "required", true),
                Map.of("type", "MANY_TO_ONE", "fieldName", "target", "targetEntity", "Account", "required", true)));
        entities.add(entity("Employee", List.of(id, name,
                        Map.of("name", "grade", "type", "ENUM", "enumValues", List.of("JUNIOR", "SENIOR"))),
                Map.of("type", "MANY_TO_ONE", "fieldName", "manager", "targetEntity", "Employee"),
                Map.of("type", "ONE_TO_MANY", "fieldName", "reports", "targetEntity", "Employee")));
        body.put("entities", entities);
        return body;
    }

    @SafeVarargs
    private static Map<String, Object> entity(String name, List<Map<String, Object>> fields, Map<String, Object>... relations) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", name);
        e.put("fields", new java.util.ArrayList<>(fields));
        if (relations.length > 0) e.put("relations", new java.util.ArrayList<>(List.of(relations)));
        return e;
    }

    /**
     * Names that are SQL keywords somewhere: fields {@code key}, {@code order}, {@code user},
     * {@code comment}, {@code date}, {@code level} and {@code value}, a table {@code values} and a
     * table {@code user} — with the options whose SQL names a column or a table itself (soft
     * delete, locking, a unique key, a link's label, search, export, demo data).
     * Asserted as text, built on H2 ({@code -Dsmoke.be}) and created on the other databases
     * ({@code -Dsmoke.docker}).
     */
    public static Map<String, Object> keywordNamesBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "words");
        body.put("packageName", "com.menora.words");
        body.put("bootVersion", "3.2.1");
        body.put("frontendTemplateSet", "react-tailwind-crud");
        body.put("dependencies", new ArrayList<>(List.of("web", "data-jpa", "h2", "validation")));
        body.put("opts", Map.of("scaffold", List.of("audit", "softDelete", "optimisticLocking",
                "csvExport", "seedData", "tests")));
        Map<String, Object> setting = entity("Setting", List.of(
                Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "key", "type", "STRING", "required", true, "unique", true),
                Map.of("name", "order", "type", "INTEGER"),
                Map.of("name", "user", "type", "STRING"),
                Map.of("name", "comment", "type", "STRING"),
                Map.of("name", "date", "type", "LOCAL_DATE"),
                Map.of("name", "level", "type", "INTEGER"),
                Map.of("name", "value", "type", "BIG_DECIMAL")));
        setting.put("tableName", "values");
        setting.put("uniqueKeys", List.of(Map.of("fields", List.of("order", "user"))));
        Map<String, Object> batch = entity("Batch", List.of(
                Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "name", "type", "STRING", "required", true)),
                Map.of("type", "MANY_TO_ONE", "fieldName", "setting", "targetEntity", "Setting", "required", true));
        batch.put("tableName", "user");
        body.put("entities", List.of(setting, batch));
        return body;
    }

    /**
     * Rows that belong to users (Round 14): {@code Client} — its account manager's (a text field,
     * users see only their own), soft-deleted; {@code Visit} — its creator's, in the given
     * {@code visitRows} mode ({@code OWN} or {@code OWN_WRITES}), linked to a client, with a
     * history and composed {@code VisitItem} rows that follow it. No {@code dependencies}: the
     * backend set's defaults bring ldap-auth-rest, which row access needs.
     */
    public static Map<String, Object> rowAccessBody(String visitRows) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "visits");
        body.put("packageName", "com.menora.visits");
        body.put("bootVersion", "3.2.1");
        body.put("frontendTemplateSet", "react-tailwind-crud");
        body.put("opts", Map.of("scaffold", List.of("softDelete", "csvExport", "xlsxExport", "bulkDelete",
                "bulkUpdate", "csvImport", "history", "seedData", "tests")));
        Map<String, Object> client = entity("Client", List.of(
                Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "name", "type", "STRING", "required", true),
                Map.of("name", "city", "type", "STRING"),
                Map.of("name", "accountManager", "type", "STRING")));
        client.put("access", new LinkedHashMap<>(Map.of("read", "USER", "write", "USER",
                "owners", List.of("accountManager"), "rows", "OWN")));
        Map<String, Object> visit = entity("Visit", List.of(
                Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "summary", "type", "STRING", "required", true),
                Map.of("name", "visitedOn", "type", "LOCAL_DATE"),
                Map.of("name", "outcome", "type", "ENUM", "enumValues", List.of("OPEN", "WON", "LOST"))),
                Map.of("type", "MANY_TO_ONE", "fieldName", "client", "targetEntity", "Client"),
                Map.of("type", "ONE_TO_MANY", "fieldName", "items", "targetEntity", "VisitItem", "composition", true));
        visit.put("access", new LinkedHashMap<>(Map.of("read", "USER", "write", "USER",
                "owners", List.of("createdBy"), "rows", visitRows)));
        Map<String, Object> item = entity("VisitItem", List.of(
                Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true),
                Map.of("name", "note", "type", "STRING", "required", true)),
                Map.of("type", "MANY_TO_ONE", "fieldName", "visit", "targetEntity", "Visit", "required", true));
        item.put("access", Map.of("read", "USER", "write", "USER"));
        body.put("entities", new ArrayList<>(List.of(client, visit, item)));
        return body;
    }

    /**
     * {@link #rowAccessBody} where everyone sees every client and visit and changes their own
     * ({@code OWN_WRITES}), laid out on the pages that draw a row's own buttons: a list with a
     * side pane, a list with a board view and cells edited in place, a record page, a
     * master–detail page, a board and a calendar.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> rowAccessPagesBody(String frontendSet) {
        Map<String, Object> body = rowAccessBody("OWN_WRITES");
        body.put("frontendTemplateSet", frontendSet);
        body.put("opts", Map.of("scaffold", List.of("softDelete", "csvExport", "bulkDelete", "bulkUpdate", "inlineEdit",
                "history", "tests")));
        List<Map<String, Object>> entities = (List<Map<String, Object>>) body.get("entities");
        ((Map<String, Object>) entities.get(0).get("access")).put("rows", "OWN_WRITES");
        entities.get(1).put("listViews", List.of("table", "kanban"));
        body.put("pages", List.of(
                Map.of("id", "clients", "type", "entity-list", "entity", "Client", "detail", "side"),
                Map.of("id", "visits", "type", "entity-list", "entity", "Visit"),
                Map.of("id", "visit", "type", "record", "entity", "Visit"),
                Map.of("id", "by-client", "type", "master-detail", "title", "Visits by client",
                        "parent", "Client", "child", "Visit", "showParent", true),
                Map.of("id", "pipeline", "type", "board", "entity", "Visit", "laneField", "outcome"),
                Map.of("id", "diary", "type", "calendar", "entity", "Visit", "dateField", "visitedOn")));
        return body;
    }

    /** POSTs a fullstack request; the response, whatever its status. */
    public static ResponseEntity<byte[]> post(TestRestTemplate rest, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/starter-fullstack.zip", HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
    }

    /** Every file of a zip by path. */
    public static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zin.transferTo(out);
                files.put(entry.getName(), out.toByteArray());
            }
        }
        return files;
    }
}
