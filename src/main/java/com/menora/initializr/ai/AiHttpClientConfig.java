package com.menora.initializr.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Wires the {@link AiProperties} binding and exposes a {@link RestClient}
 * configured with the AI endpoint's timeout. Auth header is added per-request
 * by {@link AiFileGenerationService} so a blank {@code authHeaderValue} sends
 * no header at all.
 *
 * <p>{@code RestClient} is the standard Spring 6.1 HTTP client — not an
 * AI-specific SDK. Swapping endpoints needs only YAML changes.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiHttpClientConfig {

    @Bean
    public RestClient aiRestClient(AiProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds()));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
