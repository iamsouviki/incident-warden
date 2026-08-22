package com.company.mcp.service;

import com.company.mcp.model.ExecutionLog;
import com.company.mcp.model.Incident;
import com.company.mcp.repository.ExecutionLogRepository;
import com.company.mcp.repository.ExternalIncidentRepository;
import com.company.mcp.repository.IncidentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Read-only view of the remediation loop for the Autonomous operations page.
 *
 * This class used to contain a second remediation engine: a keyword classifier that
 * picked an action from the incident text, its own HTTP executor contract, and a status
 * machine that could move an incident to RESOLVED on its own. It was unreachable —
 * gated behind a hardcoded {@code isReady() == false} — and it has been deleted rather
 * than left dormant. It shared nothing with the path that actually runs: no SOP
 * grounding, no guardrail scan, no plan hash, no human approval. One flipped boolean
 * would have routed remediation around every control in
 * {@link HitlWorkflowService}, which is precisely the failure a reviewer of this
 * codebase should not have to notice.
 *
 * Remediation now has exactly one route: an approved SOP or model-written script,
 * scanned, hash-pinned, approved by a human, dispatched to the executor agent. This
 * class only reports on it.
 */
@Service
public class AutonomousRemediationService {

    private final IncidentRepository incidents;
    private final ExternalIncidentRepository externalIncidents;
    private final ExecutionLogRepository executionLogs;
    private final AutoRemediationService autoRemediation;
    private final RemediationToolRegistry toolRegistry;

    @Value("${mcp.autonomy.enabled:false}")
    private boolean enabled;
    @Value("${mcp.autonomy.poll-interval-ms:60000}")
    private long pollIntervalMs;
    @Value("${mcp.autonomy.allow-p1:false}")
    private boolean allowP1;
    @Value("${mcp.autonomy.max-retries:2}")
    private int maxRetries;

    public AutonomousRemediationService(IncidentRepository incidents,
                                        ExternalIncidentRepository externalIncidents,
                                        ExecutionLogRepository executionLogs,
                                        AutoRemediationService autoRemediation,
                                        RemediationToolRegistry toolRegistry) {
        this.incidents = incidents;
        this.externalIncidents = externalIncidents;
        this.executionLogs = executionLogs;
        this.autoRemediation = autoRemediation;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Kept because the UI's "run cycle" button posts here, and answering it honestly is
     * better than removing the button and leaving the operator to guess whether an
     * autonomous loop exists. There is still no cycle to run — nothing scans old incidents
     * looking for work. The one unattended path is
     * {@link AutoRemediationService}, and it fires once, inline, when a ticket is created.
     */
    public Map<String, Object> runCycle() {
        boolean autorun = autoRemediation.enabled();
        return Map.of("status", autorun ? "PRECEDENT_AUTORUN_ON" : "HITL_ONLY",
                "processed", 0, "resolved", 0, "blocked", 0,
                "message", "There is no polling cycle to run. Remediation happens when a plan is approved "
                        + "in the review queue. " + (autorun
                        ? "Unattended remediation is ON: a newly logged incident that closely matches a "
                                + "resolved one repeats that incident's approved read-only or restart tool at "
                                + "creation time and emails the reporter and analysts. Everything else waits "
                                + "for approval."
                        : "Unattended remediation is OFF: nothing runs without an approval."));
    }

    public Map<String, Object> status() {
        return Map.of("enabled", enabled, "executionMode", toolRegistry.dispatchMode(), "pollIntervalMs", pollIntervalMs,
                "activeCandidates", running(), "cycleRunning", false,
                "allowP1", allowP1, "maxRetries", maxRetries,
                "autoRunFromPrecedent", autoRemediation.enabled(),
                "mode", autoRemediation.enabled() ? "HITL_PLUS_PRECEDENT_AUTORUN" : "HITL_ONLY");
    }

    /** Incidents with a dispatch in flight, across both incident tables. */
    private long running() {
        long count = StreamSupport.stream(incidents.findAll().spliterator(), false)
                .map(Incident::getStatus).filter("AUTOMATION_RUNNING"::equals).count();
        return count + StreamSupport.stream(externalIncidents.findAll().spliterator(), false)
                .filter(i -> "AUTOMATION_RUNNING".equals(i.getStatus())).count();
    }

    public List<ExecutionLog> recentTraces(int limit) {
        // ponytail: findAll + sort in memory. Fine at demo volume; replace with a
        // findTop100ByOrderByTimestampDesc derived query once the log table is large.
        return executionLogs.findAll().stream()
                .sorted(Comparator.comparing(ExecutionLog::getTimestamp, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, Math.min(limit, 100)))
                .toList();
    }

    public Map<String, Object> learningSummary() {
        List<ExecutionLog> validations = executionLogs.findAll().stream()
                .filter(entry -> "POST_VALIDATE".equalsIgnoreCase(entry.getPhase())).toList();
        long passed = validations.stream().filter(entry -> "PASS".equalsIgnoreCase(entry.getValidationStatus())).count();
        long failed = validations.size() - passed;
        double passRate = validations.isEmpty() ? 0.0 : (passed * 100.0) / validations.size();
        return Map.of("validationRuns", validations.size(), "passed", passed, "failed", failed,
                "passRate", Math.round(passRate * 100.0) / 100.0,
                "learningPolicy", "Post-execution validation feeds the confidence score for the next plan "
                        + "on the same action. It does not grant autonomy: every run is approved individually.");
    }
}
