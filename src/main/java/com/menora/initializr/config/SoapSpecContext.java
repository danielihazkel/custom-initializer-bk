package com.menora.initializr.config;

import com.menora.initializr.soap.SoapWizardOptions;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local carrier for WSDL text + per-dep wizard options across the
 * project-generation child context. Structural twin of {@link OpenApiSpecContext}, plus the
 * files a WSDL refers to (its imported WSDLs and schemas, by relative path).
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

    private static final ThreadLocal<Map<String, Map<String, String>>> FILES =
            ThreadLocal.withInitial(HashMap::new);

    public void populate(Map<String, String> wsdlByDep, Map<String, SoapWizardOptions> optionsByDep) {
        populate(wsdlByDep, null, optionsByDep);
    }

    public void populate(Map<String, String> wsdlByDep, Map<String, Map<String, String>> filesByDep,
                         Map<String, SoapWizardOptions> optionsByDep) {
        WSDL.set(wsdlByDep == null ? new HashMap<>() : new HashMap<>(wsdlByDep));
        OPTIONS.set(optionsByDep == null ? new HashMap<>() : new HashMap<>(optionsByDep));
        FILES.set(filesByDep == null ? new HashMap<>() : new HashMap<>(filesByDep));
    }

    public void clear() {
        WSDL.remove();
        OPTIONS.remove();
        FILES.remove();
    }

    /** The files the WSDL of {@code depId} refers to, by relative path; empty when it is one document. */
    public Map<String, String> filesFor(String depId) {
        Map<String, String> files = FILES.get().get(depId);
        return files == null ? Map.of() : files;
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
