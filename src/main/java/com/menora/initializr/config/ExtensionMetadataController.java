package com.menora.initializr.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@EnableConfigurationProperties(DependencyOptionsProperties.class)
public class ExtensionMetadataController {

    private final DependencyOptionsProperties props;

    public ExtensionMetadataController(DependencyOptionsProperties props) {
        this.props = props;
    }

    @GetMapping("/metadata/extensions")
    public Map<String, List<DependencyOptionsProperties.SubOption>> extensions() {
        return props.dependencyOptions() != null ? props.dependencyOptions() : Map.of();
    }
}
