package com.menora.initializr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.DepartmentEntity;
import com.menora.initializr.db.repository.DepartmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The selectable {@code department} template variable, end to end over HTTP: every generator
 * endpoint (backend {@code /starter.zip} through the filter, frontend, fullstack, wizard,
 * multi-module) carries it into its templates, an unknown id falls back to the default
 * ({@code lts}), and the admin CRUD / public metadata endpoints manage and expose the list.
 *
 * <p>A {@code fin} department is inserted per test and removed afterwards, so the seeded
 * single-{@code lts} state other tests (e.g. {@code DataSeederTest}) rely on is restored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class DepartmentIntegrationTests {

    private static final String BACKEND_PARAMS = "groupId=com.menora&artifactId=demo"
            + "&packageName=com.menora.demo&bootVersion=3.2.1&javaVersion=21"
            + "&type=maven-project&language=java&packaging=jar";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DepartmentRepository departmentRepo;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void addFinDepartment() {
        DepartmentEntity fin = new DepartmentEntity();
        fin.setDepartmentId("fin");
        fin.setName("Finance");
        fin.setSortOrder(1);
        departmentRepo.save(fin);
    }

    @AfterEach
    void removeNonSeededDepartments() {
        departmentRepo.findAll().stream()
                .filter(d -> !"lts".equals(d.getDepartmentId()))
                .forEach(departmentRepo::delete);
        departmentRepo.findByDepartmentId("lts").ifPresent(d -> {
            d.setDefault(true);
            departmentRepo.save(d);
        });
    }

    // ── Backend (/starter.zip — the department reaches the context through the filter) ──

    @Test
    void backendDefaultsToLts() throws Exception {
        Map<String, String> files = unzip(get("/starter.zip?" + BACKEND_PARAMS + "&dependencies=web,logging"));

        assertThat(file(files, "k8s/values.yaml"))
                .contains("namespace: \"lts-lan\"")
                .contains("department: \"lts\"")
                .contains("imageRepo: menora-lts")
                .contains("name: lts-srt-tls")
                .contains("VaultNamespace: lts/");
        assertThat(file(files, "src/main/resources/log4j2-spring.xml")).contains("QA_APP_LTS_MNG_SYS");
    }

    @Test
    void backendRendersTheSelectedDepartmentEverywhere() throws Exception {
        Map<String, String> files = unzip(get("/starter.zip?" + BACKEND_PARAMS
                + "&dependencies=web,logging,ldap-auth&department=fin"));

        assertThat(file(files, "k8s/values.yaml"))
                .contains("namespace: \"fin-lan\"")
                .contains("department: \"fin\"")
                .contains("imageRepo: menora-fin")
                .contains("name: fin-srt-tls")
                .contains("VaultNamespace: fin/")
                .doesNotContain("lts");
        assertThat(file(files, "src/main/resources/log4j2-spring.xml")).contains("QA_APP_FIN_MNG_SYS");
        // YAML_MERGE rows now honour MUSTACHE substitution.
        assertThat(file(files, "src/main/resources/application.yaml"))
                .contains("GRP_FINMonitoringUi_")
                .doesNotContain("{{");
        assertThat(file(files, "src/main/java/com/menora/demo/security/PermissionService.java"))
                .contains("GRP_FINMonitoringUi_");
    }

    @Test
    void unknownDepartmentFallsBackToTheDefault() throws Exception {
        Map<String, String> files = unzip(get("/starter.zip?" + BACKEND_PARAMS
                + "&dependencies=web&department=nope"));

        assertThat(file(files, "k8s/values.yaml")).contains("department: \"lts\"");
    }

    @Test
    void defaultFlagPicksTheDepartmentWhenNoneIsRequested() throws Exception {
        departmentRepo.findByDepartmentId("lts").ifPresent(d -> {
            d.setDefault(false);
            departmentRepo.save(d);
        });
        DepartmentEntity fin = departmentRepo.findByDepartmentId("fin").orElseThrow();
        fin.setDefault(true);
        departmentRepo.save(fin);

        Map<String, String> files = unzip(get("/starter.zip?" + BACKEND_PARAMS + "&dependencies=web"));

        assertThat(file(files, "k8s/values.yaml")).contains("department: \"fin\"");
    }

    @Test
    void multiModuleCarriesTheDepartmentIntoEachModule() throws Exception {
        Map<String, String> files = unzip(get("/starter-multimodule.zip?modules=api&" + BACKEND_PARAMS
                + "&dependencies=web&department=fin"));

        assertThat(files.entrySet()).anySatisfy(e -> {
            assertThat(e.getKey()).endsWith("k8s/values.yaml");
            assertThat(e.getValue()).contains("department: \"fin\"");
        });
    }

    @Test
    void wizardBodyCarriesTheDepartment() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("bootVersion", "3.2.1");
        body.put("dependencies", List.of("web"));
        body.put("department", "fin");

        Map<String, String> files = unzip(post("/starter-wizard.zip", body));

        assertThat(file(files, "k8s/values.yaml")).contains("department: \"fin\"");
    }

    // ── Frontend / fullstack ──────────────────────────────────────────────────

    @Test
    void frontendRendersTheSelectedDepartment() throws Exception {
        Map<String, String> files = unzip(get("/frontend/starter.zip?projectName=web&department=fin"));

        assertThat(files.get("web/k8s/values.yaml"))
                .contains("namespace: \"fin-lan\"")
                .contains("department: \"fin\"")
                .contains("menora-fin");
    }

    @Test
    void frontendDefaultsToLts() throws Exception {
        Map<String, String> files = unzip(get("/frontend/starter.zip?projectName=web"));

        assertThat(files.get("web/k8s/values.yaml")).contains("department: \"lts\"");
    }

    @Test
    void fullstackBodyReachesBothHalves() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "shop");
        body.put("packageName", "com.menora.shop");
        body.put("bootVersion", "3.2.1");
        body.put("department", "fin");
        body.put("entities", List.of(Map.of("name", "Item", "fields", List.of(
                Map.of("name", "id", "type", "Long", "primaryKey", true, "generated", true),
                Map.of("name", "title", "type", "String")))));

        Map<String, String> files = unzip(post("/starter-fullstack.zip", body));

        assertThat(files.get("shop/backend/k8s/values.yaml")).contains("department: \"fin\"");
        assertThat(files.get("shop/frontend/k8s/values.yaml")).contains("department: \"fin\"");
    }

    // ── Metadata + admin ──────────────────────────────────────────────────────

    @Test
    void metadataListsDepartmentsInSortOrder() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/metadata/departments", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode list = json.readTree(response.getBody());
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("id").asText()).isEqualTo("lts");
        assertThat(list.get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(list.get(1).get("id").asText()).isEqualTo("fin");
        assertThat(list.get(1).get("name").asText()).isEqualTo("Finance");
    }

    @Test
    void adminCrudKeepsASingleDefaultAndValidatesTheId() throws Exception {
        HttpHeaders auth = adminHeaders();

        ResponseEntity<String> bad = restTemplate.exchange("/admin/departments", HttpMethod.POST,
                new HttpEntity<>(Map.of("departmentId", "Bad Id", "name", "Bad"), auth), String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> created = restTemplate.exchange("/admin/departments", HttpMethod.POST,
                new HttpEntity<>(Map.of("departmentId", "ops", "name", "Operations", "isDefault", true), auth),
                String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        long id = json.readTree(created.getBody()).get("id").asLong();

        assertThat(departmentRepo.findByDepartmentId("ops").orElseThrow().isDefault()).isTrue();
        assertThat(departmentRepo.findByDepartmentId("lts").orElseThrow().isDefault()).isFalse();

        ResponseEntity<Void> deleted = restTemplate.exchange("/admin/departments/" + id, HttpMethod.DELETE,
                new HttpEntity<>(auth), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(departmentRepo.findByDepartmentId("ops")).isEmpty();
    }

    @Test
    void adminEndpointRequiresAuth() {
        ResponseEntity<String> response = restTemplate.getForEntity("/admin/departments", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private byte[] get(String url) {
        ResponseEntity<byte[]> response = restTemplate.getForEntity(url, byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private byte[] post(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders adminHeaders() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> login = restTemplate.exchange("/admin/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("password", "test"), headers), String.class);
        headers.setBearerAuth(json.readTree(login.getBody()).get("token").asText());
        return headers;
    }

    /** The one entry ending in {@code /suffix} — {@code /starter.zip} has no base dir, the others do. */
    private static String file(Map<String, String> files, String suffix) {
        return files.entrySet().stream()
                .filter(e -> e.getKey().equals(suffix) || e.getKey().endsWith("/" + suffix))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow(() -> new AssertionError("no " + suffix + " in " + files.keySet()));
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    out.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }
}
