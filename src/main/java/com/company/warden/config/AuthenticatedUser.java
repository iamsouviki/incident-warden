package com.company.warden.config;

/** Server-derived identity used by the application services. */
public record AuthenticatedUser(String username, String role) {
}
