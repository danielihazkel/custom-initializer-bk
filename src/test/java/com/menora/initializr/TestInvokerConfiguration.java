package com.menora.initializr;

import io.spring.initializr.web.project.DefaultProjectRequestToDescriptionConverter;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestInvokerConfiguration {

    @Bean
    public ProjectGenerationInvoker<ProjectRequest> invoker(ApplicationContext context) {
        return new ProjectGenerationInvoker<>(context, new DefaultProjectRequestToDescriptionConverter());
    }
}
