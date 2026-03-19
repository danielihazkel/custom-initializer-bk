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
        if (request.getParameter(FORMAT_PARAM) == null) {
            request = new HttpServletRequestWrapper(request) {
                @Override
                public String getParameter(String name) {
                    if (FORMAT_PARAM.equals(name)) return DEFAULT_FORMAT;
                    return super.getParameter(name);
                }

                @Override
                public String[] getParameterValues(String name) {
                    if (FORMAT_PARAM.equals(name)) return new String[]{DEFAULT_FORMAT};
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
            };
        }
        chain.doFilter(request, response);
    }

}
