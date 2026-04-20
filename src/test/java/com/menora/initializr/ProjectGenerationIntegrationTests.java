package com.menora.initializr;

import io.spring.initializr.generator.test.project.ProjectStructure;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class ProjectGenerationIntegrationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProjectGenerationInvoker<ProjectRequest> invoker;

    @Test
    void metadataEndpointReturnsOk() {
        ResponseEntity<String> response = restTemplate.getForEntity("/metadata/client", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("kafka");
        assertThat(response.getBody()).contains("rqueue");
    }

    @Test
    void generatedProjectContainsArtifactoryRepo() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        String pomContent = Files.readString(projectDir.resolve("pom.xml"));
        assertThat(pomContent).contains("repo.menora.co.il/artifactory/libs-release");
    }

    @Test
    void generatedProjectContainsVersionDockerfileAndK8s() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("VERSION")
                .contains("Dockerfile")
                .contains("entrypoint.sh")
                .contains("settings.xml")
                .contains("k8s/Jenkinsfile")
                .contains("k8s/values.yaml");

        assertThat(Files.readString(projectDir.resolve("VERSION")).trim()).isEqualTo("0.0.1-SNAPSHOT");
        assertThat(Files.readString(projectDir.resolve("Dockerfile"))).contains("demo");
        assertThat(Files.readString(projectDir.resolve("k8s/values.yaml"))).contains("com.menora");
    }

    @Test
    void generatedProjectContainsLog4j2() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths().contains("src/main/resources/log4j2-spring.xml");
        assertThat(project).filePaths().doesNotContain("src/main/resources/logback-spring.xml");

        String pomContent = Files.readString(projectDir.resolve("pom.xml"));
        assertThat(pomContent).contains("spring-boot-starter-log4j2");
        assertThat(pomContent).contains("spring-boot-starter-logging");  // present as exclusion
    }

    @Test
    void generatedProjectContainsEditorconfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths().contains(".editorconfig");
        assertThat(project).filePaths().doesNotContain("src/main/resources/application.properties");
    }

    @Test
    void kafkaDependencyInjectsConfigFiles() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("kafka");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("bootstrap-servers");
        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/KafkaConfig.java");
    }

    @Test
    void securityDependencyInjectsSecurityConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("security");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("oauth2");
        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/SecurityConfig.java");
    }

    @Test
    void jpaDependencyInjectsJpaConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("data-jpa");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("datasource");
        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/JpaConfig.java");
    }

    @Test
    void actuatorDependencyInjectsObservabilityConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("actuator");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();

        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("management");
    }

    @Test
    void rqueueDependencyInjectsRqueueConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("rqueue");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(Files.readString(projectDir.resolve("src/main/resources/application.yaml")))
                .contains("rqueue");
        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/config/RqueueConfig.java");
    }

    @Test
    void withoutKafkaDependencyNoKafkaFiles() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .doesNotContain("src/main/java/com/menora/demo/config/KafkaConfig.java");
    }

    @Test
    void multipleDependenciesInjectAllConfigs() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("kafka");
        request.getDependencies().add("security");
        request.getDependencies().add("data-jpa");
        request.getDependencies().add("actuator");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/resources/application.yaml")
                .contains("src/main/resources/log4j2-spring.xml")
                .contains(".editorconfig")
                .contains("src/main/java/com/menora/demo/config/KafkaConfig.java")
                .contains("src/main/java/com/menora/demo/config/SecurityConfig.java")
                .contains("src/main/java/com/menora/demo/config/JpaConfig.java");

        String appYaml = Files.readString(projectDir.resolve("src/main/resources/application.yaml"));
        assertThat(appYaml)
                .contains("bootstrap-servers")
                .contains("oauth2")
                .contains("datasource")
                .contains("management");
    }

    @Test
    void sqlWizardGeneratesEntitiesAndRepositoriesAndLombokDep() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("name", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("postgresql", "data-jpa"));
        body.put("opts", Map.of("postgresql", List.of("pg-primary")));
        body.put("sqlByDep", Map.of("postgresql", """
                CREATE TABLE users (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(200) NOT NULL,
                    created_at TIMESTAMP
                );
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    total NUMERIC(10,2)
                );
                """));
        body.put("sqlOptions", Map.of("postgresql", Map.of(
                "subPackage", "entity",
                "tables", List.of(
                        Map.of("name", "users", "generateRepository", true),
                        Map.of("name", "orders", "generateRepository", false)))));

        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-sql.zip", body, byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);

        Map<String, String> files = unzip(response.getBody());
        String usersEntity = files.get("demo/src/main/java/com/menora/demo/entity/Users.java");
        assertThat(usersEntity)
                .as("Users entity")
                .isNotNull()
                .contains("@Entity")
                .contains("@Table(name = \"users\")")
                .contains("@Data")
                .contains("private Long id;")
                .contains("private String email;")
                .contains("private LocalDateTime createdAt;");

        assertThat(files)
                .containsKey("demo/src/main/java/com/menora/demo/entity/Orders.java")
                .containsKey("demo/src/main/java/com/menora/demo/repository/UsersRepository.java");
        // orders has generateRepository=false → no repo
        assertThat(files)
                .doesNotContainKey("demo/src/main/java/com/menora/demo/repository/OrdersRepository.java");

        String pom = files.get("demo/pom.xml");
        assertThat(pom).isNotNull().contains("<artifactId>lombok</artifactId>");
    }

    @Test
    void openApiWizardGeneratesController() throws Exception {
        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-openapi.zip", openApiPetstoreBody(), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);

        Map<String, String> files = unzip(response.getBody());
        String controller = files.get("demo/src/main/java/com/menora/demo/api/PetsController.java");
        assertThat(controller)
                .as("PetsController.java")
                .isNotNull()
                .contains("@RestController")
                .contains("@GetMapping(\"/pets/{id}\")")
                .contains("@PathVariable")
                .contains("throw new UnsupportedOperationException");
    }

    @Test
    void openApiWizardGeneratesRecord() throws Exception {
        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                "/starter-openapi.zip", openApiPetstoreBody(), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> files = unzip(response.getBody());
        String dto = files.get("demo/src/main/java/com/menora/demo/dto/Pet.java");
        assertThat(dto)
                .as("Pet.java")
                .isNotNull()
                .contains("public record Pet(")
                .contains("Long id")
                .contains("String name");
    }

    @Test
    void openApiWizardParseErrorReturns400() {
        Map<String, Object> body = openApiBaseBody();
        body.put("specByDep", Map.of("web", "this is not: valid: yaml: at: all: ["));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/starter-openapi.zip", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void openApiMetadataEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/metadata/openapi-capable-deps", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("web");
    }

    private Map<String, Object> openApiBaseBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groupId", "com.menora");
        body.put("artifactId", "demo");
        body.put("name", "demo");
        body.put("packageName", "com.menora.demo");
        body.put("type", "maven-project");
        body.put("language", "java");
        body.put("bootVersion", "3.2.1");
        body.put("packaging", "jar");
        body.put("javaVersion", "21");
        body.put("dependencies", List.of("web"));
        body.put("opts", Map.of());
        return body;
    }

    private Map<String, Object> openApiPetstoreBody() {
        Map<String, Object> body = openApiBaseBody();
        body.put("specByDep", Map.of("web", """
                openapi: 3.0.3
                info:
                  title: Petstore
                  version: 1.0.0
                paths:
                  /pets/{id}:
                    get:
                      tags: [pets]
                      operationId: getPetById
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema:
                            type: integer
                            format: int64
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Pet'
                components:
                  schemas:
                    Pet:
                      type: object
                      required: [id, name]
                      properties:
                        id:
                          type: integer
                          format: int64
                        name:
                          type: string
                """));
        body.put("openApiOptions", Map.of("web", Map.of(
                "apiSubPackage", "api",
                "dtoSubPackage", "dto")));
        return body;
    }

    private Map<String, String> unzip(byte[] zipBytes) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] buf = zis.readAllBytes();
                out.put(e.getName(), new String(buf, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private WebProjectRequest createBaseRequest() {
        WebProjectRequest request = new WebProjectRequest();
        request.setGroupId("com.menora");
        request.setArtifactId("demo");
        request.setPackageName("com.menora.demo");
        request.setBootVersion("3.2.1");
        request.setLanguage("java");
        request.setJavaVersion("21");
        request.setType("maven-project");
        request.setPackaging("jar");
        request.setConfigurationFileFormat("properties");
        return request;
    }

}
