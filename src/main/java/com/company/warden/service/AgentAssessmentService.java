package com.company.warden.service;

import com.company.warden.model.Incident;
import com.company.warden.model.SopProcedure;
import com.company.warden.repository.SopProcedureRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Coordinates the explainable agent stages used by the HITL planner. LLM/RAG
 * content may enrich a plan, but these stages deliberately make the safety
 * decision from bounded, inspectable values.
 *
 * <p>There is no aggregate score. The routing rule is the one the product actually states: an
 * approved procedure backs this incident and the classifier named a tool, so a human is offered
 * the plan. Anything else escalates to a person working by hand. The individual factors below
 * survive because a reviewer reads them as evidence — how well the SOP matched, how often this
 * remediation has worked — and evidence is not the same thing as a number that decides.
 */
@Service
public class AgentAssessmentService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentAssessmentService.class);

    private final double defaultPriorSuccessRate;
    private final SopProcedureRepository sopProcedureRepository;
    /** Admin-authored categorisation vocabulary. Null in unit tests. */
    private final SkillService skills;

    public AgentAssessmentService(
            @org.springframework.beans.factory.annotation.Value("${mcp.sop.default-prior-success-rate:0.85}") double defaultPriorSuccessRate,
            SopProcedureRepository sopProcedureRepository,
            SkillService skills) {
        this.defaultPriorSuccessRate = defaultPriorSuccessRate;
        this.sopProcedureRepository = sopProcedureRepository;
        this.skills = skills;
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

    public SkillService.RuleResolution resolveFields(String category, String text,
                                                     Map<String, String> overrides) {
        return skills == null ? SkillService.RuleResolution.empty()
                : skills.resolve(category, text, overrides);
    }

    /**
     * @param historicalSuccess observed success rate for this remediation, in [0,1].
     */
    public Assessment assess(Incident incident, SopEvidence evidence, double historicalSuccess) {
        return assess(incident, evidence, historicalSuccess, 0.0);
    }

    /**
     * @param precedentSimilarity how much of this incident's wording is already covered by
     *        a past incident that a human approved and that was successfully remediated,
     *        in [0,1]. Zero when there is no such incident.
     */
    public Assessment assess(Incident incident, SopEvidence evidence, double historicalSuccess,
                             double precedentSimilarity) {
        String text = (safe(incident.getSubject()) + " " + safe(incident.getDescription())).toLowerCase(Locale.ROOT);
        Classification classification = classify(text);
        double keywordSimilarity = evidence.approvedEvidencePresent() && containsAny(evidence.excerpt().toLowerCase(Locale.ROOT), classification.keywords()) ? 0.90 : 0.0;
        precedentSimilarity = Math.max(0.0, Math.min(1.0, precedentSimilarity));
        double patternSimilarity = Math.max(keywordSimilarity, precedentSimilarity);
        historicalSuccess = Math.max(0.0, Math.min(1.0, historicalSuccess));
        double sopReliability = evidence.approvedEvidencePresent() ? evidence.reliability() : 0.0;
        double riskPenalty = riskPenalty(incident, classification.action());

        // Scenario A is exactly this: an approved SOP exists and a tool is known. A P1 no longer
        // fails the gate for being a P1 — its risk still reaches the reviewer through
        // riskPenalty, which is the right place for it. Suppressing the plan meant the operator
        // got a refusal instead of the script, evidence and rollback they needed anyway.
        String route = evidence.approvedEvidencePresent() && !classification.action().isBlank()
                ? "HITL_REQUIRED"
                : "ESCALATE";
        return new Assessment(classification.category(), classification.action(), target(incident), patternSimilarity,
                historicalSuccess, sopReliability, riskPenalty, route,
                Map.of("classification", classification.category(), "evidenceReason", evidence.reason(),
                        "keywordSimilarity", String.format(Locale.ROOT, "%.2f", keywordSimilarity),
                        "precedentSimilarity", String.format(Locale.ROOT, "%.2f", precedentSimilarity)));
    }

    /**
     * Dynamically classifies incident text using both the approved procedures in
     * sop.sop_procedure and the foundational fallback vocabulary.
     */
    private Classification classify(String text) {
        // 1. Check dynamic approved procedures
        if (sopProcedureRepository != null) {
            List<SopProcedure> procedures = sopProcedureRepository.findByApprovalStatus("APPROVED");
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

        // 2. Admin-authored categorisation skills, before the shipped vocabulary — a workspace
        //    that calls its tills "lanes" says so on the Skills page instead of waiting for a
        //    release. Shipped terms still run after, so adding a skill never removes a match.
        if (skills != null) {
            try {
                for (com.company.warden.model.Skill skill : skills.enabled(SkillService.CATEGORIZATION)) {
                    List<String> terms = SkillService.keywords(skill);
                    if (!terms.isEmpty() && containsAny(text, terms.toArray(new String[0]))) {
                        return new Classification(skill.getSkillKey().toUpperCase(Locale.ROOT),
                                skill.getActionKey() == null ? "" : skill.getActionKey(),
                                terms.toArray(new String[0]));
                    }
                }
            } catch (Exception e) {
                // Classification is on the path that decides whether a ticket is worth an
                // analyst opening first. An unreadable skills table degrades it to the shipped
                // vocabulary; it does not fail the assessment.
                log.warn("[SKILL] Categorisation skills unreadable, using built-in vocabulary: {}", e.getMessage());
            }
        }

        // 3. Foundational vocabulary fallback
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

    private double riskPenalty(Incident incident, String action) {
        if (action.isBlank()) return 0.40;
        String priority = safe(incident.getPriority()).toUpperCase(Locale.ROOT);
        return "P1".equals(priority) ? 0.60 : "P2".equals(priority) ? 0.30 : 0.10;
    }

    private String target(Incident incident) {
        return IncidentTarget.hostOrTicket(incident);
    }

    private String safe(String value) { return value == null ? "" : value; }

    private record Classification(String category, String action, String[] keywords) {}

    public record Assessment(String category, String action, String target, double patternSimilarity,
                             double historicalSuccess, double sopReliability,
                             double riskPenalty, String route,
                             Map<String, String> evidence) {}
}
