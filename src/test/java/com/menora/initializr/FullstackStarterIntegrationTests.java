package com.menora.initializr;

import org.junit.jupiter.api.Test;
import com.menora.initializr.db.VersionService;
import com.menora.initializr.db.entity.VersionKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class FullstackStarterIntegrationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private VersionService versionService;

    @Test
    void fullstackEndpoint_generatesBackendAndFrontendForTwoEntities() throws Exception {
        Map<String, Object> userPk = pkField();
        Map<String, Object> userName = Map.of("name", "name", "type", "String", "required", true);
        Map<String, Object> orderPk = pkField();
        Map<String, Object> orderTotal = Map.of("name", "total", "type", "BigDecimal", "required", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "User", "fields", List.of(userPk, userName)),
                Map.of("name", "Order", "fields", List.of(orderPk, orderTotal))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        Map<String, String> entries = unzip(response.getBody());

        // Root structure
        assertThat(entries.keySet()).anyMatch(p -> p.equals("shop/README.md"));
        assertThat(entries.keySet()).anyMatch(p -> p.equals("shop/.gitignore"));
        // README documents the dev proxy + prod base-URL story (not "talks directly to :8080")
        String readme = entries.get("shop/README.md");
        assertThat(readme).contains("proxy").contains("frontend/src/shared/api/client.ts")
                .contains("API_UPSTREAM").contains("app.cors.allowed-origins");

        // Backend pom + Application
        assertThat(entries.keySet()).anyMatch(p -> p.equals("shop/backend/pom.xml"));
        String pom = entries.get("shop/backend/pom.xml");
        // No `dependencies` field in request → set defaults are applied
        // (data-jpa, web, h2, validation, actuator, ldap-auth per spring-jpa-crud manifest).
        assertThat(pom).contains("spring-boot-starter-data-jpa");
        assertThat(pom).contains("spring-boot-starter-web");
        assertThat(pom).contains("spring-boot-starter-validation");
        assertThat(pom).contains("spring-boot-starter-actuator");
        // ldap-auth is a fullstack default → its Maven coords + AOP starter are wired in.
        assertThat(pom).contains("lts.ldap.util");
        assertThat(pom).contains("spring-boot-starter-aop");

        // Per-entity backend files for both entities
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/User.java"));
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/UserController.java"));
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/UserRepository.java"));
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/UserService.java"));
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/UserDto.java"));

        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/Order.java"));
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/OrderController.java"));

        // Spot-check rendered Java
        String userController = entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/UserController.java"))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
        assertThat(userController).contains("@RequestMapping(\"/api/users\")");
        assertThat(userController).contains("@RestController");
        assertThat(userController).contains("Pageable");
        assertThat(userController).contains("@RequestParam(required = false) String q");
        assertThat(userController).contains("Page<UserDto>");
        assertThat(userController).contains("Sort.by(\"id\").ascending()");
        // CORS is centralized in a single WebMvcConfigurer, not repeated per controller.
        assertThat(userController).doesNotContain("@CrossOrigin");
        // CORS is off by default (the FE calls same-origin /api through Vite/nginx) and is
        // switched on per environment via a property, never by editing Java.
        assertThat(contentEndingWith(entries, "/config/CorsConfig.java"))
                .contains("implements WebMvcConfigurer")
                .contains("addMapping(\"/api/**\")")
                .contains("@Value(\"${app.cors.allowed-origins:}\")")
                .contains("allowedOriginPatterns(allowedOrigins)")
                .doesNotContain("allowedOrigins(\"http://localhost:5173\")");
        // CorsConfig is generated once (non-perEntity), under the base package for component scan.
        assertThat(entries.keySet()).anyMatch(p -> p.equals("shop/backend/src/main/java/com/menora/shop/config/CorsConfig.java"));

        String userService = entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/UserService.java"))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
        assertThat(userService).contains("findAll(String q, Pageable pageable)");
        assertThat(userService).contains("Specification<User>");
        assertThat(userService).contains("root.get(\"name\")");
        // delete() loads first so a missing id is the controller's 404, not a 500 from deleteById.
        assertThat(userService)
                .contains("repository.delete(findById(id))")
                .doesNotContain("repository.deleteById(id)");

        String userRepository = entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/UserRepository.java"))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
        assertThat(userRepository).contains("JpaSpecificationExecutor<User>");

        String orderController = entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/OrderController.java"))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
        assertThat(orderController).contains("Pageable");
        assertThat(orderController).contains("Page<OrderDto>");

        String orderService = entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/OrderService.java"))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
        // Order has a numeric (BigDecimal) field → it gets a type-aware filter carrier and a
        // Specification, even though it has no STRING search field.
        assertThat(orderService).contains("findAll(String q, Filters filters, Pageable pageable)");
        assertThat(orderService).contains("Specification<Order>");

        String orderEntity = entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/Order.java"))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
        assertThat(orderEntity).contains("@Entity");
        assertThat(orderEntity).contains("@Table(name = \"orders\")");
        assertThat(orderEntity).contains("java.math.BigDecimal");

        // Frontend essentials
        assertThat(entries).containsKey("shop/frontend/package.json");
        String packageJson = entries.get("shop/frontend/package.json");
        assertThat(packageJson).contains("\"shop-frontend\"");
        // The overlay's package.json follows the selected React version rather than a hardcoded 18.
        String react = versionService.defaultId(VersionKind.REACT);
        assertThat(packageJson)
                .contains("\"react\" : \"" + versionService.reactSemver(react).orElseThrow() + "\"")
                .contains("\"@types/react\" : \"" + versionService.reactTypesSemver(react).orElseThrow() + "\"");
        // The substrate's tooling packages/scripts are merged into the overlay's package.json so the
        // eslint.config.js / .prettierrc.json / .husky/pre-commit it writes actually work.
        assertThat(packageJson)
                .contains("\"eslint\" :")
                .contains("\"typescript-eslint\" :")
                .contains("\"prettier\" :")
                .contains("\"husky\" :")
                .contains("\"lint-staged\" :")
                .contains("\"lint\" : \"eslint .\"")
                .contains("\"lint:fix\" :")
                .contains("\"format\" : \"prettier --write .\"")
                .contains("\"engines\"")
                // Overlay pins win over the catalog's __common__ rows (vite/tailwind stay as pinned).
                .contains("\"vite\" : \"^5.3.4\"")
                .contains("\"tailwindcss\" : \"^4.0.0\"");
        // (Only dev tooling is merged back — the overlay owns the runtime dependency set. The
        // Menora Digital case below pins that: it ships Assistant and must not get Inter back.)
        assertThat(entries).containsKey("shop/frontend/src/app/App.tsx");
        String app = entries.get("shop/frontend/src/app/App.tsx");
        assertThat(app).contains("UserPage");
        assertThat(app).contains("OrderPage");

        // Per-entity frontend files (Feature-Sliced Design layout)
        assertThat(entries).containsKey("shop/frontend/src/pages/user/ui/UserPage.tsx");
        assertThat(entries).containsKey("shop/frontend/src/pages/order/ui/OrderPage.tsx");
        assertThat(entries).containsKey("shop/frontend/src/entities/user/api/useUser.ts");
        assertThat(entries).containsKey("shop/frontend/src/entities/order/api/useOrder.ts");
        assertThat(entries).containsKey("shop/frontend/src/entities/user/model/types.ts");
        assertThat(entries).containsKey("shop/frontend/src/features/user-form/ui/UserForm.tsx");

        assertThat(entries.get("shop/frontend/src/entities/order/api/useOrder.ts")).contains("/api/orders");

        // Pagination + sort + search wiring
        String table = entries.get("shop/frontend/src/shared/ui/Table.tsx");
        assertThat(table).contains("onSortChange");
        assertThat(table).contains("pagination");
        assertThat(table).contains("onSearchChange");

        String userPage = entries.get("shop/frontend/src/pages/user/ui/UserPage.tsx");
        assertThat(userPage).contains("const [page, setPage]");
        assertThat(userPage).contains("const [size, setSize]");
        assertThat(userPage).contains("q: debouncedSearch");
        assertThat(userPage).contains("sortKey: 'name'");

        String useResource = entries.get("shop/frontend/src/shared/api/useResource.ts");
        assertThat(useResource).contains("PageParams");
        assertThat(useResource).contains("totalElements");

        // The frontend is now built on top of the standalone frontend generator: the FSD
        // tooling substrate (eslint/prettier/husky/Dockerfile/nginx) + layer READMEs + dev
        // .env/Vite-proxy wiring come for free, instead of being re-hand-rolled per template set.
        assertThat(entries).containsKey("shop/frontend/.gitignore");
        assertThat(entries).containsKey("shop/frontend/eslint.config.js");
        assertThat(entries).containsKey("shop/frontend/.prettierrc.json");
        assertThat(entries).containsKey("shop/frontend/Dockerfile");
        assertThat(entries).containsKey("shop/frontend/nginx/nginx.conf");
        assertThat(entries).containsKey("shop/frontend/.husky/pre-commit");
        assertThat(entries).containsKey("shop/frontend/src/widgets/README.md");
        // Paired-backend wiring: the FE always calls same-origin /api. In dev the base URL is
        // empty so the Vite proxy (rendered from the overlay's mustache with the substrate's
        // basePath/apiBaseUrl) forwards to the backend; in prod nginx proxies /api to API_UPSTREAM.
        assertThat(entries.get("shop/frontend/.env.development"))
                .contains("VITE_API_BASE_URL=").doesNotContain("VITE_API_BASE_URL=http");
        assertThat(entries.get("shop/frontend/vite.config.ts"))
                .contains("base: '/'")
                .contains("host: true")
                .contains("target: 'http://localhost:8080'")
                .contains("changeOrigin: true")
                .doesNotContain("{{");
        assertThat(entries.get("shop/frontend/nginx/nginx.conf"))
                .contains("location /api/")
                .contains("proxy_pass ${API_UPSTREAM};");
        assertThat(entries.get("shop/frontend/entrypoint.sh"))
                .contains("API_UPSTREAM=\"${API_UPSTREAM:-http://localhost:8080}\"")
                .contains("${API_UPSTREAM}");
        // The API client reads VITE_API_BASE_URL (build-time) instead of a hardcoded constant,
        // refuses a non-JSON answer (a misrouted /api serves index.html with a 200), and the
        // production env file ships with it empty (same-origin behind nginx).
        assertThat(entries.get("shop/frontend/src/shared/api/client.ts"))
                .contains("import.meta.env.VITE_API_BASE_URL")
                .contains("contentType.includes('json')")
                .doesNotContain("const BASE = ''");
        assertThat(entries.get("shop/frontend/.env.production"))
                .contains("VITE_API_BASE_URL=").doesNotContain("VITE_API_BASE_URL=http");
        assertThat(readme).contains(".env.production").doesNotContain("`BASE` constant");
        // Every list view keys rows by primary key, never by array index.
        assertThat(userPage)
                .contains("const rowKey = (row: User) => row.id as string | number")
                .contains("rowKey={rowKey}");
        // The standalone landing page is replaced by the per-entity pages — its dir is removed.
        assertThat(entries.keySet()).noneMatch(p -> p.startsWith("shop/frontend/src/pages/home/"));
    }

    @Test
    void fullstackEndpoint_hardensGeneratedApi() throws Exception {
        // Three PK shapes: generated (Product), client-supplied single (Coupon.code) and composite
        // (Line.orderId + lineNo). audit adds createdAt/updatedAt to the sortable set; tests pins
        // the mock annotation; validation (a set default) enables the constraint-violation handler.
        Map<String, Object> couponCode = new LinkedHashMap<>();
        couponCode.put("name", "code"); couponCode.put("type", "String"); couponCode.put("primaryKey", true);
        Map<String, Object> orderId = new LinkedHashMap<>();
        orderId.put("name", "orderId"); orderId.put("type", "Long"); orderId.put("primaryKey", true);
        Map<String, Object> lineNo = new LinkedHashMap<>();
        lineNo.put("name", "lineNo"); lineNo.put("type", "Integer"); lineNo.put("primaryKey", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("opts", Map.of("scaffold", List.of("audit", "tests")));
        body.put("entities", List.of(
                Map.of("name", "Product", "fields", List.of(pkField(),
                        Map.of("name", "name", "type", "String", "required", true))),
                Map.of("name", "Coupon", "fields", List.of(couponCode,
                        Map.of("name", "discount", "type", "Integer"))),
                Map.of("name", "Line", "fields", List.of(orderId, lineNo,
                        Map.of("name", "qty", "type", "Integer")))));
        Map<String, String> entries = generateZip(body);

        // One RFC-7807 advice for the whole API, plus the two exceptions the services throw.
        String base = "shop/backend/src/main/java/com/menora/shop/web/";
        assertThat(entries).containsKey(base + "ResourceNotFoundException.java");
        assertThat(entries).containsKey(base + "ResourceConflictException.java");
        assertThat(entries.get(base + "ApiExceptionHandler.java"))
                .contains("@RestControllerAdvice")
                .contains("@Order(Ordered.HIGHEST_PRECEDENCE)")
                .contains("ProblemDetail.forStatusAndDetail")
                .contains("problem.setProperty(\"errors\", errors)")
                .contains("A record with these values already exists")
                .contains("@ExceptionHandler(ConstraintViolationException.class)")   // validation dep on
                .contains("@ExceptionHandler(PropertyReferenceException.class)");

        // Controllers carry no local handlers any more and whitelist ?sort= against the DTO's columns.
        String productController = contentEndingWith(entries, "/ProductController.java");
        assertThat(productController)
                .doesNotContain("@ExceptionHandler")
                .doesNotContain("NoSuchElementException")
                .contains("SORTABLE = List.of(new String[] {")
                .contains("\"id\",").contains("\"name\",").contains("\"createdAt\",").contains("\"updatedAt\",")
                .contains("DEFAULT_SORT = Sort.by(\"id\").ascending()")
                .contains("PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortOf(pageable))")
                .contains("throw new IllegalArgumentException(");
        // Composite keys sort by every PK column so pagination is stable.
        assertThat(contentEndingWith(entries, "/LineController.java"))
                .contains("DEFAULT_SORT = Sort.by(\"orderId\", \"lineNo\").ascending()");

        // Search escapes LIKE wildcards and lowercases locale-independently; 404 is a typed exception.
        String productService = contentEndingWith(entries, "/ProductService.java");
        assertThat(productService)
                .contains("import com.menora.shop.web.ResourceNotFoundException;")
                .contains("new ResourceNotFoundException(\"Product \" + id + \" not found\")")
                .contains("escapeLike(q.toLowerCase(Locale.ROOT))")
                .contains("like, '\\\\')")
                .contains("private static String escapeLike(String s)")
                .doesNotContain("NoSuchElementException")
                .doesNotContain("existsById")           // generated PK: nothing to clash with
                .doesNotContain("ResourceConflictException");
        // A client-supplied key refuses to overwrite an existing row.
        assertThat(contentEndingWith(entries, "/CouponService.java"))
                .contains("import com.menora.shop.web.ResourceConflictException;")
                .contains("String id = entity.getCode();")
                .contains("if (id != null && repository.existsById(id))")
                .contains("throw new ResourceConflictException(\"Coupon \" + id + \" already exists\")")
                .contains("Locale.ROOT");               // the String key itself is searchable
        assertThat(contentEndingWith(entries, "/LineService.java"))
                .contains("LineId id = new LineId(entity.getOrderId(), entity.getLineNo());")
                .contains("repository.existsById(id)")
                .doesNotContain("Locale.ROOT")          // no string field → no search, no helper
                .doesNotContain("escapeLike");

        // Boot 3.2 still uses @MockBean; the switch to @MockitoBean is pinned by
        // FullstackProjectGenerationConfigurationTest.
        assertThat(contentEndingWith(entries, "/ProductControllerTest.java"))
                .contains("@MockBean").doesNotContain("MockitoBean");

        // The API client reads the problem-detail shape.
        assertThat(entries.get("shop/frontend/src/shared/api/client.ts"))
                .contains("problem.errors").contains("problem.detail || problem.title");
    }

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
    void fullstackEndpoint_scaffoldsPerLayerSubPackages() throws Exception {
        // Default domainPackage (== packageName): classes split into .entity/.repository/.dto/
        // .service/.controller, wired together by cross-layer imports.
        Map<String, Object> nameField = Map.of("name", "name", "type", "String", "required", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField(), nameField))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        // Files land in per-layer sub-package directories.
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/com/menora/shop/entity/User.java"));
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/com/menora/shop/repository/UserRepository.java"));
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/com/menora/shop/dto/UserDto.java"));
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/com/menora/shop/service/UserService.java"));
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/com/menora/shop/controller/UserController.java"));

        // Package declarations + cross-layer imports.
        assertThat(contentEndingWith(entries, "/entity/User.java"))
                .contains("package com.menora.shop.entity;");
        assertThat(contentEndingWith(entries, "/repository/UserRepository.java"))
                .contains("package com.menora.shop.repository;")
                .contains("import com.menora.shop.entity.User;");
        assertThat(contentEndingWith(entries, "/dto/UserDto.java"))
                .contains("package com.menora.shop.dto;")
                .contains("import com.menora.shop.entity.User;");
        assertThat(contentEndingWith(entries, "/service/UserService.java"))
                .contains("package com.menora.shop.service;")
                .contains("import com.menora.shop.entity.User;")
                .contains("import com.menora.shop.repository.UserRepository;");
        assertThat(contentEndingWith(entries, "/controller/UserController.java"))
                .contains("package com.menora.shop.controller;")
                .contains("import com.menora.shop.dto.UserDto;")
                .contains("import com.menora.shop.service.UserService;");
    }

    @Test
    void fullstackEndpoint_customDomainPackageUnderBase() throws Exception {
        // A domainPackage below the base package nests the layer sub-packages under it.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("domainPackage", "com.menora.shop.catalog");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "Product", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/com/menora/shop/catalog/entity/Product.java"));
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/com/menora/shop/catalog/controller/ProductController.java"));
        assertThat(contentEndingWith(entries, "/controller/ProductController.java"))
                .contains("package com.menora.shop.catalog.controller;")
                .contains("import com.menora.shop.catalog.service.ProductService;");
        // The @SpringBootApplication class stays in the base package (so its default scan covers the domain).
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/com/menora/shop/ShopApplication.java"));
    }

    @Test
    void fullstackEndpoint_rejectsDomainPackageOutsideBase() {
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "shop");
            b.put("packageName", "com.menora.shop");
            b.put("domainPackage", "com.acme.other");
            b.put("bootVersion", "3.2.1");
            b.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("domainPackage").contains("sub-package");
    }

    @Test
    void fullstackEndpoint_rendersCompilableEnumAndDateFormControls() throws Exception {
        // Regression: an ENUM field's <option> map must be wrapped in JSX braces (otherwise
        // `tsc -b` fails with "Cannot find name 'v'"), and a LOCAL_DATE field must use a
        // `date` input — not `datetime-local`, which sends `...T00:00` and is rejected by
        // Jackson for java.time.LocalDate. Neither is exercised by the User/Order test above.
        Map<String, Object> statusField = new LinkedHashMap<>();
        statusField.put("name", "status");
        statusField.put("type", "Enum");
        statusField.put("enumValues", List.of("ACTIVE", "INACTIVE"));
        Map<String, Object> birthField = Map.of("name", "birthDate", "type", "LocalDate");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "people");
        body.put("packageName", "com.menora.people");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Person", "fields", List.of(pkField(), statusField, birthField))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        String personForm = entries.get("people/frontend/src/features/person-form/ui/PersonForm.tsx");
        assertThat(personForm).isNotNull();
        // 1.1 — enum <option> map wrapped in JSX braces
        assertThat(personForm)
                .contains("{ PersonStatusTypeValues.map(v => <option key={v} value={v}>{v}</option>) }");
        // 1.2 — LocalDate uses a plain date input, never datetime-local
        assertThat(personForm).contains("type=\"date\"");
        assertThat(personForm).doesNotContain("type=\"datetime-local\"");

        // Enum union type is emitted for the field
        String personType = entries.get("people/frontend/src/entities/person/model/types.ts");
        assertThat(personType).contains("export type PersonStatusType = 'ACTIVE' | 'INACTIVE'");

        // Backend: the DTO lives in its own .dto sub-package and imports the entity plus its
        // nested enum, so the bare PersonStatusType reference still resolves across packages.
        assertThat(contentEndingWith(entries, "/dto/PersonDto.java"))
                .contains("package com.menora.people.dto;")
                .contains("import com.menora.people.entity.Person;")
                .contains("import com.menora.people.entity.Person.PersonStatusType;");
    }

    @Test
    void fullstackEndpoint_explicitDependenciesAreRespectedExactly() throws Exception {
        // When the caller passes a `dependencies` field (even just a list of two),
        // the controller does NOT merge in the set's defaults — explicit intent wins.
        // The UI relies on this so a user who unchecks a default actually loses it.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "explicit");
        body.put("packageName", "com.menora.explicit");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "security", "postgresql"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());
        String pom = entries.get("explicit/backend/pom.xml");
        // Explicitly requested
        assertThat(pom).contains("spring-boot-starter-data-jpa");
        assertThat(pom).contains("spring-boot-starter-web");
        assertThat(pom).contains("spring-boot-starter-security");
        assertThat(pom).contains("postgresql");
        // NOT in the explicit list — set defaults must NOT leak in
        assertThat(pom).doesNotContain("spring-boot-starter-validation");
        assertThat(pom).doesNotContain("spring-boot-starter-actuator");
        assertThat(pom).doesNotContain("<artifactId>h2</artifactId>");

        // Without the validation starter, the generated controller must not reference Bean
        // Validation (the import wouldn't resolve) — the @Valid wiring is gated on the dep.
        String controller = contentEndingWith(entries, "/controller/UserController.java");
        assertThat(controller).doesNotContain("jakarta.validation.Valid");
        assertThat(controller).doesNotContain("@Valid");
        assertThat(contentEndingWith(entries, "/dto/UserDto.java"))
                .doesNotContain("jakarta.validation.constraints");
    }

    @Test
    void fullstackEndpoint_wiresBeanValidationAndScopesSearch() throws Exception {
        // Account has a required, length-bounded String → DTO gets @NotNull/@Size and a
        // searchable list; Ledger has no String fields → its list hides the search box.
        Map<String, Object> email = Map.of("name", "email", "type", "String", "required", true, "length", 200);
        Map<String, Object> balance = Map.of("name", "balance", "type", "BigDecimal");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "bank");
        body.put("packageName", "com.menora.bank");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Account", "fields", List.of(pkField(), email)),
                Map.of("name", "Ledger", "fields", List.of(pkField(), balance))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        // DTO carries the constraints, with only the imports it uses.
        String accountDto = contentEndingWith(entries, "/dto/AccountDto.java");
        assertThat(accountDto)
                .contains("import jakarta.validation.constraints.NotNull;")
                .contains("import jakarta.validation.constraints.Size;")
                .contains("@NotNull")
                .contains("@Size(max = 200)");
        // id is a generated PK → it must NOT be @NotNull (it is null until persisted).
        assertThat(accountDto).contains("Long id");

        // Controller validates the body; the 400 / 409 mapping lives in the shared advice, not here.
        String accountController = contentEndingWith(entries, "/controller/AccountController.java");
        assertThat(accountController)
                .contains("import jakarta.validation.Valid;")
                .contains("@Valid")
                .doesNotContain("MethodArgumentNotValidException")
                .doesNotContain("DataIntegrityViolationException");
        assertThat(contentEndingWith(entries, "/web/ApiExceptionHandler.java"))
                .contains("@ExceptionHandler(MethodArgumentNotValidException.class)")
                .contains("@ExceptionHandler(DataIntegrityViolationException.class)")
                .contains("HttpStatus.CONFLICT");

        // Required fields are marked in the generated form (email is required → asterisk).
        assertThat(entries.get("bank/frontend/src/features/account-form/ui/AccountForm.tsx"))
                .contains("label=\"Email\" required error={errors?.email}");

        // Frontend search box is gated on the entity having a string field.
        assertThat(entries.get("bank/frontend/src/pages/account/ui/AccountPage.tsx")).contains("searchable={true}");
        assertThat(entries.get("bank/frontend/src/pages/ledger/ui/LedgerPage.tsx")).contains("searchable={false}");
        assertThat(entries.get("bank/frontend/src/shared/ui/Table.tsx"))
                .contains("searchable")
                .contains("{searchable &&");
    }

    @Test
    void fullstackEndpoint_perFieldSearchAndFilterOptOut() throws Exception {
        // Widget opts its only String field out of search (searchable=false) and its only numeric
        // field out of the filter bar (filterable=false) → no search box, no Filters carrier.
        // Gadget keeps the defaults as a control, so it still gets both.
        Map<String, Object> widgetTitle = Map.of("name", "title", "type", "String", "required", true, "searchable", false);
        Map<String, Object> widgetPriority = Map.of("name", "priority", "type", "Integer", "filterable", false);
        Map<String, Object> gadgetTitle = Map.of("name", "title", "type", "String", "required", true);
        Map<String, Object> gadgetPriority = Map.of("name", "priority", "type", "Integer");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "tools");
        body.put("packageName", "com.menora.tools");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Widget", "fields", List.of(pkField(), widgetTitle, widgetPriority)),
                Map.of("name", "Gadget", "fields", List.of(pkField(), gadgetTitle, gadgetPriority))));

        Map<String, String> entries = generateZip(body);

        // Widget: both opted out → plain findAll (no Filters arg / record), search box hidden.
        String widgetService = contentEndingWith(entries, "/service/WidgetService.java");
        assertThat(widgetService)
                .contains("findAll(String q, Pageable pageable)")
                .doesNotContain("record Filters(")
                .doesNotContain("priorityMin");
        String widgetPage = entries.get("tools/frontend/src/pages/widget/ui/WidgetPage.tsx");
        assertThat(widgetPage).contains("searchable={false}").doesNotContain("filterDescriptors");

        // Gadget: defaults preserved → Filters carrier with the numeric bounds, search box shown.
        String gadgetService = contentEndingWith(entries, "/service/GadgetService.java");
        assertThat(gadgetService)
                .contains("findAll(String q, Filters filters, Pageable pageable)")
                .contains("record Filters(")
                .contains("priorityMin");
        String gadgetPage = entries.get("tools/frontend/src/pages/gadget/ui/GadgetPage.tsx");
        assertThat(gadgetPage).contains("searchable={true}").contains("filterDescriptors");
    }

    @Test
    void fullstackEndpoint_perFieldLabelAndReadOnly() throws Exception {
        // title carries a custom (Hebrew) display label; status is read-only (locked after create).
        Map<String, Object> title = Map.of("name", "title", "type", "String", "required", true, "label", "כותרת");
        Map<String, Object> status = Map.of("name", "status", "type", "String", "readOnly", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "tasks");
        body.put("packageName", "com.menora.tasks");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Task", "fields", List.of(pkField(), title, status))));

        Map<String, String> entries = generateZip(body);

        // Frontend column uses the custom label, but the data/sort key stays the field name.
        String taskPage = entries.get("tasks/frontend/src/pages/task/ui/TaskPage.tsx");
        assertThat(taskPage).contains("label: 'כותרת', sortKey: 'title'");

        // Frontend form: the read-only field is locked after create (disabled on edit); the
        // custom label reaches the Field. A generated PK uses a bare `disabled`, so `disabled={!isNew}`
        // here comes from the read-only status field (there is no non-generated PK in this entity).
        String taskForm = contentEndingWith(entries, "/task-form/ui/TaskForm.tsx");
        assertThat(taskForm).contains("label=\"כותרת\"");
        assertThat(taskForm).contains("disabled={!isNew}");

        // Backend Service.update never overwrites the read-only field, but still copies the editable one.
        String taskService = contentEndingWith(entries, "/service/TaskService.java");
        assertThat(taskService).contains("existing.setTitle(updated.getTitle());");
        assertThat(taskService).doesNotContain("existing.setStatus(updated.getStatus());");
        // But status is still a real column/field (bound on create) — the entity carries the setter.
        String taskEntity = contentEndingWith(entries, "/entity/Task.java");
        assertThat(taskEntity).contains("private String status;");
    }

    @Test
    void fullstackEndpoint_rendersFieldConstraints() throws Exception {
        // email (String, email=true, length-bounded), age (Integer, min/max), and code
        // (String, regex pattern) exercise @Email/@Min/@Max/@Pattern in the DTO and the
        // matching HTML input attributes in the form.
        Map<String, Object> email = Map.of("name", "email", "type", "String", "required", true, "length", 200, "email", true);
        Map<String, Object> age = Map.of("name", "age", "type", "Integer", "min", 0, "max", 120);
        Map<String, Object> code = Map.of("name", "code", "type", "String", "pattern", "[A-Z]{3}");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "people");
        body.put("packageName", "com.menora.people");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Person", "fields", List.of(pkField(), email, age, code))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        String dto = contentEndingWith(entries, "/dto/PersonDto.java");
        assertThat(dto)
                .contains("import jakarta.validation.constraints.Email;")
                .contains("import jakarta.validation.constraints.Pattern;")
                .contains("import jakarta.validation.constraints.Min;")
                .contains("import jakarta.validation.constraints.Max;")
                .contains("@Email")
                .contains("@Pattern(regexp = \"[A-Z]{3}\")")
                .contains("@Min(0)")
                .contains("@Max(120)");

        String form = entries.get("people/frontend/src/features/person-form/ui/PersonForm.tsx");
        assertThat(form)
                .contains("type=\"email\"")
                .contains("min=\"0\"")
                .contains("max=\"120\"")
                .contains("pattern={\"[A-Z]{3}\"}");
    }

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
                .contains("Long customerId")
                .contains("cb.equal(root.get(\"customer\").get(\"id\"), filters.customerId())");
        assertThat(contentEndingWith(entries, "/controller/OrderController.java"))
                .contains("@RequestParam(required = false) Long customerId");

        // Frontend: type + form + page carry customerId (and the label).
        assertThat(entries.get("shop/frontend/src/entities/order/model/types.ts"))
                .contains("customerId: number | null")
                .contains("customerLabel?: string | null");
        // The FK now renders as a <select> populated from the target's list endpoint via useOptions.
        assertThat(entries.get("shop/frontend/src/features/order-form/ui/OrderForm.tsx"))
                .contains("import { useOptions } from '@shared/api'")
                .contains("useOptions<Record<string, unknown>>('/api/customers')")
                .contains("label=\"Customer\" required")
                .contains("<select")
                .contains("set('customerId'");
        // Table column shows the label (falling back to #id), and the filter bar gets a relation select.
        assertThat(entries.get("shop/frontend/src/pages/order/ui/OrderPage.tsx"))
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
    void fullstackEndpoint_lombokBackendSetUsesLombokEntities() throws Exception {
        // Selecting the spring-jpa-crud-lombok set swaps only the Entity template (Lombok
        // @Data/@NoArgsConstructor/@AllArgsConstructor, no hand-written accessors); the DTO,
        // repository, service, and controller are reused from spring-jpa-crud via sourceSet.
        Map<String, Object> name = Map.of("name", "name", "type", "String", "required", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("backendTemplateSet", "spring-jpa-crud-lombok");
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField(), name))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        String userEntity = contentEndingWith(entries, "/entity/User.java");
        assertThat(userEntity)
                .contains("import lombok.Data;")
                .contains("@Data")
                .contains("@NoArgsConstructor")
                .contains("@AllArgsConstructor")
                .contains("private String name;")
                // Lombok generates the accessors/ctor — they must NOT be hand-written.
                .doesNotContain("public Long getId()")
                .doesNotContain("public User()");

        // Reused-from-spring-jpa-crud files are still present and correct.
        assertThat(contentEndingWith(entries, "/controller/UserController.java"))
                .contains("@RequestMapping(\"/api/users\")");
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/dto/UserDto.java"));
        // Lombok is a __common__ dependency, so it is on every generated pom.
        assertThat(entries.get("shop/backend/pom.xml")).contains("lombok");
    }

    @Test
    void fullstackEndpoint_emitsControllerTestsWhenOptedIn() throws Exception {
        // opts.scaffold=[tests] flips the optScaffoldTests gate, so the per-entity @WebMvcTest is
        // rendered under src/test. It mocks the service, so it needs no datasource or extra dep.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("bootVersion", "3.2.1");
        body.put("opts", Map.of("scaffold", List.of("tests")));
        // User has no filterable field (2-arg findAll); Task has a BOOLEAN -> Filters + 3-arg findAll.
        body.put("entities", List.of(
                Map.of("name", "User", "fields", List.of(pkField())),
                Map.of("name", "Task", "fields", List.of(pkField(), Map.of("name", "done", "type", "Boolean")))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        assertThat(entries.keySet()).anyMatch(p ->
                p.equals("demo/backend/src/test/java/com/menora/demo/controller/UserControllerTest.java"));
        assertThat(contentEndingWith(entries, "/controller/UserControllerTest.java"))
                .contains("@WebMvcTest(UserController.class)")
                .contains("@MockBean")
                .contains("get(\"/api/users\")")
                // No filter fields -> the service only has findAll(q, pageable): two matchers.
                .contains("service.findAll(any(), any())")
                .doesNotContain("service.findAll(any(), any(), any())");
        // A filterable field switches the service to findAll(q, filters, pageable); the mocked
        // call must carry three matchers or the generated test does not compile.
        assertThat(contentEndingWith(entries, "/service/TaskService.java"))
                .contains("findAll(String q, Filters filters, Pageable pageable)");
        assertThat(contentEndingWith(entries, "/controller/TaskControllerTest.java"))
                .contains("service.findAll(any(), any(), any())")
                .doesNotContain("service.findAll(any(), any()))")
                // The mocked page carries a real PageRequest: PageImpl(List) alone holds
                // Pageable.unpaged(), which Jackson cannot serialize (the test would 500).
                .contains("new PageImpl<Task>(List.of(), PageRequest.of(0, 20), 0)")
                .contains("import org.springframework.data.domain.PageRequest;");
    }

    @Test
    void fullstackEndpoint_omitsControllerTestsByDefault() throws Exception {
        // No opts → the gated test file is not rendered (the default, lean output).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());
        assertThat(entries.keySet()).noneMatch(p -> p.endsWith("ControllerTest.java"));
    }

    @Test
    void fullstackEndpoint_rtlOptionSetsDirAndMirrorsShell() throws Exception {
        // opts.scaffold=[rtl] flips the frontend isRtl flag: index.html gets dir="rtl"/lang="he"
        // and the shell/shared-UI templates use Tailwind logical utilities so the layout mirrors.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("opts", Map.of("scaffold", List.of("rtl")));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        String indexHtml = entries.get("shop/frontend/index.html");
        assertThat(indexHtml).contains("dir=\"rtl\"").contains("lang=\"he\"");

        // The shell uses logical utilities (converted from physical border-l/ml/left/text-right),
        // and the Toaster flips to the RTL-appropriate corner.
        String app = entries.get("shop/frontend/src/app/App.tsx");
        assertThat(app).contains("border-s-2").doesNotContain("border-l-2");
        assertThat(app).contains("position={ 'top-left' }");
        assertThat(entries.get("shop/frontend/src/shared/ui/Table.tsx"))
                .contains("sticky end-0").doesNotContain("sticky right-0");
    }

    @Test
    void fullstackEndpoint_defaultsToLtrIndexHtml() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());
        String indexHtml = entries.get("shop/frontend/index.html");
        assertThat(indexHtml).contains("lang=\"en\"").doesNotContain("dir=\"rtl\"");
        // Logical utilities are always emitted (they behave identically in LTR).
        assertThat(entries.get("shop/frontend/src/app/App.tsx"))
                .contains("position={ 'top-right' }");
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
    void fullstackEndpoint_themesFrontendWithMenoraPaletteByDefault() throws Exception {
        // No colorPalette in the request → the isDefault palette (menora-default: navy + gold)
        // is injected into the Tailwind v4 @theme block, and components reference the brand tokens.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        String css = entries.get("shop/frontend/src/index.css");
        assertThat(css)
                .contains("@theme")
                .contains("--color-brand:")
                .contains("#9A83F7")   // Menora purple (palette primary → brand)
                .contains("#2B2F4C")   // fixed navy shell (ink)
                .contains("#FEDB41");  // gold highlight (palette secondary)

        // Components reference the brand tokens, not the old emerald defaults.
        String app = entries.get("shop/frontend/src/app/App.tsx");
        assertThat(app).contains("bg-app-shell").contains("text-gold").contains("border-gold");
        assertThat(app).doesNotContain("emerald");
        // Primary actions use the semantic action tokens (aliased to brand + white text in this set).
        assertThat(css).contains("--color-primary:").contains("--color-on-primary:   #ffffff");
        assertThat(entries.get("shop/frontend/src/pages/user/ui/UserPage.tsx")).contains("bg-primary").contains("text-on-primary");
        assertThat(entries.get("shop/frontend/src/shared/ui/FormDrawer.tsx")).contains("bg-primary");
        // The default set does not pull in the Menora Digital design-system dep.
        assertThat(entries).doesNotContainKey("shop/frontend/public/menora-mivtachim-logo.png");
        assertThat(entries.keySet()).noneMatch(k -> k.startsWith("shop/frontend/src/shared/ui/menora/"));

        // Brand logo asset shipped and referenced in the sidebar + favicon.
        assertThat(entries).containsKey("shop/frontend/public/logo.png");
        assertThat(app).contains("/logo.png").doesNotContain("Database");
        assertThat(entries.get("shop/frontend/index.html")).contains("/logo.png");
    }

    @Test
    void fullstackEndpoint_menoraDigitalSetReskinsTheFrontend() throws Exception {
        // frontendTemplateSet=react-menora-digital-crud: the overlay re-points the Tailwind theme to
        // the Menora Digital tokens (yellow action pills with ink labels, purple identity, #f8f8f8
        // ground, Assistant font), swaps the shell for the white top-bar layout, and — because the
        // set is tagged MENORA_DIGITAL — the substrate lays down the design-menora-digital dep's
        // tokens/components/logo. Every other file is borrowed from react-tailwind-crud.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("frontendTemplateSet", "react-menora-digital-crud");
        body.put("opts", Map.of("scaffold", List.of("rtl")));
        body.put("entities", List.of(
                Map.of("name", "User", "fields", List.of(pkField())),
                Map.of("name", "Order", "fields", List.of(pkField()))));
        Map<String, String> entries = generateZip(body);

        String css = entries.get("shop/frontend/src/index.css");
        assertThat(css)
                .contains("@import \"tailwindcss\"")
                .contains("@import './shared/ui/menora/tokens.css'")
                .contains("--color-primary:      #ffc700")
                .contains("--color-on-primary:   #37374e")
                .contains("--color-brand:        #684eed")
                .contains("--color-canvas:       #f8f8f8")
                .contains("--radius-2xl:         30px")
                .contains("'Assistant'")
                // dark mode re-points the same tokens to the design system's dark values
                .contains("--color-brand:        #a393ff").contains("--color-canvas:       #1e1e2f")
                .doesNotContain("#9A83F7").doesNotContain("#2B2F4C");

        assertThat(entries.get("shop/frontend/package.json"))
                .contains("@fontsource/assistant").doesNotContain("@fontsource/inter");
        assertThat(entries.get("shop/frontend/src/main.tsx")).contains("@fontsource/assistant/500.css");

        String app = entries.get("shop/frontend/src/app/App.tsx");
        assertThat(app)
                .contains("/menora-mivtachim-logo.png").contains("<ChatLauncher />")
                .contains("UserPage").contains("OrderPage")
                .contains("position={ 'top-left' }")
                .doesNotContain("bg-app-shell");

        // Dashboard hero: yellow full stop + yellow pill CTA.
        assertThat(entries.get("shop/frontend/src/pages/dashboard/ui/DashboardPage.tsx"))
                .contains("<span className=\"text-primary\">.</span>")
                .contains("bg-primary px-8 text-base font-medium text-on-primary shadow-cta");

        // Borrowed files are present and unchanged; the shared action buttons use the semantic tokens.
        assertThat(entries.get("shop/frontend/src/shared/ui/Table.tsx")).contains("onSortChange");
        assertThat(entries).containsKey("shop/frontend/src/shared/api/useResource.ts");
        assertThat(entries.get("shop/frontend/src/pages/user/ui/UserPage.tsx")).contains("bg-primary").contains("text-on-primary");
        assertThat(entries.get("shop/frontend/src/shared/ui/FormDrawer.tsx")).contains("bg-primary");
        // vite.config.ts is borrowed as a mustache row (sourceSet) and rendered, not copied raw.
        assertThat(entries.get("shop/frontend/vite.config.ts"))
                .contains("base: '/'").contains("target: 'http://localhost:8080'").doesNotContain("{{");

        // Design-system substrate files + brand mark; the standalone landing page is still removed.
        assertThat(entries).containsKey("shop/frontend/src/shared/ui/menora/tokens.css");
        assertThat(entries).containsKey("shop/frontend/src/shared/ui/menora/ChatLauncher.tsx");
        assertThat(entries).containsKey("shop/frontend/public/menora-mivtachim-logo.png");
        assertThat(entries.keySet()).noneMatch(k -> k.startsWith("shop/frontend/src/pages/home/"));
        assertThat(entries.get("shop/frontend/index.html")).contains("dir=\"rtl\"");
    }

    @Test
    void fullstackEndpoint_appliesSelectedColorPalette() throws Exception {
        // An explicit colorPalette flows through to the generated theme (forest primary = #2e7d32).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("colorPalette", "forest");
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());
        assertThat(entries.get("shop/frontend/src/index.css"))
                .contains("#2e7d32")          // forest primary injected
                .doesNotContain("#9A83F7");   // not the Menora default purple
    }

    @Test
    void fullstackEndpoint_injectsDevUserinfoHeaderWhenLdapAuthSelected() throws Exception {
        // With the ldap-auth backend dep, the generated API client adds the `userinfo` header in
        // dev (read by the backend's @RequiresPermission aspect) and .env.development carries the
        // overridable default.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "secured");
        body.put("packageName", "com.menora.secured");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "ldap-auth"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        String client = entries.get("secured/frontend/src/shared/api/client.ts");
        assertThat(client)
                .contains("import.meta.env.DEV")
                .contains("headers['userinfo']")
                .contains("VITE_DEV_USERINFO");
        assertThat(entries.get("secured/frontend/.env.development")).contains("VITE_DEV_USERINFO=dev-user");

        // Backend got the LDAP authorization scaffold + deps.
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/security/PermissionAspect.java"));
        assertThat(entries.get("secured/backend/pom.xml")).contains("lts.ldap.util");
    }

    @Test
    void fullstackEndpoint_omitsDevUserinfoHeaderWithoutLdapAuth() throws Exception {
        // Deps without ldap-auth (explicit, since ldap-auth is now a set default) → the header
        // block is not emitted.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "plain");
        body.put("packageName", "com.menora.plain");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> entries = unzip(response.getBody());

        assertThat(entries.get("plain/frontend/src/shared/api/client.ts"))
                .doesNotContain("userinfo");
        assertThat(entries.get("plain/frontend/.env.development")).doesNotContain("VITE_DEV_USERINFO");
    }

    @Test
    void fullstackEndpoint_addsOpenApiDocsWhenOptedIn() throws Exception {
        // opts.scaffold=[openapi] force-adds the springdoc starter (not a set default) and gates
        // @Tag/@Operation onto the generated controller so Swagger UI documents the CRUD endpoints.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("bootVersion", "3.2.1");
        body.put("opts", Map.of("scaffold", List.of("openapi")));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        // Springdoc starter wired into the pom even though it is not a spring-jpa-crud default.
        assertThat(entries.get("demo/backend/pom.xml")).contains("springdoc-openapi-starter-webmvc-ui");

        String controller = contentEndingWith(entries, "/controller/UserController.java");
        assertThat(controller)
                .contains("import io.swagger.v3.oas.annotations.Operation;")
                .contains("import io.swagger.v3.oas.annotations.tags.Tag;")
                .contains("@Tag(name = \"User\"")
                .contains("@Operation(summary =");
    }

    @Test
    void fullstackEndpoint_omitsOpenApiDocsByDefault() throws Exception {
        // No opts → no springdoc dep and no swagger annotations (lean default output).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        assertThat(entries.get("demo/backend/pom.xml")).doesNotContain("springdoc");
        assertThat(contentEndingWith(entries, "/controller/UserController.java"))
                .doesNotContain("io.swagger.v3.oas.annotations")
                .doesNotContain("@Tag")
                .doesNotContain("@Operation");
    }

    @Test
    void fullstackEndpoint_securesEndpointsWhenOptedIn() throws Exception {
        // opts.scaffold=[secured] scaffolds @RequiresPermission on CRUD methods (reads → USER,
        // writes → ADMIN) but COMMENTED OUT, so the user opts in per-endpoint later. ldap-auth is
        // a set default, so the security.* classes referenced by the commented hints exist.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("bootVersion", "3.2.1");
        body.put("opts", Map.of("scaffold", List.of("secured")));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        String controller = contentEndingWith(entries, "/controller/UserController.java");
        // Imports + annotations are present but commented out (no enforcement by default).
        assertThat(controller)
                .contains("// import com.menora.demo.security.Constants;")
                .contains("// import com.menora.demo.security.RequiresPermission;")
                .contains("// @RequiresPermission(Constants.USER)")    // reads
                .contains("// @RequiresPermission(Constants.ADMIN)");  // writes
        // Nothing is active: no uncommented annotation or import leaks through.
        assertThat(controller)
                .doesNotContain("    @RequiresPermission")
                .doesNotContain("\nimport com.menora.demo.security.");
    }

    @Test
    void fullstackEndpoint_securedOptIsNoOpWithoutLdapAuth() throws Exception {
        // secured requested but ldap-auth deselected → the flag short-circuits, so no
        // @RequiresPermission and no broken security.* import (the classes wouldn't exist).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("opts", Map.of("scaffold", List.of("secured")));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/controller/UserController.java"))
                .doesNotContain("@RequiresPermission")
                .doesNotContain(".security.RequiresPermission");
    }

    @Test
    void importDdlEndpoint_returnsEntitiesInWireFormat() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dialect", "H2");
        body.put("sql", """
                CREATE TABLE inv.products (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    sku VARCHAR(64) NOT NULL,
                    price NUMERIC(10,2)
                );
                """);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/metadata/fullstack/import-ddl", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) response.getBody().get("entities");
        assertThat(entities).hasSize(1);
        Map<String, Object> product = entities.get(0);
        assertThat(product).containsEntry("name", "Product");
        assertThat(product).containsEntry("tableName", "products");
        assertThat(product).containsEntry("schema", "inv");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) product.get("fields");
        assertThat(fields).hasSize(3);
        assertThat(fields.get(0)).containsEntry("type", "LONG").containsEntry("primaryKey", true);
        assertThat(fields.get(1)).containsEntry("type", "STRING").containsEntry("length", 64);
        assertThat(fields.get(2)).containsEntry("type", "BIG_DECIMAL");
    }

    @Test
    void importDdlEndpoint_returns400OnParseError() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", "CREATE TABLE bad ( this is not valid sql );");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/metadata/fullstack/import-ddl", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Invalid SQL");
    }

    @Test
    void importSelectEndpoint_returnsReadOnlyViewEntity() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", "SELECT u.id AS id, u.full_name AS fullName FROM users u");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/metadata/fullstack/import-select", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) response.getBody().get("entities");
        assertThat(entities).hasSize(1);
        Map<String, Object> view = entities.get(0);
        assertThat(view).containsEntry("name", "User");       // singularized FROM table
        assertThat(view).containsEntry("readOnly", true);
        assertThat(view.get("viewQuery").toString()).contains("SELECT").contains("users");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) view.get("fields");
        assertThat(fields).hasSize(2);
        // No types in a SELECT → everything defaults to STRING, first column is the PK.
        assertThat(fields.get(0)).containsEntry("name", "id").containsEntry("type", "STRING").containsEntry("primaryKey", true);
        assertThat(fields.get(1)).containsEntry("name", "fullName").containsEntry("type", "STRING").containsEntry("primaryKey", false);
    }

    @Test
    void importSelectEndpoint_fallsBackForNativeSqlWithNote() {
        Map<String, Object> body = new LinkedHashMap<>();
        // Oracle MODEL clause: valid native SQL JSqlParser can't parse — should still import.
        body.put("sql", "SELECT id AS id, name AS name FROM t MODEL DIMENSION BY (id) MEASURES (name) RULES ()");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/metadata/fullstack/import-select", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) response.getBody().get("entities");
        assertThat(entities).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) entities.get(0).get("fields");
        assertThat(fields).extracting(f -> f.get("name")).containsExactly("id", "name");
        // A heuristic detection carries an advisory note.
        assertThat(response.getBody().get("note").toString()).contains("heuristically");
    }

    @Test
    void importSelectEndpoint_returns400OnSelectStar() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", "SELECT * FROM users");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/metadata/fullstack/import-select", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Invalid SQL");
        assertThat(response.getBody().get("detail").toString()).contains("*");
    }

    @Test
    void fullstackEndpoint_generatesReadOnlyViewScaffolding() throws Exception {
        // A SELECT-backed read-only view alongside a normal CRUD entity in one request.
        Map<String, Object> viewId = Map.of("name", "id", "type", "Long", "primaryKey", true);
        Map<String, Object> viewName = Map.of("name", "name", "type", "String");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", "UserSummary");
        view.put("readOnly", true);
        view.put("viewQuery", "select id, name from users");
        view.put("fields", List.of(viewId, viewName));

        Map<String, Object> order = Map.of(
                "name", "Order",
                "fields", List.of(pkField(), Map.of("name", "total", "type", "BigDecimal")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(view, order));

        Map<String, String> entries = generateZip(body);

        // View entity maps to a @Subselect view, not a @Table.
        String entity = contentEndingWith(entries, "/UserSummary.java");
        assertThat(entity).contains("@Immutable").contains("@Subselect(").contains("select id, name from users");
        assertThat(entity).doesNotContain("@Table(");

        // View service/controller are GET-only.
        String service = contentEndingWith(entries, "/UserSummaryService.java");
        assertThat(service).contains("findAll").contains("findById");
        assertThat(service).doesNotContain("repository.save").doesNotContain("deleteById");

        String controller = contentEndingWith(entries, "/UserSummaryController.java");
        assertThat(controller).contains("@GetMapping");
        assertThat(controller).doesNotContain("@PostMapping").doesNotContain("@PutMapping").doesNotContain("@DeleteMapping");

        // The normal entity still gets full CRUD.
        String orderController = contentEndingWith(entries, "/OrderController.java");
        assertThat(orderController).contains("@PostMapping").contains("@DeleteMapping");

        // Read-only view frontend page has no New/Edit/Delete surface.
        String viewPage = contentEndingWith(entries, "/UserSummaryPage.tsx");
        assertThat(viewPage).doesNotContain("New UserSummary").doesNotContain("onDelete=");
    }

    @Test
    void fullstackEndpoint_rejectsGeneratedPkOnView() {
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "demo");
            b.put("bootVersion", "3.2.1");
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", "UserSummary");
            view.put("viewQuery", "select id from users");
            view.put("fields", List.of(pkField()));   // pkField() is generated=true
            b.put("entities", List.of(view));
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("view").contains("generated");
    }

    @Test
    void fullstackEndpoint_rejectsEmptyEntities() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "demo");
        body.put("entities", List.of());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("At least one entity");
    }

    @Test
    void fullstackEndpoint_rejectsUnknownBackendTemplateSet() {
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "demo");
            b.put("bootVersion", "3.2.1");
            b.put("backendTemplateSet", "does-not-exist");
            b.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("backendTemplateSet").contains("not found");
    }

    @Test
    void fullstackEndpoint_rejectsUnknownFrontendTemplateSet() {
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "demo");
            b.put("bootVersion", "3.2.1");
            b.put("frontendTemplateSet", "no-such-frontend");
            b.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("frontendTemplateSet").contains("not found");
    }

    @Test
    void fullstackEndpoint_rejectsWrongKindTemplateSet() {
        // Point frontendTemplateSet at a backend set — kind mismatch must 400, not silently swap.
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "demo");
            b.put("bootVersion", "3.2.1");
            b.put("frontendTemplateSet", "spring-jpa-crud");
            b.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("frontendTemplateSet").contains("FRONTEND_REACT");
    }

    @Test
    void fullstackEndpoint_rejectsReservedKeywordEnumConstant() {
        Map<String, Object> enumField = new LinkedHashMap<>();
        enumField.put("name", "status");
        enumField.put("type", "Enum");
        enumField.put("enumValues", List.of("ACTIVE", "class"));

        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "demo");
            b.put("bootVersion", "3.2.1");
            b.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField(), enumField))));
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("reserved keyword");
    }

    @Test
    void fullstackEndpoint_rejectsUnknownBootVersion() {
        // A bogus bootVersion from a direct API caller must 400 cleanly, not 500 deep in generation.
        ResponseEntity<String> response = postFullstack(b -> {
            b.put("artifactId", "demo");
            b.put("bootVersion", "9.9.9");
            b.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("bootVersion").contains("known version");
    }

    @Test
    void fullstackEndpoint_rendersCompositePrimaryKey() throws Exception {
        // OrderLine has a two-field composite PK (orderId + lineNo). It is rendered with @IdClass +
        // a separate top-level key class (OrderLineId.java, matching the SQL wizard); the repository
        // id type, the controller path, and the frontend key-array addressing all follow.
        Map<String, Object> orderId = Map.of("name", "orderId", "type", "Long", "primaryKey", true);
        Map<String, Object> lineNo = Map.of("name", "lineNo", "type", "Integer", "primaryKey", true);
        Map<String, Object> qty = Map.of("name", "qty", "type", "Integer");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "OrderLine", "fields", List.of(orderId, lineNo, qty))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/entity/OrderLine.java"))
                .contains("@IdClass(OrderLineId.class)")
                .doesNotContain("public static class OrderLineId");
        // The composite key is its own top-level class file, not a nested class.
        assertThat(contentEndingWith(entries, "/entity/OrderLineId.java"))
                .contains("public class OrderLineId implements java.io.Serializable")
                .contains("private Long orderId;")
                .contains("private Integer lineNo;")
                .contains("java.util.Objects.hash(orderId, lineNo)");
        assertThat(contentEndingWith(entries, "/repository/OrderLineRepository.java"))
                .contains("import com.menora.shop.entity.OrderLineId;")
                .contains("JpaRepository<OrderLine, OrderLineId>");
        assertThat(contentEndingWith(entries, "/controller/OrderLineController.java"))
                .contains("@GetMapping(\"/{orderId}/{lineNo}\")")
                .contains("@DeleteMapping(\"/{orderId}/{lineNo}\")")
                .contains("new OrderLineId(orderId, lineNo)");
        // The shared resource hook gained ordered-key path joining for composite addressing.
        assertThat(entries.get("shop/frontend/src/shared/api/useResource.ts"))
                .contains("Array.isArray(id)");
        // The page's React row key joins the key parts (no `id` property to fall back on).
        assertThat(entries.get("shop/frontend/src/pages/order-line/ui/OrderLinePage.tsx"))
                .contains("const rowKey = (row: OrderLine) => [String(row.orderId), String(row.lineNo)].join('/')")
                .contains("rowKey={rowKey}");
        assertThat(entries.get("shop/frontend/src/shared/ui/Table.tsx"))
                .contains("key={rowKey(row)}")
                .doesNotContain(".id ?? idx");
    }

    @Test
    void fullstackEndpoint_rendersTableSchema() throws Exception {
        // A schema-qualified entity (e.g. imported from `CREATE TABLE entv.test`) must render
        // @Table(name = "test", schema = "entv"), matching the standalone SQL wizard.
        Map<String, Object> id = Map.of("name", "id", "type", "Long", "primaryKey", true, "generated", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "schemaapp");
        body.put("packageName", "com.menora.schemaapp");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of(
                "name", "Test", "tableName", "test", "schema", "entv", "fields", List.of(id))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/entity/Test.java"))
                .contains("@Table(name = \"test\", schema = \"entv\")");
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
        assertThat(entries.get("shop/frontend/src/pages/widget/ui/WidgetPage.tsx"))
                .contains("restore(removed.id as number | string)")
                .contains("label: 'Undo'")
                .doesNotContain("create(removed");
        // Composite PK: no soft delete, hence no restore anywhere.
        assertThat(contentEndingWith(entries, "/repository/OrderLineRepository.java")).doesNotContain("restore");
        assertThat(contentEndingWith(entries, "/controller/OrderLineController.java")).doesNotContain("restore");
    }

    @Test
    void fullstackEndpoint_formValidatesClientSideAndDataHookGuardsStaleResponses() throws Exception {
        Map<String, Object> name = Map.of("name", "name", "type", "String", "required", true, "length", 40);
        Map<String, Object> email = Map.of("name", "email", "type", "String", "email", true);
        Map<String, Object> age = Map.of("name", "age", "type", "Integer", "min", 0, "max", 120);
        Map<String, Object> code = Map.of("name", "code", "type", "String", "pattern", "[A-Z]{3}");
        Map<String, Object> rel = Map.of(
                "type", "MANY_TO_ONE", "fieldName", "company", "targetEntity", "Company", "required", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(
                Map.of("name", "Company", "fields", List.of(pkField(), Map.of("name", "name", "type", "String"))),
                Map.of("name", "Contact", "fields", List.of(pkField(), name, email, age, code), "relations", List.of(rel))));
        Map<String, String> entries = generateZip(body);

        // The feature's model/validate.ts exports a validator derived from the same field metadata
        // as the DTO's Bean Validation; the generated PK is never validated.
        String validate = entries.get("shop/frontend/src/features/contact-form/model/validate.ts");
        assertThat(validate)
                .contains("export function validateContact(value: Partial<Contact>): Record<string, string>")
                .contains("const blank = (v: unknown)")
                .contains("if (blank(value.name)) errors.name = 'Required'")
                .contains("value.name.length > 40) errors.name = 'Must be at most 40 characters'")
                .contains("Number(value.age) < 0) errors.age = 'Must be at least 0'")
                .contains("Number(value.age) > 120) errors.age = 'Must be at most 120'")
                .contains("new RegExp('^(?:' + \"[A-Z]{3}\" + ')$').test(value.code)) errors.code = 'Invalid format'")
                .contains("errors.email = 'Must be a valid email address'")
                .contains("if (blank(value.companyId)) errors.companyId = 'Required'")
                .doesNotContain("errors.id =");
        // Nothing required on Company → no `blank` helper (the generated lint forbids unused vars).
        assertThat(entries.get("shop/frontend/src/features/company-form/model/validate.ts"))
                .contains("export function validateCompany(")
                .doesNotContain("const blank");
        assertThat(entries.get("shop/frontend/src/features/contact-form/ui/ContactForm.tsx")).doesNotContain("validateContact");
        assertThat(entries.get("shop/frontend/src/features/contact-form/index.ts"))
                .contains("export { validateContact } from './model/validate'");
        // The page validates before calling the API, and — with no soft delete — offers no Undo.
        assertThat(entries.get("shop/frontend/src/pages/contact/ui/ContactPage.tsx"))
                .contains("import { ContactForm, validateContact } from '@features/contact-form'")
                .contains("const clientErrors = validateContact(editing)")
                .contains("k in formErrors")
                .doesNotContain("label: 'Undo'")
                .doesNotContain("create(removed");
        // The drawer is a real form (Enter submits, Save is type=submit, browser bubbles off).
        assertThat(entries.get("shop/frontend/src/shared/ui/FormDrawer.tsx"))
                .contains("<form")
                .contains("noValidate")
                .contains("onSubmit={e => {")
                .contains("type=\"submit\"")
                .doesNotContain("onClick={onSave}");
        // Out-of-order responses are dropped; a refetch keeps the current rows on screen.
        assertThat(entries.get("shop/frontend/src/shared/api/useResource.ts"))
                .contains("const requestRef = useRef(0)")
                .contains("if (requestId !== requestRef.current) return")
                .contains("const restore = useCallback(");
        assertThat(entries.get("shop/frontend/src/shared/ui/Table.tsx"))
                .contains("loading && rows.length === 0 ? (");
        assertThat(entries.get("shop/frontend/src/shared/ui/CardGrid.tsx"))
                .contains("loading && rows.length === 0 ? (");
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
    void fullstackEndpoint_dbDependencyEmitsConfigClassWithoutDatasourceOpt() throws Exception {
        // Regression: the fullstack request never carries a db datasource role sub-option
        // (db2-primary/db2-secondary). The controller must default to the primary datasource so
        // the gated Db2Config class is generated — otherwise the backend has no DataSource bean
        // (the YAML uses the custom `db2.datasource` prefix, not `spring.datasource`) and JPA
        // autoconfig fails to start.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "db2app");
        body.put("packageName", "com.menora.db2app");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "db2"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        // The config class is present and binds the custom-prefix datasource as @Primary.
        assertThat(entries.keySet()).anyMatch(p ->
                p.equals("db2app/backend/src/main/java/com/menora/db2app/config/Db2Config.java"));
        assertThat(contentEndingWith(entries, "/config/Db2Config.java"))
                .contains("@Primary")
                .contains("@ConfigurationProperties(prefix = \"db2.datasource\")")
                .contains("public DataSource db2DataSource()")
                // The config must scan where the scaffolded entities/repos actually land
                // (com.menora.db2app.entity / .repository), not the legacy .db2 subpackage.
                .contains("basePackages = \"com.menora.db2app.repository\"")
                .contains("em.setPackagesToScan(\"com.menora.db2app.entity\")")
                .doesNotContain(".db2.repository")
                .doesNotContain("\"com.menora.db2app.db2\"");
        // The YAML datasource block is still written too.
        assertThat(entries.get("db2app/backend/src/main/resources/application.yaml"))
                .contains("db2:")
                .contains("driver-class-name: com.ibm.as400.access.AS400JDBCDriver");
    }

    @Test
    void fullstackEndpoint_explicitSecondaryDatasourceOptIsNotOverridden() throws Exception {
        // When the caller explicitly picks the secondary datasource role, the default-to-primary
        // must not fire — the secondary variant (no @Primary) is rendered.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "db2app");
        body.put("packageName", "com.menora.db2app");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "db2"));
        body.put("opts", Map.of("db2", List.of("db2-secondary")));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/config/Db2Config.java"))
                .contains("@ConfigurationProperties(prefix = \"db2.datasource\")")
                .doesNotContain("@Primary")
                // A secondary datasource is not the owner of the scaffolded entities — it keeps
                // its legacy .db2 subpackage convention (primary owns generated entities).
                .contains("basePackages = \"com.menora.db2app.db2.repository\"")
                .contains("em.setPackagesToScan(\"com.menora.db2app.db2\")");
    }

    @Test
    void fullstackEndpoint_dbConfigScansCustomDomainPackage() throws Exception {
        // When the entities live under a custom domainPackage, the datasource config must scan
        // that package's .entity/.repository — not packageName's.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "db2app");
        body.put("packageName", "com.menora.db2app");
        body.put("domainPackage", "com.menora.db2app.catalog");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "db2"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/config/Db2Config.java"))
                .contains("basePackages = \"com.menora.db2app.catalog.repository\"")
                .contains("em.setPackagesToScan(\"com.menora.db2app.catalog.entity\")");
        // And the entity actually lands there.
        assertThat(entries.keySet()).anyMatch(p -> p.endsWith("/catalog/entity/User.java"));
    }

    @Test
    void fullstackEndpoint_mongoConfigScansScaffoldedRepositoryPackage() throws Exception {
        // MongoDB has no setPackagesToScan, but @EnableMongoRepositories must point at the
        // scaffolded .repository package rather than the legacy .mongodb.repository.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "mongoapp");
        body.put("packageName", "com.menora.mongoapp");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "mongodb"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        assertThat(contentEndingWith(entries, "/config/MongoConfig.java"))
                .contains("basePackages = \"com.menora.mongoapp.repository\"")
                // not the legacy driver subpackage (the Spring import naturally contains
                // ".mongodb.repository", so assert on the full legacy basePackages literal).
                .doesNotContain("\"com.menora.mongoapp.mongodb.repository\"");
    }

    @Test
    void fullstackEndpoint_h2DefaultOmitsConfigClass() throws Exception {
        // h2 is excluded from the default-to-primary: its ungated YAML ships a real
        // spring.datasource block, so it is runnable with no config class. Defaulting it to
        // primary would wrongly emit H2Config + the gated h2.datasource mirror.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "h2app");
        body.put("packageName", "com.menora.h2app");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "h2"));
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

        Map<String, String> entries = generateZip(body);

        assertThat(entries.keySet()).noneMatch(p -> p.endsWith("/config/H2Config.java"));
        String yaml = entries.get("h2app/backend/src/main/resources/application.yaml");
        assertThat(yaml)
                .contains("driver-class-name: org.h2.Driver")
                .doesNotContain("hbm2ddl-auto");   // the gated custom h2.datasource mirror
    }

    @Test
    void fullstackEndpoint_schemaQualifiedEntityBootsOnH2() throws Exception {
        // A schema-qualified entity (e.g. imported from `CREATE TABLE entv.td_app_stp`) emits
        // @Table(schema="ENTV"), which crashes the bundled H2 ("Schema ENTV not found") unless
        // Hibernate is told to create namespaces first. The H2 dev config must carry the flag.
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("name", "TdAppStp");
        entity.put("tableName", "TD_APP_STP");
        entity.put("schema", "ENTV");
        entity.put("fields", List.of(pkField()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "h2app");
        body.put("packageName", "com.menora.h2app");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web", "h2"));
        body.put("entities", List.of(entity));

        Map<String, String> entries = generateZip(body);

        // The entity keeps its schema (real-DB fidelity)…
        assertThat(contentEndingWith(entries, "/entity/TdAppStp.java"))
                .contains("schema = \"ENTV\"");
        // …and the H2 dev config auto-creates it so the app boots.
        assertThat(entries.get("h2app/backend/src/main/resources/application.yaml"))
                .contains("create_namespaces: true");
    }

    @Test
    void fullstackEndpoint_rendersTextAndUuidFieldTypes() throws Exception {
        // A UUID generated PK maps to @UuidGenerator + a java.util.UUID column (no import threading),
        // and a TEXT field maps to a SQL TEXT column rendered as a <textarea> in the form.
        Map<String, Object> uuidPk = new LinkedHashMap<>();
        uuidPk.put("name", "id");
        uuidPk.put("type", "UUID");
        uuidPk.put("primaryKey", true);
        uuidPk.put("generated", true);
        Map<String, Object> details = Map.of("name", "details", "type", "TEXT");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "notes");
        body.put("packageName", "com.menora.notes");
        body.put("bootVersion", "3.2.1");
        body.put("entities", List.of(Map.of("name", "Note", "fields", List.of(uuidPk, details))));

        Map<String, String> entries = generateZip(body);

        String entity = contentEndingWith(entries, "/entity/Note.java");
        assertThat(entity)
                .contains("@org.hibernate.annotations.UuidGenerator")
                .doesNotContain("@GeneratedValue")          // UUID uses the Hibernate generator, not IDENTITY
                .contains("private java.util.UUID id;")
                .contains("columnDefinition = \"TEXT\"")
                .contains("private String details;");
        // Repository/Service address the row by the FQN UUID type (no missing import).
        assertThat(contentEndingWith(entries, "/repository/NoteRepository.java"))
                .contains("JpaRepository<Note, java.util.UUID>");
        // TEXT renders as a textarea; the TS type is string-backed.
        assertThat(entries.get("notes/frontend/src/features/note-form/ui/NoteForm.tsx"))
                .contains("<textarea");
        assertThat(entries.get("notes/frontend/src/entities/note/model/types.ts"))
                .contains("id?: string | null")
                .contains("details?: string | null");
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
        String productPage = entries.get("shop/frontend/src/pages/product/ui/ProductPage.tsx");
        assertThat(productPage)
                .contains("useState<'cards'>('cards')")
                .contains("<CardGrid")
                .doesNotContain("<Table")
                .doesNotContain("aria-pressed={viewMode === 'cards'}")   // no toggle button
                .contains("onView={setDetailRow}")
                .contains("<ProductDetail value={detailRow} />");
        assertThat(entries).containsKey("shop/frontend/src/features/product-form/ui/ProductDetail.tsx");
        // An entity with no listViews defaults to table-only (no Cards, no toggle).
        assertThat(entries.get("shop/frontend/src/pages/plain/ui/PlainPage.tsx"))
                .contains("useState<'table'>('table')")
                .doesNotContain("<CardGrid");

        // The enum field drives a dashboard breakdown chart; Plain (no enum/boolean) gets none.
        String dashboard = entries.get("shop/frontend/src/pages/dashboard/ui/DashboardPage.tsx");
        assertThat(dashboard)
                .contains("function BarChart")
                .contains("Products by Status")
                .contains("field: 'status'")
                // Grouped client-side from a sample page -> says so when the table is larger.
                .contains("Based on the first {sampled.shown} of {sampled.total} records");
        assertThat(dashboard).doesNotContain("Plains by");
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
        String taskPage = entries.get("ops/frontend/src/pages/task/ui/TaskPage.tsx");
        assertThat(taskPage)
                .contains("const [viewMode, setViewMode] = useState<'table' | 'kanban'>('table')")  // initial = first selected (canonical order)
                .contains("KanbanBoard, FilterBar")
                .contains("<KanbanBoard")
                .contains("groupField=\"status\"")
                .contains("aria-label=\"Table view\"")              // toggle bar present (2 views)
                .contains("aria-label=\"Board view\"")
                .doesNotContain("aria-label=\"Card view\"")         // cards not selected
                .doesNotContain("<CardGrid")
                .doesNotContain("<CalendarView")
                .contains("<FilterBar filters={filterDescriptors}")
                .contains("options: ['OPEN', 'DONE']")
                .contains("exportCsv('tasks.csv')")
                .contains("selectable={true}")
                .contains("isRowSelected={isRowSelected}")
                // A failed drag-and-drop is toasted and the board reloaded, never a dropped promise.
                .contains("async function onKanbanMove(row: Task, value: string)")
                .contains("onMove={onKanbanMove}")
                .contains("await reload()")
                .contains(" reload,");

        // Event page: calendar-only — no toggle bar, no Table/CardGrid/KanbanBoard.
        String eventPage = entries.get("ops/frontend/src/pages/event/ui/EventPage.tsx");
        assertThat(eventPage)
                .contains("useState<'calendar'>('calendar')")
                .contains("<CalendarView")
                .contains("dateField=\"startsAt\"")
                .doesNotContain("<Table")
                .doesNotContain("aria-label=\"Calendar view\"");     // single view → no toggle

        // Plain page: legacy listView="kanban" down-grades to table-only, no filter bar.
        String plainPage = entries.get("ops/frontend/src/pages/plain/ui/PlainPage.tsx");
        assertThat(plainPage)
                .contains("const [viewMode] = useState<'table'>('table')")   // no toggle → no unused setter
                .doesNotContain("KanbanBoard")
                .doesNotContain("onKanbanMove")
                .doesNotContain("FilterBar");

        // Backend Task service: a Filters carrier + composed Specification + bulk delete.
        String taskService = contentEndingWith(entries, "/service/TaskService.java");
        assertThat(taskService)
                .contains("public record Filters(")
                .contains("TaskStatusType status")
                .contains("Integer priorityMin, Integer priorityMax")
                .contains("LocalDate dueDateFrom, LocalDate dueDateTo")
                .contains("findAll(String q, Filters filters, Pageable pageable)")
                .contains("public void deleteAll(java.util.List<Long> ids)");

        // Backend Task controller: filter params, CSV export, bulk-delete endpoint.
        String taskController = contentEndingWith(entries, "/controller/TaskController.java");
        assertThat(taskController)
                .contains("@RequestParam(required = false) TaskStatusType status")
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
    void fullstackPreviewEndpoint_returnsTreeAndFileContents() throws Exception {
        // Same request shape as the generate test, but against the JSON preview endpoint: the
        // response carries every generated file (path + text) plus the folder tree the UI renders.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", List.of(
                Map.of("name", "User", "fields", List.of(pkField(),
                        Map.of("name", "name", "type", "String", "required", true))),
                Map.of("name", "Order", "fields", List.of(pkField(),
                        Map.of("name", "total", "type", "BigDecimal", "required", true)))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                "/starter-fullstack.preview", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        com.fasterxml.jackson.databind.JsonNode json =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());

        // files: [{path, content}] — paths are relative to the project root (no artifactId prefix,
        // unlike the zip), forward-slashed, and text files carry their rendered content.
        com.fasterxml.jackson.databind.JsonNode files = json.get("files");
        assertThat(files).isNotNull();
        assertThat(files.isArray()).isTrue();
        Map<String, String> byPath = new TreeMap<>();
        for (com.fasterxml.jackson.databind.JsonNode f : files) {
            byPath.put(f.get("path").asText(), f.get("content").asText());
        }
        assertThat(byPath).containsKey("backend/src/main/java/com/menora/shop/controller/UserController.java");
        assertThat(byPath.get("backend/src/main/java/com/menora/shop/controller/UserController.java"))
                .contains("@RestController")
                .contains("@RequestMapping(\"/api/users\")");
        assertThat(byPath).containsKey("frontend/src/pages/order/ui/OrderPage.tsx");
        assertThat(byPath.get("frontend/src/pages/order/ui/OrderPage.tsx"))
                .isNotBlank()
                .contains("export function OrderPage");
        assertThat(byPath).containsKey("README.md");

        // tree: [{name, path, type, children}] — a folder node per top-level directory, built by
        // PreviewTreeBuilder from the sorted file paths.
        com.fasterxml.jackson.databind.JsonNode tree = json.get("tree");
        assertThat(tree).isNotNull();
        assertThat(tree.isArray()).isTrue();
        Map<String, com.fasterxml.jackson.databind.JsonNode> roots = new LinkedHashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode n : tree) {
            roots.put(n.get("name").asText(), n);
        }
        assertThat(roots).containsKeys("backend", "frontend");
        assertThat(roots.get("backend").get("type").asText()).isEqualTo("directory");
        assertThat(roots.get("backend").get("path").asText()).isEqualTo("backend");
        assertThat(roots.get("backend").get("children").size()).isGreaterThan(0);
        assertThat(roots.get("frontend").get("type").asText()).isEqualTo("directory");
        assertThat(roots.get("frontend").get("children").size()).isGreaterThan(0);
        // Every file in `files` has a leaf somewhere under the tree — spot-check the README root leaf.
        assertThat(roots).containsKey("README.md");
        assertThat(roots.get("README.md").get("type").asText()).isEqualTo("file");
    }

    /** POSTs a fullstack request and returns the unzipped (path → text) generated tree. */
    private Map<String, String> generateZip(Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return unzip(response.getBody());
    }

    /** POSTs a fullstack request built by the given mutator and returns the raw response. */
    private ResponseEntity<String> postFullstack(java.util.function.Consumer<Map<String, Object>> mutator) {
        Map<String, Object> body = new LinkedHashMap<>();
        mutator.accept(body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private static Map<String, Object> pkField() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "id");
        m.put("type", "Long");
        m.put("primaryKey", true);
        m.put("generated", true);
        return m;
    }

    /** Content of the single entry whose path ends with the given suffix. */
    private static String contentEndingWith(Map<String, String> entries, String suffix) {
        return entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith(suffix))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
    }

    /** Returns paths as keys (string contents) for text files. */
    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zin.transferTo(out);
                result.put(entry.getName(), out.toString());
            }
        }
        return result;
    }
}
