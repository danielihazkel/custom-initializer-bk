package com.menora.initializr.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The two checks that keep what a request says from naming a file outside the generated project. */
class GeneratedProjectFilesTest {

    @TempDir
    Path root;

    @Test
    void aPathInsideTheProjectResolvesAsBefore() {
        assertThat(GeneratedProjectFiles.resolveInside(root, "pom.xml")).isEqualTo(root.resolve("pom.xml"));
        assertThat(GeneratedProjectFiles.resolveInside(root, "src/main/java/com/menora/demo/A.java"))
                .isEqualTo(root.resolve("src/main/java/com/menora/demo/A.java"));
        // Going down and back up is still inside.
        assertThat(GeneratedProjectFiles.resolveInside(root, "src/../k8s/values.yaml"))
                .isEqualTo(root.resolve("k8s/values.yaml"));
        assertThat(GeneratedProjectFiles.resolveInside(root, ".env")).isEqualTo(root.resolve(".env"));
    }

    @Test
    void aPathThatLeavesTheProjectIsRefused() {
        String absolute = root.getRoot().resolve("somewhere").resolve("else.txt").toString();
        for (String outside : List.of("../x", "../../x.yaml", "src/../../x", "a/b/../../../x", "..", ".", "",
                absolute, "..\\x", "src\\..\\..\\x")) {
            // A backslash separates directories on Windows and is part of a name elsewhere, where
            // such a path stays inside the project and may be written.
            boolean backslash = outside.contains("\\") && !outside.equals(absolute);
            if (backslash && !java.io.File.separator.equals("\\")) continue;
            assertThatThrownBy(() -> GeneratedProjectFiles.resolveInside(root, outside))
                    .as("'" + outside + "'")
                    .isInstanceOf(WizardArgumentException.class)
                    .hasMessageContaining("inside the project");
        }
    }

    @Test
    void aPlainNameIsLettersDigitsAndAFewMarks() {
        for (String name : List.of("demo", "my-app", "my_app", "app.core", "App2", "_x", "ldap-auth-rest", "a")) {
            assertThat(GeneratedProjectFiles.requirePlainName(name, "artifactId")).isEqualTo(name);
        }
        for (String name : new String[] {null, "", " ", "..", "../x", "a/b", "a\\b", ".hidden", "-x", "a..b",
                "my app", "a\"b", "a\nb", "C:x", "x".repeat(129)}) {
            assertThatThrownBy(() -> GeneratedProjectFiles.requirePlainName(name, "artifactId"))
                    .as("'" + name + "'")
                    .isInstanceOf(WizardArgumentException.class)
                    .hasMessageContaining("artifactId")
                    .satisfies(e -> assertThat(((WizardArgumentException) e).path()).isEqualTo("artifactId"));
        }
    }
}
