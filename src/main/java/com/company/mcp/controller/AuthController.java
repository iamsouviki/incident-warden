package com.company.mcp.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        if (username == null || username.trim().isEmpty()) {
            username = "admin";
        }
        
        // Return dummy user data expected by the frontend AuthUser interface
        return ResponseEntity.ok(Map.of(
            "username", username,
            "role", "ADMIN",
            "tenantId", "tenant-1",
            "tenantName", "Primary Workspace",
            "token", "mock-jwt-token-12345"
        ));
    }
}
