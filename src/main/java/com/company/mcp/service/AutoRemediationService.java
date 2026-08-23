package com.company.mcp.service;

import com.company.mcp.model.ActionExecution;
import com.company.mcp.model.Incident;
import com.company.mcp.model.IncidentHistory;
import com.company.mcp.model.RemediationPlan;
import com.company.mcp.model.SystemConfig;
import com.company.mcp.repository.ActionExecutionRepository;
import com.company.mcp.repository.IncidentHistoryRepository;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.repository.RemediationPlanRepository;
import com.company.mcp.repository.SystemConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one lane that acts without asking a human first.
 *
 * It exists for a narrow case: this tenant has already had this incident, a person
 * reviewed and approved a specific saved tool for it, that tool ran and succeeded, and
 * the tool does nothing worse than read or restart. Repeating it and sending mail is
 * better than waking somebody to click Approve on a decision they already made.
 *
 * Everything else about it is refusal. The gates below are ordered cheapest-first and
 * every one of them is a hard stop with a recorded reason — there is no "probably fine"
 * branch, and no retry. The authority to act is inherited from a human approval on a
 * past incident; if any link in that chain is missing or weaker than it looks, the
 * incident goes to the normal HITL queue instead.
 *
 * What is deliberately NOT here:
 *   - no background poller. This runs once, inline, when a ticket is created. A loop that
 *     re-examines old incidents would eventually act on one whose context has changed.
 *   - no bulk path. Imports do not reach this class: a 500-row import must never become
 *     500 unattended restarts.
 *   - no self-bootstrapping. Only executions carrying a hitl_request_id count as
 *     precedent, so an auto-run can never become the authority for the next auto-run.
 */
@Service
public class AutoRemediationService {
    private static final Logger log = LoggerFactory.getLogger(AutoRemediationService.class);

    /** UI-managed kill switch. Absent means off: this capability is opt-in, never inherited. */
    public static final String ENABLED_KEY = "autorun_enabled";

    /**
     * Tools safe enough to repeat unattended. CHECK_URL changes nothing; RESTART_SERVICE
     * is disruptive but bounded, reversible by repetition, and the single most common
     * approved remedy. CLEAR_CACHE and RERUN_JOB are absent on purpose — a flushed cache
     * cannot be restored and a non-idempotent job rerun can double-post its output, so
     * both keep needing a person.
     */
    private static final Set<String> AUTO_RUNNABLE_TOOLS = Set.of("CHECK_URL", "RESTART_SERVICE");

    /** Script provenance strong enough to repeat. A model's unreviewed guess is not. */
    private static final Set<String> TRUSTED_SCRIPT_SOURCES = Set.of("SOP_TEMPLATE", "SOP_GROUNDED");

    /**
     * How much of the new ticket's wording the past one must already cover, and how many
     * distinct signal terms must line up.
     *
     * ponytail: two constants, not settings. Every threshold moved into the UI is a
     * threshold somebody can quietly lower until autonomy means nothing; the kill switch
     * is the control that matters. The term floor is there because coverage alone is
     * gameable by a two-word ticket — "printer offline" would match at 1.00.
     */
    private static final double MIN_SIMILARITY = 0.60;
    private static final int MIN_MATCHED_TERMS = 3;

    private final SystemConfigRepository config;
    private final IncidentPrecedentService precedents;
    private final RemediationToolRegistry tools;
    private final GuardrailService guardrails;
    private final RemediationPlanRepository plans;
    private final ActionExecutionRepository executions;
    private final IncidentRepository incidents;
    private final IncidentHistoryRepository history;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ObjectMapper json;

    public AutoRemediationService(SystemConfigRepository config, IncidentPrecedentService precedents,
                                  RemediationToolRegistry tools, GuardrailService guardrails,
                                  RemediationPlanRepository plans, ActionExecutionRepository executions,
                                  IncidentRepository incidents, IncidentHistoryRepository history,
                                  AuditService audit, NotificationService notifications, ObjectMapper json) {
        this.config = config;
        this.precedents = precedents;
        this.tools = tools;
        this.guardrails = guardrails;
        this.plans = plans;
        this.executions = executions;
        this.incidents = incidents;
        this.history = history;
        this.audit = audit;
        this.notifications = notifications;
        this.json = json;
    }

