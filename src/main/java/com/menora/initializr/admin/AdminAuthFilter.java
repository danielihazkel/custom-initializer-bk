package com.menora.initializr.admin;

import com.menora.initializr.admin.history.CatalogChangeContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Everything under {@code /admin} needs a valid admin token, but signing in. The token names its
 * admin: the name is put on the request ({@link #USER_ATTRIBUTE}) and a catalog change context
 * is opened for the request, so every change it makes is recorded as theirs.
 */
@Configuration
public class AdminAuthFilter {

    /** The request attribute that holds the signed-in admin's name. */
    public static final String USER_ATTRIBUTE = "menora.admin.user";

    /** Open to anyone: signing in, and what the login screen may offer. */
    private static final Set<String> OPEN = Set.of("/admin/login", "/admin/login/sso", "/admin/login/options");

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> adminAuthFilterRegistration(AdminTokenStore tokenStore) {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                String path = request.getRequestURI();
                if (OPEN.contains(path)) {
                    chain.doFilter(request, response);
                    return;
                }

                String authHeader = request.getHeader("Authorization");
                Optional<String> user = authHeader != null && authHeader.startsWith("Bearer ")
                        ? tokenStore.userOf(authHeader.substring(7)) : Optional.empty();
                if (user.isPresent()) {
                    request.setAttribute(USER_ATTRIBUTE, user.get());
                    CatalogChangeContext.open(user.get(), "admin");
                    try {
                        chain.doFilter(request, response);
                    } finally {
                        CatalogChangeContext.close();
                    }
                    return;
                }

                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Unauthorized\"}");
            }
        });
        reg.addUrlPatterns("/admin/*");
        reg.setOrder(1);
        return reg;
    }
}
