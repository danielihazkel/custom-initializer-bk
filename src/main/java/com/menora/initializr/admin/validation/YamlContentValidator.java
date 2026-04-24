package com.menora.initializr.admin.validation;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.List;

@Component
public class YamlContentValidator implements ContentSyntaxValidator {

    @Override
    public List<String> validate(String content) {
        try {
            Yaml yaml = new Yaml();
            for (Object ignored : yaml.loadAll(content)) {
                // force parse of every document
            }
            return List.of();
        } catch (YAMLException e) {
            return List.of(e.getMessage());
        }
    }
}
