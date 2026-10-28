package com.menora.initializr.config;

import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.VersionService;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.metadata.InitializrProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

@Configuration
public class MetadataProviderConfig {

    /**
     * Declares the metadata provider bean with @Primary to ensure it takes precedence
     * over the one registered by InitializrAutoConfiguration.
     *
     * We bind InitializrProperties fresh from the Environment at provider construction
     * time to avoid the circular-dependency issue that arises from injecting the
     * auto-configured provider or bean-early-init issues with @EnableConfigurationProperties.
     */
    @Bean
    @Primary
    public InitializrMetadataProvider initializrMetadataProvider(
            Environment environment,
            DependencyConfigService configService,
            VersionService versionService) {
        var log = org.slf4j.LoggerFactory.getLogger(MetadataProviderConfig.class);
        if (environment instanceof org.springframework.core.env.ConfigurableEnvironment ce) {
            ce.getPropertySources().forEach(ps -> {
                if (ps instanceof org.springframework.core.env.EnumerablePropertySource<?> eps) {
                    for (String name : eps.getPropertyNames()) {
                        if (name.startsWith("initializr.")) {
                            log.debug("  env key: {}={}", name, eps.getProperty(name));
                        }
                    }
                }
            });
        }
        InitializrProperties properties = Binder.get(environment)
                .bind("initializr", InitializrProperties.class)
                .orElseGet(InitializrProperties::new);
        log.debug("Binder result: types={}, packagings={}, javaVersions={}",
                properties.getTypes().size(),
                properties.getPackagings().size(),
                properties.getJavaVersions().size());
        return new DatabaseInitializrMetadataProvider(properties, configService, versionService);
    }

    /**
     * Builds the metadata once the app is up, so the first visitor's {@code /metadata/client}
     * doesn't pay for building it. A failure here is only logged: the first request retries and
     * reports it, as it always has.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmMetadata(ApplicationReadyEvent event) {
        try {
            event.getApplicationContext().getBean(InitializrMetadataProvider.class).get();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(MetadataProviderConfig.class)
                    .warn("Could not build the dependency metadata at startup: {}", e.getMessage());
        }
    }
}
