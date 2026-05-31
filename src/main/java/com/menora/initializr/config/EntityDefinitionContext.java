package com.menora.initializr.config;

import com.menora.initializr.fullstack.EntityDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Thread-local carrier for fullstack entity definitions + the selected template set keys.
 * Structural twin of {@link SqlScriptsContext} and {@link OpenApiSpecContext}.
 *
 * <p>Populated by {@code FullstackStarterController} before invoking the generator and
 * cleared in {@code finally}. The {@code EntityScaffoldContributor} short-circuits when
 * empty so legacy {@code /starter.zip} flows are unaffected.
 */
@Component
public class EntityDefinitionContext {

    private static final ThreadLocal<List<EntityDefinition>> ENTITIES =
            ThreadLocal.withInitial(List::of);
    private static final ThreadLocal<String> BACKEND_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> FRONTEND_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> DOMAIN_PACKAGE = new ThreadLocal<>();

    public void populate(List<EntityDefinition> entities, String backendSetKey, String frontendSetKey,
                         String domainPackage) {
        ENTITIES.set(entities == null ? List.of() : List.copyOf(entities));
        BACKEND_KEY.set(backendSetKey);
        FRONTEND_KEY.set(frontendSetKey);
        DOMAIN_PACKAGE.set(domainPackage);
    }

    public void clear() {
        ENTITIES.remove();
        BACKEND_KEY.remove();
        FRONTEND_KEY.remove();
        DOMAIN_PACKAGE.remove();
    }

    public List<EntityDefinition> all() {
        return ENTITIES.get();
    }

    public String getBackendSetKey() {
        return BACKEND_KEY.get();
    }

    public String getFrontendSetKey() {
        return FRONTEND_KEY.get();
    }

    public String getDomainPackage() {
        return DOMAIN_PACKAGE.get();
    }

    public boolean isEmpty() {
        List<EntityDefinition> list = ENTITIES.get();
        return list == null || list.isEmpty();
    }
}
