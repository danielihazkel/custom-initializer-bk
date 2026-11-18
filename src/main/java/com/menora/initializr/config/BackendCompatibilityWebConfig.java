package com.menora.initializr.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Answers a framework-bound generation request whose dependencies conflict
 * ({@link InitializrWebConfiguration} marks it) with the usual 400 — thrown before the handler
 * runs, so {@code GlobalExceptionHandler} words it and the generation audit records it like any
 * other refused request.
 */
@Configuration
public class BackendCompatibilityWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (request.getAttribute(InitializrWebConfiguration.CONFLICT_ATTRIBUTE) instanceof String conflict) {
                    throw new WizardArgumentException(conflict, "dependencies");
                }
                return true;
            }
        });
    }
}
