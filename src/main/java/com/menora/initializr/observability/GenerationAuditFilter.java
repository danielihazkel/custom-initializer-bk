package com.menora.initializr.observability;

import com.menora.initializr.db.entity.GenerationEventEntity;
import com.menora.initializr.db.entity.GenerationEventEntity.Status;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Configuration
public class GenerationAuditFilter {

    private static final Logger log = LoggerFactory.getLogger(GenerationAuditFilter.class);

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> generationAuditFilterRegistration(
            GenerationAuditService auditService,
            @Value("${menora.audit.log-remote-addr:true}") boolean logRemoteAddr) {

        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                String uri = request.getRequestURI();
                if (!isStarterEndpoint(uri)) {
                    chain.doFilter(request, response);
                    return;
                }

                Instant start = Instant.now();
                long startNs = System.nanoTime();
                Status status = Status.SUCCESS;
                String errorMessage = null;

                try {
                    chain.doFilter(request, response);
                    if (response.getStatus() >= 400) {
                        status = Status.FAILURE;
                        errorMessage = "HTTP " + response.getStatus();
                    }
                } catch (RuntimeException | ServletException | IOException ex) {
                    status = Status.FAILURE;
                    errorMessage = trim(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 1024);
                    throw ex;
                } finally {
                    try {
                        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
                        GenerationEventEntity event = GenerationAuditService.newEvent(
                                deriveEndpoint(uri), start, durationMs, status);
                        event.setErrorMessage(errorMessage);
                        event.setArtifactId(request.getParameter("artifactId"));
                        event.setGroupId(request.getParameter("groupId"));
                        event.setBootVersion(request.getParameter("bootVersion"));
                        event.setJavaVersion(request.getParameter("javaVersion"));
                        event.setPackaging(request.getParameter("packaging"));
                        event.setLanguage(request.getParameter("language"));
                        event.setDependencyIds(
                                GenerationAuditService.csv(request.getParameterValues("dependencies")));
                        if (logRemoteAddr) {
                            event.setRemoteAddr(trim(clientIp(request), 64));
                        }
                        auditService.record(event);
                    } catch (Exception auditEx) {
                        log.warn("Failed to record generation audit event", auditEx);
                    }
                }
            }
        });
        reg.addUrlPatterns("/starter*", "/starter.*", "/starter-sql.*", "/starter-multimodule.*");
        // Order=5 runs after AdminAuthFilter (order=1) and after InitializrWebConfiguration (Integer.MIN_VALUE),
        // so timing reflects what the end user sees.
        reg.setOrder(5);
        return reg;
    }

    private static boolean isStarterEndpoint(String uri) {
        if (uri == null) return false;
        return uri.startsWith("/starter");
    }

    private static String deriveEndpoint(String uri) {
        if (uri == null) return "unknown";
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        if (path.startsWith("/")) path = path.substring(1);
        return trim(path, 64);
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
