package com.company.mcp.service;

import com.company.mcp.model.ExternalIncident;
import com.company.mcp.model.Incident;
import com.company.mcp.model.IncidentHistory;
import com.company.mcp.model.ExecutionLog;
import com.company.mcp.repository.ExternalIncidentRepository;
import com.company.mcp.repository.ExecutionLogRepository;
import com.company.mcp.repository.IncidentHistoryRepository;
import com.company.mcp.repository.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.StreamSupport;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AutonomousRemediationService {

    private static final Logger log = LoggerFactory.getLogger(AutonomousRemediationService.class);
    private final IncidentRepository incidents;
    private final ExternalIncidentRepository externalIncidents;
    private final IncidentHistoryRepository history;
    private final ExecutionLogRepository executionLogs;
    private final RestClient.Builder restClientBuilder;
    private final AtomicBoolean cycleRunning = new AtomicBoolean(false);

    @Value("${mcp.autonomy.enabled:false}")
    private boolean enabled;
    @Value("${mcp.autonomy.execution-mode:SIMULATED}")
    private String executionMode;
    @Value("${mcp.autonomy.batch-size:10}")
    private int batchSize;
    @Value("${mcp.autonomy.poll-interval-ms:60000}")
    private long pollIntervalMs;
    @Value("${mcp.autonomy.allow-p1:false}")
    private boolean allowP1;
    @Value("${mcp.autonomy.max-retries:2}")
    private int maxRetries;
    @Value("${mcp.autonomy.executor-url:}")
    private String executorUrl;

    public AutonomousRemediationService(IncidentRepository incidents,
                                         ExternalIncidentRepository externalIncidents,
                                         IncidentHistoryRepository history,
                                         ExecutionLogRepository executionLogs,
                                         RestClient.Builder restClientBuilder) {
        this.incidents = incidents;
        this.externalIncidents = externalIncidents;
        this.history = history;
        this.executionLogs = executionLogs;
        this.restClientBuilder = restClientBuilder;
    }

    @Scheduled(fixedDelayString = "${mcp.autonomy.poll-interval-ms:60000}")
    public void scheduledCycle() {
        if (enabled) runCycle();
    }

    @Transactional
    public Map<String, Object> runCycle() {
        if (!cycleRunning.compareAndSet(false, true)) {
            return Map.of("status", "BUSY", "processed", 0);
        }
        int processed = 0;
        int resolved = 0;
        int blocked = 0;
        try {
            for (Incident incident : incidents.findAll()) {
                if (processed >= Math.max(1, batchSize)) break;
                if (!isReady(incident.getStatus())) continue;
                Outcome outcome = process(incident);
                processed++;
                if ("RESOLVED".equals(outcome.status)) resolved++;
                if (outcome.blocked) blocked++;
            }
            for (ExternalIncident incident : externalIncidents.findAll()) {
                if (processed >= Math.max(1, batchSize)) break;
                if (!isReady(incident.getStatus())) continue;
                Outcome outcome = process(incident);
                processed++;
                if ("RESOLVED".equals(outcome.status)) resolved++;
                if (outcome.blocked) blocked++;
            }
            return Map.of("status", "COMPLETED", "processed", processed, "resolved", resolved, "blocked", blocked);
        } finally {
            cycleRunning.set(false);
        }
    }

    public Map<String, Object> status() {
        long active = StreamSupport.stream(incidents.findAll().spliterator(), false)
                .filter(i -> isReady(i.getStatus()) || "AUTOMATION_RUNNING".equals(i.getStatus())).count();
        active += StreamSupport.stream(externalIncidents.findAll().spliterator(), false)
                .filter(i -> isReady(i.getStatus()) || "AUTOMATION_RUNNING".equals(i.getStatus())).count();
        return Map.of("enabled", enabled, "executionMode", executionMode, "pollIntervalMs", pollIntervalMs,
                "batchSize", batchSize, "activeCandidates", active, "cycleRunning", cycleRunning.get(),
                "allowP1", allowP1, "maxRetries", maxRetries);
    }

    public List<ExecutionLog> recentTraces(int limit) {
        return executionLogs.findAll().stream()
                .sorted(Comparator.comparing(ExecutionLog::getTimestamp, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, Math.min(limit, 100)))
                .toList();
    }

    private boolean isReady(String status) {
        // Legacy direct execution is intentionally disabled. Remediation must use a persisted HITL request and dry-run path.
        return false;
    }

    private Outcome process(Incident incident) {
        return processCommon(incident.getId(), incident.getSubject(), incident.getDescription(), incident.getPriority(),
                null, status -> { incident.setStatus(status); incidents.save(incident); });
    }

    private Outcome process(ExternalIncident incident) {
        return processCommon(incident.getId(), incident.getSubject(), incident.getDescription(), incident.getPriority(),
                null, status -> { incident.setStatus(status); externalIncidents.save(incident); });
    }

    private Outcome processCommon(UUID id, String subject, String description, String priority, String targetHost,
                                  java.util.function.Consumer<String> setStatus) {
        if ("P1".equalsIgnoreCase(priority) && !allowP1) {
            setStatus.accept("PENDING_APPROVAL");
            trace(id, subject, "policy-agent", "POLICY_GATE", "BLOCKED", 1, "P1 requires explicit enterprise policy enablement.", "P1 auto-remediation disabled");
            return new Outcome("PENDING_APPROVAL", true);
        }

        String script = buildSafeAction(subject, description);
        if (script.isBlank()) {
            setStatus.accept("PENDING_APPROVAL");
            trace(id, subject, "policy-agent", "ACTION_SELECTION", "BLOCKED", 1, "No approved action matched this incident.", "Manual or human-guided remediation required.");
            return new Outcome("PENDING_APPROVAL", true);
        }

        setStatus.accept("AUTOMATION_RUNNING");
        trace(id, subject, "execution-agent", "EXECUTE", "RUNNING", 0, "Mode=" + executionMode + "; target=" + (targetHost == null ? "store-device" : targetHost), script);

        ExecutionResult execution = executeAction(id, subject, script, targetHost);
        boolean success = execution.success;
        String validation = success ? "PASS" : "FAIL";
        if (success) {
            setStatus.accept("RESOLVED");
            saveHistory(id, "status", "AUTOMATION_RUNNING", "RESOLVED", "autonomous-execution-agent");
            saveHistory(id, "autonomy_learning", "validation", "PASS", "autonomous-learning-agent");
        } else if (executionAttempts(id) < Math.max(0, maxRetries)) {
            setStatus.accept("AUTO_RETRY");
            saveHistory(id, "status", "AUTOMATION_RUNNING", "AUTO_RETRY", "autonomous-validation-agent");
        } else {
            setStatus.accept("ESCALATED");
            saveHistory(id, "status", "AUTOMATION_RUNNING", "ESCALATED", "autonomous-validation-agent");
            saveHistory(id, "autonomy_learning", "validation", "FAIL", "autonomous-learning-agent");
        }
        trace(id, subject, "validation-agent", "POST_VALIDATE", validation, success ? 0 : 1,
                execution.output, validation);
        return new Outcome(success ? "RESOLVED" : (executionAttempts(id) <= Math.max(0, maxRetries) ? "AUTO_RETRY" : "ESCALATED"), false);
    }

    public Map<String, Object> learningSummary() {
        List<ExecutionLog> validations = executionLogs.findAll().stream()
                .filter(log -> "POST_VALIDATE".equalsIgnoreCase(log.getPhase())).toList();
        long passed = validations.stream().filter(log -> "PASS".equalsIgnoreCase(log.getValidationStatus())).count();
        long failed = validations.size() - passed;
        double passRate = validations.isEmpty() ? 0.0 : (passed * 100.0) / validations.size();
        return Map.of("validationRuns", validations.size(), "passed", passed, "failed", failed,
                "passRate", Math.round(passRate * 100.0) / 100.0,
                "learningPolicy", "Successful actions remain eligible; failed actions retry within policy then escalate.");
    }

    private int executionAttempts(UUID incidentId) {
        return (int) executionLogs.findAll().stream()
                .filter(log -> incidentId.equals(log.getIncidentId()) && "EXECUTE".equalsIgnoreCase(log.getPhase()))
                .count();
    }

    private ExecutionResult executeAction(UUID incidentId, String subject, String script, String targetHost) {
        if ("SIMULATED".equalsIgnoreCase(executionMode)) {
            return new ExecutionResult(true, "[SIMULATED] Approved action completed for incident " + incidentId + ".");
        }
        if (!"HTTP".equalsIgnoreCase(executionMode) || executorUrl == null || executorUrl.isBlank()) {
            return new ExecutionResult(false, "No production executor configured. Set MCP_AUTONOMY_EXECUTION_MODE=HTTP and MCP_AUTONOMY_EXECUTOR_URL.");
        }
        try {
            Map<?, ?> response = restClientBuilder.build().post().uri(executorUrl).body(Map.of(
                    "incidentId", incidentId.toString(), "subject", subject == null ? "" : subject,
                    "script", script, "targetHost", targetHost == null ? "store-device" : targetHost
            )).retrieve().body(Map.class);
            boolean success = response != null && Boolean.TRUE.equals(response.get("success"));
            Object message = response == null ? null : response.get("message");
            return new ExecutionResult(success, message == null ? (response == null ? "Executor returned no response." : response.toString()) : String.valueOf(message));
        } catch (Exception e) {
            log.warn("Production executor failed for {}: {}", incidentId, e.getMessage());
            return new ExecutionResult(false, "Executor request failed: " + e.getMessage());
        }
    }

    private String buildSafeAction(String subject, String description) {
        String text = ((subject == null ? "" : subject) + " " + (description == null ? "" : description)).toLowerCase(Locale.ROOT);
        if (text.contains("restart") || text.contains("service unavailable") || text.contains("pos offline") || text.contains("pos_offline") || text.contains("kiosk offline") || text.contains("kiosk_offline")) {
            return "restart-approved-service --target store-device\nhealth-check --target store-device";
        }
        if (text.contains("printer") && (text.contains("offline") || text.contains("jam"))) {
            return "clear-printer-queue --target store-device\nhealth-check --target printer";
        }
        if (text.contains("network") || text.contains("vpn") || text.contains("wifi")) {
            return "refresh-network-session --target store-device\nhealth-check --target network";
        }
        return "";
    }

    private void trace(UUID incidentId, String name, String agent, String phase, String validation, int exitCode, String stdout, String script) {
        try {
            ExecutionLog logEntry = new ExecutionLog();
            logEntry.setId(UUID.randomUUID());
            logEntry.setIncidentId(incidentId);
            logEntry.setAgent(agent);
            logEntry.setPhase(phase);
            logEntry.setValidationStatus(validation);
            logEntry.setName(name == null ? "Autonomous remediation" : name);
            logEntry.setTimestamp(OffsetDateTime.now());
            logEntry.setScriptContent(script == null ? "" : script);
            logEntry.setStatus("RUNNING".equals(validation) ? "RUNNING" : ("PASS".equals(validation) ? "SUCCESS" : "BLOCKED"));
            logEntry.setExitCode(exitCode);
            logEntry.setStdout(stdout);
            logEntry.setStderr("SIMULATED".equalsIgnoreCase(executionMode) ? "Execution adapter is in SIMULATED mode." : "");
            executionLogs.save(logEntry);
        } catch (Exception e) {
            log.warn("Unable to persist autonomy trace for {}: {}", incidentId, e.getMessage());
        }
    }

    private void saveHistory(UUID id, String field, String oldValue, String newValue, String actor) {
        history.save(new IncidentHistory(UUID.randomUUID(), id, field, oldValue, newValue, actor, OffsetDateTime.now()));
    }

    private record Outcome(String status, boolean blocked) {}
    private record ExecutionResult(boolean success, String output) {}
}
