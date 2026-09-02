package com.company.warden.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Puts a ceiling on every outbound {@code RestClient} call.
 *
 * Spring Boot's autoconfigured {@code RestClient.Builder} has no read timeout, and the JDK
 * HTTP client it wraps waits forever by default. Both {@code RagService} (the LLM provider)
 * and {@code IncidentService} (ServiceNow/Freshservice) inject that shared builder, so a
 * provider that accepts a request and then never answers pins a Tomcat worker thread for the
 * lifetime of the process. Observed live: two chat requests to a hosted provider logged
 * "[RAG] Routing chat query" and then nothing — no answer, no exception, and the operator
 * sees a spinner that never resolves. Enough of those and the thread pool is gone.
 *
 * Fixed here rather than at each call site because the builder is the one thing they share:
 * a timeout added in RagService alone leaves the ticket-sync path still able to hang.
 *
 * ponytail: two fixed durations, no properties. READ is deliberately generous — a large
 * RAG prompt against a slow model legitimately takes over a minute, and a timeout that
 * fires during normal generation is a worse bug than the one being fixed. If a provider
 * ever needs its own budget, give RagService its own builder rather than making this
 * per-caller configurable.
 */
@Configuration
public class OutboundHttpConfig {

    private static final Duration CONNECT = Duration.ofSeconds(10);
    private static final Duration READ = Duration.ofSeconds(120);

    @Bean
    public RestClientCustomizer outboundTimeouts() {
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(CONNECT).withReadTimeout(READ)));
    }
}
