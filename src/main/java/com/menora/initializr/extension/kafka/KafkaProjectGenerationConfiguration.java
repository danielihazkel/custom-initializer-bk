package com.menora.initializr.extension.kafka;

import com.menora.initializr.config.ProjectOptionsContext;
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
@ConditionalOnRequestedDependency("kafka")
public class KafkaProjectGenerationConfiguration {

    @Bean
    ProjectContributor kafkaYamlContributor() {
        return projectRoot -> {
            CommonProjectGenerationConfiguration.appendToApplicationYaml(
                    "static-configs/kafka/application-kafka.yml",
                    projectRoot.resolve("src/main/resources/application.yaml"));
        };
    }

    @Bean
    ProjectContributor kafkaConfigClassContributor(ProjectDescription description) {
        return projectRoot -> {
            String packageName = description.getPackageName();
            String packagePath = packageName.replace('.', '/');
            Path configDir = projectRoot.resolve("src/main/java/" + packagePath + "/config");
            Files.createDirectories(configDir);

            String template = readTemplate("templates/kafka-config.mustache");
            String rendered = template.replace("{{packageName}}", packageName);
            Files.writeString(configDir.resolve("KafkaConfig.java"), rendered);
        };
    }

    @Bean
    ProjectContributor kafkaConsumerExampleContributor(ProjectOptionsContext optionsContext, ProjectDescription description) {
        return projectRoot -> {
            if (!optionsContext.hasOption("kafka", "consumer-example")) return;
            String packageName = description.getPackageName();
            String packagePath = packageName.replace('.', '/');
            Path configDir = projectRoot.resolve("src/main/java/" + packagePath + "/config");
            Files.createDirectories(configDir);
            String template = readTemplate("templates/kafka-consumer-example.mustache");
            Files.writeString(configDir.resolve("KafkaConsumerExample.java"),
                    template.replace("{{packageName}}", packageName));
        };
    }

    @Bean
    ProjectContributor kafkaProducerExampleContributor(ProjectOptionsContext optionsContext, ProjectDescription description) {
        return projectRoot -> {
            if (!optionsContext.hasOption("kafka", "producer-example")) return;
            String packageName = description.getPackageName();
            String packagePath = packageName.replace('.', '/');
            Path configDir = projectRoot.resolve("src/main/java/" + packagePath + "/config");
            Files.createDirectories(configDir);
            String template = readTemplate("templates/kafka-producer-example.mustache");
            Files.writeString(configDir.resolve("KafkaProducerExample.java"),
                    template.replace("{{packageName}}", packageName));
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
