package com.menora.initializr;

import com.menora.initializr.ai.AiDtos.GeneratedAiFile;
import com.menora.initializr.config.AiFilesContext;
import io.spring.initializr.generator.test.project.ProjectStructure;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the AI file contributor — populates {@link
 * AiFilesContext} directly (bypassing the AI service), runs the standard
 * project-generation pipeline, and asserts the AI files end up in the
 * generated project tree.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInvokerConfiguration.class)
class AiFileContributionIntegrationTests {

    @Autowired
    private ProjectGenerationInvoker<ProjectRequest> invoker;

    @Autowired
    private AiFilesContext aiFilesContext;

    @AfterEach
    void clearContext() {
        aiFilesContext.clear();
    }

    @Test
    void aiFilesAreWrittenIntoGeneratedProject() throws Exception {
        aiFilesContext.populate(List.of(
                new GeneratedAiFile(
                        "src/main/java/com/menora/demo/HelloController.java",
                        "package com.menora.demo;\n\npublic class HelloController { /* hi */ }\n"),
                new GeneratedAiFile(
                        "src/main/resources/static/welcome.txt",
                        "welcome from the AI assistant\n")));

        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths()
                .contains("src/main/java/com/menora/demo/HelloController.java")
                .contains("src/main/resources/static/welcome.txt");
        assertThat(Files.readString(projectDir.resolve(
                "src/main/java/com/menora/demo/HelloController.java")))
                .contains("HelloController");
        assertThat(Files.readString(projectDir.resolve(
                "src/main/resources/static/welcome.txt")))
                .isEqualTo("welcome from the AI assistant\n");
    }

    @Test
    void emptyContextIsHarmless() throws Exception {
        aiFilesContext.populate(List.of());

        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();
        ProjectStructure project = new ProjectStructure(projectDir);

        assertThat(project).filePaths().contains("pom.xml");
    }

    @Test
    void aiFileTargetingPomXmlIsSkippedNotApplied() throws Exception {
        // Generate once without the malicious AI file to capture baseline pom.xml.
        WebProjectRequest baseline = createBaseRequest();
        baseline.getDependencies().add("web");
        Path baselineDir = invoker.invokeProjectStructureGeneration(baseline).getRootDirectory();
        String expectedPom = Files.readString(baselineDir.resolve("pom.xml"));

        // Now try to clobber pom.xml via the AI files context.
        aiFilesContext.populate(List.of(
                new GeneratedAiFile("pom.xml", "<malicious/>")));
        WebProjectRequest request = createBaseRequest();
        request.getDependencies().add("web");
        Path projectDir = invoker.invokeProjectStructureGeneration(request).getRootDirectory();

        // The contributor logs-and-skips unsafe paths; pom.xml should be
        // identical to the baseline, untouched by the malicious entry.
        assertThat(Files.readString(projectDir.resolve("pom.xml"))).isEqualTo(expectedPom);
    }

    private static WebProjectRequest createBaseRequest() {
        WebProjectRequest r = new WebProjectRequest();
        r.setType("maven-project");
        r.setLanguage("java");
        r.setBootVersion("3.2.1");
        r.setGroupId("com.menora");
        r.setArtifactId("demo");
        r.setName("demo");
        r.setDescription("Demo");
        r.setPackageName("com.menora.demo");
        r.setPackaging("jar");
        r.setJavaVersion("21");
        r.setVersion("0.0.1-SNAPSHOT");
        r.setConfigurationFileFormat("properties");
        r.getDependencies().clear();
        return r;
    }
}
