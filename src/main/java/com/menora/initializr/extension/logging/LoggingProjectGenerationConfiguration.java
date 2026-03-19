package com.menora.initializr.extension.logging;

import com.menora.initializr.extension.common.CommonProjectGenerationConfiguration;
import io.spring.initializr.generator.condition.ConditionalOnRequestedDependency;
import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import io.spring.initializr.generator.project.contributor.ProjectContributor;
import org.springframework.context.annotation.Bean;

@ProjectGenerationConfiguration
@ConditionalOnRequestedDependency("logging")
public class LoggingProjectGenerationConfiguration {

    @Bean
    ProjectContributor loggingYamlContributor() {
        return projectRoot -> {
            CommonProjectGenerationConfiguration.copyClasspathResource(
                    "static-configs/logging/application-logging.yml",
                    projectRoot.resolve("src/main/resources/application-logging.yml"));
        };
    }

}
