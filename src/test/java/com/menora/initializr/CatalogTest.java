package com.menora.initializr;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The context of the tests that change the catalog itself — the admin API, imports, the seeder
 * and the catalog and template syncs. They reload what the application caches from the catalog,
 * so they keep a context of their own (shared among them), apart from {@link IntegrationTest}'s,
 * whose generation tests read the shipped catalog. MockMvc, no server.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
public @interface CatalogTest {
}
