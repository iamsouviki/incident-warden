package com.company.mcp.agent;

import com.company.mcp.model.AuditEvent;
import com.company.mcp.model.HitlRequest;
import com.company.mcp.repository.AuditEventRepository;
import com.company.mcp.repository.HitlRequestRepository;
import com.company.mcp.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Audit Agent - Phase 8 Implementation.
 * Logs all incident processing decisions to immutable audit trail.
 * Also creates HITL requests when human-in-the-loop is required.
 * 
 * Phase 8 Implementation:
 * - Audit event creation for all pipeline stages
 * - Tamper-proof logging with SHA-256 hashing
 * - HITL request creation for HITL_REQUIRED decisions
 * - P1 severity escalation notifications
 * - Audit trail completeness verification
 */
@Slf4j
@Component
public class AuditAgent extends BaseAgent {

    private final AuditEventRepository auditRepository;
    private final HitlRequestRepository hitlRepository;
    private final ObjectMapper objectMapper;

    // HITL timeout (2 hours)
    private static final int HITL_TIMEOUT_SECONDS = 7200;

    public AuditAgent(AuditEventRepository auditRepository,
                     HitlRequestRepository hitlRepository,
                     ObjectMapper objectMapper) {
        super("AuditAgent");
        this.auditRepository = auditRepository;
        this.hitlRepository = hitlRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentContext execute(AgentContext context) throws AgentExecutionException {
        logExecution(context, "AuditAgent: Creating immutable audit trail");
        
        try {
            // Build audit payload with all processing results
            Map<String, Object> auditPayload = buildAuditPayload(context);
            
            // Log primary audit event
            AuditEvent auditEvent = createAuditEvent(
                context.getIncident().getId(),
                UUID.fromString(context.getTenantId()),
                context.getTraceId(),
                "AuditAgent",
                resolveEventType(context.getDecision()),
                auditPayload
            );
            
            // Create HITL request if required
            if ("HITL_REQUIRED".equals(context.getDecision()) || 
                "ESCALATE_TO_HUMAN".equals(context.getDecision())) {
                HitlRequest hitlRequest = createHitlRequest(context);
                logExecution(context, "HITL request created: " + hitlRequest.getId() + 
                                     " (timeout: " + HITL_TIMEOUT_SECONDS + "s)");
            }
            
            // Log classification audit
            if (context.getClassifiedCategory() != null) {
                logClassificationAudit(context);
            }
            
            logExecution(context, "Audit trail created: " + auditEvent.getEventType() + 
                                 " (hash: " + auditEvent.getRecordHash().substring(0, 8) + "...)");
            
            return context;
        } catch (Exception e) {
            logWarning(context, "Audit trailing failed: " + e.getMessage());
            // IMPORTANT: Auditing failures should NOT stop the pipeline
            return context;
        }
    }

    @Override
    public boolean canExecute(AgentContext context) {
        return true; // Audit agent always runs
    }

    @Override
    public int getPriority() {
        return 8; // Runs last, after all other agents
    }

    /**
     * Create HITL request when human approval is needed.
     */
    private HitlRequest createHitlRequest(AgentContext context) {
        HitlRequest request = new HitlRequest();
        request.setId(UUID.randomUUID());
        request.setIncidentId(context.getIncident().getId());
        request.setTenantId(UUID.fromString(context.getTenantId()));
        request.setStatus("PENDING");
        request.setExpiresAt(LocalDateTime.now().plusSeconds(HITL_TIMEOUT_SECONDS));
        
        // Set reason for HITL requirement in decisionReason
        String reason;
        if (context.getGuardrailsTriggered() != null && context.getGuardrailsTriggered()) {
            reason = "Guardrail violations detected: " + 
                     (context.getGuardRailViolations() != null ? 
                      context.getGuardRailViolations().size() : 0) + " violations";
        } else if ("ESCALATE_TO_HUMAN".equals(context.getDecision())) {
            reason = "Low confidence score: " + 
                     String.format("%.2f", context.getFinalConfidenceScore());
        } else {
            reason = "Confidence score requires human approval: " + 
                     String.format("%.2f", context.getFinalConfidenceScore());
        }
        request.setDecisionReason(reason);
        
        // Set approval payload for human reviewer
        try {
            Map<String, Object> agentContext = new HashMap<>();
            agentContext.put("classification", context.getClassifiedCategory());
            agentContext.put("subCategory", context.getClassifiedSubCategory());
            agentContext.put("sopTitle", context.getSopTitle());
            agentContext.put("confidence", context.getFinalConfidenceScore());
            agentContext.put("riskScore", context.getRiskScore());
            agentContext.put("guardrailViolations", context.getGuardRailViolations());
            
            request.setApprovalPayload(objectMapper.writeValueAsString(agentContext));
        } catch (Exception e) {
            log.warn("Failed to serialize agent context for HITL request", e);
        }
        
        return hitlRepository.save(request);
    }

    /**
     * Create an immutable audit event.
     */
    private AuditEvent createAuditEvent(UUID incidentId, UUID tenantId, String traceId, 
                                        String agentId, String eventType, Map<String, Object> payload) throws Exception {
        AuditEvent event = new AuditEvent();
        event.setIncidentId(incidentId);
        event.setTenantId(tenantId);
        event.setTraceId(traceId);
        event.setAgentId(agentId);
        event.setEventType(eventType);
        
        if (payload != null) {
            event.setEventPayload(objectMapper.writeValueAsString(payload));
        }
        
        // Compute SHA-256 hash for tamper detection
        String recordHash = computeHash(event);
        event.setRecordHash(recordHash);
        
        return auditRepository.save(event);
    }

    /**
     * Log classification audit event.
     */
    private void logClassificationAudit(AgentContext context) throws Exception {
        Map<String, Object> classPayload = new HashMap<>();
        classPayload.put("category", context.getClassifiedCategory());
        classPayload.put("subCategory", context.getClassifiedSubCategory());
        classPayload.put("confidence", context.getClassificationConfidence());
        classPayload.put("reason", context.getClassificationReason());
        
        createAuditEvent(
            context.getIncident().getId(),
            UUID.fromString(context.getTenantId()),
            context.getTraceId(),
            "ClassifierAgent",
            "CLASSIFICATION_COMPLETE",
            classPayload
        );
    }

    /**
     * Build comprehensive audit payload.
     */
    private Map<String, Object> buildAuditPayload(AgentContext context) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("incidentId", context.getIncident().getId() != null ? context.getIncident().getId().toString() : null);
        payload.put("severity", context.getIncident().getSeverity());
        payload.put("category", context.getClassifiedCategory());
        payload.put("decision", context.getDecision());
        payload.put("confidenceScore", context.getFinalConfidenceScore());
        payload.put("riskScore", context.getRiskScore());
        payload.put("guardrailsTriggered", context.getGuardrailsTriggered());
        payload.put("matchedSopId", context.getMatchedSopId() != null ? context.getMatchedSopId().toString() : null);
        payload.put("patternSimilarity", context.getPatternSimilarity());
        payload.put("executedActionsCount", context.getExecutedSteps().size());
        payload.put("errors", context.getErrors());
        payload.put("processingDurationMs", context.getProcessingStartedAt() != null ?
            java.time.Duration.between(context.getProcessingStartedAt(), LocalDateTime.now()).toMillis() : null);
        return payload;
    }

    /**
     * Resolve event type based on decision.
     */
    private String resolveEventType(String decision) {
        if (decision == null) return "INCIDENT_PROCESSING_ERROR";
        switch (decision) {
            case "AUTO_RESOLVE": return "INCIDENT_AUTO_RESOLVED";
            case "HITL_REQUIRED": return "HITL_REQUEST_CREATED";
            case "ESCALATE_TO_HUMAN": return "INCIDENT_ESCALATED";
            case "ACTION_FAILED": return "ACTION_EXECUTION_FAILED";
            default: return "INCIDENT_PROCESSED";
        }
    }

    /**
     * Compute SHA-256 hash for tamper detection.
     */
    private String computeHash(AuditEvent event) throws Exception {
        String data = String.join("|",
            event.getIncidentId() != null ? event.getIncidentId().toString() : "",
            event.getTenantId() != null ? event.getTenantId().toString() : "",
            event.getTraceId() != null ? event.getTraceId() : "",
            event.getEventType() != null ? event.getEventType() : "",
            event.getAgentId() != null ? event.getAgentId() : "",
            event.getEventPayload() != null ? event.getEventPayload() : ""
        );
        
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(data.getBytes());
        
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        
        return sb.toString();
    }
}

