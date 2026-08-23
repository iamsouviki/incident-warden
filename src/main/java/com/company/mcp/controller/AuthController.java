package com.company.mcp.controller;

import com.company.mcp.model.AppUser;
import com.company.mcp.repository.UserRepository;
import com.company.mcp.service.JwtService;
import com.company.mcp.service.NotificationService;
import com.company.mcp.service.OidcTokenValidator;
import com.company.mcp.service.RateLimiterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final long ACCESS_TTL = 60 * 60 * 1000L;                 // 1h
    private static final long SESSION_REFRESH_TTL = 24 * 60 * 60 * 1000L;   // 1d
    private static final long REMEMBER_REFRESH_TTL = 7 * 24 * 60 * 60 * 1000L; // 7d

    private static final Set<String> ALLOWED_ROLES = Set.of("VIEWER", "ANALYST", "ADMIN");

    /**
     * The one password a new account starts with — including the seeded admin, whose hash
     * is set to this same value by changelog 1.20.
     *
     * There used to be two defaults: admin123 for the seed and michaels@1 here. An operator
     * who read the create-user dialog and then tried to sign in as admin got 401 and
     * reported the login as broken. Returned in the create response too, so the UI states
     * whatever this constant is rather than repeating the literal.
     */
    public static final String DEFAULT_PASSWORD = "michaels@1";

    private final UserRepository users;
    private final JwtService jwtService;
    private final PasswordEncoder encoder;
    private final OidcTokenValidator oidc;
    private final RateLimiterService rateLimiter;
    private final Set<String> ssoAllowedDomains;
    private final String ssoDefaultTenant;

    public AuthController(UserRepository users, JwtService jwtService, PasswordEncoder encoder, OidcTokenValidator oidc,
                          RateLimiterService rateLimiter,
                          @Value("${mcp.sso.allowed-email-domains:}") String ssoAllowedDomains,
                          @Value("${mcp.sso.default-tenant-id:tenant-1}") String ssoDefaultTenant) {
        this.users      = users;
        this.jwtService = jwtService;
        this.encoder    = encoder;
        this.oidc       = oidc;
        this.rateLimiter = rateLimiter;
        this.ssoAllowedDomains = java.util.Arrays.stream(ssoAllowedDomains.split(","))
                .map(String::trim).map(String::toLowerCase).filter(d -> !d.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.ssoDefaultTenant = ssoDefaultTenant;
    }

    /** POST /api/auth/login  { username, password, rememberMe? } */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest http) {
        String username  = (String) body.getOrDefault("username", "");
        String password  = (String) body.getOrDefault("password", "");
        boolean remember = Boolean.TRUE.equals(body.get("rememberMe"));

        if (username.isBlank() || password.isBlank())
            return ResponseEntity.status(400).body(Map.of("error", "Username and password required"));

        if (!rateLimiter.allowLogin(username.trim().toLowerCase()) || !rateLimiter.allowLogin(http.getRemoteAddr()))
            return ResponseEntity.status(429).body(Map.of("error", "Too many sign-in attempts. Try again in a minute."));

        AppUser user = users.findByUsername(username.trim()).orElse(null);
        if (user == null || !user.isEnabled())
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));

        if (user.getSsoProvider() == null) {
            if (user.getPasswordHash() == null || !encoder.matches(password, user.getPasswordHash()))
                return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        } else {
            return ResponseEntity.status(401).body(Map.of("error", "This account signs in through your identity provider"));
        }
        rateLimiter.reset(username.trim().toLowerCase());

        String role = user.getRole();
        long refreshTtl = remember ? REMEMBER_REFRESH_TTL : SESSION_REFRESH_TTL;
        String token = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tenantId", user.getTenantId(), "tokenType", "access"), ACCESS_TTL);
        String refreshToken = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tenantId", user.getTenantId(), "tokenType", "refresh", "rememberMe", remember), refreshTtl);

        return ResponseEntity.ok(Map.of(
                "token",           token,
                "refreshToken",    refreshToken,
                "username",        user.getUsername(),
                "fullName",        user.getFullName() != null ? user.getFullName() : user.getUsername(),
                "role",            role,
                "department",      user.getDepartment() != null ? user.getDepartment() : "",
                "tenantId",        user.getTenantId(),
                "tenantName",      user.getTenantName() != null ? user.getTenantName() : "Primary Workspace",
                "expiresIn",       ACCESS_TTL,
                "refreshExpiresIn", refreshTtl
        ));
    }

    /**
     * POST /api/auth/sso   { idToken }
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
            u.setTenantId(ssoDefaultTenant);
            u.setTenantName("Primary Workspace");
            u.setEnabled(true);
            return users.save(u);
        });

        if (!user.isEnabled())
            return ResponseEntity.status(403).body(Map.of("error", "Account disabled"));
        if (user.getSsoSubject() != null && !user.getSsoSubject().equals(subject))
            return ResponseEntity.status(403).body(Map.of("error", "This account is bound to a different provider identity"));

        String token = jwtService.generate(user.getUsername(),
                Map.of("role", user.getRole(), "tenantId", user.getTenantId(),
                       "ssoProvider", providerClaims.getIssuer(), "tokenType", "access"), ACCESS_TTL);
        String refreshToken = jwtService.generate(user.getUsername(),
                Map.of("role", user.getRole(), "tenantId", user.getTenantId(),
                       "ssoProvider", providerClaims.getIssuer(), "tokenType", "refresh", "rememberMe", false), SESSION_REFRESH_TTL);

        return ResponseEntity.ok(Map.of(
                "token",           token,
                "refreshToken",    refreshToken,
                "username",        user.getUsername(),
                "fullName",        user.getFullName() != null ? user.getFullName() : user.getUsername(),
                "role",            user.getRole(),
                "department",      user.getDepartment() != null ? user.getDepartment() : "",
                "tenantId",        user.getTenantId(),
                "tenantName",      user.getTenantName() != null ? user.getTenantName() : "Primary Workspace",
                "expiresIn",       ACCESS_TTL,
                "refreshExpiresIn", SESSION_REFRESH_TTL
        ));
    }

    /** POST /api/auth/refresh  { refreshToken } */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String old = body.get("refreshToken");
        if (old == null || old.isBlank()) old = body.get("token");
        if (old == null || !jwtService.isRefreshToken(old))
            return ResponseEntity.status(401).body(Map.of("error", "Refresh token invalid or expired"));

        var claims = jwtService.parse(old);
        AppUser user = users.findByUsername(claims.getSubject()).orElse(null);
        if (user == null || !user.isEnabled())
            return ResponseEntity.status(401).body(Map.of("error", "Account is no longer active"));

        boolean remember = Boolean.TRUE.equals(claims.get("rememberMe", Boolean.class));

        // Rotation must not extend the session. The replacement refresh token inherits the
        // OLD token's expiry rather than getting a fresh 7 days, so the clock that started
        // at password entry keeps running: rotate every half hour for a week and you are
        // still signed out on day 7.
        //
        // Minting a full TTL here — which is what this did — turned "keep me signed in for
        // 7 days" into "signed in forever, as long as one tab stays open", because every
        // rotation reset the very deadline it exists to enforce. There is no server-side
        // session table to expire it instead, so this expiry IS the session length.
        long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (remainingMs <= 0)
            return ResponseEntity.status(401).body(Map.of("error", "Session expired. Please sign in again."));

        String role = user.getRole();
        // Capped for the same reason: an access token minted in the last minute of the
        // window must not outlive the window.
        long accessTtl = Math.min(ACCESS_TTL, remainingMs);
        String fresh = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tenantId", user.getTenantId(), "tokenType", "access"), accessTtl);
        String rotatedRefresh = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tenantId", user.getTenantId(), "tokenType", "refresh", "rememberMe", remember), remainingMs);
        return ResponseEntity.ok(Map.of(
                "token", fresh,
                "refreshToken", rotatedRefresh,
                "role", role,
                "fullName", user.getFullName() != null ? user.getFullName() : user.getUsername(),
                "department", user.getDepartment() != null ? user.getDepartment() : "",
                "tenantId", user.getTenantId(),
                "expiresIn", accessTtl,
                "refreshExpiresIn", remainingMs
        ));
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
                "username",    user.getUsername(),
                "fullName",    user.getFullName() != null ? user.getFullName() : user.getUsername(),
                "email",       user.getEmail() != null ? user.getEmail() : "",
                "role",        user.getRole(),
                "department",  user.getDepartment() != null ? user.getDepartment() : "",
                "tenantId",    user.getTenantId(),
                "tenantName",  user.getTenantName() != null ? user.getTenantName() : "Primary Workspace",
                "ssoProvider", user.getSsoProvider() != null ? user.getSsoProvider() : ""
        ));
    }

    /** GET /api/auth/users — Admin view of workspace users */
    @GetMapping("/users")
    public ResponseEntity<?> getUsers(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        if (!jwtService.isValid(token)) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        var claims = jwtService.parse(token);
        String tenantId = (String) claims.getOrDefault("tenantId", "tenant-1");
        List<AppUser> list = users.findByTenantId(tenantId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AppUser u : list) {
            result.add(Map.of(
                    "id", u.getId() != null ? u.getId().toString() : "",
                    "username", u.getUsername(),
                    "fullName", u.getFullName() != null ? u.getFullName() : u.getUsername(),
                    "email", u.getEmail() != null ? u.getEmail() : "",
                    "role", u.getRole(),
                    "department", u.getDepartment() != null ? u.getDepartment() : "",
                    "enabled", u.isEnabled()
            ));
        }
        return ResponseEntity.ok(result);
    }

    /** POST /api/auth/users — Admin creation of a new user with {@link #DEFAULT_PASSWORD}. */
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestHeader("Authorization") String authHeader, @RequestBody Map<String, String> body) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        if (!jwtService.isValid(token)) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        var claims = jwtService.parse(token);
        String callerRole = (String) claims.getOrDefault("role", "");
        if (!"ADMIN".equalsIgnoreCase(callerRole)) {
            return ResponseEntity.status(403).body(Map.of("error", "Only administrators can create users"));
        }

        String username = body.getOrDefault("username", "").trim();
        String fullName = body.getOrDefault("fullName", "").trim();
        String email = body.getOrDefault("email", "").trim();
        String role = body.getOrDefault("role", "VIEWER").trim().toUpperCase();
        String department = body.getOrDefault("department", "").trim();
        String password = body.getOrDefault("password", DEFAULT_PASSWORD).trim();
        if (password.isBlank()) password = DEFAULT_PASSWORD;

        if (username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        }
        // An account with no usable address is an account no incident notification can ever
        // reach — and the UI shows it as a person who was told. Same rule the sender applies.
        if (!NotificationService.isSendableAddress(email)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "A valid email address is required, or this user can never be notified about an incident."));
        }
        // Refuse an unrecognised role rather than quietly filing the person as a VIEWER:
        // an admin who asked for ANALYST and silently got read-only finds out when that
        // person cannot approve anything, which reads as a broken permission system.
        if (!ALLOWED_ROLES.contains(role)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unknown role '" + role + "'. Choose one of " + ALLOWED_ROLES));
        }
        if (users.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username already exists"));
        }

        String tenantId = (String) claims.getOrDefault("tenantId", "tenant-1");
        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setUsername(username);
        u.setFullName(fullName.isBlank() ? username : fullName);
        u.setEmail(email);
        u.setRole(role);
        u.setDepartment(department);
        u.setPasswordHash(encoder.encode(password));
        u.setTenantId(tenantId);
        u.setTenantName("Primary Workspace");
        u.setEnabled(true);
        AppUser saved = users.save(u);

        return ResponseEntity.ok(Map.of(
                "message", "User created successfully with default password",
                "defaultPassword", DEFAULT_PASSWORD,
                "username", saved.getUsername(),
                "role", saved.getRole(),
                "fullName", saved.getFullName()
        ));
    }
}
