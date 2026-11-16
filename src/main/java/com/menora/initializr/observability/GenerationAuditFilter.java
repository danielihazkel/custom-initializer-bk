package com.menora.initializr.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.db.entity.GenerationEventEntity;
import com.menora.initializr.db.entity.GenerationEventEntity.Status;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Records every generation request — a ZIP, a patch or a preview — as a
 * {@link GenerationEventEntity}: what was asked for (the request's parameters, or for a JSON
 * request what its controller resolved, {@link AuditDetails}), what kind of project, by whom, how
 * long it took and, when it failed, why (the error response's own message).
 */
@Configuration
public class GenerationAuditFilter {

    private static final Logger log = LoggerFactory.getLogger(GenerationAuditFilter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** How much of a response is kept to read an error's message from. */
    private static final int CAPTURE = 4096;

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> generationAuditFilterRegistration(
            GenerationAuditService auditService,
            @Value("${menora.audit.log-remote-addr:true}") boolean logRemoteAddr,
            @Value("${menora.audit.user-header:userinfo}") String userHeader) {

        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                String uri = request.getRequestURI();
                if (!isGeneration(uri) || isLivePreview(request, uri)) {
                    chain.doFilter(request, response);
                    return;
                }

                Instant start = Instant.now();
                long startNs = System.nanoTime();
                Status status = Status.SUCCESS;
                String errorMessage = null;
                CapturingResponse captured = new CapturingResponse(response);

                try {
                    chain.doFilter(request, captured);
                    if (captured.getStatus() >= 400) {
                        status = Status.FAILURE;
                        errorMessage = trim(errorMessageOf(captured.getStatus(), captured.captured()), 1024);
                    }
                } catch (RuntimeException | ServletException | IOException ex) {
                    status = Status.FAILURE;
                    errorMessage = trim(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 1024);
                    throw ex;
                } finally {
                    // A body written through the writer reaches the response whatever happened.
                    captured.flushWriter();
                    try {
                        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
                        String endpoint = deriveEndpoint(uri);
                        GenerationEventEntity event = GenerationAuditService.newEvent(endpoint, start, durationMs, status);
                        event.setErrorMessage(errorMessage);
                        event.setArtifactId(param(request, "artifactId", "projectName"));
                        event.setGroupId(param(request, "groupId", "scope"));
                        event.setBootVersion(request.getParameter("bootVersion"));
                        event.setJavaVersion(request.getParameter("javaVersion"));
                        event.setPackaging(request.getParameter("packaging"));
                        event.setLanguage(request.getParameter("language"));
                        event.setDependencyIds(GenerationAuditService.csv(request.getParameterValues("dependencies")));
                        event.setDepartment(trim(request.getParameter("department"), 64));
                        AuditDetails details = AuditDetails.of(request);
                        if (details != null) apply(details, event);
                        event.setAction(actionOf(endpoint));
                        event.setKind(kindOf(endpoint, details));
                        if (logRemoteAddr) {
                            event.setRemoteAddr(trim(clientIp(request), 64));
                        }
                        event.setUsername(trim(request.getHeader(userHeader), 255));
                        auditService.record(event);
                    } catch (Exception auditEx) {
                        log.warn("Failed to record generation audit event", auditEx);
                    }
                }
            }
        });
        // Map to every request and let isGeneration() decide. Servlet URL patterns only
        // support /path/*, *.ext, / and exact strings — a pattern like "/starter*" is treated as
        // an exact match and never matches "/starter.zip", so the earlier narrow mapping meant the
        // filter never fired and the audit stayed empty.
        reg.addUrlPatterns("/*");
        // Order=5 runs after AdminAuthFilter (order=1) and after InitializrWebConfiguration (Integer.MIN_VALUE),
        // so timing reflects what the end user sees.
        reg.setOrder(5);
        return reg;
    }

    /** What the controller resolved wins over the parameters (a JSON request has none). */
    private static void apply(AuditDetails d, GenerationEventEntity event) {
        if (d.request != null) {
            var r = d.request;
            if (r.getArtifactId() != null) event.setArtifactId(trim(r.getArtifactId(), 255));
            if (r.getGroupId() != null) event.setGroupId(trim(r.getGroupId(), 255));
            if (r.getBootVersion() != null) event.setBootVersion(trim(r.getBootVersion(), 32));
            if (r.getJavaVersion() != null) event.setJavaVersion(trim(r.getJavaVersion(), 16));
            if (r.getPackaging() != null) event.setPackaging(trim(r.getPackaging(), 16));
            if (r.getLanguage() != null) event.setLanguage(trim(r.getLanguage(), 16));
            if (r.getDependencies() != null) event.setDependencyIds(GenerationAuditService.csv(r.getDependencies()));
        }
        if (d.dependencies != null) event.setDependencyIds(GenerationAuditService.csv(d.dependencies));
        if (d.department != null) event.setDepartment(trim(d.department, 64));
        if (!d.wizards.isEmpty()) event.setWizards(trim(String.join(",", d.wizards), 64));
        if (d.entityCount != null) event.setEntityCount(d.entityCount);
    }

    /**
     * A generation endpoint: a ZIP, a patch or a preview, of the backend generators (at the root)
     * or the frontend one ({@code /frontend/…}). The wizards' detection calls are not generations.
     */
    static boolean isGeneration(String uri) {
        if (uri == null) return false;
        if (!uri.startsWith("/starter") && !uri.startsWith("/frontend/starter")) return false;
        return uri.endsWith(".zip") || uri.endsWith(".patch") || uri.endsWith(".preview");
    }

    static String actionOf(String endpoint) {
        if (endpoint.endsWith(".zip")) return "download";
        if (endpoint.endsWith(".patch")) return "patch";
        if (endpoint.endsWith(".preview")) return "preview";
        return "other";
    }

    /**
     * The kind of project: from the endpoint, except that the wizard endpoint without a wizard
     * document is a plain backend, and a patch is the kind its model says.
     */
    static String kindOf(String endpoint, AuditDetails details) {
        if (details != null && details.kind != null) return details.kind;
        String e = endpoint == null ? "" : endpoint;
        if (e.startsWith("frontend/")) return "frontend";
        if (e.startsWith("starter-fullstack")) return "fullstack";
        if (e.startsWith("starter-multimodule")) return "multimodule";
        if (e.startsWith("starter-wizard") || e.equals("starter.patch")) {
            if (details == null) return e.startsWith("starter-wizard") ? "wizard" : "backend";
            return details.wizards.isEmpty() ? "backend" : "wizard";
        }
        return "backend";
    }

    /**
     * Why a request failed, as its error response says: {@code detail}, {@code messages} or
     * {@code message}/{@code error} of a JSON body, else the text, else the status.
     */
    public static String errorMessageOf(int status, byte[] body) {
        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{")) {
            try {
                JsonNode node = JSON.readTree(text);
                List<String> parts = new ArrayList<>();
                if (node.path("detail").isTextual()) parts.add(node.path("detail").asText());
                if (node.path("messages").isArray()) node.path("messages").forEach(m -> parts.add(m.asText()));
                if (parts.isEmpty() && node.path("message").isTextual()) parts.add(node.path("message").asText());
                if (parts.isEmpty() && node.path("error").isTextual()) parts.add(node.path("error").asText());
                String side = node.path("side").isTextual() ? node.path("side").asText() + ": " : "";
                if (!parts.isEmpty()) return "HTTP " + status + " — " + side + String.join("; ", parts);
            } catch (IOException notJson) {
                // a body cut at the capture limit, or not JSON: the text below
            }
        }
        if (!text.isEmpty() && !text.startsWith("<") && !text.startsWith("{")) return "HTTP " + status + " — " + text;
        return "HTTP " + status;
    }

    /**
     * A preview the UI fetched by itself to keep its file tree current (the Backend tab's file
     * dock sends {@code X-Preview-Intent: live} on every refresh). Nobody asked for those, so
     * they are not activity. Only a {@code .preview} endpoint can opt out — a generation is
     * always recorded, whatever header it carries.
     */
    private static boolean isLivePreview(HttpServletRequest request, String uri) {
        return uri != null && uri.endsWith(".preview")
                && "live".equalsIgnoreCase(request.getHeader("X-Preview-Intent"));
    }

    /** First non-blank of the {@code primary} then {@code fallback} request param. */
    private static String param(HttpServletRequest request, String primary, String fallback) {
        String v = request.getParameter(primary);
        if (v != null && !v.isBlank()) return v;
        String f = request.getParameter(fallback);
        return (f != null && !f.isBlank()) ? f : null;
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

    /**
     * Passes the response through untouched and keeps a copy of its first {@link #CAPTURE} bytes —
     * an error body is short, and a ZIP's first bytes cost nothing to keep.
     */
    private static final class CapturingResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream copy = new ByteArrayOutputStream();
        private ServletOutputStream stream;
        private PrintWriter writer;

        CapturingResponse(HttpServletResponse response) {
            super(response);
        }

        byte[] captured() {
            flushWriter();
            return copy.toByteArray();
        }

        void flushWriter() {
            if (writer != null) writer.flush();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                ServletOutputStream target = super.getOutputStream();
                stream = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return target.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        target.setWriteListener(listener);
                    }

                    @Override
                    public void write(int b) throws IOException {
                        target.write(b);
                        if (copy.size() < CAPTURE) copy.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        target.write(b, off, len);
                        int room = CAPTURE - copy.size();
                        if (room > 0) copy.write(b, off, Math.min(room, len));
                    }

                    @Override
                    public void flush() throws IOException {
                        target.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        target.close();
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                String encoding = getCharacterEncoding() == null ? StandardCharsets.UTF_8.name() : getCharacterEncoding();
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), encoding), true);
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) writer.flush();
            super.flushBuffer();
        }
    }
}
