package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden per-file hashes of the plain backend generators: {@code /starter.zip}, the SQL, OpenAPI
 * and SOAP wizards ({@code /starter-wizard.zip}) in each of their modes, and
 * {@code /starter-multimodule.zip}.
 *
 * <p>The fullstack output has been pinned this way since Round 9 ({@link
 * FullstackGoldenOutputTests}); these generators were only asserted by {@code contains(...)}
 * checks, so a change to what they emit — on purpose or not — showed nowhere. The wizard inputs
 * are the fixtures under {@code src/test/resources/wizard-fixtures/}, each written to reach every
 * shape its generator renders differently.
 *
 * <p>After an intended change: {@code mvn test -Dtest=WizardGoldenOutputTests -Dgolden.update=true},
 * then review the {@code src/test/resources/golden/wizard/*.sha} diff.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class WizardGoldenOutputTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void everyPlainGeneratorProducesItsPinnedBytes() throws Exception {
        GoldenManifests golden = manifests();

        // /starter.zip — the framework binds this one itself.
        golden.pin("starter__default", get("/starter.zip?" + BASE_QUERY));
        golden.pin("starter__common-dependencies", get("/starter.zip?" + BASE_QUERY
                + "&dependencies=web,validation,data-jpa,postgresql,kafka,security,actuator,openapi,mapstruct"
                + "&opts-kafka=consumer-example,producer-example&opts-postgresql=pg-primary&opts-web=rest-example"));
        golden.pin("starter__java17-secondary-datasource", get("/starter.zip?" + BASE_QUERY.replace("javaVersion=21", "javaVersion=17")
                + "&dependencies=web,data-jpa,postgresql,oracle&opts-postgresql=pg-primary&opts-oracle=oracle-secondary"
                + "&department=lts"));

        // The SQL wizard: every API mode over the library script, and a T-SQL script.
        for (String mode : List.of("NONE", "ENTITY_DIRECT", "INLINE_DTO", "MAPSTRUCT_DTO")) {
            golden.pin("sql__postgresql__" + mode.toLowerCase().replace('_', '-'), wizard(sqlBody("postgresql",
                    "library-postgresql.sql", mode, "entity")));
        }
        Map<String, Object> ownPackage = sqlBody("postgresql", "library-postgresql.sql", "INLINE_DTO", "model");
        ownPackage.put("dependencies", List.of("web", "data-jpa", "postgresql", "validation"));
        golden.pin("sql__postgresql__inline-dto__own-package", wizard(ownPackage));
        // What the build adds: the script as the Flyway baseline, validated bodies, a test per controller.
        Map<String, Object> baseline = sqlBody("postgresql", "library-postgresql.sql", "MAPSTRUCT_DTO", "entity");
        baseline.put("dependencies", List.of("web", "data-jpa", "postgresql", "validation", "flyway"));
        @SuppressWarnings("unchecked")
        Map<String, Object> baselineOptions = new LinkedHashMap<>(((Map<String, Map<String, Object>>) baseline.get("sqlOptions")).get("postgresql"));
        baselineOptions.put("generateTests", true);
        baseline.put("sqlOptions", Map.of("postgresql", baselineOptions));
        golden.pin("sql__postgresql__mapstruct-dto__flyway-validation-tests", wizard(baseline));
        golden.pin("sql__mssql__mapstruct-dto", wizard(sqlBody("mssql", "legacy-mssql.sql", "MAPSTRUCT_DTO", "entity")));

        // The OpenAPI wizard: server stubs, a client, both.
        golden.pin("openapi__controllers", wizard(openApiBody("CONTROLLERS", List.of("web"), null)));
        golden.pin("openapi__controllers__validation", wizard(openApiBody("CONTROLLERS", List.of("web", "validation"), null)));
        golden.pin("openapi__client", wizard(openApiBody("CLIENT", List.of("web"), Map.of(
                "clientSubPackage", "remote", "dtoSubPackage", "remote.model", "baseUrlProperty", "petstore.base-url"))));
        golden.pin("openapi__both", wizard(openApiBody("BOTH", List.of("web", "validation"), Map.of("apiSubPackage", "rest"))));
        // A RestClient client with the document kept in the project; no web starter named (it is added).
        golden.pin("openapi__client__rest-client__copy-spec", wizard(openApiBody("CLIENT", List.of("validation"), Map.of(
                "clientKind", "restClient", "copySpec", true))));

        // The events wizard: an AsyncAPI 3 document as the application it describes; the same events
        // as a 2.x document, for the other party, with chosen channels, tests and validation.
        golden.pin("events__asyncapi3", wizard(eventsBody("orders-asyncapi-v3.yaml", List.of("kafka"), null)));
        Map<String, Object> otherParty = new LinkedHashMap<>();
        otherParty.put("subPackage", "messaging");
        otherParty.put("payloadSubPackage", "messaging.dto");
        otherParty.put("groupId", "billing-service");
        otherParty.put("generateTests", true);
        otherParty.put("channels", List.of(
                Map.of("name", "orders.created", "produce", false, "consume", true),
                Map.of("name", "orders.events", "produce", true, "consume", false),
                Map.of("name", "inventory.stock-levels", "produce", true, "consume", true)));
        golden.pin("events__asyncapi2__chosen-channels__tests", wizard(eventsBody("orders-asyncapi-v2.yaml",
                List.of("kafka", "validation"), otherParty)));

        // The SOAP wizard: endpoints, a client, both.
        golden.pin("soap__endpoints", wizard(soapBody("ENDPOINTS", null)));
        golden.pin("soap__client", wizard(soapBody("CLIENT", Map.of("clientSubPackage", "remote", "baseUrlProperty", "countries.url"))));
        golden.pin("soap__both", wizard(soapBody("BOTH", Map.of("endpointSubPackage", "ws", "payloadSubPackage", "ws.model",
                "contextPath", "/services"))));

        // All three wizards in one request.
        Map<String, Object> all = sqlBody("postgresql", "library-postgresql.sql", "INLINE_DTO", "entity");
        all.put("dependencies", List.of("web", "data-jpa", "postgresql", "web-services", "validation"));
        all.put("specByDep", Map.of("web", fixture("petstore.yaml")));
        all.put("openApiOptions", Map.of("web", Map.of("mode", "BOTH")));
        all.put("wsdlByDep", Map.of("web-services", fixture("countries.wsdl")));
        all.put("soapOptions", Map.of("web-services", Map.of("mode", "BOTH")));
        golden.pin("wizards__sql-openapi-soap", wizard(all));

        // Multi-module: the three seeded module templates, and two of them with an extra dependency.
        golden.pin("multimodule__api-core-persistence", get("/starter-multimodule.zip?modules=api,core,persistence&" + BASE_QUERY));
        golden.pin("multimodule__api-core__kafka", get("/starter-multimodule.zip?modules=api,core&dependencies=kafka&" + BASE_QUERY
                + "&department=lts"));

        golden.finish();
    }

    /** The wizard endpoint with no wizard payload is the plain starter: same files, same bytes. */
    @Test
    void wizardEndpointWithoutPayloadsEqualsTheStarter() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("dependencies", List.of("web", "kafka", "actuator"));
        body.put("opts", Map.of("kafka", List.of("consumer-example")));
        Map<String, String> viaWizard = manifests().hashes(wizard(body));
        Map<String, String> viaStarter = manifests().hashes(get("/starter.zip?" + BASE_QUERY
                + "&dependencies=web,kafka,actuator&opts-kafka=consumer-example"));
        assertThat(stripRoot(viaWizard)).isEqualTo(stripRoot(viaStarter));
    }

    /** Generation is deterministic, or a golden hash would be meaningless. */
    @Test
    void generationIsDeterministic() throws Exception {
        Map<String, Object> body = sqlBody("postgresql", "library-postgresql.sql", "MAPSTRUCT_DTO", "entity");
        assertThat(manifests().hashes(wizard(body))).isEqualTo(manifests().hashes(wizard(body)));
    }

    // ── Requests ─────────────────────────────────────────────────────────────

    private static final String BASE_QUERY = "groupId=com.menora&artifactId=golden&name=golden&description=Golden"
            + "&packageName=com.menora.golden&type=maven-project&language=java&bootVersion=3.2.1&packaging=jar&javaVersion=21";

    private static Map<String, Object> baseBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "golden");
        body.put("name", "golden");
        body.put("description", "Golden");
        body.put("packageName", "com.menora.golden");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        return body;
    }

    /** A script under one database dependency; one table's repository is switched off. */
    private static Map<String, Object> sqlBody(String dep, String script, String apiMode, String subPackage) throws IOException {
        Map<String, Object> body = baseBody();
        body.put("dependencies", List.of("web", "data-jpa", dep));
        body.put("sqlByDep", Map.of(dep, fixture(script)));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("subPackage", subPackage);
        options.put("apiMode", apiMode);
        options.put("tables", List.of(
                Map.of("name", "audit_log", "generateRepository", false),
                Map.of("name", "publishers", "generateRepository", true)));
        body.put("sqlOptions", Map.of(dep, options));
        return body;
    }

    private static Map<String, Object> eventsBody(String document, List<String> dependencies, Map<String, Object> options) throws IOException {
        Map<String, Object> body = baseBody();
        body.put("dependencies", dependencies);
        body.put("asyncApiByDep", Map.of("kafka", fixture(document)));
        if (options != null) body.put("eventOptions", Map.of("kafka", options));
        return body;
    }

    private static Map<String, Object> openApiBody(String mode, List<String> dependencies, Map<String, Object> extra) throws IOException {
        Map<String, Object> body = baseBody();
        body.put("dependencies", dependencies);
        body.put("specByDep", Map.of("web", fixture("petstore.yaml")));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("mode", mode);
        if (extra != null) options.putAll(extra);
        body.put("openApiOptions", Map.of("web", options));
        return body;
    }

    private static Map<String, Object> soapBody(String mode, Map<String, Object> extra) throws IOException {
        Map<String, Object> body = baseBody();
        body.put("dependencies", List.of("web-services"));
        body.put("wsdlByDep", Map.of("web-services", fixture("countries.wsdl")));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("mode", mode);
        if (extra != null) options.putAll(extra);
        body.put("soapOptions", Map.of("web-services", options));
        return body;
    }

    /** A wizard input, with LF line endings whatever the checkout made of the file. */
    static String fixture(String name) throws IOException {
        try (var in = new ClassPathResource("wizard-fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    // ── Calls ────────────────────────────────────────────────────────────────

    private Map<String, byte[]> get(String url) throws IOException {
        return unzipped(restTemplate.getForEntity(url, byte[].class), url);
    }

    private Map<String, byte[]> wizard(Map<String, Object> body) throws IOException {
        return unzipped(restTemplate.postForEntity("/starter-wizard.zip", body, byte[].class), "POST /starter-wizard.zip");
    }

    private static Map<String, byte[]> unzipped(ResponseEntity<byte[]> response, String what) throws IOException {
        assertThat(response.getStatusCode())
                .as(what + ": " + (response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8)))
                .isEqualTo(HttpStatus.OK);
        return FullstackExampleRequests.unzip(response.getBody());
    }

    /** The paths without their first segment, when every one has the same: the zips name their root differently. */
    private static Map<String, String> stripRoot(Map<String, String> hashes) {
        Map<String, String> out = new java.util.TreeMap<>();
        hashes.forEach((path, sha) -> out.put(path.contains("/") && path.startsWith("golden/") ? path.substring("golden/".length()) : path, sha));
        return out;
    }

    private static GoldenManifests manifests() {
        // The model file names the catalog rows the project was generated from: masked, or any
        // edit of a catalog file would move every case here.
        return new GoldenManifests("wizard", Path.of("target", "golden-actual", "wizard"), (path, text) ->
                path.endsWith("menora-model.json")
                        ? text.replaceAll("\"catalogHash\" : \"[0-9a-f]{64}\"", "\"catalogHash\" : \"<catalog>\"")
                        : text);
    }
}
