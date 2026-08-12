package com.company.mcp.controller;

import com.company.mcp.service.HitlWorkflowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hitl")
public class HitlWorkflowController {
    private final HitlWorkflowService workflow;
    public HitlWorkflowController(HitlWorkflowService workflow) { this.workflow = workflow; }

    @PostMapping("/incidents/{incidentId}/plan")
    public ResponseEntity<?> createPlan(@PathVariable UUID incidentId) { return ResponseEntity.ok(workflow.createPlan(incidentId)); }
    @GetMapping("/requests")
    public ResponseEntity<?> pending() { return ResponseEntity.ok(workflow.pendingReviewItems()); }
    @PostMapping("/requests/{requestId}/decision")
    public ResponseEntity<?> decide(@PathVariable UUID requestId, @RequestBody Map<String,String> body) { return ResponseEntity.ok(workflow.decide(requestId, body.get("decision"), body.get("reason"))); }
    @PostMapping("/requests/{requestId}/dry-run")
    public ResponseEntity<?> dryRun(@PathVariable UUID requestId) { return ResponseEntity.ok(workflow.dryRunAndExecute(requestId)); }
}