    /** Read per call from the database, so the UI toggle takes effect on the next ticket. */
    public boolean enabled() {
        return config.findById(ENABLED_KEY)
                .map(SystemConfig::getConfigValue)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    public void setEnabled(boolean value) {
        config.save(new SystemConfig(ENABLED_KEY, Boolean.toString(value)));
        log.warn("[AUTORUN] Unattended remediation {} by configuration", value ? "ENABLED" : "DISABLED");
    }

    /**
     * Decides whether this freshly-created incident can be fixed by repeating a saved tool,
     * and does it if so.
     *
     * @return the outcome, always. {@code ran} is false for every refusal and {@code reason}
     *         names the gate that closed, because "nothing happened" is only useful to an
     *         operator if they can find out why.
     */
    public Result considerNewIncident(Incident incident) {
        if (!enabled()) return Result.refused("AUTORUN_DISABLED");
        if (incident == null || incident.getId() == null || incident.getTenantId() == null) {
            return Result.refused("INCIDENT_NOT_PERSISTED");
        }
        // A P1 is the case where a human's judgement about blast radius is worth the wait.
        if ("P1".equalsIgnoreCase(incident.getPriority())) return Result.refused("P1_ALWAYS_NEEDS_A_HUMAN");

        boolean planInFlight = plans.findByIncidentIdOrderByCreatedAtDesc(incident.getId()).stream()
                .anyMatch(p -> Set.of("PENDING_APPROVAL", "APPROVED", "EXECUTING").contains(p.getStatus()));
        if (planInFlight) return Result.refused("PLAN_ALREADY_IN_FLIGHT");

        Optional<IncidentPrecedentService.Precedent> found = precedents.findPrecedent(incident.getTenantId(), incident);
        if (found.isEmpty()) return Result.refused("NO_COMPARABLE_RESOLVED_INCIDENT");
        IncidentPrecedentService.Precedent precedent = found.get();

        // Autonomy is inherited per store, and this is the gate that makes that true.
        //
        // A human approved a restart at store 0042. That approval is evidence about store
        // 0042's hardware, its network, its opening hours and its tolerance for a till going
        // down — not about store 0099, however identically the two tickets are worded. Same
        // store, same fix, no human: different store, back to the approval queue.
        //
        // Both blank compares equal, so a non-store incident behaves exactly as it did
        // before this gate existed.
        String store = IncidentTarget.store(incident);
        if (!store.equalsIgnoreCase(precedent.storeNumber())) {
            return Result.refused("STORE_MISMATCH:" + (store.isBlank() ? "none" : store)
                    + "!=" + (precedent.storeNumber().isBlank() ? "none" : precedent.storeNumber()));
        }

        if (precedent.similarity() < MIN_SIMILARITY) {
            return Result.refused("PRECEDENT_TOO_WEAK:%.2f<%.2f".formatted(precedent.similarity(), MIN_SIMILARITY));
        }
        if (precedent.matchedTerms().size() < MIN_MATCHED_TERMS) {
            return Result.refused("PRECEDENT_TOO_THIN:" + precedent.matchedTerms().size() + "_terms");
        }
        if (!TRUSTED_SCRIPT_SOURCES.contains(precedent.scriptSource())) {
            return Result.refused("SCRIPT_SOURCE_NOT_TRUSTED:" + precedent.scriptSource());
        }
        // The past plan must still name the approved procedures it was built from. Checked
        // here rather than left to the guardrail evaluation below — which would also refuse,
        // but under a reason that reads like a policy failure instead of a missing citation.
        if (precedent.procedureIds().isEmpty() || precedent.sopEvidence().isBlank()) {
            return Result.refused("PRECEDENT_NOT_SOP_BACKED");
        }

        RemediationToolRegistry.ParsedAction parsed = tools.parse(precedent.actionKey());
        if (!parsed.valid()) return Result.refused("PRECEDENT_ACTION_UNRUNNABLE:" + parsed.reason());
        if (!AUTO_RUNNABLE_TOOLS.contains(parsed.tool().name())) {
            return Result.refused("TOOL_NOT_AUTO_RUNNABLE:" + parsed.tool().name());
        }

        // Re-scanned now, not trusted from the past plan: a term added to the block list
        // since that approval must still stop this script. PASS, not merely non-blocking —
        // a WARN on something nobody is about to read is not a risk worth taking.
        GuardrailService.ScriptScan scan = guardrails.scanScript(precedent.script());
        if (!"PASS".equals(scan.level())) return Result.refused("SCRIPT_SCAN_NOT_CLEAN:" + scan.level());

        // The target is this incident's, never the precedent's: repeating an approved tool
        // means doing the same thing to the machine that is broken now, not to the one that
        // was broken last month.
        String target = IncidentTarget.hostOrTicket(incident);

        GuardrailService.Result check = guardrails.evaluate(precedent.actionName(), target,
                precedent.asEvidence(), 0);
        if (!check.passed()) {
            return Result.refused("GUARDRAIL_BLOCKED:" + check.findings().stream()
                    .filter(GuardrailService::isBlockingFinding).findFirst().orElse("UNKNOWN"));
        }

        // The connection the operator had to establish for this store last time is part of
        // what was proven. Inherited, not written back to the incident: an operator changing
        // it on the ticket must stay the only way it changes.
        String connection = IncidentTarget.connection(incident);
        if (connection.isBlank()) connection = precedent.connectionMethod();

        // Last, because it is the only gate that leaves this process: there is no point
        // dialling a host for an action policy has already refused.
        //
        // A mutating tool with no named machine stops here. There is no human watching an
        // unattended run, so there is nobody to ask — and "restart the service on whichever
        // host the executor guesses" is the exact accident this lane must not have.
        // UNREACHABLE is a refusal so the ticket goes to a person who can confirm the
        // server; UNKNOWN (no executor configured) is not, because that is the demo path.
        if (parsed.tool().mutating()) {
            IncidentTarget.Target host = IncidentTarget.resolve(incident);
            if (!host.known()) return Result.refused("TARGET_HOST_UNKNOWN");
            RemediationToolRegistry.Probe reach = tools.reachable(host.host(), connection);
            if (reach.unreachable()) return Result.refused(reach.reason());

            // This lane repeats a stored script rather than writing a new one, so the machine
            // it repeats it on has to be the same *kind* of machine. A Windows till and a
            // Linux application server can sit in one store under one store number, and
            // Restart-Service dispatched to bash is not a fix, it is a mystery in a log.
            //
            // ponytail: compares the interpreter, so windows-vs-unix is caught and
            // linux-vs-darwin is not (both are bash, and only the service manager differs —
            // which fails loudly on the host and lands the ticket back with a person rather
            // than doing damage). Record the platform on remediation_plans and compare that
            // instead if a mixed Linux/macOS estate ever turns up.
            String language = IncidentTarget.platform(incident, reach.platform(), parsed.platformHint()).language();
            if (!language.equalsIgnoreCase(precedent.scriptLanguage())) {
                return Result.refused("PLATFORM_MISMATCH:" + precedent.scriptLanguage() + "!=" + language);
            }
        }

        return run(incident, precedent, parsed, target, connection, scan);
    }

    /**
     * One attempt. No retry, ever: "restart the service" is not safely idempotent when the
     * first call may have succeeded and only its response was lost, and a failed unattended
     * action is precisely the moment to hand over to a person.
     */
    private Result run(Incident incident, IncidentPrecedentService.Precedent precedent,
                       RemediationToolRegistry.ParsedAction parsed, String target, String connection,
                       GuardrailService.ScriptScan scan) {
        String tenant = incident.getTenantId();
        RemediationPlan plan = plan(incident, precedent, parsed, target, scan);
        plans.save(plan);

        RemediationToolRegistry.Outcome outcome = tools.execute(precedent.actionKey(), precedent.script(),
                precedent.scriptLanguage(), target, connection, false);

        ActionExecution execution = new ActionExecution();
        execution.setTenantId(tenant);
        execution.setIncidentId(incident.getId());
        execution.setPlanId(plan.getId());
        // Null on purpose, and load-bearing: this is how the precedent query tells a
        // human-approved execution from an unattended one, so this run can never become
        // the authority for the next.
        execution.setHitlRequestId(null);
        execution.setMode(outcome.mode());
        execution.setStatus(outcome.status());
        execution.setOutput(outcome.output());
        execution.setValidationResult(("Unattended run. Authority: incident %s, whose plan for the identical "
                + "action key '%s' was approved by a human and executed successfully. %d of this incident's "
                + "%.0f%%-matching terms were shared (%s). The script was re-scanned at execution time (%s) and "
                + "the action was re-checked against the deterministic guardrail boundary. No command ran inside "
                + "this process.").formatted(precedent.reference(), precedent.actionKey(),
                precedent.matchedTerms().size(), precedent.similarity() * 100.0,
                String.join(", ", precedent.matchedTerms()), scan.level()));
        execution.setCompletedAt(OffsetDateTime.now());
        executions.save(execution);

        // Three states, not two. "SIMULATED" means the platform is not wired to change
        // anything (no executor agent, or execution switched off) — reporting that as a
        // failed remediation would send an alarm about a thing that never started.
        boolean simulatedOnly = "SIMULATED".equals(outcome.mode());
        plan.setAttempts(1);
        plan.setStatus(simulatedOnly ? "SIMULATED" : outcome.succeeded() ? "EXECUTED" : "FAILED");
        plans.save(plan);

        if (!simulatedOnly) {
            String previous = incident.getStatus();
            incident.setStatus(outcome.succeeded() ? "RESOLVED" : "ESCALATED");
            incident.setUpdatedAt(OffsetDateTime.now());
            incidents.save(incident);
            history.save(new IncidentHistory(UUID.randomUUID(), incident.getId(), "status", previous,
                    incident.getStatus(), "System (auto-remediation)", OffsetDateTime.now()));
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("planId", plan.getId());
        details.put("executionId", execution.getId());
        details.put("actionKey", precedent.actionKey());
        details.put("tool", parsed.tool().name());
        details.put("target", target);
        details.put("connection", connection.isBlank() ? "executor-default" : connection);
        details.put("store", IncidentTarget.store(incident).isBlank() ? "none" : IncidentTarget.store(incident));
        details.put("precedentIncident", precedent.reference());
        details.put("precedentSimilarity", Math.round(precedent.similarity() * 100.0) / 100.0);
        details.put("precedentMatchedTerms", precedent.matchedTerms());
        details.put("scriptSource", precedent.scriptSource());
        details.put("scriptScanLevel", scan.level());
        details.put("mode", outcome.mode());
        details.put("status", outcome.status());
        details.put("reason", outcome.reason());
        details.put("approvedBy", "NOBODY:inherited from the human approval on " + precedent.reference());
        // Deliberately before the email: AuditService throws if it cannot write, and an
        // unattended action nobody can audit must not also be an email nobody can trace.
        audit.record(tenant, "ACTION_EXECUTION", execution.getId(), "AUTO_REMEDIATION_EXECUTED",
                "system:auto-remediation", details);

        // Mail only when something actually happened on a real system. A simulated no-op
        // is recorded and visible in the UI, but it is not news.
        boolean notified = !simulatedOnly && notifications.notifyAutoRemediation(incident,
                precedent.actionName(), target, parsed.tool().name(), outcome.succeeded(), precedent.reference());

        log.info("[AUTORUN] Incident {} ran {} from precedent {} -> {} ({}), notified={}",
                incident.getId(), precedent.actionKey(), precedent.reference(), outcome.status(),
                outcome.mode(), notified);

        return new Result(!simulatedOnly, simulatedOnly ? "NOTHING_EXECUTED:" + outcome.reason() : outcome.status(),
                precedent.reference(), precedent.actionKey(), outcome.succeeded() && !simulatedOnly, notified);
    }

    private RemediationPlan plan(Incident incident, IncidentPrecedentService.Precedent precedent,
                                 RemediationToolRegistry.ParsedAction parsed, String target,
                                 GuardrailService.ScriptScan scan) {
        RemediationPlan plan = new RemediationPlan();
        plan.setTenantId(incident.getTenantId());
        plan.setIncidentId(incident.getId());
        plan.setStatus("EXECUTING");
        plan.setActionName(precedent.actionName());
        plan.setTarget(target);
        plan.setParametersJson(parameters(precedent, parsed));
        plan.setSopEvidence(precedent.sopEvidence());
        // Not a model's confidence: the measured share of this ticket's wording that the
        // precedent already covers. That is the whole basis for acting, so it is what the
        // plan records.
        plan.setConfidenceScore(Math.round(precedent.similarity() * 10000.0) / 100.0);
        plan.setRiskScore(parsed.tool().mutating() ? 30.0 : 0.0);
        plan.setGuardrailStatus("PASS");
        plan.setGuardrailFindings("AUTO_RUN_FROM_APPROVED_PRECEDENT;SCRIPT_RESCAN_" + scan.level()
                + ";PRECEDENT_" + precedent.reference());
        plan.setRemediationScript(precedent.script());
        plan.setScriptLanguage(precedent.scriptLanguage());
        plan.setScriptSource(precedent.scriptSource());
        plan.setScriptScanLevel(scan.level());
        plan.setRollbackPlan(parsed.tool().mutating()
                ? "A restart is not reversible, but it is repeatable. This ran unattended and was NOT retried: "
                    + "if the service did not come back, start it by hand on " + target + " and read its log from "
                    + "the moment of the restart. Escalate to the application owner rather than restarting again."
                : "None required. The probe is read-only and changed nothing.");
        // Hashed like any other plan, so the recorded script cannot be edited after the fact
        // and still match the execution row. Nobody approved this hash; it is an integrity
        // seal on what ran, not a permission.
        plan.setPlanHash(hash(incident.getTenantId() + "|" + incident.getId() + "|" + precedent.actionName()
                + "|" + target + "|" + precedent.procedureIds() + "|" + plan.getParametersJson()
                + "|" + precedent.script()));
        return plan;
    }

    private String parameters(IncidentPrecedentService.Precedent precedent,
                              RemediationToolRegistry.ParsedAction parsed) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("classification", "AUTO_PRECEDENT");
            params.put("procedureIds", precedent.procedureIds());
            params.put("approvedActionKey", precedent.actionKey());
            params.put("scriptSource", precedent.scriptSource());
            params.put("scriptLanguage", precedent.scriptLanguage());
            params.put("tool", parsed.tool().name());
            params.put("precedent", Map.of(
                    "reference", precedent.reference(),
                    "incidentId", precedent.incidentId().toString(),
                    "actionKey", precedent.actionKey(),
                    "similarity", Math.round(precedent.similarity() * 100.0) / 100.0,
                    "matchedTerms", precedent.matchedTerms(),
                    "resolutionNote", precedent.resolutionNote(),
                    "resolvedAt", String.valueOf(precedent.resolvedAt())));
            return json.writeValueAsString(params);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param ran        whether anything reached a real system
     * @param reason     the outcome status, or the gate that refused
     * @param resolved   whether the incident is now believed fixed
     * @param notified   whether the relay accepted the notification
     */
    public record Result(boolean ran, String reason, String precedentReference, String actionKey,
                         boolean resolved, boolean notified) {
        static Result refused(String reason) {
            return new Result(false, reason, "", "", false, false);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("autoRemediationRan", ran);
            map.put("reason", reason);
            map.put("precedentIncident", precedentReference);
            map.put("actionKey", actionKey);
            map.put("resolved", resolved);
            map.put("notified", notified);
            return map;
        }

        /** Refusal reasons worth logging at INFO: a real candidate that a gate stopped. */
        public boolean informative() {
            return !ran && !List.of("AUTORUN_DISABLED", "NO_COMPARABLE_RESOLVED_INCIDENT").contains(reason);
        }
    }
}
