package com.company.mcp.service;

import com.company.mcp.model.Incident;
import com.company.mcp.model.SopProcedure;
import com.company.mcp.repository.SopProcedureRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Coordinates the explainable agent stages used by the HITL planner. LLM/RAG
 * content may enrich a plan, but these stages deliberately make the safety
 * decision from bounded, inspectable values.
 */
@Service
public class AgentAssessmentService {

    private final double hitlThresholdConfig;
    private final double defaultPriorSuccessRate;
    private final SopProcedureRepository sopProcedureRepository;

    public AgentAssessmentService(
            @org.springframework.beans.factory.annotation.Value("${mcp.confidence.hitl-threshold:0.80}") double hitlThresholdConfig,
            @org.springframework.beans.factory.annotation.Value("${mcp.confidence.default-prior-success-rate:0.85}") double defaultPriorSuccessRate,
            SopProcedureRepository sopProcedureRepository) {
        this.hitlThresholdConfig = hitlThresholdConfig;
        this.defaultPriorSuccessRate = defaultPriorSuccessRate;
        this.sopProcedureRepository = sopProcedureRepository;
    }

    /**
     * Assesses with the configured prior for historical success. Used when no SOP
     * execution history exists for this action yet.
     */
    public Assessment assess(Incident incident, SopEvidence evidence) {
        return assess(incident, evidence, defaultPriorSuccessRate);
    }

    /** The configured prior, for callers that pass a precedent score alongside it. */
    public double defaultPrior() { return defaultPriorSuccessRate; }

    /**
     * @param historicalSuccess observed success rate for this remediation, in [0,1].
     */
    public Assessment assess(Incident incident, SopEvidence evidence, double historicalSuccess) {
        return assess(incident, evidence, historicalSuccess, 0.0);
    }

    /**
     * @param precedentSimilarity how much of this incident's wording is already covered by
     *        a past incident in the same tenant that a human approved and that was
     *        successfully remediated, in [0,1]. Zero when there is no such incident.
     */
    public Assessment assess(Incident incident, SopEvidence evidence, double historicalSuccess,
                             double precedentSimilarity) {
        String text = (safe(incident.getSubject()) + " " + safe(incident.getDescription())).toLowerCase(Locale.ROOT);
        String tenantId = incident.getTenantId() != null ? incident.getTenantId() : "tenant-1";
        Classification classification = classify(text, tenantId);
        double keywordSimilarity = evidence.approvedEvidencePresent() && containsAny(evidence.excerpt().toLowerCase(Locale.ROOT), classification.keywords()) ? 0.90 : 0.0;
        precedentSimilarity = Math.max(0.0, Math.min(1.0, precedentSimilarity));
        double patternSimilarity = Math.max(keywordSimilarity, precedentSimilarity);
        historicalSuccess = Math.max(0.0, Math.min(1.0, historicalSuccess));
        double sopReliability = evidence.approvedEvidencePresent() ? evidence.reliability() : 0.0;
        double systemHealth = systemHealth(incident);
        double riskPenalty = riskPenalty(incident, classification.action());
        double score = clamp(100.0 * ((0.35 * patternSimilarity) + (0.25 * historicalSuccess) +
                (0.20 * sopReliability) + (0.15 * systemHealth) - riskPenalty));

        double threshold = hitlThresholdConfig * 100.0;
        String route = evidence.approvedEvidencePresent() && !classification.action().isBlank() && score >= threshold ? "HITL_REQUIRED" : "ESCALATE";
        return new Assessment(classification.category(), classification.action(), target(incident), patternSimilarity,
                historicalSuccess, sopReliability, systemHealth, riskPenalty, score, route,
                Map.of("classification", classification.category(), "evidenceReason", evidence.reason(),
                        "keywordSimilarity", String.format(Locale.ROOT, "%.2f", keywordSimilarity),
                        "precedentSimilarity", String.format(Locale.ROOT, "%.2f", precedentSimilarity)));
    }

