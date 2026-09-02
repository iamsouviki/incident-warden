package com.company.warden.controller;

import com.company.warden.model.AppUser;
import com.company.warden.repository.UserRepository;
import com.company.warden.service.JwtService;
import com.company.warden.service.OidcTokenValidator;
import com.company.warden.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An account an admin created starts on its own username as its password, so the only thing
 * keeping that acceptable is that it cannot survive first use. These tests are that guarantee:
 * the flag is set when the account is made, sign-in reports it, and the account cannot satisfy
 * the forced reset by setting the username straight back.
 *
 * The username is over eight characters on purpose: a shorter one would be rejected by the length
 * rule first, and the reuse rule below would never be reached.
 *
 * A real BCrypt encoder, not a mock — every assertion here is about whether a specific password
 * matches a specific hash, which a mock that always answers true cannot tell you.
 */
class UserAdminTest {

    private static final String NEW_USER = "priya.mehta";

    private final Map<String, AppUser> table = new HashMap<>();
    private final UserRepository users = mock(UserRepository.class);
    private final JwtService jwt = new JwtService("test-secret-that-is-comfortably-over-32-bytes");
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);   // 4 rounds: a test, not a login
    private final RateLimiterService rateLimiter = mock(RateLimiterService.class);
    private final com.company.warden.service.TokenRevocationService tokenRevocationService = new com.company.warden.service.TokenRevocationService(null);
    private final AuthController controller = new AuthController(
            users, jwt, encoder, mock(OidcTokenValidator.class), rateLimiter, tokenRevocationService, "");

    UserAdminTest() {
        AppUser owner = new AppUser();
        owner.setUsername("owner");
        owner.setRole("OWNER");
        owner.setEnabled(true);
        owner.setPasswordHash(encoder.encode("an-owner-password-nobody-published"));
        table.put("owner", owner);

        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        admin.setPasswordHash(encoder.encode("an-admin-password-nobody-published"));
        table.put("admin", admin);

        when(users.findByUsername(anyString())).thenAnswer(i -> Optional.ofNullable(table.get(i.getArgument(0))));
        when(users.save(any())).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            table.put(u.getUsername(), u);
            return u;
        });
        when(rateLimiter.allowLogin(anyString())).thenReturn(true);
    }

    @Test
    void anOwnerCanCreateAccountAndHandStarterPassword() {
        Map<String, Object> created = asMap(controller.createUser(ownerToken(), Map.of(
                "username", NEW_USER, "email", "priya.mehta@company.com", "role", "ANALYST")));

        assertThat(created.get("defaultPassword")).isEqualTo(NEW_USER);
        assertThat(table.get(NEW_USER).isMustChangePassword()).isTrue();
        assertThat(encoder.matches(NEW_USER, table.get(NEW_USER).getPasswordHash()))
                .as("the password the owner reads out is the one that works")
                .isTrue();

        // Sign-in has to say so, or the client never knows to block.
        var http = new org.springframework.mock.web.MockHttpServletRequest();
        Map<String, Object> session = asMap(controller.login(
                Map.of("username", NEW_USER, "password", NEW_USER), http));
        assertThat(session.get("mustChangePassword")).isEqualTo(true);
    }

    @Test
    void anAdminCannotCreateAccountOnlyOwnerCan() {
        ResponseEntity<?> resp = controller.createUser(adminToken(), Map.of(
                "username", NEW_USER, "email", "priya.mehta@company.com", "role", "ANALYST"));
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void theForcedResetCannotBeSatisfiedByTheStarterPasswordItself() {
        controller.createUser(ownerToken(), Map.of(
                "username", NEW_USER, "email", "priya.mehta@company.com", "role", "ANALYST"));
        String token = accessTokenFor(NEW_USER, "ANALYST");

        ResponseEntity<?> reuse = controller.changePassword(token, Map.of(
                "currentPassword", NEW_USER,
                "newPassword", NEW_USER));
        assertThat(reuse.getStatusCode().value()).isEqualTo(400);
        assertThat(table.get(NEW_USER).isMustChangePassword()).as("still locked out of the app").isTrue();

        ResponseEntity<?> tooShort = controller.changePassword(token, Map.of(
                "currentPassword", NEW_USER, "newPassword", "short"));
        assertThat(tooShort.getStatusCode().value()).isEqualTo(400);

        // A wrong current password is 400, never 401: the client signs the user out on 401, and
        // being signed out by a typo mid-reset is a lockout with no way back.
        assertThat(controller.changePassword(token, Map.of(
                "currentPassword", "not-the-one-they-were-given", "newPassword", "a-password-of-their-own"))
                .getStatusCode().value()).isEqualTo(400);

        ResponseEntity<?> ok = controller.changePassword(token, Map.of(
                "currentPassword", NEW_USER, "newPassword", "a-password-of-their-own"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(table.get(NEW_USER).isMustChangePassword()).isFalse();
        assertThat(encoder.matches("a-password-of-their-own", table.get(NEW_USER).getPasswordHash())).isTrue();
    }

    @Test
    void anAdminCannotLockTheWorkspaceOutOfItsOwnSettings() {
        assertThat(controller.updateUser(adminToken(), "admin", Map.of("enabled", false))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.updateUser(adminToken(), "admin", Map.of("role", "ANALYST"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(table.get("admin").getRole()).as("nothing was written on the way out").isEqualTo("ADMIN");
        assertThat(table.get("admin").isEnabled()).isTrue();

        // Someone else's account is fair game, and a reset flags it again.
        controller.createUser(ownerToken(), Map.of(
                "username", NEW_USER, "email", "priya.mehta@company.com", "role", "ANALYST"));
        controller.changePassword(accessTokenFor(NEW_USER, "ANALYST"), Map.of(
                "currentPassword", NEW_USER, "newPassword", "a-password-of-their-own"));
        assertThat(table.get(NEW_USER).isMustChangePassword()).isFalse();

        assertThat(controller.resetPassword(adminToken(), NEW_USER).getStatusCode().value()).isEqualTo(200);
        assertThat(table.get(NEW_USER).isMustChangePassword()).isTrue();
        assertThat(controller.updateUser(adminToken(), NEW_USER, Map.of("enabled", false))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(table.get(NEW_USER).isEnabled()).isFalse();
    }

    @Test
    void onlyAnAdminOrOwnerReachesTheUserRoutes() {
        String analyst = accessTokenFor("someone", "ANALYST");
        assertThat(controller.updateUser(analyst, "admin", Map.of("role", "ANALYST"))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(controller.resetPassword(analyst, "admin").getStatusCode().value()).isEqualTo(403);
        assertThat(controller.createUser(analyst, Map.of(
                "username", NEW_USER, "email", "priya.mehta@company.com", "role", "ADMIN"))
                .getStatusCode().value()).isEqualTo(403);
    }

    private String ownerToken() {
        return accessTokenFor("owner", "OWNER");
    }

    private String adminToken() {
        return accessTokenFor("admin", "ADMIN");
    }

    private String accessTokenFor(String username, String role) {
        return "Bearer " + jwt.generate(username,
                Map.of("role", role, "tokenType", "access"), 60_000);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(ResponseEntity<?> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (Map<String, Object>) res.getBody();
    }
}
