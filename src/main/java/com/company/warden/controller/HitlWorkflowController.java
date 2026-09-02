package com.company.warden.controller;

import com.company.warden.service.HitlWorkflowService;
import com.company.warden.service.RemediationToolRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Role rules for these routes live in {@code SecurityConfig}, not in annotations here.
 * Method security is not enabled in this application, so a {@code @PreAuthorize} on a
 * handler would be silently inert — worse than no annotation, because it reads as a
 * check that is not running.
 */
@RestController
@RequestMapping("/api/v1/hitl")
public class HitlWorkflowController {
    private final HitlWorkflowService workflow;
    private final RemediationToolRegistry tools;

    public HitlWorkflowController(HitlWorkflowService workflow, RemediationToolRegistry tools) {
        this.workflow = workflow;
        this.tools = tools;
    }

    @PostMapping("/incidents/{incidentId}/plan")
    public ResponseEntity<?> createPlan(@PathVariable UUID incidentId,
                                        @RequestBody(required = false) Map<String, String> fields) {
        return ResponseEntity.ok(workflow.createPlan(incidentId, fields == null ? Map.of() : fields));
    }

    @GetMapping("/requests")
    public ResponseEntity<?> pending() {
        return ResponseEntity.ok(workflow.pendingReviewItems());
    }

    @GetMapping("/requests/{requestId}")
    public ResponseEntity<?> reviewDetail(@PathVariable UUID requestId) {
        return ResponseEntity.ok(workflow.reviewDetail(requestId));
    }

    @PostMapping("/requests/{requestId}/decision")
    public ResponseEntity<?> decide(@PathVariable UUID requestId, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(workflow.decide(requestId, body.get("decision"), body.get("reason")));
    }

    @PostMapping("/requests/{requestId}/dry-run")
    public ResponseEntity<?> dryRun(@PathVariable UUID requestId) {
        return ResponseEntity.ok(workflow.dryRunAndExecute(requestId));
    }

    /** Real execution. ADMIN only — see the route rule in SecurityConfig. */
    @PostMapping("/requests/{requestId}/execute")
    public ResponseEntity<?> execute(@PathVariable UUID requestId) {
        return ResponseEntity.ok(workflow.execute(requestId));
    }

    /** The tool catalogue, so the UI can show what the platform is capable of running. */
    @GetMapping("/tools")
    public ResponseEntity<?> tools() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tools", tools.tools());
        return ResponseEntity.ok(body);
    }
}
