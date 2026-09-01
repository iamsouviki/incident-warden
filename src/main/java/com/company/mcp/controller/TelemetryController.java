package com.company.mcp.controller;

import com.company.mcp.model.TelemetryEvent;
import com.company.mcp.service.TelemetryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {
    private final TelemetryService telemetry;

    public TelemetryController(TelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    @PostMapping("/events")
    public ResponseEntity<?> ingest(@RequestBody TelemetryEvent event) {
        try {
            return ResponseEntity.ok(telemetry.ingest(event));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/events")
    public ResponseEntity<?> recent() {
        return ResponseEntity.ok(telemetry.recent());
    }
}
