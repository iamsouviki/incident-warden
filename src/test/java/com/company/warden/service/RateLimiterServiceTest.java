package com.company.warden.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterServiceTest {

    @Test
    void testSlidingWindowLimit() {
        RateLimiterService limiter = new RateLimiterService(3, 5, null);
        String user = "testuser";

        assertThat(limiter.allowLogin(user)).isTrue(); // hit 1
        assertThat(limiter.allowLogin(user)).isTrue(); // hit 2
        assertThat(limiter.allowLogin(user)).isTrue(); // hit 3
        assertThat(limiter.allowLogin(user)).isFalse(); // hit 4 exceeds limit 3

        // Reset clears history
        limiter.reset(user);
        assertThat(limiter.allowLogin(user)).isTrue();
    }

    @Test
    void rejectsNewKeysWhenTheFallbackStoreIsFull() {
        RateLimiterService limiter = new RateLimiterService(1, 1, null);

        for (int index = 0; index < 10_000; index++) {
            assertThat(limiter.allow("key-" + index, 1)).isTrue();
        }

        assertThat(limiter.allow("key-over-cap", 1)).isFalse();
    }
}
