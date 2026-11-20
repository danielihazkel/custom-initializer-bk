package com.menora.initializr.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Refuses a request whose body is larger than this server takes: 413, before anything reads it.
 *
 * <p>Every endpoint that takes JSON reads the whole body into memory, and nothing bounded it —
 * {@code menora.wizard.max-documents-size} counts a wizard's documents only after the body has
 * been parsed. {@code menora.request.max-body-size} (32 MB unless configured) bounds every
 * request; the admin configuration import, which carries the whole catalog, has
 * {@code menora.request.max-import-size} (100 MB). {@code 0} switches either off.
 *
 * <p>A body that says how long it is ({@code Content-Length}) is refused by that number. One that
 * does not (chunked) is read here up to the limit and handed on from memory — it would be read
 * into memory by the JSON parser anyway — so it is refused the moment it passes the limit.
 *
 * <p>First in the chain: nothing else should run for a request that is going to be refused.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    static final String IMPORT_PATH = "/admin/import";

    private final long maxBytes;
    private final long maxImportBytes;

    public RequestBodyLimitFilter(@Value("${menora.request.max-body-size:32MB}") DataSize max,
                                  @Value("${menora.request.max-import-size:100MB}") DataSize maxImport) {
        this.maxBytes = max.toBytes();
        this.maxImportBytes = maxImport.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean isImport = IMPORT_PATH.equals(pathOf(request));
        long limit = isImport ? maxImportBytes : maxBytes;
        if (limit <= 0) {
            chain.doFilter(request, response);
            return;
        }
        String setting = isImport ? "menora.request.max-import-size" : "menora.request.max-body-size";
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            refuse(response, megabytes(declared) + " MB", limit, setting);
            return;
        }
        if (declared >= 0 || !mayCarryABody(request) || isForm(request)) {
            chain.doFilter(request, response);
            return;
        }
        // No length given: read it, to the limit and one byte more.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        InputStream in = request.getInputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            body.write(buffer, 0, read);
            if (body.size() > limit) {
                refuse(response, "more than " + megabytes(limit) + " MB", limit, setting);
                return;
            }
        }
        chain.doFilter(new BufferedBody(request, body.toByteArray()), response);
    }

    private static String pathOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && path.startsWith(context) ? path.substring(context.length()) : path;
    }

    private static boolean mayCarryABody(HttpServletRequest request) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS") || method.equals("TRACE")) {
            return request.getHeader("Transfer-Encoding") != null;
        }
        return true;
    }

    /**
     * A form's fields are read by the container itself, from the request it knows — which has its
     * own limit for them ({@code server.tomcat.max-http-form-post-size}, 2 MB). Reading the body
     * here would leave it nothing to read.
     */
    private static boolean isForm(HttpServletRequest request) {
        String type = request.getContentType();
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE) || lower.startsWith("multipart/");
    }

    private static void refuse(HttpServletResponse response, String size, long limit, String setting) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // The rest of the body is not going to be read: the connection is of no further use.
        response.setHeader("Connection", "close");
        response.getWriter().write("{\"error\":\"Request too large\",\"detail\":\"The request body is " + size
                + "; this server takes " + megabytes(limit) + " MB (" + setting + ")\"}");
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    /** A request whose body has been read already: it is handed on from memory. */
    private static final class BufferedBody extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("The body is in memory: there is nothing to wait for");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
