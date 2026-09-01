package com.company.mcp.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Configures HTTP client settings for outbound ITSM integration calls.
 * Timeouts are turned off by default (timeout-enabled: false) and can be configured via environment properties.
 */
@Configuration
public class IntegrationHttpConfig {

    private final boolean timeoutEnabled;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public IntegrationHttpConfig(
            @Value("${mcp.integrations.http.timeout-enabled:false}") boolean timeoutEnabled,
            @Value("${mcp.integrations.http.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${mcp.integrations.http.read-timeout-ms:10000}") int readTimeoutMs) {
        this.timeoutEnabled = timeoutEnabled;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Bean(name = "integrationRestTemplate")
    public RestTemplate integrationRestTemplate() {
        if (!timeoutEnabled) {
            return new RestTemplate();
        }

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();

        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return new RestTemplate(requestFactory);
    }
}
