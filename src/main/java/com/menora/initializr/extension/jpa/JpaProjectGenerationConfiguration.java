package com.menora.initializr.extension.jpa;

import com.menora.initializr.extension.common.CommonProjectGenerationConfiguration;
import io.spring.initializr.generator.condition.ConditionalOnRequestedDependency;
import io.spring.initializr.generator.project.ProjectDescription;
import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import io.spring.initializr.generator.project.contributor.ProjectContributor;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@ProjectGenerationConfiguration
@ConditionalOnRequestedDependency("data-jpa")
public class JpaProjectGenerationConfiguration {

    @Bean
    ProjectContributor jpaYamlContributor() {
        return projectRoot -> {
            CommonProjectGenerationConfiguration.appendToApplicationYaml(
                    "static-configs/jpa/application-jpa.yml",
                    projectRoot.resolve("src/main/resources/application.yaml"));
        };
    }

    @Bean
    ProjectContributor jpaConfigClassContributor(ProjectDescription description) {
        return projectRoot -> {
            String packageName = description.getPackageName();
            String packagePath = packageName.replace('.', '/');
            Path configDir = projectRoot.resolve("src/main/java/" + packagePath + "/config");
            Files.createDirectories(configDir);

            String template = readTemplate("templates/jpa-config.mustache");
            String rendered = template.replace("{{packageName}}", packageName);
            Files.writeString(configDir.resolve("JpaConfig.java"), rendered);
        };
    }

    private String readTemplate(String path) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Template not found: " + path);
            }
            return new String(in.readAllBytes());
        }
    }

}
