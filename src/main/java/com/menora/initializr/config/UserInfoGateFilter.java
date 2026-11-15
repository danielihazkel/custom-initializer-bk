package com.menora.initializr.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * The access gate: with {@code menora.access.require-user-header=true}, a request that does not
 * carry the SSO gateway's user header ({@code menora.audit.user-header}, {@code userinfo} unless
 * renamed) is answered 403 and reaches nothing behind it. Off — the default — the filter is not
 * registered at all, so local runs, tests and environments without the gateway are unchanged.
 *
 * <p>{@code menora.access.open-paths} (comma-separated Ant patterns, the actuator endpoints by
 * default) lists what is still answered without the header: Kubernetes probes do not pass the
 * gateway.
 *
 * <p>The header is not proof of anything by itself — whoever can reach this server directly can
 * send it. The gate is as strong as the rule that only the gateway can.
 */
@Configuration
public class UserInfoGateFilter {

    /** What the UI looks for to tell "not signed in through the gateway" from any other 403. */
    public static final String BLOCKED_CODE = "USERINFO_REQUIRED";

    @Bean
    @ConditionalOnProperty(name = "menora.access.require-user-header", havingValue = "true")
    public FilterRegistrationBean<OncePerRequestFilter> userInfoGateFilterRegistration(
            @Value("${menora.audit.user-header:userinfo}") String userHeader,
            @Value("${menora.access.open-paths:/actuator/**}") String[] openPaths) {

        List<String> open = Arrays.stream(openPaths)
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .toList();
        AntPathMatcher matcher = new AntPathMatcher();
        byte[] refusal = ("{\"error\":\"Forbidden\",\"code\":\"" + BLOCKED_CODE + "\","
                + "\"detail\":\"Missing '" + jsonEscape(userHeader) + "' header\"}")
                .getBytes(StandardCharsets.UTF_8);

        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                String user = request.getHeader(userHeader);
                if ((user != null && !user.isBlank()) || isOpen(request)) {
                    chain.doFilter(request, response);
                    return;
                }
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setHeader("Cache-Control", "no-store");
                response.setContentLength(refusal.length);
                response.getOutputStream().write(refusal);
            }

            private boolean isOpen(HttpServletRequest request) {
                String path = pathOf(request);
                for (String pattern : open) {
                    if (matcher.match(pattern, path)) return true;
                }
                return false;
            }
        });
        reg.addUrlPatterns("/*");
        // Before AdminAuthFilter (1) and GenerationAuditFilter (5): a refused call is neither an
        // admin 401 nor a failed generation in the audit.
        reg.setOrder(0);
        return reg;
    }

    /**
     * The path the container resolved the request to. Not {@code getRequestURI()}: that is the
     * line as the client sent it, so {@code /actuator/../metadata/client} would match an open
     * pattern while being served as {@code /metadata/client}.
     */
    private static String pathOf(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        return (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
