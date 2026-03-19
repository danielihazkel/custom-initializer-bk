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
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(Integer.MIN_VALUE)
public class InitializrWebConfiguration extends OncePerRequestFilter {

    private static final String FORMAT_PARAM = "configurationFileFormat";
    private static final String DEFAULT_FORMAT = "properties";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new HttpServletRequestWrapper(request) {

            // Always inject configurationFileFormat default if absent.
            @Override
            public String getParameter(String name) {
                if (FORMAT_PARAM.equals(name) && super.getParameter(name) == null) return DEFAULT_FORMAT;
                return super.getParameter(name);
            }

            @Override
            public String[] getParameterValues(String name) {
                if (FORMAT_PARAM.equals(name) && super.getParameter(name) == null) return new String[]{DEFAULT_FORMAT};
                return super.getParameterValues(name);
            }

            @Override
            public Map<String, String[]> getParameterMap() {
                Map<String, String[]> map = new LinkedHashMap<>(super.getParameterMap());
                map.putIfAbsent(FORMAT_PARAM, new String[]{DEFAULT_FORMAT});
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
    }

}
