package com.company.mcp.controller;

import com.company.mcp.model.AppUser;
import com.company.mcp.repository.UserRepository;
import com.company.mcp.service.JwtService;
import com.company.mcp.service.OidcTokenValidator;
import com.company.mcp.service.RateLimiterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final long ACCESS_TTL = 60 * 60 * 1000L;                 // 1h
    private static final long SESSION_REFRESH_TTL = 24 * 60 * 60 * 1000L;   // 1d
    private static final long REMEMBER_REFRESH_TTL = 7 * 24 * 60 * 60 * 1000L; // 7d

    private static final Set<String> POC_ROLES = Set.of("VIEWER", "ANALYST", "ADMIN");
    private final UserRepository users;
    private final JwtService jwtService;
    private final PasswordEncoder encoder;
    private final OidcTokenValidator oidc;
    private final RateLimiterService rateLimiter;
    private final boolean pocRoleSelectionEnabled;
    private final Set<String> ssoAllowedDomains;
    private final String ssoDefaultTenant;

    public AuthController(UserRepository users, JwtService jwtService, PasswordEncoder encoder, OidcTokenValidator oidc,
                          RateLimiterService rateLimiter,
                          @Value("${mcp.poc.role-selection-enabled:false}") boolean pocRoleSelectionEnabled,
                          @Value("${mcp.sso.allowed-email-domains:}") String ssoAllowedDomains,
                          @Value("${mcp.sso.default-tenant-id:tenant-1}") String ssoDefaultTenant) {
        this.users      = users;
        this.jwtService = jwtService;
        this.encoder    = encoder;
        this.oidc       = oidc;
        this.rateLimiter = rateLimiter;
        this.pocRoleSelectionEnabled = pocRoleSelectionEnabled;
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

        // Throttle by username and by source address so neither a single account nor a
        // single host can be used to grind through a password list.
        if (!rateLimiter.allowLogin(username.trim().toLowerCase()) || !rateLimiter.allowLogin(http.getRemoteAddr()))
            return ResponseEntity.status(429).body(Map.of("error", "Too many sign-in attempts. Try again in a minute."));

        AppUser user = users.findByUsername(username.trim()).orElse(null);
        if (user == null || !user.isEnabled())
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));

        if (user.getSsoProvider() == null) {
            if (user.getPasswordHash() == null || !encoder.matches(password, user.getPasswordHash()))
                return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        } else {
            // An SSO-provisioned account has no local password to check; it must not fall
            // through to a successful login on an empty hash.
            return ResponseEntity.status(401).body(Map.of("error", "This account signs in through your identity provider"));
        }
        rateLimiter.reset(username.trim().toLowerCase());

        // The requested role is honored only when the explicit local POC feature flag is enabled.
        String role = effectiveRole(body, user.getRole());
        long refreshTtl = remember ? REMEMBER_REFRESH_TTL : SESSION_REFRESH_TTL;
        String token = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tenantId", user.getTenantId(), "tokenType", "access"), ACCESS_TTL);
        String refreshToken = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tenantId", user.getTenantId(), "tokenType", "refresh", "rememberMe", remember), refreshTtl);

        // One key for the access token, "token", on every response here. There used to be an
        // "accessToken" alias holding a second copy; both readers in the UI already fall back
        // through `accessToken || token`, so the duplicate bought nothing — and it pushed this
        // map to eleven pairs, one past Map.of's ceiling, which is why login was 500ing.
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
     *
     * Exchanges a provider ID token for a first-party session. The provider token
     * signature, issuer and audience are verified against the configured JWKS, and
     * the identity is read from the verified claims — never from the request body.
     * Auto-provisioning is limited to the configured email domain allow-list.
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
            u.setRole("VIEWER");                     // never provision a privileged role from SSO
            u.setSsoProvider(providerClaims.getIssuer());
            u.setSsoSubject(subject);
            u.setTenantId(ssoDefaultTenant);          // tenant is deployment policy, not a caller-supplied value
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

    private String effectiveRole(Map<String, Object> body, String storedRole) {
        if (!pocRoleSelectionEnabled) return storedRole;
        Object requested = body.get("role");
        if (!(requested instanceof String value)) return storedRole;
        String normalized = value.trim().toUpperCase();
        return POC_ROLES.contains(normalized) ? normalized : storedRole;
    }

    /** POST /api/auth/refresh  { refreshToken } */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String old = body.get("refreshToken");
        if (old == null || old.isBlank()) old = body.get("token");
        // A refresh token is the only credential accepted here. Presenting an access
        // token must not mint a new pair, or a leaked access token becomes permanent.
        if (old == null || !jwtService.isRefreshToken(old))
            return ResponseEntity.status(401).body(Map.of("error", "Refresh token invalid or expired"));

        var claims = jwtService.parse(old);
        // The account must still exist and still be enabled; the role is re-read from
        // the database so a revocation or demotion takes effect on the next refresh.
        AppUser user = users.findByUsername(claims.getSubject()).orElse(null);
        if (user == null || !user.isEnabled())
            return ResponseEntity.status(401).body(Map.of("error", "Account is no longer active"));

        boolean remember = Boolean.TRUE.equals(claims.get("rememberMe", Boolean.class));
        long refreshTtl = remember ? REMEMBER_REFRESH_TTL : SESSION_REFRESH_TTL;
        String role = pocRoleSelectionEnabled ? String.valueOf(claims.getOrDefault("role", user.getRole())) : user.getRole();
        String fresh = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tenantId", user.getTenantId(), "tokenType", "access"), ACCESS_TTL);
        String rotatedRefresh = jwtService.generate(user.getUsername(),
                Map.of("role", role, "tenantId", user.getTenantId(), "tokenType", "refresh", "rememberMe", remember), refreshTtl);
        return ResponseEntity.ok(Map.of(
                "token", fresh,
                "refreshToken", rotatedRefresh,
                "role", role,
                "fullName", user.getFullName() != null ? user.getFullName() : user.getUsername(),
                "department", user.getDepartment() != null ? user.getDepartment() : "",
                "tenantId", user.getTenantId(),
                "expiresIn", ACCESS_TTL,
                "refreshExpiresIn", refreshTtl
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
}
