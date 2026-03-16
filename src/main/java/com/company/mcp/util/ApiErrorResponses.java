package com.company.mcp.util;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Centralizes simple user-facing API error responses so backend exceptions do
 * not leak implementation details into the UI.
 */
public final class ApiErrorResponses {

    public static final String SIMPLE_ERROR_MESSAGE = "Something went wrong. Please try again.";

    private ApiErrorResponses() {
    }

    public static ResponseEntity<Map<String, String>> badRequest() {
        return ResponseEntity.badRequest().body(body());
    }

    public static ResponseEntity<Map<String, String>> internalServerError() {
        return ResponseEntity.internalServerError().body(body());
    }

    public static Map<String, String> body() {
        return Map.of("error", SIMPLE_ERROR_MESSAGE);
    }
}
