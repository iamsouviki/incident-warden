package com.company.mcp.controller;

import com.company.mcp.config.security.JwtUtil;
import com.company.mcp.model.Tenant;
import com.company.mcp.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * AuthController — spec §2 "Multi-Tenant Auth".
 *
 * POST /api/auth/login   — returns a signed JWT on valid credentials
 * POST /api/auth/refresh — (stub) refresh token flow
 * GET  /api/auth/me      — returns current user info from JWT
 *
 * ─────── Built-in development users ───────────────────────────────────
 *  username   password      role      tenantId
 * ────────────────────────────────────────────────────────────────────────
 *  admin      admin123      ADMIN     00000000-0000-0000-0000-000000000001
 *  analyst    analyst123    ANALYST   00000000-0000-0000-0000-000000000001
 *  viewer     viewer123     VIEWER    00000000-0000-0000-0000-000000000001
 * ─────────────────────────────────────────────────────────────────────
 *
 * NOTE: For production, replace the static map with a proper UserRepository
 * + BCrypt password verification.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000001";

    /** username → [password, role] */
    private static final Map<String, String[]> USERS = Map.of(
            "admin",   new String[]{"admin123",   "ADMIN"},
            "analyst", new String[]{"analyst123", "ANALYST"},
            "viewer",  new String[]{"viewer123",  "VIEWER"}
    );

    private final JwtUtil jwtUtil;
    private final TenantRepository tenantRepository;

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    /**
     * POST /api/auth/login
     * Body: {@code { "username": "admin", "password": "admin123" }}
     *
     * Returns:
     * <pre>
     * {
     *   "token":     "eyJ…",
     *   "username":  "admin",
     *   "role":      "ADMIN",
     *   "tenantId":  "00000000-…",
     *   "expiresIn": 86400
     * }
     * </pre>
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "username and password are required"));
        }

        String[] creds = USERS.get(username.toLowerCase());
        if (creds == null || !creds[0].equals(password)) {
            log.warn("Login failed for user '{}'", username);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        }

        String tenantId = body.getOrDefault("tenantId", DEFAULT_TENANT);
        String role     = creds[1];
        String token    = jwtUtil.generateToken(username, tenantId, role);
        String tenantName = resolveTenantDisplayName(tenantId);

        log.info("Login ok: user={} role={} tenant={}", username, role, tenantId);

        return ResponseEntity.ok(Map.of(
                "token",     token,
                "username",  username,
                "role",      role,
                "tenantId",  tenantId,
                "tenantName", tenantName,
                "expiresIn", 86400
        ));
    }

    // -------------------------------------------------------------------------
    // Me (introspect token — frontend reads user info without calling DB)
    // -------------------------------------------------------------------------

    /**
     * GET /api/auth/me
     * Requires valid {@code Authorization: Bearer <token>} header.
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing or invalid Authorization header"));
        }
        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return ResponseEntity.status(401).body(Map.of("error", "Token expired or invalid"));
        }
        return ResponseEntity.ok(Map.of(
                "username", jwtUtil.extractUsername(token),
                "tenantId", jwtUtil.extractTenantId(token),
                "tenantName", resolveTenantDisplayName(jwtUtil.extractTenantId(token)),
                "role",     jwtUtil.extractRole(token)
        ));
    }

    // -------------------------------------------------------------------------
    // Refresh stub
    // -------------------------------------------------------------------------

    /** POST /api/auth/refresh — issues a new token from a valid non-expired one. */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null || !jwtUtil.validateToken(token)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or expired token"));
        }
        String newToken = jwtUtil.generateToken(
                jwtUtil.extractUsername(token),
                jwtUtil.extractTenantId(token),
                jwtUtil.extractRole(token));
        return ResponseEntity.ok(Map.of("token", newToken, "expiresIn", 86400));
    }

    private String resolveTenantDisplayName(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "Workspace";
        }
        if (DEFAULT_TENANT.equals(tenantId)) {
            return "Primary Workspace";
        }
        try {
            return tenantRepository.findById(UUID.fromString(tenantId))
                    .map(Tenant::getName)
                    .filter(name -> !name.isBlank())
                    .orElse("Workspace");
        } catch (IllegalArgumentException ex) {
            return "Workspace";
        }
    }
}
