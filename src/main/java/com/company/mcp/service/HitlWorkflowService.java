package com.company.mcp.service;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.model.*;
import com.company.mcp.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class HitlWorkflowService {
    private final IncidentRepository incidents;
    private final RemediationPlanRepository plans;
    private final HitlRequestRepository requests;
    private final ActionExecutionRepository executions;
    private final CurrentUser currentUser;
    private final RagService rag;
    private final GuardrailService guardrails;
    private final AuditService audit;
    private final ObjectMapper json;
    @Value("${mcp.autonomy.execution-enabled:false}") private boolean executionEnabled;

    public HitlWorkflowService(IncidentRepository incidents, RemediationPlanRepository plans, HitlRequestRepository requests,
                               ActionExecutionRepository executions, CurrentUser currentUser, RagService rag,
                               GuardrailService guardrails, AuditService audit, ObjectMapper json) {
        this.incidents = incidents; this.plans = plans; this.requests = requests; this.executions = executions;
        this.currentUser = currentUser; this.rag = rag; this.guardrails = guardrails; this.audit = audit; this.json = json;
    }

    @Transactional
    public Map<String, Object> createPlan(UUID incidentId) {
        String tenant = currentUser.tenantId();
        Incident incident = incidents.findById(incidentId).filter(i -> tenant.equals(i.getTenantId()))
                .orElseThrow(() -> new NoSuchElementException("Incident not found"));
        boolean active = plans.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
                .anyMatch(p -> Set.of("PENDING_APPROVAL", "APPROVED", "EXECUTING").contains(p.getStatus()));
        String action = selectAction(incident.getSubject(), incident.getDescription());
        String evidence = rag.askStrictSopRag(UUID.randomUUID().toString(), incident.getSubject() + "\n" + incident.getDescription());
        double risk = risk(incident.getPriority());
        double pattern = action.isBlank() ? 0.0 : 1.0;
        double sop = evidence.contains("unavailable") || evidence.contains("couldn't find") ? 0.0 : 1.0;
        double score = Math.max(0, Math.min(100, 100 * ((.35 * pattern) + (.25 * 0.0) + (.20 * sop) + (.15 * .8) - risk)));
        GuardrailService.Result check = guardrails.evaluate(action, target(incident), evidence, active ? 1 : 0);
        RemediationPlan plan = new RemediationPlan();
        plan.setTenantId(tenant); plan.setIncidentId(incidentId); plan.setStatus(check.passed() ? "PENDING_APPROVAL" : "BLOCKED");
        plan.setActionName(action.isBlank() ? "none" : action); plan.setTarget(target(incident)); plan.setParametersJson("{}"); plan.setSopEvidence(evidence);
        plan.setConfidenceScore(score); plan.setRiskScore(risk * 100); plan.setGuardrailStatus(check.passed() ? "PASS" : "BLOCK");
        plan.setGuardrailFindings(String.join(";", check.findings())); plan.setRollbackPlan("No mutation has run. If execution is later enabled, invoke the tool-specific rollback runbook.");
        plan.setPlanHash(hash(tenant + "|" + incidentId + "|" + plan.getActionName() + "|" + plan.getTarget() + "|" + plan.getSopEvidence()));
        plans.save(plan);
        audit.record(tenant, "REMEDIATION_PLAN", plan.getId(), "PLAN_CREATED", currentUser.username(), Map.of("incidentId", incidentId, "status", plan.getStatus(), "score", score, "guardrails", check.findings()));
        if (!check.passed()) { incident.setStatus("ESCALATED"); incidents.save(incident); return Map.of("plan", plan, "hitlRequest", "", "route", "ESCALATE"); }
        HitlRequest request = new HitlRequest(); request.setTenantId(tenant); request.setIncidentId(incidentId); request.setPlanId(plan.getId()); request.setStatus("PENDING"); request.setRequestedBy(currentUser.username()); requests.save(request);
        incident.setStatus("PENDING_APPROVAL"); incident.setConfidenceScore(score); incidents.save(incident);
        audit.record(tenant, "HITL_REQUEST", request.getId(), "APPROVAL_REQUESTED", currentUser.username(), Map.of("planId", plan.getId(), "planHash", plan.getPlanHash()));
        return Map.of("plan", plan, "hitlRequest", request, "route", "HITL_REQUIRED");
    }

    public List<HitlRequest> pending() { return requests.findByTenantIdAndStatusOrderByCreatedAtAsc(currentUser.tenantId(), "PENDING"); }

    @Transactional
    public Map<String, Object> decide(UUID requestId, String decision, String reason) {
        String tenant = currentUser.tenantId();
        HitlRequest request = requests.findByIdAndTenantId(requestId, tenant).orElseThrow(() -> new NoSuchElementException("Approval request not found"));
        if (!"PENDING".equals(request.getStatus())) throw new IllegalStateException("Approval request is already decided");
        RemediationPlan plan = plans.findById(request.getPlanId()).filter(p -> tenant.equals(p.getTenantId())).orElseThrow(() -> new NoSuchElementException("Plan not found"));
        boolean approve = "APPROVE".equalsIgnoreCase(decision);
        request.setStatus(approve ? "APPROVED" : "REJECTED"); request.setReviewer(currentUser.username()); request.setDecisionReason(reason == null ? "" : reason); request.setDecidedAt(OffsetDateTime.now()); request.setApprovedPlanHash(approve ? plan.getPlanHash() : null); requests.save(request);
        plan.setStatus(approve ? "APPROVED" : "REJECTED"); plans.save(plan);
        Incident incident = incidents.findById(request.getIncidentId()).orElseThrow(); incident.setStatus(approve ? "APPROVED" : "REJECTED"); incidents.save(incident);
        audit.record(tenant, "HITL_REQUEST", request.getId(), approve ? "APPROVED" : "REJECTED", currentUser.username(), Map.of("planId", plan.getId(), "reason", request.getDecisionReason()));
        return Map.of("request", request, "plan", plan);
    }

    @Transactional
    public Map<String, Object> dryRunAndExecute(UUID requestId) {
        String tenant = currentUser.tenantId();
        HitlRequest request = requests.findByIdAndTenantId(requestId, tenant).orElseThrow(() -> new NoSuchElementException("Approval request not found"));
        if (!"APPROVED".equals(request.getStatus())) throw new IllegalStateException("Only an approved request can execute");
        RemediationPlan plan = plans.findById(request.getPlanId()).orElseThrow();
        if (!Objects.equals(request.getApprovedPlanHash(), plan.getPlanHash()) || !"PASS".equals(plan.getGuardrailStatus())) throw new IllegalStateException("Plan changed or guardrails no longer pass");
        ActionExecution execution = new ActionExecution(); execution.setTenantId(tenant); execution.setIncidentId(request.getIncidentId()); execution.setPlanId(plan.getId()); execution.setHitlRequestId(request.getId()); execution.setMode(executionEnabled ? "BLOCKED_HTTP" : "SIMULATED"); execution.setStatus("DRY_RUN_PASSED"); execution.setValidationResult("Target is singular, action allow-listed, plan hash approved, and no local shell execution is permitted."); execution.setOutput(executionEnabled ? "HTTP execution is deliberately not implemented in this service; use a separately allow-listed executor." : "Simulated dry run completed. No system mutation was performed."); execution.setCompletedAt(OffsetDateTime.now()); executions.save(execution);
        plan.setAttempts(plan.getAttempts() + 1); plan.setStatus("SIMULATED"); plans.save(plan);
        Incident incident = incidents.findById(request.getIncidentId()).orElseThrow(); incident.setStatus("RESOLUTION_SIMULATED"); incidents.save(incident);
        audit.record(tenant, "ACTION_EXECUTION", execution.getId(), "DRY_RUN_COMPLETED", currentUser.username(), Map.of("planId", plan.getId(), "mode", executionEnabled ? "BLOCKED_HTTP" : "SIMULATED"));
        return Map.of("execution", execution, "message", "Dry run recorded; no mutation was performed.");
    }

    private String selectAction(String subject, String description) { String t = (String.valueOf(subject) + " " + String.valueOf(description)).toLowerCase(); if (t.contains("printer")) return "clear-printer-queue"; if (t.contains("network") || t.contains("vpn") || t.contains("wifi")) return "refresh-network-session"; if (t.contains("offline") || t.contains("service") || t.contains("restart")) return "restart-approved-service"; return ""; }
    private String target(Incident incident) { return Optional.ofNullable(incident.getExternalId()).filter(v -> !v.isBlank()).orElse("incident-" + incident.getId()); }
    private double risk(String priority) { return "P1".equalsIgnoreCase(priority) ? .60 : "P2".equalsIgnoreCase(priority) ? .30 : .10; }
    private String hash(String value) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder hex = new StringBuilder(); for (byte b : bytes) hex.append(String.format("%02x", b)); return hex.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }
}
