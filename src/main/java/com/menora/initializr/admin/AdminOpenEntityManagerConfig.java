package com.menora.initializr.admin;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Open-in-view for the admin API only. {@code spring.jpa.open-in-view} is off so a generation
 * request does not hold a pooled connection from its first query to the end of the response,
 * but the admin endpoints return entities straight from the repositories — a dependency
 * entry's lazy {@code group}, a template dep's {@code template}, a group's {@code entries} — and
 * Jackson must still be able to read those while writing the response.
 */
@Configuration
public class AdminOpenEntityManagerConfig implements WebMvcConfigurer {

    private final EntityManagerFactory entityManagerFactory;

    public AdminOpenEntityManagerConfig(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
        interceptor.setEntityManagerFactory(entityManagerFactory);
        registry.addWebRequestInterceptor(interceptor).addPathPatterns("/admin/**");
    }
}
