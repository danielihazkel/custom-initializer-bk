package com.menora.initializr;

import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.WebProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ProjectGenerationInvoker} registers every generated root directory in a private
 * {@code Map<Path, List<Path>> temporaryFiles} and only ever removes entries in
 * {@code cleanTempFiles(Path)}. The invoker is a singleton bean, so a generation endpoint
 * that deletes the directory with {@code FileSystemUtils.deleteRecursively} instead leaks
 * one map entry per request for the lifetime of the JVM.
 *
 * <p>The map is private, so this asserts on it reflectively — that is the only way to
 * observe the leak without waiting for an OOM.
 */
@SpringBootTest
@Import(TestInvokerConfiguration.class)
class InvokerTempFileCleanupTests {

    @Autowired
    private ProjectGenerationInvoker<ProjectRequest> invoker;

    @SuppressWarnings("unchecked")
    private Map<Path, ?> temporaryFiles() {
        return (Map<Path, ?>) ReflectionTestUtils.getField(invoker, "temporaryFiles");
    }

    @Test
    void cleanTempFilesLeavesNoRegisteredDirectories() {
        int before = temporaryFiles().size();

        for (int i = 0; i < 5; i++) {
            Path dir = invoker.invokeProjectStructureGeneration(newRequest()).getRootDirectory();
            assertThat(Files.exists(dir)).isTrue();
            invoker.cleanTempFiles(dir);
            assertThat(Files.exists(dir)).as("cleanTempFiles must also delete the tree").isFalse();
        }

        assertThat(temporaryFiles().size())
                .as("every generated root must be unregistered, not just deleted")
                .isEqualTo(before);
    }

    @Test
    void plainDeleteWouldLeakAnEntry() {
        int before = temporaryFiles().size();
        Path dir = invoker.invokeProjectStructureGeneration(newRequest()).getRootDirectory();

        // Pins the behaviour this fix exists for: generating alone registers an entry.
        assertThat(temporaryFiles().size()).isEqualTo(before + 1);

        invoker.cleanTempFiles(dir);
        assertThat(temporaryFiles().size()).isEqualTo(before);
    }

    private WebProjectRequest newRequest() {
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
