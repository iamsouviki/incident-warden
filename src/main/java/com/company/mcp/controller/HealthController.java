package com.company.mcp.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("application", "MCP Incident Automation");
        response.put("version", "1.0.0");
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }

    @GetMapping("/health/readiness")
    public Map<String, String> readiness() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "READY");
        response.put("services", "All services operational");
        return response;
    }
}
