package com.menora.initializr.config;

import com.menora.initializr.db.DependencyConfigService;
import com.menora.initializr.db.entity.DependencySubOptionEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class ExtensionMetadataController {

    private final DependencyConfigService configService;

    public ExtensionMetadataController(DependencyConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/metadata/extensions")
    public Map<String, List<SubOptionDto>> extensions() {
        return configService.getAllSubOptions().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .map(so -> new SubOptionDto(so.getOptionId(), so.getLabel(), so.getDescription()))
                                .collect(Collectors.toList())
                ));
    }

    public record SubOptionDto(String id, String label, String description) {}
}
