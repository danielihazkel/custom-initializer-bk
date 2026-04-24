package com.menora.initializr.admin.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JsonContentValidator implements ContentSyntaxValidator {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<String> validate(String content) {
        try {
            mapper.readTree(content);
            return List.of();
        } catch (JsonProcessingException e) {
            return List.of(e.getOriginalMessage() != null ? e.getOriginalMessage() : e.getMessage());
        }
    }
}
