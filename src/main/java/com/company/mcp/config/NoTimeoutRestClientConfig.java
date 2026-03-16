package com.company.mcp.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Ensures provider HTTP calls via Spring AI (Ollama/OpenAI/Anthropic)
 * do not fail early on low-spec machines due to client-side timeouts.
 */
@Configuration
public class NoTimeoutRestClientConfig {

    @Bean
    public RestClientCustomizer noTimeoutRestClientCustomizer() {
        return builder -> {
            ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(Duration.ZERO)
                    .withReadTimeout(Duration.ZERO);
            builder.requestFactory(ClientHttpRequestFactories.get(settings));
        };
    }
}
