package com.company.mcp.controller;

import com.company.mcp.model.AppUser;
import com.company.mcp.repository.UserRepository;
import com.company.mcp.service.JwtService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final long TOKEN_TTL    = 60 * 60 * 1000L;           // 1h
    private static final long REMEMBER_TTL = 7 * 24 * 60 * 60 * 1000L; // 7d

    private final UserRepository users;
    private final JwtService jwtService;
    private final PasswordEncoder encoder;

    public AuthController(UserRepository users, JwtService jwtService, PasswordEncoder encoder) {
        this.users      = users;
        this.jwtService = jwtService;
        this.encoder    = encoder;
    }

    /** POST /api/auth/login  { username, password, rememberMe? } */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body) {
        String username  = (String) body.getOrDefault("username", "");
        String password  = (String) body.getOrDefault("password", "");
        boolean remember = Boolean.TRUE.equals(body.get("rememberMe"));

        if (username.isBlank() || password.isBlank())
            return ResponseEntity.status(400).body(Map.of("error", "Username and password required"));

        AppUser user = users.findByUsername(username.trim()).orElse(null);
        if (user == null || !user.isEnabled())
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));

        if (user.getSsoProvider() == null) {
            if (user.getPasswordHash() == null || !encoder.matches(password, user.getPasswordHash()))
                return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        }

        long ttl     = remember ? REMEMBER_TTL : TOKEN_TTL;
        String token = jwtService.generate(user.getUsername(),
                Map.of("role", user.getRole(), "tenantId", user.getTenantId()), ttl);

        return ResponseEntity.ok(Map.of(
                "token",      token,
                "username",   user.getUsername(),
                "role",       user.getRole(),
                "tenantId",   user.getTenantId(),
                "tenantName", user.getTenantName() != null ? user.getTenantName() : "Primary Workspace",
                "expiresIn",  ttl
        ));
    }

    /**
     * POST /api/auth/sso
     * SSO token exchange — frontend calls this AFTER validating the provider token
     * (Okta, Azure AD, Google OIDC, etc.) via their SDK / JWKS.
     * Body: { provider, subject, email, name?, tenantId? }
     * Auto-provisions the user on first SSO login.
     *
     * TODO before go-live: add JWKS endpoint validation of provider token here.
     */
    @PostMapping("/sso")
    public ResponseEntity<?> sso(@RequestBody Map<String, Object> body) {
        String provider = (String) body.get("provider");  // e.g. "OKTA", "AZURE_AD"
        String subject  = (String) body.get("subject");   // provider's unique user sub
        String email    = (String) body.getOrDefault("email", "");
        String tenantId = (String) body.getOrDefault("tenantId", "tenant-1");

        if (provider == null || subject == null || email.isBlank())
            return ResponseEntity.status(400).body(Map.of("error", "provider, subject, email required"));

        AppUser user = users.findByUsername(email).orElseGet(() -> {
            AppUser u = new AppUser();
            u.setUsername(email);
            u.setEmail(email);
            u.setRole("VIEWER");
            u.setSsoProvider(provider);
            u.setSsoSubject(subject);
            u.setTenantId(tenantId);
            u.setTenantName("Primary Workspace");
            u.setEnabled(true);
            return users.save(u);
        });

        if (!user.isEnabled())
            return ResponseEntity.status(403).body(Map.of("error", "Account disabled"));

        String token = jwtService.generate(user.getUsername(),
                Map.of("role", user.getRole(), "tenantId", user.getTenantId(),
                       "ssoProvider", provider), TOKEN_TTL);

        return ResponseEntity.ok(Map.of(
                "token",      token,
                "username",   user.getUsername(),
                "role",       user.getRole(),
                "tenantId",   user.getTenantId(),
                "tenantName", user.getTenantName() != null ? user.getTenantName() : "Primary Workspace",
                "expiresIn",  TOKEN_TTL
        ));
    }

    /** POST /api/auth/refresh  { token } */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String old = body.get("token");
        if (old == null || !jwtService.isValid(old))
            return ResponseEntity.status(401).body(Map.of("error", "Token invalid or expired"));

        var claims   = jwtService.parse(old);
        String fresh = jwtService.generate(claims.getSubject(),
                Map.of("role", claims.getOrDefault("role", "VIEWER"),
                       "tenantId", claims.getOrDefault("tenantId", "tenant-1")), TOKEN_TTL);
        return ResponseEntity.ok(Map.of("token", fresh, "expiresIn", TOKEN_TTL));
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
                "email",       user.getEmail() != null ? user.getEmail() : "",
                "role",        user.getRole(),
                "tenantId",    user.getTenantId(),
                "tenantName",  user.getTenantName() != null ? user.getTenantName() : "Primary Workspace",
                "ssoProvider", user.getSsoProvider() != null ? user.getSsoProvider() : ""
        ));
    }
}
