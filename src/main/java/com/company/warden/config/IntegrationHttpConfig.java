package com.company.warden.config;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

/**
 * The one HTTP client every outbound ITSM call goes through.
 *
 * <p>Timeouts are not optional. The durations are tunable because a slow vendor is an operational
 * fact, but there is no switch that turns the ceiling off: an ITSM host that accepts a connection
 * and then stops answering would otherwise pin a request thread for the lifetime of the process.
 *
 * <p>Retries are bounded and restricted to idempotent methods. A failed {@code PATCH} that added a
 * work note may well have been applied before the connection dropped, so replaying it is how one
 * status change becomes three.
 */
@Configuration
public class IntegrationHttpConfig {

    private static final int MAX_RETRIES = 2;
    private static final TimeValue BASE_BACKOFF = TimeValue.ofSeconds(1);
    private static final TimeValue MAX_BACKOFF = TimeValue.ofSeconds(8);

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public IntegrationHttpConfig(
            @Value("${mcp.integrations.http.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${mcp.integrations.http.read-timeout-ms:10000}") int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Bean(name = "integrationRestTemplate")
    public RestTemplate integrationRestTemplate() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setRetryStrategy(idempotentOnlyBackoff())
                .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    /**
     * Bounded exponential backoff for safe methods only.
     *
     * <p>{@link DefaultHttpRequestRetryStrategy} already refuses to replay a non-idempotent method
     * after an {@code IOException}; the status-code path does not know the method, so it is checked
     * here against the request on the context.
     */
    static HttpRequestRetryStrategy idempotentOnlyBackoff() {
        return new DefaultHttpRequestRetryStrategy(MAX_RETRIES, BASE_BACKOFF) {
            @Override
            public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
                return isIdempotent(context) && super.retryRequest(response, execCount, context);
            }

            @Override
            public boolean retryRequest(HttpRequest request, IOException exception, int execCount, HttpContext context) {
                return super.retryRequest(request, exception, execCount, context);
            }

            @Override
            public TimeValue getRetryInterval(HttpResponse response, int execCount, HttpContext context) {
                TimeValue serverAsked = super.getRetryInterval(response, execCount, context);
                long backoffMs = BASE_BACKOFF.toMilliseconds() * (1L << Math.max(0, execCount - 1));
                long ms = Math.min(MAX_BACKOFF.toMilliseconds(), Math.max(serverAsked.toMilliseconds(), backoffMs));
                return TimeValue.ofMilliseconds(ms);
            }
        };
    }

    private static boolean isIdempotent(HttpContext context) {
        HttpRequest request = HttpClientContext.adapt(context).getRequest();
        if (request == null) return false;
        try {
            return Method.normalizedValueOf(request.getMethod()).isIdempotent();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
