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
 * Validates the 30-minute access token and 3-hour sliding refresh token lifecycle.
 */
class TokenRotationTest {

    private static final long THREE_HOURS = 3 * 60 * 60 * 1000L;
    private static final long THIRTY_MINS = 30 * 60 * 1000L;
    private static final String IRRELEVANT_PASSWORD = "any-string-the-mock-encoder-accepts";

    private final UserRepository users = mock(UserRepository.class);
    private final JwtService jwt = new JwtService("test-secret-that-is-comfortably-over-32-bytes");
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final RateLimiterService rateLimiter = mock(RateLimiterService.class);
    private final com.company.mcp.service.TokenRevocationService tokenRevocationService = new com.company.mcp.service.TokenRevocationService(null);
    private final AuthController controller = new AuthController(
            users, jwt, encoder, mock(OidcTokenValidator.class), rateLimiter,
            mock(com.company.mcp.config.BootstrapPassword.class), tokenRevocationService, "", "tenant-1");

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
    void loginMintsThirtyMinuteAccessAndThreeHourRefreshToken() {
        Map<String, Object> session = login(false);
        long issuedDeadline = expiryOf((String) session.get("refreshToken"));
        assertThat(issuedDeadline - System.currentTimeMillis())
                .as("login mints a 3 hour sliding refresh window")
                .isBetween(THREE_HOURS - 5_000, THREE_HOURS);

        assertThat(expiryOf((String) session.get("token")) - System.currentTimeMillis())
                .as("login mints a 30 minute access token")
                .isBetween(THIRTY_MINS - 5_000, THIRTY_MINS);
    }

    @Test
    void rotatingARefreshTokenRenewsTheThreeHourWindowForActiveUsers() {
        Map<String, Object> session = login(false);
        Map<String, Object> once = refresh((String) session.get("refreshToken"));

        assertThat(expiryOf((String) once.get("refreshToken")) - System.currentTimeMillis())
                .as("refresh extends sliding window by 3 hours")
                .isBetween(THREE_HOURS - 5_000, THREE_HOURS);

        assertThat(expiryOf((String) once.get("token")) - System.currentTimeMillis())
                .as("refresh mints new 30-minute access token")
                .isBetween(THIRTY_MINS - 5_000, THIRTY_MINS);
    }

    @Test
    void anAccessTokenIsNotAcceptedAsARefreshToken() {
        String access = (String) login(false).get("token");
        ResponseEntity<?> result = controller.refresh(Map.of("refreshToken", access));
        assertThat(result.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void logoutRevokesTokens() {
        Map<String, Object> session = login(false);
        String access = (String) session.get("token");
        String refresh = (String) session.get("refreshToken");

        io.jsonwebtoken.Claims claims = jwt.parse(access);
        assertThat(tokenRevocationService.isRevoked(claims.getId(), "admin", claims.getIssuedAt().toInstant())).isFalse();

        ResponseEntity<?> response = controller.logout("Bearer " + access, Map.of("refreshToken", refresh));
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        assertThat(tokenRevocationService.isRevoked(claims.getId(), "admin", claims.getIssuedAt().toInstant())).isTrue();
    }

    private Map<String, Object> login(boolean rememberMe) {
        jakarta.servlet.http.HttpServletRequest req = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        ResponseEntity<?> resp = controller.login(Map.of("username", "admin", "password", IRRELEVANT_PASSWORD), req);
        return (Map<String, Object>) resp.getBody();
    }

    private Map<String, Object> refresh(String refreshToken) {
        ResponseEntity<?> resp = controller.refresh(Map.of("refreshToken", refreshToken));
        return (Map<String, Object>) resp.getBody();
    }

    private long expiryOf(String jwtString) {
        return jwt.parse(jwtString).getExpiration().getTime();
    }
}
