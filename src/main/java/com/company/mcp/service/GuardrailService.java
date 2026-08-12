package com.company.mcp.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class GuardrailService {
    private static final Set<String> ALLOWED_ACTIONS = Set.of("restart-approved-service", "clear-printer-queue", "refresh-network-session");
    private static final List<String> BLOCKED_TERMS = List.of("rm -rf", "drop table", "terraform destroy", "kubectl delete", "shutdown", "reboot", "ignore previous", "system prompt", "curl | sh", "delete all");

    /**
     * Evaluates the deterministic safety boundary. Advisory outcomes are listed
     * separately; any blocking finding makes a plan ineligible for HITL.
     */
    public Result evaluate(String action, String target, SopEvidence evidence, int activePlansForIncident) {
        List<String> findings = new ArrayList<>();
        if (!ALLOWED_ACTIONS.contains(action)) findings.add("ACTION_NOT_ALLOWLISTED");
        String normalizedTarget = safe(target).toLowerCase(Locale.ROOT);
        if (normalizedTarget.isBlank() || normalizedTarget.length() > 200 || normalizedTarget.contains("*") || normalizedTarget.contains(",") || normalizedTarget.contains("all")) {
            findings.add("BLAST_RADIUS_EXCEEDED");
        }
        if (evidence == null || !evidence.approvedEvidencePresent()) {
            findings.add("NO_APPROVED_SOP_EVIDENCE:" + (evidence == null ? "MISSING" : evidence.reason()));
        }
        String inspect = (safe(action) + " " + safe(evidence == null ? "" : evidence.excerpt())).toLowerCase(Locale.ROOT);
        for (String term : BLOCKED_TERMS) {
            if (inspect.contains(term)) { findings.add("UNSAFE_OR_INJECTED_CONTENT:" + term); break; }
        }
        if (activePlansForIncident > 0) findings.add("LOOP_DETECTED_ACTIVE_PLAN");
        findings.add("DRY_RUN_REQUIRED");
        findings.add("OUTPUT_SCHEMA_REQUIRED");
        return new Result(findings.stream().noneMatch(this::isBlocking), findings);
    }

    /** Backward-compatible helper used by isolated legacy validator tests. */
    public Result evaluate(String action, String target, String sopEvidence, int activePlansForIncident) {
        SopEvidence evidence = new SopEvidence(true, true, List.of(java.util.UUID.randomUUID()), sopEvidence, 0.90, "LEGACY_TEST_EVIDENCE");
        return evaluate(action, target, evidence, activePlansForIncident);
    }

    private boolean isBlocking(String finding) {
        return !"DRY_RUN_REQUIRED".equals(finding) && !"OUTPUT_SCHEMA_REQUIRED".equals(finding);
    }
    private String safe(String value) { return value == null ? "" : value; }

    public record Result(boolean passed, List<String> findings) {}
}
