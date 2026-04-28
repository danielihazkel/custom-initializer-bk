package com.menora.initializr.config;

import com.menora.initializr.soap.SoapWizardOptions;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local carrier for WSDL text + per-dep wizard options across the
 * project-generation child context. Structural twin of {@link OpenApiSpecContext}.
 *
 * <p>Populated by {@code WizardStarterController} before invoking the generator
 * and cleared in {@code finally}. Reads (from the contributor beans) return
 * empty collections when the wizard isn't in play.
 */
@Component
public class SoapSpecContext {

    private static final ThreadLocal<Map<String, String>> WSDL =
            ThreadLocal.withInitial(HashMap::new);

    private static final ThreadLocal<Map<String, SoapWizardOptions>> OPTIONS =
            ThreadLocal.withInitial(HashMap::new);

    public void populate(Map<String, String> wsdlByDep, Map<String, SoapWizardOptions> optionsByDep) {
        WSDL.set(wsdlByDep == null ? new HashMap<>() : new HashMap<>(wsdlByDep));
        OPTIONS.set(optionsByDep == null ? new HashMap<>() : new HashMap<>(optionsByDep));
    }

    public void clear() {
        WSDL.remove();
        OPTIONS.remove();
    }

    public Map<String, String> all() {
        return WSDL.get();
    }

    public SoapWizardOptions optionsFor(String depId) {
        return OPTIONS.get().get(depId);
    }

    public boolean isEmpty() {
        Map<String, String> map = WSDL.get();
        if (map.isEmpty()) return true;
        return map.values().stream().allMatch(v -> v == null || v.isBlank());
    }
}
