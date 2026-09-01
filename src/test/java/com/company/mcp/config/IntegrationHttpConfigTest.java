package com.company.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationHttpConfigTest {

    @Test
    void testIntegrationRestTemplateWhenTimeoutDisabled() {
        IntegrationHttpConfig config = new IntegrationHttpConfig(false, 5000, 10000);
        RestTemplate restTemplate = config.integrationRestTemplate();
        assertThat(restTemplate).isNotNull();
    }

    @Test
    void testIntegrationRestTemplateWhenTimeoutEnabled() {
        IntegrationHttpConfig config = new IntegrationHttpConfig(true, 3000, 8000);
        RestTemplate restTemplate = config.integrationRestTemplate();
        assertThat(restTemplate).isNotNull();
        assertThat(restTemplate.getRequestFactory()).isNotNull();
    }
}
