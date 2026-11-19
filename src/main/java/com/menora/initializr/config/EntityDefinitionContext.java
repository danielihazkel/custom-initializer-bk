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
    /** The layout's shell shows who is signed in: the backend answers GET /api/me without a workflow. */
    private static final ThreadLocal<Boolean> SHELL_USER = ThreadLocal.withInitial(() -> false);
    /** Set by the backend contributor when it wrote a Flyway baseline; read by the update endpoint. */
    private static final ThreadLocal<com.menora.initializr.fullstack.schema.SchemaMigrations.Generated> SCHEMA =
            new ThreadLocal<>();

    public void populate(List<EntityDefinition> entities, String backendSetKey, String frontendSetKey,
                         String domainPackage) {
        ENTITIES.set(entities == null ? List.of() : List.copyOf(entities));
        BACKEND_KEY.set(backendSetKey);
        FRONTEND_KEY.set(frontendSetKey);
        DOMAIN_PACKAGE.set(domainPackage);
        SHELL_USER.set(false);
    }

    /** As above, saying whether the frontend's shell asks who is signed in (the backend render
     *  path never sees the layout, so what it needs of it travels here). */
    public void populate(List<EntityDefinition> entities, String backendSetKey, String frontendSetKey,
                         String domainPackage, boolean shellUser) {
        populate(entities, backendSetKey, frontendSetKey, domainPackage);
        SHELL_USER.set(shellUser);
    }

    public void clear() {
        ENTITIES.remove();
        BACKEND_KEY.remove();
        FRONTEND_KEY.remove();
        DOMAIN_PACKAGE.remove();
        SHELL_USER.remove();
        SCHEMA.remove();
        // The database the request's names were rendered for goes with the request.
        com.menora.initializr.fullstack.SchemaNames.clearDatabase();
    }

    /** The schema the generation on this thread wrote a migration for, or null when it wrote none. */
    public com.menora.initializr.fullstack.schema.SchemaMigrations.Generated generatedSchema() {
        return SCHEMA.get();
    }

    public void setGeneratedSchema(com.menora.initializr.fullstack.schema.SchemaMigrations.Generated schema) {
        SCHEMA.set(schema);
    }

    public boolean shellUser() {
        return Boolean.TRUE.equals(SHELL_USER.get());
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
