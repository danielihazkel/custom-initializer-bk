package com.menora.initializr;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The one application context the integration tests share: the whole application on a random
 * port (for {@code TestRestTemplate}), MockMvc beside it, and {@link TestInvokerConfiguration}'s
 * invoker. Spring caches a context by its configuration, so every class that says exactly this
 * reuses one context per test JVM instead of starting its own; a class that needs something else
 * (its own properties, an extra controller) says so and pays for a context of its own.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestInvokerConfiguration.class)
public @interface IntegrationTest {
}
