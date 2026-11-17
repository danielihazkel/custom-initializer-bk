package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code tests} option: what a fullstack project's own tests are. The backend gets an API test
 * per entity, run on an in-memory H2 under the {@code test} profile whatever the application's
 * database; the frontend gets Vitest and a test of each entity's validator and list page. Built
 * and run for real by {@code GeneratedBackendBuildSmokeTests} / {@code GeneratedFrontendBuildSmokeTests}.
 */
@IntegrationTest
class FullstackGeneratedTestsTests {

    private static final String BE = "support/backend/";
    private static final String BE_TEST = BE + "src/test/java/com/menora/support/";
    private static final String FE = "support/frontend/";

    @Autowired
    private TestRestTemplate rest;

    private Map<String, String> generate(Map<String, Object> body) throws Exception {
        ResponseEntity<byte[]> response = FullstackExampleRequests.post(rest, body);
        assertThat(response.getStatusCode())
                .as(response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(HttpStatus.OK);
        Map<String, String> files = new LinkedHashMap<>();
        FullstackExampleRequests.unzip(response.getBody()).forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
        return files;
    }

    private static Map<String, Object> orders(List<String> scaffold) {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("orders", "react-tailwind-crud");
        body.put("pages", List.of());
        body.put("opts", Map.of("scaffold", scaffold));
        return body;
    }

    @Test
    void theOptionGivesEveryEntityAnApiTestOnTheTestProfile() throws Exception {
        Map<String, String> files = generate(orders(List.of("tests", "softDelete")));
        assertThat(files).containsKeys(BE_TEST + "ApiTestSupport.java", BE + "src/test/resources/application-test.yaml",
                BE_TEST + "controller/CustomerApiTest.java", BE_TEST + "controller/OrderApiTest.java");
        assertThat(files.keySet()).noneMatch(p -> p.endsWith("ControllerTest.java"));

        String support = files.get(BE_TEST + "ApiTestSupport.java");
        assertThat(support)
                .contains("@ActiveProfiles(\"test\")")
                // A row's parents are made first; values come from the row number.
                .contains("case \"Order\" -> {")
                .contains("row.set(\"customerId\", create(\"Customer\").get(\"id\"));")
                .contains("case \"Customer\" -> \"/api/customers\";")
                .doesNotContain("{{");
        String customer = files.get(BE_TEST + "controller/CustomerApiTest.java");
        assertThat(customer)
                .contains("class CustomerApiTest extends ApiTestSupport")
                .contains("void aRowIsCreatedReadChangedAndDeleted()")
                .contains("send(post(path + \"/restore\"), null, 200);")
                .contains("void theSearchFindsARowByItsText()")
                .doesNotContain("@Disabled(")
                .doesNotContain("{{");

        // The application's own database is not asked for: H2 in memory, demo rows off, the
        // callers the access checks know (the sets' default ldap-auth-rest).
        assertThat(files.get(BE + "src/test/resources/application-test.yaml"))
                .contains("url: jdbc:h2:mem:api-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false")
                .contains("ddl-auto: create-drop")
                .contains("    enabled: false")
                .contains("api-test-admin: ADMIN").contains("api-test-user: USER");
        assertThat(customer).contains("void nobodySignedInIsTurnedAway()").contains("void onlyAnAdminWrites()");
        // The framework's context-load test starts on the same profile.
        assertThat(files.get(BE_TEST + "SupportApplicationTests.java"))
                .contains("import org.springframework.test.context.ActiveProfiles;")
                .contains("@SpringBootTest\n@ActiveProfiles(\"test\")");

        // The frontend: Vitest, and a test of each validator and list page.
        assertThat(files.get(FE + "package.json"))
                .contains("\"test\" : \"vitest run\"").contains("\"vitest\" : \"^2.0.5\"").contains("\"@testing-library/react\"");
        assertThat(files).containsKeys(FE + "vitest.config.ts",
                FE + "src/features/customer-form/model/validate.test.ts",
                FE + "src/pages/customer/ui/CustomerPage.test.tsx");
        assertThat(files.get(FE + "src/features/customer-form/model/validate.test.ts"))
                .contains("import { validateCustomer } from './validate'")
                .contains("it('passes a complete record'")
                .doesNotContain("{{");
        assertThat(files.get(FE + "src/pages/customer/ui/CustomerPage.test.tsx"))
                .contains("render(<CustomerPage />)")
                .contains("if (path === '/api/customers') return");
        // The README says how to run them.
        assertThat(files.get("support/README.md")).contains("## Tests").contains("cd backend && mvn test").contains("cd frontend && npm test");
    }

    @Test
    void withoutTheOptionNothingIsAdded() throws Exception {
        Map<String, String> files = generate(orders(List.of("softDelete")));
        assertThat(files.keySet()).noneMatch(p -> p.contains("/src/test/resources/") || p.endsWith("ApiTest.java")
                || p.endsWith(".test.ts") || p.endsWith(".test.tsx") || p.endsWith("vitest.config.ts"));
        assertThat(files.get(FE + "package.json")).doesNotContain("vitest");
        assertThat(files.get(BE_TEST + "SupportApplicationTests.java")).doesNotContain("ActiveProfiles");
        assertThat(files.get("support/README.md")).doesNotContain("## Tests");
    }

    @Test
    void aBackendOnAnotherDatabaseTestsOnH2() throws Exception {
        Map<String, Object> body = orders(List.of("tests"));
        body.put("dependencies", List.of("web", "data-jpa", "postgresql", "validation", "flyway"));
        Map<String, String> files = generate(body);
        String pom = files.get(BE + "pom.xml");
        int h2 = pom.indexOf("<artifactId>h2</artifactId>");
        assertThat(h2).as("h2 on the test classpath").isPositive();
        assertThat(pom.substring(h2, pom.indexOf("</dependency>", h2))).contains("<scope>test</scope>");
        // Its datasource config binds postgresql.*: those point at H2 too, and its PostgreSQL
        // baseline is not run (Hibernate creates the schema instead).
        assertThat(files.get(BE + "src/test/resources/application-test.yaml"))
                .contains("postgresql:\n  datasource:\n    jdbc-url: jdbc:h2:mem:api-test")
                .contains("    dialect: org.hibernate.dialect.H2Dialect\n    hbm2ddl-auto: create-drop")
                .contains("  flyway:\n    enabled: false")
                // No ldap-auth dependency: no roles to give.
                .doesNotContain("dev-roles");
        assertThat(files.get(BE_TEST + "controller/CustomerApiTest.java")).doesNotContain("nobodySignedInIsTurnedAway");

        // On H2 with Flyway, the baseline is H2's: the tests run it and Hibernate validates.
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation", "flyway"));
        assertThat(generate(body).get(BE + "src/test/resources/application-test.yaml"))
                .contains("ddl-auto: validate").contains("  flyway:\n    enabled: true")
                .doesNotContain("postgresql:");
    }

    @Test
    void aRowTheTestsCannotMakeIsSaidSo() throws Exception {
        Map<String, Object> id = Map.of("name", "id", "type", "LONG", "primaryKey", true, "generated", true);
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("name", "Account");
        account.put("fields", List.of(id, Map.of("name", "iban", "type", "STRING", "required", true, "pattern", "IL[0-9]{21}")));
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("name", "Payment");
        payment.put("fields", List.of(id, Map.of("name", "note", "type", "STRING")));
        payment.put("relations", List.of(Map.of("fieldName", "account", "targetEntity", "Account", "type", "MANY_TO_ONE", "required", true)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactId", "support");
        body.put("packageName", "com.menora.support");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("web", "data-jpa", "h2", "validation"));
        body.put("opts", Map.of("scaffold", List.of("tests")));
        body.put("entities", List.of(account, payment));
        Map<String, String> files = generate(body);

        assertThat(files.get(BE_TEST + "controller/AccountApiTest.java"))
                .contains("@Disabled(\"'iban' must match a pattern the tests cannot guess a value for — put a matching value in ApiTestSupport.body\")")
                // Reading needs no row of its own.
                .contains("void theListAnswersAPage()");
        assertThat(files.get(BE_TEST + "controller/PaymentApiTest.java"))
                .contains("@Disabled(\"the required link 'account' needs a Account, and 'iban' must match");
        assertThat(files.get(BE_TEST + "ApiTestSupport.java"))
                .contains("throw new IllegalStateException(\"No Account can be made here: 'iban' must match");
        // The validator test has no complete row to pass, but checks every rule it can.
        assertThat(files.get(FE + "src/features/account-form/model/validate.test.ts"))
                .doesNotContain("passes a complete record")
                .contains("it('asks for iban'");
    }

    @Test
    void aWorkflowIsMovedThroughItsApi() throws Exception {
        Map<String, Object> body = FullstackPagesIntegrationTests.exampleBody("approvals", "react-tailwind-crud");
        body.put("opts", Map.of("scaffold", List.of("tests", "audit")));
        Map<String, String> files = generate(body);
        assertThat(files.get(BE_TEST + "controller/ExpenseClaimApiTest.java"))
                .contains("void aRowMovesOnlyByItsWorkflow()")
                .contains("isEqualTo(\"DRAFT\")")
                .contains("post(path + \"/transitions/submit\")")
                .contains("post(path + \"/claim\")");
        // The workflow's status and assignee are never sent: the server sets them.
        assertThat(files.get(BE_TEST + "ApiTestSupport.java"))
                .doesNotContain("row.put(\"status\"").doesNotContain("row.put(\"assignee\"");
    }
}
