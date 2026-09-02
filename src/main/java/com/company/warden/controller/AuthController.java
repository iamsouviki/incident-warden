package com.company.warden.controller;

import com.company.warden.model.AppUser;
import com.company.warden.repository.UserRepository;
import com.company.warden.service.JwtService;
import com.company.warden.service.NotificationService;
import com.company.warden.service.OidcTokenValidator;
import com.company.warden.service.RateLimiterService;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final long ACCESS_TTL = 30 * 60 * 1000L; // 30 mins
    private static final long REFRESH_TTL = 3 * 60 * 60 * 1000L; // 3 hr sliding window
    private static final long SESSION_REFRESH_TTL = REFRESH_TTL;

    private static final Set<String> ALLOWED_ROLES = Set.of("VIEWER", "ANALYST", "ADMIN", "OWNER");

    /**
     * Short, because a length rule people route around with "Password1" buys
     * nothing.
     */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository users;
    private final JwtService jwtService;
    private final PasswordEncoder encoder;
    private final OidcTokenValidator oidc;
    private final RateLimiterService rateLimiter;
    private final com.company.warden.service.TokenRevocationService tokenRevocationService;
    private final Set<String> ssoAllowedDomains;

    public AuthController(UserRepository users, JwtService jwtService, PasswordEncoder encoder, OidcTokenValidator oidc,
            RateLimiterService rateLimiter,
            com.company.warden.service.TokenRevocationService tokenRevocationService,
            @Value("${mcp.sso.allowed-email-domains:}") String ssoAllowedDomains) {
        this.users = users;
        this.jwtService = jwtService;
        this.encoder = encoder;
        this.oidc = oidc;
        this.rateLimiter = rateLimiter;
        this.tokenRevocationService = tokenRevocationService;
        this.ssoAllowedDomains = java.util.Arrays.stream(ssoAllowedDomains.split(","))
                .map(String::trim).map(String::toLowerCase).filter(d -> !d.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** POST /api/auth/login { username, password } */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body,
            jakarta.servlet.http.HttpServletRequest http) {
        String username = (String) body.getOrDefault("username", "");
        String password = (String) body.getOrDefault("password", "");

        if (username.isBlank() || password.isBlank())
            return ResponseEntity.status(400).body(Map.of("error", "Username and password required"));

        if (!rateLimiter.allowLogin(username.trim().toLowerCase()) || !rateLimiter.allowLogin(http.getRemoteAddr()))
            return ResponseEntity.status(429)
                    .body(Map.of("error", "Too many sign-in attempts. Try again in a minute."));

        AppUser user = users.findByUsername(username.trim()).orElse(null);
        if (user == null || !user.isEnabled())
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));

        if (user.getSsoProvider() == null) {
            if (user.getPasswordHash() == null || !encoder.matches(password, user.getPasswordHash()))
                return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        } else {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "This account signs in through your identity provider"));
        }
        rateLimiter.reset(username.trim().toLowerCase());

        String role = user.getRole();
        String token = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tokenType", "access"), ACCESS_TTL);
        String refreshToken = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tokenType", "refresh"), REFRESH_TTL);

        return ResponseEntity.ok(Map.ofEntries(
                Map.entry("token", token),
                Map.entry("refreshToken", refreshToken),
                Map.entry("username", user.getUsername()),
                Map.entry("fullName", user.getFullName() != null ? user.getFullName() : user.getUsername()),
                Map.entry("role", role),
                Map.entry("department", user.getDepartment() != null ? user.getDepartment() : ""),
                Map.entry("expiresIn", ACCESS_TTL),
                Map.entry("refreshExpiresIn", REFRESH_TTL),
                // The client blocks on this until POST /api/auth/password succeeds. Reported at
                // sign-in rather than discovered later, because the whole point of the flag is
                // that the password an admin read out loud does not survive first use.
                Map.entry("mustChangePassword", user.isMustChangePassword())));
    }

    /**
     * POST /api/auth/sso { idToken }
     */
    @PostMapping("/sso")
    public ResponseEntity<?> sso(@RequestBody Map<String, Object> body) {
        if (!oidc.enabled())
            return ResponseEntity.status(503).body(Map.of("error", "SSO is not enabled on this deployment"));

        String idToken = (String) body.get("idToken");
        io.jsonwebtoken.Claims providerClaims;
        try {
            providerClaims = oidc.verify(idToken);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }

        String email = String.valueOf(providerClaims.getOrDefault("email", "")).trim().toLowerCase();
        String subject = providerClaims.getSubject();
        if (email.isBlank() || !email.contains("@"))
            return ResponseEntity.status(401).body(Map.of("error", "Provider token carries no usable email claim"));
        if (!ssoAllowedDomains.isEmpty() && !ssoAllowedDomains.contains(email.substring(email.indexOf('@') + 1)))
            return ResponseEntity.status(403).body(Map.of("error", "This email domain is not permitted to sign in"));

        AppUser user = users.findByUsername(email).orElseGet(() -> {
            AppUser u = new AppUser();
            u.setUsername(email);
            u.setEmail(email);
            u.setRole("VIEWER");
            u.setSsoProvider(providerClaims.getIssuer());
            u.setSsoSubject(subject);
            u.setEnabled(true);
            return users.save(u);
        });

        if (!user.isEnabled())
            return ResponseEntity.status(403).body(Map.of("error", "Account disabled"));
        if (user.getSsoSubject() != null && !user.getSsoSubject().equals(subject))
            return ResponseEntity.status(403)
                    .body(Map.of("error", "This account is bound to a different provider identity"));

        String token = jwtService.generate(user.getUsername(),
                Map.of("role", user.getRole(),
                        "ssoProvider", providerClaims.getIssuer(), "tokenType", "access"),
                ACCESS_TTL);
        String refreshToken = jwtService.generate(user.getUsername(),
                Map.of("role", user.getRole(),
                        "ssoProvider", providerClaims.getIssuer(), "tokenType", "refresh", "rememberMe", false),
                SESSION_REFRESH_TTL);

        return ResponseEntity.ok(Map.of(
                "token", token,
                "refreshToken", refreshToken,
                "username", user.getUsername(),
                "fullName", user.getFullName() != null ? user.getFullName() : user.getUsername(),
                "role", user.getRole(),
                "department", user.getDepartment() != null ? user.getDepartment() : "",
                "expiresIn", ACCESS_TTL,
                "refreshExpiresIn", SESSION_REFRESH_TTL));
    }

    /** POST /api/auth/refresh { refreshToken } */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String old = body.get("refreshToken");
        if (old == null || old.isBlank())
            old = body.get("token");
        if (old == null || !jwtService.isRefreshToken(old))
            return ResponseEntity.status(401).body(Map.of("error", "Refresh token invalid or expired"));

        var claims = jwtService.parse(old);
        AppUser user = users.findByUsername(claims.getSubject()).orElse(null);
        if (user == null || !user.isEnabled())
            return ResponseEntity.status(401).body(Map.of("error", "Account is no longer active"));

        // Sliding window: while the user is actively using the application, mint a
        // fresh
        // 30-minute access token and extend the refresh token by 3 hours.
        String role = user.getRole();
        String fresh = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tokenType", "access"), ACCESS_TTL);
        String rotatedRefresh = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tokenType", "refresh"), REFRESH_TTL);
        return ResponseEntity.ok(Map.of(
                "token", fresh,
                "refreshToken", rotatedRefresh,
                "role", role,
                "fullName", user.getFullName() != null ? user.getFullName() : user.getUsername(),
                "department", user.getDepartment() != null ? user.getDepartment() : "",
                "expiresIn", ACCESS_TTL,
                "refreshExpiresIn", REFRESH_TTL));
    }

    /** POST /api/auth/logout — Revoke current access token and session */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                    @RequestBody(required = false) Map<String, String> body) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtService.isValid(token)) {
                try {
                    Claims claims = jwtService.parse(token);
                    tokenRevocationService.revokeToken(
                            claims.getId(),
                            claims.getExpiration() != null ? claims.getExpiration().toInstant() : null);
                } catch (Exception ignored) {}
            }
        }
        if (body != null && body.containsKey("refreshToken")) {
            String rToken = body.get("refreshToken");
            if (jwtService.isValid(rToken)) {
                try {
                    Claims rClaims = jwtService.parse(rToken);
                    tokenRevocationService.revokeToken(
                            rClaims.getId(),
                            rClaims.getExpiration() != null ? rClaims.getExpiration().toInstant() : null);
                } catch (Exception ignored) {}
            }
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    /** GET /api/auth/me */
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        if (!jwtService.isValid(token))
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        var claims = jwtService.parse(token);
        AppUser user = users.findByUsername(claims.getSubject()).orElse(null);
        if (user == null)
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));

        return ResponseEntity.ok(Map.of(
                "username", user.getUsername(),
                "fullName", user.getFullName() != null ? user.getFullName() : user.getUsername(),
                "email", user.getEmail() != null ? user.getEmail() : "",
                "role", user.getRole(),
                "department", user.getDepartment() != null ? user.getDepartment() : "",
                "ssoProvider", user.getSsoProvider() != null ? user.getSsoProvider() : ""));
    }

    /** GET /api/auth/users — Admin view of workspace users */
    @GetMapping("/users")
    public ResponseEntity<?> getUsers(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        if (!jwtService.isValid(token))
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        List<AppUser> list = users.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AppUser u : list) {
            result.add(Map.of(
                    "id", u.getId() != null ? u.getId().toString() : "",
                    "username", u.getUsername(),
                    "fullName", u.getFullName() != null ? u.getFullName() : u.getUsername(),
                    "email", u.getEmail() != null ? u.getEmail() : "",
                    "role", u.getRole(),
                    "department", u.getDepartment() != null ? u.getDepartment() : "",
                    "enabled", u.isEnabled(),
                    "mustChangePassword", u.isMustChangePassword()));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/auth/users — Admin creation of a new user. An omitted password
     * falls back to the username, and the response states which password was actually
     * set so the admin has something to hand over.
     */
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> body) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        if (!jwtService.isValid(token))
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        var claims = jwtService.parse(token);
        String callerRole = (String) claims.getOrDefault("role", "");
        if (!"OWNER".equalsIgnoreCase(callerRole)) {
            return ResponseEntity.status(403).body(Map.of("error", "Only workspace owners can create new user accounts"));
        }

        String username = body.getOrDefault("username", "").trim();
        String fullName = body.getOrDefault("fullName", "").trim();
        String email = body.getOrDefault("email", "").trim();
        String role = body.getOrDefault("role", "VIEWER").trim().toUpperCase();
        String department = body.getOrDefault("department", "").trim();
        String password = body.getOrDefault("password", "").trim();
        // Handed over, not chosen — so the account is flagged and the first sign-in has
        // to
        // replace it. An admin who types a password in is doing the same thing by hand,
        // so
        // that case is flagged too: neither of them is a password its owner picked.
        if (username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        }
        if (password.isBlank()) password = username;
        // An account with no usable address is an account no incident notification can
        // ever
        // reach — and the UI shows it as a person who was told. Same rule the sender
        // applies.
        if (!NotificationService.isSendableAddress(email)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "A valid email address is required, or this user can never be notified about an incident."));
        }
        // Refuse an unrecognised role rather than quietly filing the person as a
        // VIEWER:
        // an admin who asked for ANALYST and silently got read-only finds out when that
        // person cannot approve anything, which reads as a broken permission system.
        if (!ALLOWED_ROLES.contains(role)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unknown role '" + role + "'. Choose one of " + ALLOWED_ROLES));
        }
        if (users.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username already exists"));
        }

        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setUsername(username);
        u.setFullName(fullName.isBlank() ? username : fullName);
        u.setEmail(email);
        u.setRole(role);
        u.setDepartment(department);
        u.setPasswordHash(encoder.encode(password));
        u.setMustChangePassword(true);
        u.setEnabled(true);
        AppUser saved = users.save(u);

        return ResponseEntity.ok(Map.of(
                "message", "User created. Give them this password — they will be asked to replace it "
                        + "the first time they sign in.",
                "defaultPassword", password,
                "username", saved.getUsername(),
                "role", saved.getRole(),
                "fullName", saved.getFullName()));
    }

    /**
     * PUT /api/auth/users/{username} — change a role, or switch an account off.
     *
     * Disabling rather than deleting: an incident, a plan and an approval all
     * reference the
     * username that raised them, so removing the row would leave an audit trail
     * pointing at
     * nobody. A disabled account cannot sign in, which is the part that matters.
     */
    @PutMapping("/users/{username}")
    public ResponseEntity<?> updateUser(@RequestHeader("Authorization") String authHeader,
            @PathVariable String username,
            @RequestBody Map<String, Object> body) {
        var caller = requireAdmin(authHeader);
        if (caller.error() != null)
            return caller.error();

        AppUser u = users.findByUsername(username).orElse(null);
        if (u == null)
            return ResponseEntity.status(404).body(Map.of("error", "No such user in this workspace"));

        if (body.get("role") != null) {
            String role = String.valueOf(body.get("role")).trim().toUpperCase();
            if (!ALLOWED_ROLES.contains(role))
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Unknown role '" + role + "'. Choose one of " + ALLOWED_ROLES));
            // Checked before anything is written: an admin who demotes their own account
            // has
            // locked the workspace out of its own settings page with no way back but the
            // database. Same reason the disable below refuses.
            if (!"ADMIN".equalsIgnoreCase(role) && !"OWNER".equalsIgnoreCase(role) && u.getUsername().equals(caller.username()))
                return ResponseEntity.badRequest().body(Map.of("error",
                        "You cannot remove your own admin or owner role — ask another administrator to do it."));
            u.setRole(role);
        }
        if (body.get("enabled") != null) {
            boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
            if (!enabled && u.getUsername().equals(caller.username()))
                return ResponseEntity.badRequest().body(Map.of("error",
                        "You cannot disable the account you are signed in with."));
            u.setEnabled(enabled);
            if (!enabled) {
                tokenRevocationService.revokeAllUserTokens(username);
            }
        }

        u.setUpdatedAt(OffsetDateTime.now());
        users.save(u);
        return ResponseEntity.ok(Map.of("message", u.getUsername() + " updated.",
                "role", u.getRole(), "enabled", u.isEnabled()));
    }

    /**
     * POST /api/auth/users/{username}/reset-password — back to the starter, and
     * flagged again.
     */
    @PostMapping("/users/{username}/reset-password")
    public ResponseEntity<?> resetPassword(@RequestHeader("Authorization") String authHeader,
            @PathVariable String username) {
        var caller = requireAdmin(authHeader);
        if (caller.error() != null)
            return caller.error();

        AppUser u = users.findByUsername(username).orElse(null);
        if (u == null)
            return ResponseEntity.status(404).body(Map.of("error", "No such user in this workspace"));

        u.setPasswordHash(encoder.encode(u.getUsername()));
        u.setMustChangePassword(true);
        u.setUpdatedAt(OffsetDateTime.now());
        users.save(u);
        tokenRevocationService.revokeAllUserTokens(username);
        return ResponseEntity.ok(Map.of(
                "message", "Password reset. " + u.getUsername() + " signs in with this and is asked "
                        + "to replace it immediately.",
                "defaultPassword", u.getUsername()));
    }

    /**
     * POST /api/auth/password — the signed-in user replaces their own password.
     *
     * Open to every role, including VIEWER, on purpose: this is the one write a
     * read-only
     * account must be able to make, and it is also the only way out of the
     * forced-reset state.
     * The current password is required even though the caller already holds a valid
     * token, so a
     * stolen token cannot be turned into a permanent takeover of the account.
     */
    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> body) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        if (!jwtService.isValid(token))
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        AppUser u = users.findByUsername(jwtService.parse(token).getSubject()).orElse(null);
        if (u == null || !u.isEnabled())
            return ResponseEntity.status(401).body(Map.of("error", "Account is no longer active"));
        if (u.getPasswordHash() == null)
            return ResponseEntity.badRequest().body(Map.of("error",
                    "This account signs in through your identity provider and has no password here."));

        String current = body.getOrDefault("currentPassword", "");
        String next = body.getOrDefault("newPassword", "");
        if (!encoder.matches(current, u.getPasswordHash()))
            return ResponseEntity.badRequest().body(Map.of("error", "Current password is incorrect"));
        if (next.length() < MIN_PASSWORD_LENGTH)
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Choose a password of at least " + MIN_PASSWORD_LENGTH + " characters."));
        if (next.equals(u.getUsername()) || encoder.matches(next, u.getPasswordHash()))
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Choose a password that is not the one you were given."));

        u.setPasswordHash(encoder.encode(next));
        u.setMustChangePassword(false);
        u.setUpdatedAt(OffsetDateTime.now());
        users.save(u);
        tokenRevocationService.revokeAllUserTokens(u.getUsername());
        return ResponseEntity.ok(Map.of("message", "Password updated.", "mustChangePassword", false));
    }

    /** Token parsed once, role checked once, for every admin-only route above. */
    private Caller requireAdmin(String authHeader) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        if (!jwtService.isValid(token))
            return new Caller(null, ResponseEntity.status(401).body(Map.of("error", "Unauthorized")));
        var claims = jwtService.parse(token);
        String role = (String) claims.getOrDefault("role", "");
        if (!"ADMIN".equalsIgnoreCase(role) && !"OWNER".equalsIgnoreCase(role))
            return new Caller(null, ResponseEntity.status(403)
                    .body(Map.of("error", "Only administrators and owners can manage users")));
        return new Caller(claims.getSubject(), null);
    }

    private record Caller(String username, ResponseEntity<?> error) {
    }
}
