package com.menora.initializr.admin.validation;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Properties;

@Component
public class PropertiesContentValidator implements ContentSyntaxValidator {

    @Override
    public List<String> validate(String content) {
        try {
            Properties props = new Properties();
            props.load(new StringReader(content));
            return List.of();
        } catch (IOException | IllegalArgumentException e) {
            return List.of(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
