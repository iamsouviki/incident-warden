package com.company.mcp.service;

import com.company.mcp.model.Incident;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Coordinates the explainable agent stages used by the HITL planner. LLM/RAG
 * content may enrich a plan, but these stages deliberately make the safety
 * decision from bounded, inspectable values.
 */
@Service
public class AgentAssessmentService {

    public Assessment assess(Incident incident, SopEvidence evidence) {
        String text = (safe(incident.getSubject()) + " " + safe(incident.getDescription())).toLowerCase(Locale.ROOT);
        Classification classification = classify(text);
        double patternSimilarity = evidence.approvedEvidencePresent() && containsAny(evidence.excerpt().toLowerCase(Locale.ROOT), classification.keywords()) ? 0.90 : 0.0;
        double historicalSuccess = 0.0; // Conservative until successful, approved executions are persisted and measured.
        double sopReliability = evidence.approvedEvidencePresent() ? evidence.reliability() : 0.0;
        double systemHealth = systemHealth(incident);
        double riskPenalty = riskPenalty(incident, classification.action());
        double score = clamp(100.0 * ((0.35 * patternSimilarity) + (0.25 * historicalSuccess) +
                (0.20 * sopReliability) + (0.15 * systemHealth) - riskPenalty));
        // A proposal may only reach a human approval queue when both trusted SOP evidence and the tenant's minimum HITL confidence band are met.
        // No automatic route is emitted: a later explicit approval and a simulation-only executor remain mandatory.
        String route = evidence.approvedEvidencePresent() && !classification.action().isBlank() && score >= 80.0 ? "HITL_REQUIRED" : "ESCALATE";
        return new Assessment(classification.category(), classification.action(), target(incident), patternSimilarity,
                historicalSuccess, sopReliability, systemHealth, riskPenalty, score, route,
                Map.of("classification", classification.category(), "evidenceReason", evidence.reason()));
    }

    private Classification classify(String text) {
        if (containsAny(text, new String[]{"printer", "print queue", "print job"})) return new Classification("PRINTING", "clear-printer-queue", new String[]{"printer", "print", "queue"});
        if (containsAny(text, new String[]{"vpn", "wifi", "network", "router", "switch"})) return new Classification("NETWORK", "refresh-network-session", new String[]{"vpn", "wifi", "network", "router", "switch"});
        if (containsAny(text, new String[]{"service", "daemon", "application unavailable"})) return new Classification("APPLICATION", "restart-approved-service", new String[]{"service", "application", "daemon"});
        return new Classification("UNCLASSIFIED", "", new String[]{});
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
        String target = safe(incident.getExternalId());
        return target.isBlank() ? "incident-" + incident.getId() : target;
    }

    private String safe(String value) { return value == null ? "" : value; }
    private double clamp(double value) { return Math.max(0.0, Math.min(100.0, value)); }

    private record Classification(String category, String action, String[] keywords) {}

    public record Assessment(String category, String action, String target, double patternSimilarity,
                             double historicalSuccess, double sopReliability, double systemHealth,
                             double riskPenalty, double confidenceScore, String route,
                             Map<String, String> evidence) {}
}
