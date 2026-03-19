package com.menora.initializr.extension.kafka;

import io.spring.initializr.generator.test.ProjectAssertion;
import io.spring.initializr.generator.test.ProjectStructure;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KafkaProjectGenerationConfigurationTests {

    @Autowired
    private ProjectGenerationInvoker<ProjectRequest> invoker;

    @Test
    void kafkaDependencyInjectsConfigFiles() throws Exception {
        WebProjectRequest request = new WebProjectRequest();
        request.setGroupId("com.menora");
        request.setArtifactId("test-kafka");
        request.setPackageName("com.menora.testkafka");
        request.setBootVersion("3.2.1");
        request.setLanguage("java");
        request.setJavaVersion("21");
        request.setType("maven-project");
        request.getDependencies().add("web");
        request.getDependencies().add("kafka");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/resources/application-kafka.yml")
                .contains("src/main/java/com/menora/testkafka/config/KafkaConfig.java");
    }

    @Test
    void withoutKafkaDependencyNoKafkaFiles() throws Exception {
        WebProjectRequest request = new WebProjectRequest();
        request.setGroupId("com.menora");
        request.setArtifactId("test-web-only");
        request.setPackageName("com.menora.testwebonly");
        request.setBootVersion("3.2.1");
        request.setLanguage("java");
        request.setJavaVersion("21");
        request.setType("maven-project");
        request.getDependencies().add("web");

        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .doesNotContain("src/main/resources/application-kafka.yml")
                .doesNotContain("src/main/java/com/menora/testwebonly/config/KafkaConfig.java");
    }

}
