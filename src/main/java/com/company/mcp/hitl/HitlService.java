package com.company.mcp.hitl;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.model.HitlRequest;
import com.company.mcp.model.Incident;
import com.company.mcp.repository.HitlRequestRepository;
import com.company.mcp.repository.IncidentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HitlService — spec §7 "Human-in-the-Loop Escalation".
 *
 * Central service for all HITL lifecycle operations:
 *   • createRequest       — builds and persists a new HITL request from an AgentContext
 *   • approve / reject    — handles decision from human operator
 *   • escalate            — manual or automatic escalation
 *   • prepareApprovalPackage — serializes decision context for UI / email / Slack
 *
 * SLA timeouts per severity (configurable):
 *   P1 →  15 min   P2 →  30 min   P3 → 120 min   P4 → 480 min
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HitlService {

    private final HitlRequestRepository hitlRepository;
    private final IncidentRepository    incidentRepository;
    private final HitlNotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Value("${mcp.hitl.sla.p1-minutes:15}")
    private int slaP1;
    @Value("${mcp.hitl.sla.p2-minutes:30}")
    private int slaP2;
    @Value("${mcp.hitl.sla.p3-minutes:120}")
    private int slaP3;
    @Value("${mcp.hitl.sla.p4-minutes:480}")
    private int slaP4;

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Create and persist a HITL request derived from an AgentContext.
     * Fires a Slack/email notification to the on-call team.
     */
    @Transactional
    public HitlRequest createRequest(AgentContext context) {
        Incident incident = context.getIncident();

        HitlRequest req = new HitlRequest();
        req.setId(UUID.randomUUID());
        req.setIncidentId(incident.getId());
        req.setTenantId(UUID.fromString(context.getTenantId()));
        req.setStatus("PENDING");
        req.setExpiresAt(LocalDateTime.now().plusMinutes(slaMinutes(incident.getSeverity())));
        req.setDecisionReason(buildReason(context));
        req.setApprovalPayload(prepareApprovalPackage(context));

        HitlRequest saved = hitlRepository.save(req);
        log.info("HITL request created: id={} incidentId={} severity={} expiresAt={}",
                saved.getId(), incident.getId(), incident.getSeverity(), saved.getExpiresAt());

        notificationService.notifyPendingApproval(saved, incident);
        return saved;
    }

    // -------------------------------------------------------------------------
    // Approve / Reject / Escalate
    // -------------------------------------------------------------------------

    /**
     * Record an approval decision.
     *
     * @param requestId  HITL request to approve
     * @param decidedBy  email / username of the approver
     * @param notes      optional free-text notes
     * @return updated request
     */
    @Transactional
    public HitlRequest approve(UUID requestId, String decidedBy, String notes) {
        HitlRequest req = requirePending(requestId);
        req.setStatus("APPROVED");
        req.setDecision("APPROVED");
        req.setDecidedBy(decidedBy);
        req.setDecisionReason(notes);
        req.setDecidedAt(LocalDateTime.now());

        updateIncidentStatus(req.getIncidentId(), "AUTO_RESOLVED", "AUTO_RESOLVE");

        HitlRequest saved = hitlRepository.save(req);
        log.info("HITL approved: id={} decidedBy={}", requestId, decidedBy);
        notificationService.notifyDecision(saved, "APPROVED");
        return saved;
    }

    /**
     * Record a rejection decision.
     */
    @Transactional
    public HitlRequest reject(UUID requestId, String decidedBy, String reason) {
        HitlRequest req = requirePending(requestId);
        req.setStatus("REJECTED");
        req.setDecision("REJECTED");
        req.setDecidedBy(decidedBy);
        req.setDecisionReason(reason);
        req.setDecidedAt(LocalDateTime.now());

        updateIncidentStatus(req.getIncidentId(), "REJECTED", "REJECTED");

        HitlRequest saved = hitlRepository.save(req);
        log.info("HITL rejected: id={} decidedBy={} reason={}", requestId, decidedBy, reason);
        notificationService.notifyDecision(saved, "REJECTED");
        return saved;
    }

    /**
     * Escalate to a senior analyst.
     */
    @Transactional
    public HitlRequest escalate(UUID requestId, String reason) {
        HitlRequest req = requirePending(requestId);
        req.setStatus("ESCALATED");
        req.setDecision("ESCALATE_TO_HUMAN");
        req.setDecisionReason(reason);
        req.setDecidedAt(LocalDateTime.now());

        updateIncidentStatus(req.getIncidentId(), "ESCALATED", "ESCALATE_TO_HUMAN");

        HitlRequest saved = hitlRepository.save(req);
        log.warn("HITL escalated: id={} reason={}", requestId, reason);
        notificationService.triggerPagerDuty(saved, reason);
        return saved;
    }

    // -------------------------------------------------------------------------
    // Approval package
    // -------------------------------------------------------------------------

    /**
     * Serialises the relevant AgentContext fields into a JSON string suitable
     * for display in the approver UI or HTML email body.
     */
    public String prepareApprovalPackage(AgentContext context) {
        Map<String, Object> pkg = new HashMap<>();
        pkg.put("incidentId",        context.getIncident().getId());
        pkg.put("severity",          context.getIncident().getSeverity());
        pkg.put("title",             context.getIncident().getTitle());
        pkg.put("category",          context.getClassifiedCategory());
        pkg.put("subCategory",       context.getClassifiedSubCategory());
        pkg.put("sopTitle",          context.getSopTitle());
        pkg.put("confidenceScore",   context.getFinalConfidenceScore());
        pkg.put("riskScore",         context.getRiskScore());
        pkg.put("guardrailsTriggered", context.getGuardrailsTriggered());
        pkg.put("guardrailViolations", context.getGuardRailViolations());
        try {
            return objectMapper.writeValueAsString(pkg);
        } catch (Exception e) {
            log.warn("Failed to serialize approval package: {}", e.getMessage());
            return "{}";
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HitlRequest requirePending(UUID requestId) {
        HitlRequest req = hitlRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("HITL request not found: " + requestId));
        if (!"PENDING".equals(req.getStatus())) {
            throw new IllegalStateException("HITL request " + requestId + " is not PENDING (current: " + req.getStatus() + ")");
        }
        return req;
    }

    private void updateIncidentStatus(UUID incidentId, String status, String finalDecision) {
        incidentRepository.findById(incidentId).ifPresentOrElse(inc -> {
            inc.setStatus(status);
            inc.setFinalDecision(finalDecision);
            if ("AUTO_RESOLVED".equals(status)) {
                inc.setResolvedAt(LocalDateTime.now());
            }
            incidentRepository.save(inc);
        }, () -> log.warn("HitlService: incident {} not found for status update", incidentId));
    }

    private int slaMinutes(String severity) {
        if (severity == null) return slaP3;
        return switch (severity.toUpperCase()) {
            case "P1" -> slaP1;
            case "P2" -> slaP2;
            case "P4" -> slaP4;
            default   -> slaP3;
        };
    }

    private static String buildReason(AgentContext context) {
        if (Boolean.TRUE.equals(context.getGuardrailsTriggered())) {
            int violations = context.getGuardRailViolations() != null
                    ? context.getGuardRailViolations().size() : 0;
            return "Guardrail violation(s) detected: " + violations;
        }
        return String.format("Confidence %.0f%% requires human approval",
                (context.getFinalConfidenceScore() != null ? context.getFinalConfidenceScore() : 0) * 100);
    }
}
