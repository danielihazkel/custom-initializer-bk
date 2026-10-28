package com.menora.initializr.design;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Headers for the design preview runtime, served as static files from
 * {@code classpath:/static/design-runtime/} (built and copied there by the design-studio repo's
 * {@code npm run publish:runtime}). The layout designer and the Design Studio load it in an
 * {@code <iframe sandbox="allow-scripts allow-forms">}, so it runs in an opaque origin; this policy
 * is the second wall: it may run its own script and the code it compiles ({@code 'unsafe-eval'}),
 * draw blob/data images and fonts, and nothing else — no network ({@code connect-src 'none'}, the
 * generated app's API is answered in memory), no form submission (forms are allowed only so their
 * submit events fire), and only same-origin pages may frame it.
 *
 * <p>Fonts are fetched from an opaque origin, which is a CORS request, hence the
 * {@code Access-Control-Allow-Origin: *} on these public files.
 */
@Component
public class DesignRuntimeFilter extends OncePerRequestFilter {

    public static final String PATH = "/design-runtime/";

    /** The postMessage protocol the shipped runtime speaks; pinned against its
     *  runtime-manifest.json by {@code DesignRuntimeManifestTest}, and against the generator UI's
     *  host copy through the contract fixture. */
    public static final int PROTOCOL_VERSION = 4;

    public static final String CSP = String.join("; ",
            "default-src 'none'",
            "script-src 'self' 'unsafe-eval'",
            "style-src 'self' 'unsafe-inline' blob:",
            "img-src 'self' blob: data:",
            "font-src 'self' blob: data:",
            "connect-src 'none'",
            "form-action 'none'",
            "base-uri 'none'",
            "frame-ancestors 'self'");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Referrer-Policy", "no-referrer");
        // The file names carry no hash: revalidate every time (the ETag makes that a 304).
        response.setHeader("Cache-Control", "no-cache");
        chain.doFilter(request, response);
    }
}
