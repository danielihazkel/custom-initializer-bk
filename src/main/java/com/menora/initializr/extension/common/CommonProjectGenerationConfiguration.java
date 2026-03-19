package com.menora.initializr.extension.common;

import io.spring.initializr.generator.buildsystem.MavenRepository;
import io.spring.initializr.generator.buildsystem.maven.MavenBuild;
import io.spring.initializr.generator.project.ProjectDescription;
import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import io.spring.initializr.generator.spring.build.BuildCustomizer;
import io.spring.initializr.generator.project.contributor.ProjectContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@ProjectGenerationConfiguration
public class CommonProjectGenerationConfiguration {

    @Bean
    BuildCustomizer<MavenBuild> artifactoryBuildCustomizer() {
        return build -> {
            build.repositories().add("menora-release",
                    MavenRepository.withIdAndUrl("menora-release",
                                    "https://repo.menora.co.il/artifactory/libs-release")
                            .name("Menora Artifactory Releases")
                            .build());
            build.repositories().add("menora-snapshot",
                    MavenRepository.withIdAndUrl("menora-snapshot",
                                    "https://repo.menora.co.il/artifactory/libs-snapshot")
                            .name("Menora Artifactory Snapshots")
                            .snapshotsEnabled(true)
                            .build());
        };
    }

    @Bean
    ProjectContributor logbackContributor() {
        return projectRoot -> {
            copyClasspathResource("static-configs/common/logback-spring.xml",
                    projectRoot.resolve("src/main/resources/logback-spring.xml"));
        };
    }

    @Bean
    ProjectContributor editorConfigContributor() {
        return projectRoot -> {
            copyClasspathResource("static-configs/common/.editorconfig",
                    projectRoot.resolve(".editorconfig"));
        };
    }

    @Bean
    ProjectContributor entrypointContributor() {
        return projectRoot -> {
            copyClasspathResource("static-configs/common/entrypoint.sh",
                    projectRoot.resolve("entrypoint.sh"));
        };
    }

    @Bean
    ProjectContributor settingsXmlContributor() {
        return projectRoot -> {
            copyClasspathResource("static-configs/common/settings.xml",
                    projectRoot.resolve("settings.xml"));
        };
    }

    @Bean
    ProjectContributor versionFileContributor(ProjectDescription description) {
        return projectRoot -> {
            String content = renderTemplate("templates/VERSION.mustache", description);
            Files.writeString(projectRoot.resolve("VERSION"), content);
        };
    }

    @Bean
    ProjectContributor dockerfileContributor(ProjectDescription description) {
        return projectRoot -> {
            String content = renderTemplate("templates/Dockerfile.mustache", description);
            Files.writeString(projectRoot.resolve("Dockerfile"), content);
        };
    }

    @Bean
    ProjectContributor jenkinsfileContributor(ProjectDescription description) {
        return projectRoot -> {
            Path k8sDir = projectRoot.resolve("k8s");
            Files.createDirectories(k8sDir);
            String content = renderTemplate("templates/Jenkinsfile.mustache", description);
            Files.writeString(k8sDir.resolve("Jenkinsfile"), content);
        };
    }

    @Bean
    ProjectContributor k8sValuesContributor(ProjectDescription description) {
        return projectRoot -> {
            Path k8sDir = projectRoot.resolve("k8s");
            Files.createDirectories(k8sDir);
            String content = renderTemplate("templates/k8s-values.mustache", description);
            Files.writeString(k8sDir.resolve("values.yaml"), content);
        };
    }

    public static void copyClasspathResource(String classpathPath, Path target) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpathPath);
        if (resource.exists()) {
            Files.createDirectories(target.getParent());
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String renderTemplate(String classpathPath, ProjectDescription description) throws IOException {
        try (InputStream in = CommonProjectGenerationConfiguration.class
                .getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IOException("Template not found: " + classpathPath);
            }
            return new String(in.readAllBytes())
                    .replace("{{artifactId}}", description.getArtifactId())
                    .replace("{{groupId}}", description.getGroupId())
                    .replace("{{version}}", description.getVersion());
        }
    }

}
