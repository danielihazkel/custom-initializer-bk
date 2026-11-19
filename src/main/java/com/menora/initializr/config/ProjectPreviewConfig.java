package com.menora.initializr.config;

import io.spring.initializr.generator.project.ProjectAssetGenerator;
import io.spring.initializr.generator.project.ProjectDescription;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.web.controller.DefaultProjectGenerationController;
import io.spring.initializr.web.controller.ProjectGenerationController;
import io.spring.initializr.web.project.DefaultProjectRequestPlatformVersionTransformer;
import io.spring.initializr.web.project.DefaultProjectRequestToDescriptionConverter;
import io.spring.initializr.web.project.ProjectGenerationInvoker;
import io.spring.initializr.web.project.ProjectRequest;
import io.spring.initializr.web.project.ProjectRequestPlatformVersionTransformer;
import io.spring.initializr.web.project.ProjectRequestToDescriptionConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Ensures a ProjectGenerationInvoker bean is available for the preview endpoint.
 * The @ConditionalOnMissingBean guard means if initializr-web's auto-config already
 * registered one, this definition is skipped entirely.
 *
 * <p>Also the framework's own generation controller ({@code /starter.zip}, {@code /pom.xml}…), as the
 * auto-configuration builds it — but over an invoker that names each project it generates
 * ({@link GeneratedSnapshots#stamp}) once every contributor has run. The framework's bean stands
 * back for one of ours ({@code @ConditionalOnMissingBean}).
 */
@Configuration
public class ProjectPreviewConfig {

    @Bean
    @ConditionalOnMissingBean
    public ProjectGenerationInvoker<ProjectRequest> projectGenerationInvoker(ApplicationContext context) {
        return new ProjectGenerationInvoker<>(context, new DefaultProjectRequestToDescriptionConverter());
    }

    @Bean
    public ProjectGenerationController<ProjectRequest> projectGenerationController(
            InitializrMetadataProvider metadataProvider,
            ObjectProvider<ProjectRequestPlatformVersionTransformer> platformVersionTransformer,
            ApplicationContext context) {
        return new DefaultProjectGenerationController(metadataProvider, new StampingInvoker(context,
                new DefaultProjectRequestToDescriptionConverter(
                        platformVersionTransformer.getIfAvailable(DefaultProjectRequestPlatformVersionTransformer::new))));
    }

    /** The framework's invoker, naming the finished project: after the last contributor, before the zip. */
    static final class StampingInvoker extends ProjectGenerationInvoker<ProjectRequest> {

        StampingInvoker(ApplicationContext context, ProjectRequestToDescriptionConverter<ProjectRequest> converter) {
            super(context, converter);
        }

        @Override
        protected ProjectAssetGenerator<Path> getProjectAssetGenerator(ProjectDescription description) {
            ProjectAssetGenerator<Path> generator = super.getProjectAssetGenerator(description);
            return context -> {
                Path root = generator.generate(context);
                GeneratedSnapshots.stamp(root);
                return root;
            };
        }
    }
}
