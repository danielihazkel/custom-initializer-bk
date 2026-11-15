package com.menora.initializr.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the shipped {@code application.yml} must say — the tests run on their own properties, so
 * nothing else would notice one of these being switched back.
 */
class ShippedConfigurationTest {

    @SuppressWarnings("unchecked")
    private static Object setting(String... path) throws IOException {
        Object node;
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            node = new Yaml().load(in);
        }
        for (String key : path) {
            assertThat(node).as(String.join(".", path)).isInstanceOf(Map.class);
            node = ((Map<String, Object>) node).get(key);
        }
        return node;
    }

    @Test
    void theH2ConsoleIsOff() throws IOException {
        // A SQL prompt on the catalog database, user "sa" with no password, outside the admin login.
        assertThat(setting("spring", "h2", "console", "enabled")).isEqualTo(false);
    }

    @Test
    void wrongAdminPasswordsAreLimited() throws IOException {
        assertThat((Integer) setting("admin", "login", "max-failures")).isPositive();
        assertThat(setting("admin", "login", "window")).isNotNull();
    }

    @Test
    void theWizardsDocumentsHaveASizeLimit() throws IOException {
        DataSize shipped = DataSize.parse(String.valueOf(setting("menora", "wizard", "max-documents-size")));
        assertThat(shipped.toBytes()).isBetween(1L, DataSize.ofMegabytes(20).toBytes());

        WizardDocumentLimit limit = new WizardDocumentLimit(DataSize.ofKilobytes(1));
        limit.check("x".repeat(1024));
        limit.check((String) null);
        limit.check(Map.of("a", "x".repeat(600)), null, Map.of("b", "x".repeat(424)));
        assertThatThrownBy(() -> limit.check("x".repeat(1025)))
                .isInstanceOf(WizardArgumentException.class)
                .hasMessageContaining("menora.wizard.max-documents-size");
        // Together, not each: two documents under the limit can be over it.
        assertThatThrownBy(() -> limit.check(Map.of("a", "x".repeat(600)), Map.of("b", "x".repeat(600))))
                .isInstanceOf(WizardArgumentException.class);
    }
}
