package com.company.mcp.service;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class GuardrailService {
    private static final Set<String> ALLOWED_ACTIONS = Set.of("restart-approved-service", "clear-printer-queue", "refresh-network-session");
    private static final List<String> BLOCKED_TERMS = List.of("rm -rf", "drop table", "terraform destroy", "kubectl delete", "shutdown", "reboot", "ignore previous", "system prompt", "curl | sh");

    public Result evaluate(String action, String target, String sopEvidence, int activePlansForIncident) {
        List<String> findings = new ArrayList<>();
        if (!ALLOWED_ACTIONS.contains(action)) findings.add("ACTION_NOT_ALLOWLISTED");
        String normalizedTarget = target == null ? "" : target.toLowerCase(Locale.ROOT);
        if (normalizedTarget.isBlank() || normalizedTarget.contains("*") || normalizedTarget.contains(",") || normalizedTarget.contains("all")) findings.add("BLAST_RADIUS_EXCEEDED");
        String evidence = sopEvidence == null ? "" : sopEvidence.toLowerCase(Locale.ROOT);
        if (evidence.isBlank() || evidence.contains("unavailable") || evidence.contains("no tenant-approved sop")) findings.add("NO_APPROVED_SOP_EVIDENCE");
        for (String term : BLOCKED_TERMS) if ((action + " " + evidence).toLowerCase(Locale.ROOT).contains(term)) { findings.add("UNSAFE_OR_INJECTED_CONTENT:" + term); break; }
        if (activePlansForIncident > 0) findings.add("LOOP_DETECTED_ACTIVE_PLAN");
        findings.add("DRY_RUN_REQUIRED");
        findings.add("OUTPUT_SCHEMA_REQUIRED");
        return new Result(findings.stream().noneMatch(value -> !value.equals("DRY_RUN_REQUIRED") && !value.equals("OUTPUT_SCHEMA_REQUIRED")), findings);
    }

    public record Result(boolean passed, List<String> findings) {}
}
