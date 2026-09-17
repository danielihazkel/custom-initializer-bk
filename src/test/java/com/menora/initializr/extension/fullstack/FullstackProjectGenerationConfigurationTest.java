package com.menora.initializr.extension.fullstack;

import io.spring.initializr.generator.version.Version;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the Boot-version switch behind the generated slice test's mock annotation. */
class FullstackProjectGenerationConfigurationTest {

    @Test
    void mockBeanBeforeBoot34() {
        assertThat(FullstackProjectGenerationConfiguration.usesMockitoBean(Version.parse("3.2.1"))).isFalse();
        assertThat(FullstackProjectGenerationConfiguration.usesMockitoBean(Version.parse("3.3.5"))).isFalse();
        assertThat(FullstackProjectGenerationConfiguration.usesMockitoBean(null)).isFalse();
    }

    @Test
    void mockitoBeanFromBoot34() {
        assertThat(FullstackProjectGenerationConfiguration.usesMockitoBean(Version.parse("3.4.0"))).isTrue();
        assertThat(FullstackProjectGenerationConfiguration.usesMockitoBean(Version.parse("3.5.2"))).isTrue();
        assertThat(FullstackProjectGenerationConfiguration.usesMockitoBean(Version.parse("4.0.0"))).isTrue();
    }
}
