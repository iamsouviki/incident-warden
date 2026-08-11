package com.company.mcp.controller;

import com.company.mcp.service.AutonomousRemediationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/autonomy")
public class AutonomyController {

    private final AutonomousRemediationService autonomy;

    public AutonomyController(AutonomousRemediationService autonomy) {
        this.autonomy = autonomy;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(autonomy.status());
    }

    @PostMapping("/run")
    public ResponseEntity<?> run() {
        return ResponseEntity.ok(autonomy.runCycle());
    }

    @GetMapping("/traces")
    public ResponseEntity<?> traces(@RequestParam(defaultValue = "30") int limit) {
        return ResponseEntity.ok(autonomy.recentTraces(limit));
    }

    @GetMapping("/learning")
    public ResponseEntity<?> learning() {
        return ResponseEntity.ok(autonomy.learningSummary());
    }
}
