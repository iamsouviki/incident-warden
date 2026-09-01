package com.company.mcp.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenRevocationServiceTest {

    private final TokenRevocationService service = new TokenRevocationService(null);

    @Test
    void testTokenRevocationByJti() {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();

        assertThat(service.isRevoked(jti, "alice", now)).isFalse();

        // Revoke token
        service.revokeToken(jti, now.plus(Duration.ofHours(1)));

        assertThat(service.isRevoked(jti, "alice", now)).isTrue();
        assertThat(service.isRevoked(UUID.randomUUID().toString(), "alice", now)).isFalse();
    }

    @Test
    void testUserWideRevocationInvalidatesPriorTokens() {
        String user = "bob";
        Instant t0 = Instant.now().minus(Duration.ofMinutes(5));
        String jti = UUID.randomUUID().toString();

        assertThat(service.isRevoked(jti, user, t0)).isFalse();

        // Admin resets user or disables account
        service.revokeAllUserTokens(user);

        // Prior token is now revoked
        assertThat(service.isRevoked(jti, user, t0)).isTrue();

        // New token issued after revocation timestamp is valid
        Instant tAfter = Instant.now().plus(Duration.ofSeconds(2));
        assertThat(service.isRevoked(UUID.randomUUID().toString(), user, tAfter)).isFalse();
    }
}
