package com.menora.initializr.config;

import com.menora.initializr.db.entity.DependencySubOptionEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Carries the per-request dependency sub-option selections into the project
 * generation child context via a ThreadLocal. The filter populates it before
 * generation starts and clears it afterward.
 *
 * URL param convention: opts-{depId}=opt1,opt2
 * e.g. opts-kafka=consumer-example,producer-example
 */
@Component
public class ProjectOptionsContext {

    private static final String PARAM_PREFIX = "opts-";

    private static final ThreadLocal<Map<String, Set<String>>> OPTIONS =
            ThreadLocal.withInitial(HashMap::new);

    public void populate(HttpServletRequest request) {
        Map<String, Set<String>> options = new HashMap<>();
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.startsWith(PARAM_PREFIX)) {
                String depId = name.substring(PARAM_PREFIX.length());
                String value = request.getParameter(name);
                if (value != null && !value.isBlank()) {
                    options.put(depId, Arrays.stream(value.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toSet()));
                }
            }
        }
        OPTIONS.set(options);
    }

    /** Direct map-based populate for callers that don't have an HttpServletRequest
     *  (e.g. JSON-body POST endpoints). Keys are dep ids, values are the selected option ids. */
    public void populate(Map<String, List<String>> optsByDep) {
        Map<String, Set<String>> normalised = new HashMap<>();
        if (optsByDep != null) {
            for (var e : optsByDep.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    normalised.put(e.getKey(), new HashSet<>(e.getValue()));
                }
            }
        }
        OPTIONS.set(normalised);
    }

    /**
     * After {@link #populate}: for each selected datasource-role dep (one that has BOTH a
     * {@code <dep>-primary} and {@code <dep>-secondary} sub-option) whose custom-prefix YAML
     * needs a hand-written config class, default-select {@code <dep>-primary} when the caller
     * picked neither role. This makes paths that never send a datasource sub-option (e.g. the
     * fullstack endpoint) still generate the {@code DataSource} config class, so the project
     * actually starts.
     *
     * <p>{@code h2} is excluded: its ungated YAML ships a real {@code spring.datasource} block,
     * so it is runnable with no config class (see {@code static-configs/h2/application-h2.yml},
     * pinned by {@code ProjectGenerationIntegrationTests.h2DefaultDoesNotEmitTopLevelH2DatasourceBlock}).
     */
    public void defaultDatasourceRoles(Set<String> selectedDepIds,
                                       Map<String, List<DependencySubOptionEntity>> subOptionsByDep) {
        Map<String, Set<String>> opts = OPTIONS.get();
        for (String depId : selectedDepIds) {
            if ("h2".equals(depId)) continue;                       // spring.datasource fallback exists
            List<DependencySubOptionEntity> subs = subOptionsByDep.getOrDefault(depId, List.of());
            String primary = subs.stream().map(DependencySubOptionEntity::getOptionId)
                    .filter(id -> id.endsWith("-primary")).findFirst().orElse(null);
            boolean hasSecondary = subs.stream()
                    .anyMatch(s -> s.getOptionId().endsWith("-secondary"));
            if (primary == null || !hasSecondary) continue;         // not a datasource-role dep
            Set<String> selected = opts.getOrDefault(depId, Set.of());
            boolean roleChosen = subs.stream()
                    .map(DependencySubOptionEntity::getOptionId)
                    .anyMatch(id -> (id.endsWith("-primary") || id.endsWith("-secondary"))
                            && selected.contains(id));
            if (!roleChosen) {
                opts.computeIfAbsent(depId, k -> new HashSet<>()).add(primary);  // default to @Primary
            }
        }
    }

    public void clear() {
        OPTIONS.remove();
    }

    public boolean hasOption(String depId, String optId) {
        return OPTIONS.get().getOrDefault(depId, Collections.emptySet()).contains(optId);
    }

    /** All sub-option ids selected for {@code depId}; empty set when none. */
    public Set<String> selectedOptions(String depId) {
        return OPTIONS.get().getOrDefault(depId, Collections.emptySet());
    }
}
