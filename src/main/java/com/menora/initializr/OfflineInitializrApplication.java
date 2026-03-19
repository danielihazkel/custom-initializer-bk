package com.menora.initializr;

import io.spring.initializr.generator.project.ProjectGenerationConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.ANNOTATION,
        classes = ProjectGenerationConfiguration.class
))
public class OfflineInitializrApplication {

    public static void main(String[] args) {
        SpringApplication.run(OfflineInitializrApplication.class, args);
    }

}
