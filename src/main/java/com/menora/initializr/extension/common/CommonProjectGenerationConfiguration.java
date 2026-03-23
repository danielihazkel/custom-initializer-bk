package com.menora.initializr.extension.common;

import io.spring.initializr.generator.buildsystem.Dependency;
import io.spring.initializr.generator.buildsystem.MavenRepository;
import io.spring.initializr.generator.buildsystem.maven.MavenBuild;
import io.spring.initializr.generator.project.ProjectDescription;
import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import io.spring.initializr.generator.spring.build.BuildCustomizer;
import io.spring.initializr.generator.project.contributor.ProjectContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

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
    @Order(Ordered.LOWEST_PRECEDENCE)
    ProjectContributor removeApplicationPropertiesContributor() {
        return projectRoot -> {
            Path appProperties = projectRoot.resolve("src/main/resources/application.properties");
            Files.deleteIfExists(appProperties);
        };
    }

    @Bean
    ProjectContributor log4j2Contributor() {
        return projectRoot -> {
            copyClasspathResource("static-configs/common/log4j2-spring.xml",
                    projectRoot.resolve("src/main/resources/log4j2-spring.xml"));
        };
    }

    @Bean
    BuildCustomizer<MavenBuild> log4j2BuildCustomizer() {
        return build -> {
            build.dependencies().add("spring-boot-starter",
                Dependency.withCoordinates("org.springframework.boot", "spring-boot-starter")
                    .exclusions(new Dependency.Exclusion("org.springframework.boot", "spring-boot-starter-logging"))
                    .build());
            build.dependencies().add("spring-boot-starter-log4j2",
                Dependency.withCoordinates("org.springframework.boot", "spring-boot-starter-log4j2")
                    .build());
        };
    }

    @Bean
    BuildCustomizer<MavenBuild> lombokBuildCustomizer() {
        return build -> {
            build.dependencies().add("lombok",
                Dependency.withCoordinates("org.projectlombok", "lombok")
                    .build());
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
            String javaVersion = description.getLanguage().jvmVersion();
            String templatePath = "templates/Dockerfile-java" + javaVersion + ".mustache";
            String content = renderTemplate(templatePath, description);
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

    public static void appendToApplicationYaml(String classpathPath, Path applicationYamlPath) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpathPath);
        if (!resource.exists()) return;
        Files.createDirectories(applicationYamlPath.getParent());
        String newContent = new String(resource.getInputStream().readAllBytes());

        Yaml yaml = new Yaml();
        Map<String, Object> merged;
        if (Files.exists(applicationYamlPath)) {
            Map<String, Object> existing = yaml.load(Files.readString(applicationYamlPath));
            Map<String, Object> incoming = yaml.load(newContent);
            merged = deepMerge(existing, incoming);
        } else {
            merged = yaml.load(newContent);
        }

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        Files.writeString(applicationYamlPath, new Yaml(opts).dump(merged));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            Object baseVal = result.get(entry.getKey());
            Object overrideVal = entry.getValue();
            if (baseVal instanceof Map && overrideVal instanceof Map) {
                result.put(entry.getKey(), deepMerge((Map<String, Object>) baseVal, (Map<String, Object>) overrideVal));
            } else {
                result.put(entry.getKey(), overrideVal);
            }
        }
        return result;
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
