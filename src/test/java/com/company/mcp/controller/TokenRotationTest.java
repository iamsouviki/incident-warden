package com.company.mcp.controller;

import com.company.mcp.model.AppUser;
import com.company.mcp.repository.UserRepository;
import com.company.mcp.service.JwtService;
import com.company.mcp.service.OidcTokenValidator;
import com.company.mcp.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * There is no session table in this deployment, so the refresh token's own expiry IS the
 * session length. That makes one property load-bearing: rotating a refresh token must not
 * move its deadline. Without it, "keep me signed in for 7 days" means "signed in until the
 * browser closes", because the client rotates every half hour and each rotation would hand
 * back another full week.
 */
class TokenRotationTest {

    private static final long ONE_DAY = 24 * 60 * 60 * 1000L;
    private static final long SEVEN_DAYS = 7 * ONE_DAY;

    /**
     * Any string at all: the encoder below is a mock whose {@code matches} always returns
     * true, so this never has to be a real password — and must not be one. A literal that
     * looked like a credential here was one of three findings a secret scanner raised on this
     * repository, all of them the same compiled-in default password.
     */
    private static final String IRRELEVANT_PASSWORD = "any-string-the-mock-encoder-accepts";

    private final UserRepository users = mock(UserRepository.class);
    private final JwtService jwt = new JwtService("test-secret-that-is-comfortably-over-32-bytes");
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final RateLimiterService rateLimiter = mock(RateLimiterService.class);
    private final AuthController controller = new AuthController(
            users, jwt, encoder, mock(OidcTokenValidator.class), rateLimiter,
            mock(com.company.mcp.config.BootstrapPassword.class), "", "tenant-1");

    TokenRotationTest() {
        AppUser user = new AppUser();
        user.setUsername("admin");
        user.setRole("ADMIN");
        user.setTenantId("tenant-1");
        user.setEnabled(true);
        user.setPasswordHash("$2a$10$hash");
        when(users.findByUsername("admin")).thenReturn(Optional.of(user));
        when(encoder.matches(any(), anyString())).thenReturn(true);
        when(rateLimiter.allowLogin(anyString())).thenReturn(true);
    }

    @Test
    void rotatingARefreshTokenKeepsTheDeadlineItWasIssuedWith() {
        Map<String, Object> session = login(true);
        long issuedDeadline = expiryOf((String) session.get("refreshToken"));
        assertThat(issuedDeadline - System.currentTimeMillis())
                .as("keep-me-signed-in mints a 7 day window")
                .isBetween(SEVEN_DAYS - 5_000, SEVEN_DAYS);

        // Two rotations, as a browser open for a working day would do dozens of times.
        Map<String, Object> once = refresh((String) session.get("refreshToken"));
        Map<String, Object> twice = refresh((String) once.get("refreshToken"));

        assertThat(expiryOf((String) once.get("refreshToken"))).isEqualTo(issuedDeadline);
        assertThat(expiryOf((String) twice.get("refreshToken"))).isEqualTo(issuedDeadline);
        assertThat((Long) twice.get("refreshExpiresIn"))
                .as("what is left of the week, not another week")
                .isLessThan(SEVEN_DAYS);
        // Signing in without the box ticked is a one day window, and rotation cannot promote it.
        assertThat(expiryOf((String) refresh((String) login(false).get("refreshToken")).get("refreshToken"))
                - System.currentTimeMillis()).isLessThanOrEqualTo(ONE_DAY);
    }

    @Test
    void anAccessTokenCannotOutliveTheWindowThatMintedIt() {
        String almostDone = jwt.generate("admin",
                Map.of("role", "ADMIN", "tenantId", "tenant-1", "tokenType", "refresh", "rememberMe", true), 30_000);

        Map<String, Object> rotated = refresh(almostDone);

        assertThat(expiryOf((String) rotated.get("token")) - System.currentTimeMillis())
                .as("30s left in the session means at most a 30s access token, not the usual hour")
                .isLessThanOrEqualTo(30_000);
        assertThat((Long) rotated.get("expiresIn")).isLessThanOrEqualTo(30_000L);
    }

    @Test
    void anAccessTokenIsNotAcceptedAsARefreshToken() {
        // The rotation endpoint is the only place a long-lived token is honoured; the reverse
        // swap has to fail too, or a leaked access token would buy a fresh week.
        String access = (String) login(true).get("token");
        assertThat(controller.refresh(Map.of("refreshToken", access)).getStatusCode().value()).isEqualTo(401);
    }

    private Map<String, Object> login(boolean remember) {
        var http = new org.springframework.mock.web.MockHttpServletRequest();
        ResponseEntity<?> res = controller.login(
                Map.of("username", "admin", "password", IRRELEVANT_PASSWORD, "rememberMe", remember), http);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return asMap(res);
    }

    private Map<String, Object> refresh(String refreshToken) {
        ResponseEntity<?> res = controller.refresh(Map.of("refreshToken", refreshToken));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return asMap(res);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(ResponseEntity<?> res) {
        return (Map<String, Object>) res.getBody();
    }

    private long expiryOf(String token) {
        return jwt.parse(token).getExpiration().getTime();
    }
}
