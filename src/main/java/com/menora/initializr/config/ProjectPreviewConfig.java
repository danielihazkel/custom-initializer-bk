package com.menora.initializr.config;

import io.spring.initializr.web.project.DefaultProjectRequestToDescriptionConverter;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ensures a ProjectGenerationInvoker bean is available for the preview endpoint.
 * The @ConditionalOnMissingBean guard means if initializr-web's auto-config already
 * registered one, this definition is skipped entirely.
 */
@Configuration
public class ProjectPreviewConfig {

    @Bean
    @ConditionalOnMissingBean
    public ProjectGenerationInvoker<ProjectRequest> projectGenerationInvoker(ApplicationContext context) {
        return new ProjectGenerationInvoker<>(context, new DefaultProjectRequestToDescriptionConverter());
    }
}
