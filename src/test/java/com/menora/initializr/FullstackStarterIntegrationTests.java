package com.menora.initializr;

import org.junit.jupiter.api.Test;
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
        assertThat(readme).contains("proxy").contains("frontend/src/shared/api/client.ts");

        // Backend pom + Application
        assertThat(entries.keySet()).anyMatch(p -> p.equals("shop/backend/pom.xml"));
        String pom = entries.get("shop/backend/pom.xml");
        // No `dependencies` field in request → set defaults are applied
        // (data-jpa, web, h2, validation, actuator per spring-jpa-crud manifest).
        assertThat(pom).contains("spring-boot-starter-data-jpa");
        assertThat(pom).contains("spring-boot-starter-web");
        assertThat(pom).contains("spring-boot-starter-validation");
        assertThat(pom).contains("spring-boot-starter-actuator");

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
        assertThat(contentEndingWith(entries, "/config/CorsConfig.java"))
                .contains("implements WebMvcConfigurer")
                .contains("addMapping(\"/api/**\")")
                .contains("allowedOrigins(\"http://localhost:5173\")");
        // CorsConfig is generated once (non-perEntity), under the base package for component scan.
        assertThat(entries.keySet()).anyMatch(p -> p.equals("shop/backend/src/main/java/com/menora/shop/config/CorsConfig.java"));

        String userService = entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/UserService.java"))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
        assertThat(userService).contains("findAll(String q, Pageable pageable)");
        assertThat(userService).contains("Specification<User>");
        assertThat(userService).contains("root.get(\"name\")");

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
        assertThat(orderService).contains("findAll(String q, Pageable pageable)");
        // Order has no STRING fields, so no Specification block should render
        assertThat(orderService).doesNotContain("Specification<Order>");

        String orderEntity = entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith("/Order.java"))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
        assertThat(orderEntity).contains("@Entity");
        assertThat(orderEntity).contains("@Table(name = \"orders\")");
        assertThat(orderEntity).contains("java.math.BigDecimal");

        // Frontend essentials
        assertThat(entries).containsKey("shop/frontend/package.json");
        assertThat(entries.get("shop/frontend/package.json")).contains("\"shop-frontend\"");
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
        assertThat(entries).containsKey("shop/frontend/nginx.conf");
        assertThat(entries).containsKey("shop/frontend/.husky/pre-commit");
        assertThat(entries).containsKey("shop/frontend/src/widgets/README.md");
        // Paired-backend wiring: the dev .env points the FE at the proxied /api, and the Vite
        // dev-server proxy is emitted by the substrate.
        assertThat(entries).containsKey("shop/frontend/.env.development");
        assertThat(entries.get("shop/frontend/vite.config.ts")).contains("/api");
        // The standalone landing page is replaced by the per-entity pages — its dir is removed.
        assertThat(entries.keySet()).noneMatch(p -> p.startsWith("shop/frontend/src/pages/home/"));
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

        // Controller validates the body and maps the failure modes to 400 / 409.
        String accountController = contentEndingWith(entries, "/controller/AccountController.java");
        assertThat(accountController)
                .contains("import jakarta.validation.Valid;")
                .contains("@Valid")
                .contains("MethodArgumentNotValidException")
                .contains("DataIntegrityViolationException")
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
                .contains("#0a2b6b")   // Menora navy (palette primary)
                .contains("#2d3344")   // charcoal sidebar (secondary)
                .contains("#ffc700");  // gold accent

        // Components reference the brand tokens, not the old emerald defaults.
        String app = entries.get("shop/frontend/src/app/App.tsx");
        assertThat(app).contains("bg-ink").contains("text-gold").contains("border-gold");
        assertThat(app).doesNotContain("emerald");
        assertThat(entries.get("shop/frontend/src/pages/user/ui/UserPage.tsx")).contains("bg-brand");
        assertThat(entries.get("shop/frontend/src/shared/ui/FormDrawer.tsx")).contains("bg-brand");
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
                .doesNotContain("#0a2b6b");   // not the Menora default
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
        // Default deps (no ldap-auth) → the header block is not emitted.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "plain");
        body.put("packageName", "com.menora.plain");
        body.put("bootVersion", "3.2.1");
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
    void importDdlEndpoint_returnsEntitiesInWireFormat() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dialect", "H2");
        body.put("sql", """
                CREATE TABLE products (
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
