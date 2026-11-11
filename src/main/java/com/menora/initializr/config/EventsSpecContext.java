package com.menora.initializr.config;

import com.menora.initializr.events.EventsWizardOptions;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local carrier for AsyncAPI document text + per-dep wizard options across the
 * project-generation child context. Structural twin of {@link OpenApiSpecContext}.
 *
 * <p>Populated by {@code WizardStarterController} before invoking the generator and cleared in
 * {@code finally}; {@code InitializrWebConfiguration} clears it too, as the backstop for every
 * generation context. Reads return empty collections when the wizard isn't in play.
 */
@Component
public class EventsSpecContext {

    private static final ThreadLocal<Map<String, String>> DOCUMENTS =
            ThreadLocal.withInitial(HashMap::new);

    private static final ThreadLocal<Map<String, EventsWizardOptions>> OPTIONS =
            ThreadLocal.withInitial(HashMap::new);

    public void populate(Map<String, String> documentByDep, Map<String, EventsWizardOptions> optionsByDep) {
        DOCUMENTS.set(documentByDep == null ? new HashMap<>() : new HashMap<>(documentByDep));
        OPTIONS.set(optionsByDep == null ? new HashMap<>() : new HashMap<>(optionsByDep));
    }

    public void clear() {
        DOCUMENTS.remove();
        OPTIONS.remove();
    }

    public Map<String, String> all() {
        return DOCUMENTS.get();
    }

    public EventsWizardOptions optionsFor(String depId) {
        return OPTIONS.get().get(depId);
    }

    public boolean isEmpty() {
        Map<String, String> map = DOCUMENTS.get();
        if (map.isEmpty()) return true;
        return map.values().stream().allMatch(v -> v == null || v.isBlank());
    }
}
