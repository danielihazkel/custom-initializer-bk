package com.menora.initializr.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Order(Integer.MIN_VALUE)
public class InitializrWebConfiguration extends OncePerRequestFilter {

    private static final String FORMAT_PARAM = "configurationFileFormat";
    private static final String DEFAULT_FORMAT = "properties";
    private static final String BOOT_VERSION_PARAM = "bootVersion";
    private static final String DEPENDENCIES_PARAM = "dependencies";

    /**
     * The endpoints the framework binds a {@code WebProjectRequest} for from query parameters —
     * where the compatibility rules ({@link BackendCompatibility}) are applied here, since no code
     * of ours builds those requests. (The JSON and multi-module endpoints apply them themselves,
     * through {@link ProjectRequestFactory}; the frontend endpoint has its own rules.)
     */
    private static final Set<String> FRAMEWORK_BOUND = Set.of(
            "/starter.zip", "/starter.tgz", "/pom.xml", "/pom", "/build.gradle", "/build", "/starter.preview");

    /**
     * Set on a request whose dependencies conflict; {@link BackendCompatibilityWebConfig} turns it
     * into the 400 (a filter's own error would bypass the error handler and the generation audit).
     */
    static final String CONFLICT_ATTRIBUTE = InitializrWebConfiguration.class.getName() + ".dependencyConflict";

    private final ProjectOptionsContext optionsContext;
    private final SqlScriptsContext sqlContext;
    private final OpenApiSpecContext specContext;
    private final SoapSpecContext soapContext;
    private final EventsSpecContext eventsContext;
    private final EntityDefinitionContext entityContext;
    private final BackendCompatibility compatibility;

    public InitializrWebConfiguration(ProjectOptionsContext optionsContext,
                                      SqlScriptsContext sqlContext,
                                      OpenApiSpecContext specContext,
                                      SoapSpecContext soapContext,
                                      EventsSpecContext eventsContext,
                                      EntityDefinitionContext entityContext,
                                      BackendCompatibility compatibility) {
        this.optionsContext = optionsContext;
        this.sqlContext = sqlContext;
        this.specContext = specContext;
        this.soapContext = soapContext;
        this.eventsContext = eventsContext;
        this.entityContext = entityContext;
        this.compatibility = compatibility;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        optionsContext.populate(request);
        String[] dependencies = resolveDependencies(request);
        try {
        chain.doFilter(new HttpServletRequestWrapper(request) {

            // Always inject configurationFileFormat default if absent.
            @Override
            public String getParameter(String name) {
                if (FORMAT_PARAM.equals(name) && super.getParameter(name) == null) return DEFAULT_FORMAT;
                if (BOOT_VERSION_PARAM.equals(name)) return normalizeBootVersion(super.getParameter(name));
                if (DEPENDENCIES_PARAM.equals(name) && dependencies != null) return dependencies[0];
                return super.getParameter(name);
            }

            @Override
            public String[] getParameterValues(String name) {
                if (FORMAT_PARAM.equals(name) && super.getParameter(name) == null) return new String[]{DEFAULT_FORMAT};
                if (BOOT_VERSION_PARAM.equals(name)) {
                    String[] vals = super.getParameterValues(name);
                    if (vals == null) return null;
                    return new String[]{normalizeBootVersion(vals[0])};
                }
                if (DEPENDENCIES_PARAM.equals(name) && dependencies != null) return dependencies.clone();
                return super.getParameterValues(name);
            }

            @Override
            public Map<String, String[]> getParameterMap() {
                Map<String, String[]> map = new LinkedHashMap<>(super.getParameterMap());
                map.putIfAbsent(FORMAT_PARAM, new String[]{DEFAULT_FORMAT});
                map.computeIfPresent(BOOT_VERSION_PARAM, (k, v) -> new String[]{normalizeBootVersion(v[0])});
                if (dependencies != null) map.put(DEPENDENCIES_PARAM, dependencies.clone());
                return Collections.unmodifiableMap(map);
            }

            @Override
            public Enumeration<String> getParameterNames() {
                List<String> names = Collections.list(super.getParameterNames());
                if (!names.contains(FORMAT_PARAM)) names.add(FORMAT_PARAM);
                return Collections.enumeration(names);
            }

            /**
             * Sanitize X-Forwarded-Port: the initializr concatenates this header value
             * directly into the app URL string. If the header is absent, Java null
             * concatenation produces "8080null". Return an empty string instead so the
             * concatenation resolves to the bare port number (e.g. "8080").
             */
            @Override
            public String getHeader(String name) {
                if ("X-Forwarded-Port".equalsIgnoreCase(name)) {
                    String value = super.getHeader(name);
                    if (value == null) return "";
                    try {
                        Integer.parseInt(value.trim());
                        return value.trim();
                    } catch (NumberFormatException e) {
                        return ""; // e.g. Vite sends literal "null" — ignore it
                    }
                }
                return super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                if ("X-Forwarded-Port".equalsIgnoreCase(name)) {
                    String sanitized = getHeader(name);
                    return Collections.enumeration(
                            sanitized.isEmpty() ? Collections.emptyList() : Collections.singletonList(sanitized)
                    );
                }
                return super.getHeaders(name);
            }

        }, response);
        } finally {
            clearAllContexts();
        }
    }

    /**
     * Unconditional backstop for every generation ThreadLocal. Controllers clear their own
     * contexts on the happy path, but no single controller covers all six, and an exception
     * that escapes to the {@code @ControllerAdvice} clears none of them — a leftover context
     * would then be scaffolded into the next request served by this pooled thread.
     */
    private void clearAllContexts() {
        com.menora.initializr.gen.WizardMemo.close();
        optionsContext.clear();
        sqlContext.clear();
        specContext.clear();
        soapContext.clear();
        eventsContext.clear();
        entityContext.clear();
    }

    /**
     * The {@code dependencies} a framework-bound endpoint should see: with what they require added,
     * or null to leave the parameter as sent (another endpoint, no dependencies, nothing to add, or
     * a conflict — which is marked on the request for the 400).
     */
    private String[] resolveDependencies(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!FRAMEWORK_BOUND.contains(path)) return null;
        String[] raw = request.getParameterValues(DEPENDENCIES_PARAM);
        if (raw == null) return null;
        List<String> asked = Arrays.stream(raw)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .toList();
        String boot = request.getParameter(BOOT_VERSION_PARAM);
        BackendCompatibility.Outcome outcome = compatibility.resolve(asked, boot == null ? null : normalizeBootVersion(boot));
        if (outcome.conflict() != null) {
            request.setAttribute(CONFLICT_ATTRIBUTE, outcome.conflict());
            return null;
        }
        return outcome.dependencies().equals(asked) ? null : new String[]{String.join(",", outcome.dependencies())};
    }

    private static String normalizeBootVersion(String v) {
        return BootVersions.normalize(v);
    }

}
