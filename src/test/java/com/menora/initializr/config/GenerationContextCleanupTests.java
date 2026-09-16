package com.menora.initializr.config;

import com.menora.initializr.fullstack.EntityDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The five generation ThreadLocals are populated per request and read by the generation
 * child context. No single controller clears all five, and an exception escaping to the
 * {@code @ControllerAdvice} clears none — so a leftover context would be scaffolded into
 * the next request served by the same pooled Tomcat thread.
 *
 * <p>{@link InitializrWebConfiguration} is the unconditional backstop; these tests drive the
 * filter directly so the guarantee is pinned independently of any controller.
 */
@SpringBootTest
class GenerationContextCleanupTests {

    @Autowired private InitializrWebConfiguration filter;
    @Autowired private ProjectOptionsContext optionsContext;
    @Autowired private SqlScriptsContext sqlContext;
    @Autowired private OpenApiSpecContext specContext;
    @Autowired private SoapSpecContext soapContext;
    @Autowired private EntityDefinitionContext entityContext;

    @Test
    void filterClearsEveryContextAfterASuccessfulRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/starter.zip"),
                new MockHttpServletResponse(),
                (req, res) -> populateEverything());

        assertAllContextsEmpty();
    }

    @Test
    void filterClearsEveryContextWhenTheRequestFails() {
        try {
            filter.doFilter(new MockHttpServletRequest("POST", "/starter-wizard.zip"),
                    new MockHttpServletResponse(),
                    (req, res) -> {
                        populateEverything();
                        // Stands in for anything thrown past the controller's own finally —
                        // e.g. a DB failure between populate and the try block.
                        throw new IllegalStateException("boom");
                    });
        } catch (Exception expected) {
            // swallowed on purpose: the point is what the filter's finally did
        }

        assertAllContextsEmpty();
    }

    private void populateEverything() {
        optionsContext.populate(Map.of("kafka", List.of("consumer-example")));
        sqlContext.populate(Map.of("postgresql", "CREATE TABLE t (id BIGINT PRIMARY KEY);"), Map.of());
        specContext.populate(Map.of("openapi", "openapi: 3.0.0"), Map.of());
        soapContext.populate(Map.of("web-services", "<wsdl:definitions/>"), Map.of());
        entityContext.populate(List.<EntityDefinition>of(), "spring-jpa-crud", "react-tailwind-crud", "com.menora.demo");
    }

    private void assertAllContextsEmpty() {
        assertThat(optionsContext.selectedOptions("kafka")).as("optionsContext").isEmpty();
        assertThat(sqlContext.isEmpty()).as("sqlContext").isTrue();
        assertThat(specContext.isEmpty()).as("specContext").isTrue();
        assertThat(soapContext.isEmpty()).as("soapContext").isTrue();
        assertThat(entityContext.getDomainPackage()).as("entityContext domain package").isNull();
    }
}
