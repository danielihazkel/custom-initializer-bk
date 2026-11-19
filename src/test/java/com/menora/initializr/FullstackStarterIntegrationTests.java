package com.menora.initializr;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.menora.initializr.db.entity.VersionKind;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /starter-fullstack.zip}: the project as a whole — both halves, packages, dependencies,
 * template sets, versions, the preview and the model file. What a model's parts generate is in the
 * classes beside it: {@code FullstackFieldsGenerationTests}, {@code FullstackRelationsGenerationTests},
 * {@code FullstackScaffoldOptionsTests}, {@code FullstackThemeGenerationTests},
 * {@code FullstackAccessGenerationTests}, {@code FullstackDatasourceConfigTests} and
 * {@code FullstackSchemaImportTests}.
 */
@IntegrationTest
@Tag("slow") // left out of mvn test -Pquick
class FullstackStarterIntegrationTests extends FullstackEndpointTestSupport {

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
        // The backend carries the local Vault connection; its .env stays out of git
        // (backend/.gitignore lists it, and the root names it without touching frontend/.env*).
        assertThat(entries.get("shop/backend/.env")).contains("VAULT_TOKEN=").doesNotContain("{{");
        assertThat(entries.get("shop/backend/.gitignore")).contains(".env");
        assertThat(entries.get("shop/.gitignore")).contains("backend/.env").doesNotContain("\n.env");
        assertThat(entries).containsKey("shop/backend/src/main/resources/application-vault-local.yaml");
        // README documents the dev proxy + prod base-URL story (not "talks directly to :8080")
        String readme = entries.get("shop/README.md");
        assertThat(readme).contains("proxy").contains("frontend/src/shared/api/client.ts")
                .contains("API_UPSTREAM").contains("app.cors.allowed-origins");
        // The __common__ catalog deletes the Maven wrapper, so the run command must use plain mvn.
        assertThat(readme).contains("mvn spring-boot:run").doesNotContain("mvnw");

        // Backend pom + Application
        assertThat(entries.keySet()).anyMatch(p -> p.equals("shop/backend/pom.xml"));
        String pom = entries.get("shop/backend/pom.xml");
        // No `dependencies` field in request → set defaults are applied
        // (data-jpa, web, h2, validation, actuator, ldap-auth-rest per spring-jpa-crud manifest).
        assertThat(pom).contains("spring-boot-starter-data-jpa");
        assertThat(pom).contains("spring-boot-starter-web");
        assertThat(pom).contains("spring-boot-starter-validation");
        assertThat(pom).contains("spring-boot-starter-actuator");
        // ldap-auth-rest is the fullstack default: groups come from the LDAP REST service, so the
        // AOP starter is wired in but the direct-bind lts.ldap.util library is not.
        assertThat(pom).contains("spring-boot-starter-aop");
        assertThat(pom).doesNotContain("lts.ldap.util");
        assertThat(entries.get("shop/backend/src/main/resources/application.yaml")).contains("ldap-rest:");
        assertThat(contentEndingWith(entries, "/security/LdapService.java")).contains("RestClient");
        // Page roles and the generated /api/me/roles work with it like with the direct-bind variant.
        assertThat(contentEndingWith(entries, "/web/MeController.java")).contains("@GetMapping(\"/roles\")");

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

        String userPage = ListPages.of(entries, "shop/frontend/src/pages/user/ui/UserPage.tsx");
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
                .contains("rowKey={list.rowKey}");
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
                // No string field → no search and no helper. (Bare "Locale.ROOT" would also match
                // the stats endpoint, which lowercases its own agg/bucket parameters.)
                .doesNotContain("q.toLowerCase(Locale.ROOT)")
                .doesNotContain("escapeLike");

        // The tests option: the API test drives the real endpoints (FullstackGeneratedTestsTests).
        assertThat(contentEndingWith(entries, "/ProductApiTest.java"))
                .contains("class ProductApiTest extends ApiTestSupport");

