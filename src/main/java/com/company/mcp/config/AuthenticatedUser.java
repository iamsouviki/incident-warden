package com.company.mcp.config;

/** Server-derived identity used by tenant-scoped application services. */
public record AuthenticatedUser(String username, String tenantId, String role) {
}
