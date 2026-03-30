package com.menora.initializr.admin.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public class OrphanCheckResponse {

    private String message;
    private Map<String, Long> references;

    public OrphanCheckResponse(Map<String, Long> references) {
        this.references = references;
        this.message = buildMessage(references);
    }

    private static String buildMessage(Map<String, Long> refs) {
        StringJoiner parts = new StringJoiner(", ");
        refs.forEach((table, count) -> {
            if (count > 0) {
                parts.add(count + " " + table);
            }
        });
        return "Cannot delete: referenced by " + parts;
    }

    public boolean hasReferences() {
        return references.values().stream().anyMatch(c -> c > 0);
    }

    public String getMessage() { return message; }
    public Map<String, Long> getReferences() { return references; }

    public static Map<String, Long> depRefsMap(long fileContribs, long buildCustoms, long subOptions,
                                                long compatRules, long templateDeps, long moduleMappings) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("fileContributions", fileContribs);
        m.put("buildCustomizations", buildCustoms);
        m.put("subOptions", subOptions);
        m.put("compatibilityRules", compatRules);
        m.put("starterTemplateDeps", templateDeps);
        m.put("moduleDependencyMappings", moduleMappings);
        return m;
    }
}
