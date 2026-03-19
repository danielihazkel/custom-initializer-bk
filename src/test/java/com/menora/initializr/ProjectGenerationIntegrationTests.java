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
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;

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
    }

    @Test
    void securityDependencyInjectsSecurityConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("security");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/resources/application-security.yml")
                .contains("src/main/java/com/menora/demo/config/SecurityConfig.java");
    }

    @Test
    void jpaDependencyInjectsJpaConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("data-jpa");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/resources/application-jpa.yml")
                .contains("src/main/java/com/menora/demo/config/JpaConfig.java");
    }

    @Test
    void actuatorDependencyInjectsObservabilityConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        request.getDependencies().add("actuator");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/resources/application-observability.yml");
    }

    @Test
    void rqueueDependencyInjectsRqueueConfig() throws Exception {
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("rqueue");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/resources/application-rqueue.yml")
                .contains("src/main/java/com/menora/demo/config/RqueueConfig.java");
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
                .contains("src/main/resources/application-kafka.yml")
                .contains("src/main/resources/application-security.yml")
                .contains("src/main/resources/application-jpa.yml")
                .contains("src/main/resources/application-observability.yml")
                .contains("src/main/resources/log4j2-spring.xml")
                .contains(".editorconfig")
                .contains("src/main/java/com/menora/demo/config/KafkaConfig.java")
                .contains("src/main/java/com/menora/demo/config/SecurityConfig.java")
                .contains("src/main/java/com/menora/demo/config/JpaConfig.java");
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
