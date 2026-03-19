package com.menora.initializr.extension.observability;

import com.menora.initializr.extension.common.CommonProjectGenerationConfiguration;
import io.spring.initializr.generator.condition.ConditionalOnRequestedDependency;
import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import io.spring.initializr.generator.project.contributor.ProjectContributor;
import org.springframework.context.annotation.Bean;

@ProjectGenerationConfiguration
@ConditionalOnRequestedDependency("actuator")
public class ObservabilityProjectGenerationConfiguration {

    @Bean
    ProjectContributor observabilityYamlContributor() {
        return projectRoot -> {
            CommonProjectGenerationConfiguration.appendToApplicationYaml(
                    "static-configs/observability/application-observability.yml",
                    projectRoot.resolve("src/main/resources/application.yaml"));
        };
    }

}