        // The API client reads the problem-detail shape.
        assertThat(entries.get("shop/frontend/src/shared/api/client.ts"))
                .contains("problem.errors").contains("problem.detail || problem.error || problem.message")
                // Errors reach the user in their language, never as "HTTP 500 on GET /api/…".
                .contains("import { t } from '../i18n'")
                .contains("throw new ApiError(0, t('errorOffline'))")
                .contains("if (status === 403) return t('errorForbidden')")
                .doesNotContain("HTTP ${res.status} on");
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
        assertThat(ListPages.of(byPath, "frontend/src/pages/order/ui/OrderPage.tsx"))
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

    @Test
    void fullstackEndpoint_shipsTheModelItWasGeneratedFrom() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> task = Map.of("name", "Task", "fields", List.of(pkField(), Map.of("name", "title", "type", "String")));

        // The editor's snapshot rides along and lands verbatim at the zip root.
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("format", "menora-fullstack-model/1");
        snapshot.put("meta", Map.of("artifactId", "ops", "groupId", "com.menora"));
        snapshot.put("entities", List.of(Map.of("name", "Task", "fields", List.of())));
        snapshot.put("pages", List.of(Map.of("id", "tasks", "type", "entity-list", "entity", "Task", "idLocked", true)));
        snapshot.put("dismissedWarnings", List.of("unreachable:tasks"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "ops");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("data-jpa", "web"));
        body.put("entities", List.of(task));
        body.put("editorModel", snapshot);
        Map<String, String> entries = generateZip(body);
        com.fasterxml.jackson.databind.node.ObjectNode shipped =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(entries.get("ops/menora-model.json"));
        // …followed by what the project was rendered with, which is never part of the model.
        assertThat(shipped.remove("generator").path("templatesHash").asText()).matches("[0-9a-f]{64}");
        assertThat(shipped).isEqualTo(mapper.valueToTree(snapshot));
        assertThat(entries.get("ops/README.md")).contains("## Reopen in the generator").contains("menora-model.json")
                .contains("**Download update**").contains("git apply --3way");

        // Without one (curl, an IDE), the file holds the request itself — what was sent, not the
        // dependencies the template set added.
        body.remove("editorModel");
        com.fasterxml.jackson.databind.JsonNode model = mapper.readTree(generateZip(body).get("ops/menora-model.json"));
        assertThat(model.get("format").asText()).isEqualTo("menora-fullstack-request/1");
        assertThat(model.get("request").get("artifactId").asText()).isEqualTo("ops");
        assertThat(model.get("request").get("dependencies")).isEqualTo(mapper.valueToTree(List.of("data-jpa", "web")));
        assertThat(model.get("request").get("entities").get(0).get("name").asText()).isEqualTo("Task");
        assertThat(model.get("request").has("editorModel")).isFalse();

        // A model that is not an editor snapshot falls back to the request too.
        body.put("editorModel", "not a model");
        assertThat(mapper.readTree(generateZip(body).get("ops/menora-model.json")).get("format").asText())
                .isEqualTo("menora-fullstack-request/1");
    }

    @Test
    void fullstackEndpoint_acceptsTheClientMetadataSpellingOfTheBootVersion() throws Exception {
        // /metadata/client hands browsers "3.2.1.RELEASE" for catalog id "3.2.1"; both must work and
        // the generated pom must carry the catalog id.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "verspell");
        body.put("packageName", "com.menora.verspell");
        body.put("bootVersion", "3.2.1.RELEASE");
        body.put("dependencies", List.of("data-jpa", "web", "h2"));
        body.put("entities", List.of(Map.of("name", "Item", "fields", List.of(pkField(),
                Map.of("name", "name", "type", "String")))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/starter-fullstack.zip", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String pom = unzip(response.getBody()).get("verspell/backend/pom.xml");
        assertThat(pom).contains("<version>3.2.1</version>").doesNotContain("3.2.1.RELEASE");
    }
}
