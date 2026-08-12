package com.company.mcp.service;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.model.ActionExecution;
import com.company.mcp.model.HitlRequest;
import com.company.mcp.model.Incident;
import com.company.mcp.model.RemediationPlan;
import com.company.mcp.repository.ActionExecutionRepository;
import com.company.mcp.repository.HitlRequestRepository;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.repository.RemediationPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class HitlWorkflowService {
    private final IncidentRepository incidents;
    private final RemediationPlanRepository plans;
    private final HitlRequestRepository requests;
    private final ActionExecutionRepository executions;
    private final CurrentUser currentUser;
    private final RagService rag;
    private final GuardrailService guardrails;
    private final AgentAssessmentService agents;
    private final AuditService audit;
    private final ObjectMapper json;
    @Value("${mcp.autonomy.execution-enabled:false}") private boolean executionEnabled;

    public HitlWorkflowService(IncidentRepository incidents, RemediationPlanRepository plans, HitlRequestRepository requests,
                               ActionExecutionRepository executions, CurrentUser currentUser, RagService rag,
                               GuardrailService guardrails, AgentAssessmentService agents, AuditService audit, ObjectMapper json) {
        this.incidents = incidents; this.plans = plans; this.requests = requests; this.executions = executions;
        this.currentUser = currentUser; this.rag = rag; this.guardrails = guardrails; this.agents = agents;
        this.audit = audit; this.json = json;
    }

    @Transactional
    public Map<String, Object> createPlan(UUID incidentId) {
        String tenant = currentUser.tenantId();
        Incident incident = incidents.findById(incidentId).filter(i -> tenant.equals(i.getTenantId()))
                .orElseThrow(() -> new NoSuchElementException("Incident not found"));
        boolean active = plans.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
                .anyMatch(p -> Set.of("PENDING_APPROVAL", "APPROVED", "EXECUTING").contains(p.getStatus()));

        // Agent stages: tenant-scoped SOP matcher -> classifier/pattern matcher -> confidence/risk -> deterministic guardrails.
        SopEvidence evidence = rag.findApprovedSopEvidence(tenant, incident.getSubject() + "\n" + incident.getDescription());
        AgentAssessmentService.Assessment assessment = agents.assess(incident, evidence);
        GuardrailService.Result check = guardrails.evaluate(assessment.action(), assessment.target(), evidence, active ? 1 : 0);
        boolean eligible = evidence.approvedEvidencePresent() && check.passed() && "HITL_REQUIRED".equals(assessment.route());

        RemediationPlan plan = new RemediationPlan();
        plan.setTenantId(tenant);
        plan.setIncidentId(incidentId);
        plan.setStatus(eligible ? "PENDING_APPROVAL" : "BLOCKED");
        plan.setActionName(assessment.action().isBlank() ? "none" : assessment.action());
        plan.setTarget(assessment.target());
        plan.setParametersJson(parameters(assessment, evidence));
        plan.setSopEvidence(evidence.approvedEvidencePresent() ? evidence.excerpt() : "SOP evidence unavailable: " + evidence.reason());
        plan.setConfidenceScore(assessment.confidenceScore());
        plan.setRiskScore(assessment.riskPenalty() * 100.0);
        plan.setGuardrailStatus(eligible ? "PASS" : "BLOCK");
        plan.setGuardrailFindings(String.join(";", check.findings()));
        plan.setRollbackPlan("No mutation has run. A future allow-listed executor must provide and validate its action-specific rollback runbook.");
        plan.setPlanHash(hash(tenant + "|" + incidentId + "|" + plan.getActionName() + "|" + plan.getTarget() + "|" + evidence.procedureIds() + "|" + plan.getParametersJson()));
        plans.save(plan);

        Map<String, Object> assessmentAudit = new LinkedHashMap<>();
        assessmentAudit.put("classification", assessment.category());
        assessmentAudit.put("patternSimilarity", assessment.patternSimilarity());
        assessmentAudit.put("historicalSuccess", assessment.historicalSuccess());
        assessmentAudit.put("sopReliability", assessment.sopReliability());
        assessmentAudit.put("systemHealth", assessment.systemHealth());
        assessmentAudit.put("riskPenalty", assessment.riskPenalty());
        assessmentAudit.put("confidenceScore", assessment.confidenceScore());
        assessmentAudit.put("sopEvidenceReason", evidence.reason());
        assessmentAudit.put("procedureIds", evidence.procedureIds());
        assessmentAudit.put("guardrails", check.findings());
        assessmentAudit.put("status", plan.getStatus());
        audit.record(tenant, "REMEDIATION_PLAN", plan.getId(), "PLAN_CREATED", currentUser.username(), assessmentAudit);

        if (!eligible) {
            incident.setStatus("ESCALATED");
            incident.setConfidenceScore(assessment.confidenceScore());
            incidents.save(incident);
            audit.record(tenant, "INCIDENT", incidentId, "PLAN_ESCALATED", currentUser.username(), Map.of("planId", plan.getId(), "reason", evidence.reason()));
            return Map.of("plan", plan, "hitlRequest", "", "route", "ESCALATE", "reason", evidence.reason());
        }

        HitlRequest request = new HitlRequest();
        request.setTenantId(tenant); request.setIncidentId(incidentId); request.setPlanId(plan.getId());
        request.setStatus("PENDING"); request.setRequestedBy(currentUser.username()); requests.save(request);
        incident.setStatus("PENDING_APPROVAL"); incident.setConfidenceScore(assessment.confidenceScore()); incidents.save(incident);
        audit.record(tenant, "HITL_REQUEST", request.getId(), "APPROVAL_REQUESTED", currentUser.username(), Map.of("planId", plan.getId(), "planHash", plan.getPlanHash()));
        return Map.of("plan", plan, "hitlRequest", request, "route", "HITL_REQUIRED");
    }

    public java.util.List<HitlRequest> pending() {
        return requests.findByTenantIdAndStatusOrderByCreatedAtAsc(currentUser.tenantId(), "PENDING");
    }

    public java.util.List<Map<String, Object>> pendingReviewItems() {
        String tenant = currentUser.tenantId();
        return java.util.stream.Stream.concat(
                pending().stream(),
                requests.findByTenantIdAndStatusOrderByCreatedAtAsc(tenant, "APPROVED").stream()
            ).sorted(java.util.Comparator.comparing(HitlRequest::getCreatedAt)).map(request -> {
            RemediationPlan plan = plans.findById(request.getPlanId()).filter(p -> tenant.equals(p.getTenantId())).orElse(null);
            Incident incident = incidents.findById(request.getIncidentId()).filter(i -> tenant.equals(i.getTenantId())).orElse(null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("request", request); item.put("plan", plan); item.put("incident", incident);
            return item;
        }).filter(item -> item.get("plan") != null && item.get("incident") != null).toList();
    }

    @Transactional
    public Map<String, Object> decide(UUID requestId, String decision, String reason) {
        String tenant = currentUser.tenantId();
        HitlRequest request = requests.findByIdAndTenantId(requestId, tenant).orElseThrow(() -> new NoSuchElementException("Approval request not found"));
        if (!"PENDING".equals(request.getStatus())) throw new IllegalStateException("Approval request is already decided");
        RemediationPlan plan = plans.findById(request.getPlanId()).filter(p -> tenant.equals(p.getTenantId())).orElseThrow(() -> new NoSuchElementException("Plan not found"));
        if (!"PENDING_APPROVAL".equals(plan.getStatus()) || !"PASS".equals(plan.getGuardrailStatus())) throw new IllegalStateException("Only a guardrail-passing pending plan may be decided");
        boolean approve = "APPROVE".equalsIgnoreCase(decision);
        request.setStatus(approve ? "APPROVED" : "REJECTED"); request.setReviewer(currentUser.username());
        request.setDecisionReason(reason == null ? "" : reason); request.setDecidedAt(OffsetDateTime.now());
        request.setApprovedPlanHash(approve ? plan.getPlanHash() : null); requests.save(request);
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
        RemediationPlan plan = plans.findById(request.getPlanId()).filter(p -> tenant.equals(p.getTenantId())).orElseThrow(() -> new NoSuchElementException("Plan not found"));
        if (!Objects.equals(request.getApprovedPlanHash(), plan.getPlanHash()) || !"PASS".equals(plan.getGuardrailStatus()) || !"APPROVED".equals(plan.getStatus())) {
            throw new IllegalStateException("Plan changed or is not eligible for a simulated execution");
        }
        ActionExecution execution = new ActionExecution();
        execution.setTenantId(tenant); execution.setIncidentId(request.getIncidentId()); execution.setPlanId(plan.getId()); execution.setHitlRequestId(request.getId());
        execution.setMode("SIMULATED"); execution.setStatus("DRY_RUN_PASSED");
        execution.setValidationResult("Simulation only. Target is singular, action is allow-listed, plan hash is approved, and no local shell execution is permitted.");
        execution.setOutput("Simulated dry run completed. No system mutation was performed. Real execution remains disabled.");
        execution.setCompletedAt(OffsetDateTime.now()); executions.save(execution);
        plan.setAttempts(plan.getAttempts() + 1); plan.setStatus("SIMULATED"); plans.save(plan);
        Incident incident = incidents.findById(request.getIncidentId()).orElseThrow(); incident.setStatus("RESOLUTION_SIMULATED"); incidents.save(incident);
        audit.record(tenant, "ACTION_EXECUTION", execution.getId(), "DRY_RUN_COMPLETED", currentUser.username(), Map.of("planId", plan.getId(), "mode", "SIMULATED", "executionEnabledConfigurationIgnored", executionEnabled));
        return Map.of("execution", execution, "message", "Dry run recorded; no mutation was performed.");
    }

    private String parameters(AgentAssessmentService.Assessment assessment, SopEvidence evidence) {
        try {
            return json.writeValueAsString(Map.of("classification", assessment.category(), "procedureIds", evidence.procedureIds(), "simulationOnly", true));
        } catch (Exception ignored) { return "{\"simulationOnly\":true}"; }
    }

    private String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : bytes) hex.append(String.format("%02x", b)); return hex.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
