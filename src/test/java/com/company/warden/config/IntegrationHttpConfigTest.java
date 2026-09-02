package com.company.warden.config;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two properties of the outbound client that are safety properties, not preferences.
 *
 * <p>The timeouts are tunable but not switchable off, so there is no "disabled" case to test — the
 * assertion is that a template is built at all, and that the retry policy underneath it will not
 * replay a request that may already have changed a ticket.
 */
class IntegrationHttpConfigTest {

    private final HttpRequestRetryStrategy retry = IntegrationHttpConfig.idempotentOnlyBackoff();

    @Test
    void aTemplateIsBuiltWithTheConfiguredTimeouts() {
        RestTemplate restTemplate = new IntegrationHttpConfig(3000, 8000).integrationRestTemplate();

        assertThat(restTemplate).isNotNull();
        assertThat(restTemplate.getRequestFactory()).isNotNull();
    }

    /**
     * The defect this pins: a 503 on a PATCH that added a work note may have been applied before the
     * response was lost, so replaying it turns one status change into three.
     */
    @Test
    void aRetryableStatusIsNotReplayedForANonIdempotentMethod() {
        assertThat(retry.retryRequest(throttled(), 1, contextFor(Method.POST))).isFalse();
        assertThat(retry.retryRequest(throttled(), 1, contextFor(Method.PATCH))).isFalse();
    }

    @Test
    void aRetryableStatusIsReplayedForASafeMethod() {
        assertThat(retry.retryRequest(throttled(), 1, contextFor(Method.GET))).isTrue();
    }

    /** Bounded: the interval cannot grow past the ceiling however many attempts have been made. */
    @Test
    void theBackoffIsBounded() {
        for (int attempt = 1; attempt <= 20; attempt++) {
            assertThat(retry.getRetryInterval(throttled(), attempt, contextFor(Method.GET)).toMilliseconds())
                    .as("attempt %d", attempt)
                    .isBetween(1000L, 8000L);
        }
    }

    private static HttpResponse throttled() {
        return new BasicHttpResponse(503);
    }

    private static HttpClientContext contextFor(Method method) {
        HttpClientContext context = HttpClientContext.create();
        context.setAttribute(HttpCoreContext.HTTP_REQUEST, new BasicHttpRequest(method, "/api/now/table/incident"));
        return context;
    }
}
