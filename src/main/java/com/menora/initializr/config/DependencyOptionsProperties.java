package com.menora.initializr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties("menora")
public record DependencyOptionsProperties(
        Map<String, List<SubOption>> dependencyOptions
) {
    public record SubOption(String id, String label, String description) {}
}
