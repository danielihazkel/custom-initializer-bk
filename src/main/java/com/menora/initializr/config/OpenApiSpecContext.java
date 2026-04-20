package com.menora.initializr.config;

import com.menora.initializr.openapi.OpenApiWizardOptions;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local carrier for OpenAPI spec text + per-dep wizard options across the
 * project-generation child context. Structural twin of {@link SqlScriptsContext}.
 *
 * <p>Populated by {@code OpenApiStarterController} before invoking the generator
 * and cleared in {@code finally}. Reads (from the contributor beans) return
 * empty collections when the wizard isn't in play.
 */
@Component
public class OpenApiSpecContext {

    private static final ThreadLocal<Map<String, String>> SPEC =
            ThreadLocal.withInitial(HashMap::new);

    private static final ThreadLocal<Map<String, OpenApiWizardOptions>> OPTIONS =
            ThreadLocal.withInitial(HashMap::new);

    public void populate(Map<String, String> specByDep, Map<String, OpenApiWizardOptions> optionsByDep) {
        SPEC.set(specByDep == null ? new HashMap<>() : new HashMap<>(specByDep));
        OPTIONS.set(optionsByDep == null ? new HashMap<>() : new HashMap<>(optionsByDep));
    }

    public void clear() {
        SPEC.remove();
        OPTIONS.remove();
    }

    public Map<String, String> all() {
        return SPEC.get();
    }

    public OpenApiWizardOptions optionsFor(String depId) {
        return OPTIONS.get().get(depId);
    }

    public boolean isEmpty() {
        Map<String, String> map = SPEC.get();
        if (map.isEmpty()) return true;
        return map.values().stream().allMatch(v -> v == null || v.isBlank());
    }
}