    /**
     * Dynamically classifies incident text using both approved procedures in sop.sop_procedure
     * for the tenant and the foundational fallback vocabulary.
     */
    private Classification classify(String text, String tenantId) {
        // 1. Check dynamic approved procedures for the tenant
        if (sopProcedureRepository != null && tenantId != null && !tenantId.isBlank()) {
            List<SopProcedure> procedures = sopProcedureRepository.findByTenantIdAndApprovalStatus(tenantId, "APPROVED");
            for (SopProcedure proc : procedures) {
                List<String> terms = new ArrayList<>();
                if (proc.getMatchKeywords() != null && !proc.getMatchKeywords().isBlank()) {
                    for (String kw : proc.getMatchKeywords().split("[,;\\s]+")) {
                        if (!kw.trim().isEmpty()) terms.add(kw.trim().toLowerCase(Locale.ROOT));
                    }
                }
                if (proc.getTitle() != null && !proc.getTitle().isBlank()) {
                    terms.add(proc.getTitle().toLowerCase(Locale.ROOT));
                }
                if (!terms.isEmpty() && containsAny(text, terms.toArray(new String[0]))) {
                    String category = deriveCategoryFromAction(proc.getActionKey(), proc.getTitle());
                    String action = deriveActionFromKey(proc.getActionKey());
                    return new Classification(category, action, terms.toArray(new String[0]));
                }
            }
        }

        // 2. Foundational vocabulary fallback
        if (containsAny(text, new String[]{"printer", "print queue", "print job"})) {
            return new Classification("PRINTING", "clear-printer-queue", new String[]{"printer", "print", "queue"});
        }
        if (containsAny(text, new String[]{"vpn", "wifi", "network", "router", "switch"})) {
            return new Classification("NETWORK", "refresh-network-session", new String[]{"vpn", "wifi", "network", "router", "switch"});
        }
        if (containsAny(text, new String[]{"service", "daemon", "application unavailable", "tomcat",
                "unresponsive", "not responding", "app is down", "application is down", "502", "hung", "crashed"})) {
            return new Classification("APPLICATION", "restart-approved-service", new String[]{"service", "application", "daemon"});
        }

        return new Classification("UNCLASSIFIED", "", new String[]{});
    }

    private String deriveCategoryFromAction(String actionKey, String title) {
        if (actionKey == null) return "APPLICATION";
        String upper = actionKey.toUpperCase(Locale.ROOT);
        if (upper.contains("NETWORK") || upper.contains("VPN") || upper.contains("WIFI")) return "NETWORK";
        if (upper.contains("PRINT")) return "PRINTING";
        if (upper.contains("DB") || upper.contains("DATABASE") || upper.contains("POSTGRES") || upper.contains("SQL")) return "DATABASE";
        return "APPLICATION";
    }

    private String deriveActionFromKey(String actionKey) {
        if (actionKey == null || actionKey.isBlank()) return "restart-approved-service";
        String lower = actionKey.toLowerCase(Locale.ROOT);
        if (lower.startsWith("restart_service") || lower.startsWith("restart-approved-service")) return "restart-approved-service";
        if (lower.startsWith("clear_printer") || lower.startsWith("clear-printer-queue")) return "clear-printer-queue";
        if (lower.startsWith("refresh_network") || lower.startsWith("refresh-network-session")) return "refresh-network-session";
        if (lower.startsWith("check_url")) return "check-url";
        return "restart-approved-service";
    }

    private boolean containsAny(String text, String[] terms) {
        for (String term : terms) if (!term.isBlank() && text.contains(term)) return true;
        return false;
    }

    private double systemHealth(Incident incident) {
        String priority = safe(incident.getPriority()).toUpperCase(Locale.ROOT);
        return "P1".equals(priority) ? 0.30 : "P2".equals(priority) ? 0.55 : 0.80;
    }

    private double riskPenalty(Incident incident, String action) {
        if (action.isBlank()) return 0.40;
        String priority = safe(incident.getPriority()).toUpperCase(Locale.ROOT);
        return "P1".equals(priority) ? 0.60 : "P2".equals(priority) ? 0.30 : 0.10;
    }

    private String target(Incident incident) {
        return IncidentTarget.hostOrTicket(incident);
    }

    private String safe(String value) { return value == null ? "" : value; }
    private double clamp(double value) { return Math.max(0.0, Math.min(100.0, value)); }

    private record Classification(String category, String action, String[] keywords) {}

    public record Assessment(String category, String action, String target, double patternSimilarity,
                             double historicalSuccess, double sopReliability, double systemHealth,
                             double riskPenalty, double confidenceScore, String route,
                             Map<String, String> evidence) {}
}
