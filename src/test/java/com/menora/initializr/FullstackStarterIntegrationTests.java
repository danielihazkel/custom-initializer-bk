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

        // Owning entity: JPA association.
        String orderEntity = contentEndingWith(entries, "/entity/Order.java");
        assertThat(orderEntity)
                .contains("@ManyToOne(fetch = FetchType.LAZY, optional = false)")
                .contains("@JoinColumn(name = \"customer_id\", nullable = false)")
                .contains("private Customer customer;")
                .contains("public Customer getCustomer()");

        // DTO: FK exposed as customerId, imports the target entity, @NotNull because required.
        String orderDto = contentEndingWith(entries, "/dto/OrderDto.java");
        assertThat(orderDto)
                .contains("import com.menora.shop.entity.Customer;")
                .contains("@NotNull Long customerId")
                .contains("entity.getCustomer() == null ? null : entity.getCustomer().getId()")
                .contains("Customer customer = new Customer();")
                .contains("customer.setId(this.customerId);")
                .contains("entity.setCustomer(customer);")
                // Comma-correctness: scalar fields keep their commas; the relation FK is the last
                // record component, so it must NOT be followed by a comma (which would dangle before
                // the close paren). CRLF-agnostic so it holds regardless of resource line endings.
                .contains("Long id,")
                .doesNotContain("customerId,")
                .doesNotContain(",,");

        // Service copies the association on update.
        assertThat(contentEndingWith(entries, "/service/OrderService.java"))
                .contains("existing.setCustomer(updated.getCustomer());");

        // Frontend: type + form + page carry customerId.
        assertThat(entries.get("shop/frontend/src/entities/order/model/types.ts"))
                .contains("customerId: number | null");
        // The FK now renders as a <select> populated from the target's list endpoint via useOptions.
        assertThat(entries.get("shop/frontend/src/features/order-form/ui/OrderForm.tsx"))
                .contains("import { useOptions } from '@shared/api'")
                .contains("useOptions<Record<string, unknown>>('/api/customers')")
                .contains("label=\"Customer\" required")
                .contains("<select")
                .contains("set('customerId'");
        assertThat(entries.get("shop/frontend/src/pages/order/ui/OrderPage.tsx"))
                .contains("label: 'Customer ID'");

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
        body.put("entities", List.of(Map.of("name", "User", "fields", List.of(pkField()))));

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
                .contains("get(\"/api/users\")");
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
        assertThat(entries.get("shop/frontend/src/pages/user/ui/UserPage.tsx")).contains("bg-brand");
        assertThat(entries.get("shop/frontend/src/shared/ui/FormDrawer.tsx")).contains("bg-brand");

        // Brand logo asset shipped and referenced in the sidebar + favicon.
        assertThat(entries).containsKey("shop/frontend/public/logo.png");
        assertThat(app).contains("/logo.png").doesNotContain("Database");
        assertThat(entries.get("shop/frontend/index.html")).contains("/logo.png");
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
                .contains("private List<Order> orders = new ArrayList<>();");
        assertThat(contentEndingWith(entries, "/dto/CustomerDto.java"))
                .contains("int ordersCount")
                .contains("entity.getOrders() == null ? 0 : entity.getOrders().size()");
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
                .contains("driver-class-name: com.ibm.db2.jcc.DB2Driver");
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
        // listView=cards seeds the page's initial view mode (it always ships a Table/Cards toggle);
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

        // Cards entity starts in card mode and wires the detail drawer.
        String productPage = entries.get("shop/frontend/src/pages/product/ui/ProductPage.tsx");
        assertThat(productPage)
                .contains("useState<'table' | 'cards'>('cards')")
                .contains("import { Table, CardGrid")
                .contains("onView={setDetailRow}")
                .contains("<ProductDetail value={detailRow} />");
        assertThat(entries).containsKey("shop/frontend/src/features/product-form/ui/ProductDetail.tsx");
        // An entity with no listView defaults to table mode.
        assertThat(entries.get("shop/frontend/src/pages/plain/ui/PlainPage.tsx"))
                .contains("useState<'table' | 'cards'>('table')");

        // The enum field drives a dashboard breakdown chart; Plain (no enum/boolean) gets none.
        String dashboard = entries.get("shop/frontend/src/pages/dashboard/ui/DashboardPage.tsx");
        assertThat(dashboard)
                .contains("function BarChart")
                .contains("Products by Status")
                .contains("field: 'status'");
        assertThat(dashboard).doesNotContain("Plains by");
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
