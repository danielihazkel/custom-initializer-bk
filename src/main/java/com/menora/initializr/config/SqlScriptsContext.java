package com.menora.initializr.config;

import com.menora.initializr.sql.SqlDepOptions;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local carrier for SQL scripts + per-dep wizard options across the
 * project-generation child context. Structural twin of {@link ProjectOptionsContext}.
 *
 * <p>Populated by {@code WizardStarterController} before invoking the generator
 * and cleared in {@code finally}. Reads (from the contributor beans) return
 * empty collections when the wizard isn't in play.
 */
@Component
public class SqlScriptsContext {

    private static final ThreadLocal<Map<String, String>> SQL =
            ThreadLocal.withInitial(HashMap::new);

    private static final ThreadLocal<Map<String, SqlDepOptions>> OPTIONS =
            ThreadLocal.withInitial(HashMap::new);

    public void populate(Map<String, String> sqlByDep, Map<String, SqlDepOptions> optionsByDep) {
        SQL.set(sqlByDep == null ? new HashMap<>() : new HashMap<>(sqlByDep));
        OPTIONS.set(optionsByDep == null ? new HashMap<>() : new HashMap<>(optionsByDep));
    }

    public void clear() {
        SQL.remove();
        OPTIONS.remove();
    }

    public Map<String, String> all() {
        return SQL.get();
    }

    public SqlDepOptions optionsFor(String depId) {
        return OPTIONS.get().get(depId);
    }

    public boolean isEmpty() {
        Map<String, String> map = SQL.get();
        if (map.isEmpty()) return true;
        return map.values().stream().allMatch(v -> v == null || v.isBlank());
    }
}
