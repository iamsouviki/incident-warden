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
import org.springframework.security.access.AccessDeniedException;
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
    private final RemediationToolRegistry tools;
    private final SopProcedureService sopProcedures;
    private final RemediationScriptService scripts;
    private final IncidentPrecedentService precedents;
    private final com.company.mcp.repository.TeamEmployeeRepository memberRepository;
    private final com.company.mcp.repository.UserRepository userRepository;
    // Off only for single-operator demos. Any shared environment must leave this on.
    @Value("${mcp.hitl.separation-of-duties:true}") private boolean separationOfDutiesRequired;
    /**
     * Whether an incident with no approved procedure may still reach a reviewer with a
     * script written from model knowledge alone. Turning this off restores the strict
     * posture: no approved SOP, no plan, escalate to a human with no script attached.
     */
    @Value("${mcp.hitl.allow-ungrounded-scripts:true}") private boolean allowUngroundedScripts;

    public HitlWorkflowService(IncidentRepository incidents, RemediationPlanRepository plans, HitlRequestRepository requests,
                               ActionExecutionRepository executions, CurrentUser currentUser, RagService rag,
                               GuardrailService guardrails, AgentAssessmentService agents, AuditService audit, ObjectMapper json,
                               RemediationToolRegistry tools, SopProcedureService sopProcedures, RemediationScriptService scripts,
                               IncidentPrecedentService precedents,
                               com.company.mcp.repository.TeamEmployeeRepository memberRepository,
                               com.company.mcp.repository.UserRepository userRepository) {
        this.incidents = incidents; this.plans = plans; this.requests = requests; this.executions = executions;
        this.currentUser = currentUser; this.rag = rag; this.guardrails = guardrails; this.agents = agents;
        this.audit = audit; this.json = json; this.tools = tools; this.sopProcedures = sopProcedures;
        this.scripts = scripts; this.precedents = precedents;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Map<String, Object> createPlan(UUID incidentId) {
        String tenant = currentUser.tenantId();
        Incident incident = incidents.findById(incidentId).filter(i -> tenant.equals(i.getTenantId()))
                .orElseThrow(() -> new NoSuchElementException("Incident not found"));
        boolean active = plans.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
                .anyMatch(p -> Set.of("PENDING_APPROVAL", "APPROVED", "EXECUTING").contains(p.getStatus()));

        // Agent stages: tenant-scoped SOP matcher -> past-incident matcher -> classifier/pattern
        // matcher -> confidence/risk -> deterministic guardrails.
        SopEvidence evidence = rag.findApprovedSopEvidence(tenant, incident.getSubject() + "\n" + incident.getDescription());
        // "Have we fixed this before?" — a resolved ticket in this tenant whose remediation a
        // human approved and watched succeed. Advisory here: it raises confidence and is put
        // in front of the reviewer, but the approval gate is unchanged.
        java.util.Optional<IncidentPrecedentService.Precedent> precedent = precedents.findPrecedent(tenant, incident);
        double precedentSimilarity = precedent.map(IncidentPrecedentService.Precedent::similarity).orElse(0.0);
        // The procedure's observed success rate is passed as the historical prior, so a
        // procedure that has actually failed in this tenant scores lower next time. This
        // is the learning loop; the counters are written back after a real execution.
        AgentAssessmentService.Assessment assessment = agents.assess(incident, evidence,
                evidence.approvedEvidencePresent() ? evidence.reliability() : agents.defaultPrior(),
                precedentSimilarity);
        GuardrailService.Result check = guardrails.evaluate(assessment.action(), assessment.target(), evidence, active ? 1 : 0);

        // A procedure that declares an action key must declare a runnable one. A typo'd or
        // unknown tool is an escalation, not a script invented to cover for it: falling
        // back to the model there would manufacture authority the operator never granted.
        RemediationToolRegistry.ParsedAction parsedAction = tools.parse(evidence.approvedActionKey());
        boolean brokenActionKey = !evidence.approvedActionKey().isBlank() && !parsedAction.valid();

        // ── Which machine? ───────────────────────────────────────────────────────────
        // Deliberately ahead of script generation. A read-only probe carries its own URL
        // inside the action key. Everything else ends as a script dispatched to a host, and
        // "which host" is the one thing this platform must never guess. The condition is
        // deliberately the same one RemediationToolRegistry.execute() routes on, so a plan
        // cannot pass a gate the executor would then fail.
        //
        // The connection is tried the cheap way first: IncidentTarget.connection() is empty
        // until a human has had to fill it in, and empty means "executor, use the path you
        // already have". Only an unreachable answer asks anybody for anything.
        boolean needsHost = !(parsedAction.valid() && !parsedAction.tool().mutating());
        IncidentTarget.Target host = IncidentTarget.resolve(incident);
        RemediationToolRegistry.Probe reach = needsHost && host.known()
                ? tools.reachable(host.host(), IncidentTarget.connection(incident))
                : RemediationToolRegistry.Probe.notAsked();
        // UNKNOWN never blocks. No executor configured is not evidence a host is down, and
        // a demo with execution disabled must plan exactly as it did before this gate.
        boolean targetOk = !needsHost || (host.known() && !reach.unreachable());
        String targetReason = !host.known() ? host.reason() : reach.unreachable() ? reach.reason() : "";

        // ── Which operating system? ──────────────────────────────────────────────────
        // The reason the host is resolved first: the probe above is where the machine gets
        // to say what it is, and the script has to be written for that. The same approved
        // Tomcat procedure becomes PowerShell on a Windows till and bash on a Linux
        // application server — the procedure authorises the action, the host decides the
        // dialect.
        IncidentTarget.Platform platform =
                IncidentTarget.platform(incident, reach.platform(), parsedAction.platformHint());

        RemediationScriptService.GeneratedScript script = brokenActionKey
                ? new RemediationScriptService.GeneratedScript("", "", "NONE", "BLOCK",
                        java.util.List.of(parsedAction.reason()), parsedAction.reason())
                : scripts.generate(incident, evidence, parsedAction, platform);

        // ── Eligibility ──────────────────────────────────────────────────────────────
        // Grounded: an APPROVED procedure for this tenant backs the plan. The full
        // deterministic boundary applies and a WARN-level script is tolerated, because an
        // operator curated the procedure it came from.
        //
        // Ungrounded: no approved procedure. The two findings waived here are the ones
        // that only restate that fact — the missing evidence itself, and the action
        // category the keyword classifier could not name. Blast radius, destructive
        // content, secret access, prompt injection and loop detection still block. The
        // script must scan clean, not merely non-fatal, and a human still approves the
        // exact text before anything runs.
        boolean grounded = evidence.approvedEvidencePresent();
        boolean eligible = targetOk && (grounded
                ? check.passed() && "HITL_REQUIRED".equals(assessment.route()) && script.usable()
                : allowUngroundedScripts && script.usableUngrounded()
                        && check.findings().stream().filter(GuardrailService::isBlockingFinding)
                                .allMatch(f -> f.startsWith("NO_APPROVED_SOP_EVIDENCE") || f.equals("ACTION_NOT_ALLOWLISTED")));

        java.util.List<String> findings = new java.util.ArrayList<>(check.findings());
        findings.addAll(script.findings());
        if (!grounded && eligible) findings.add("UNGROUNDED_LLM_SCRIPT");
        if (!targetReason.isBlank()) findings.add(targetReason);
        // Recorded, not blocking: a reviewer should see that nobody confirmed the host is up.
        if (needsHost && host.known() && !reach.known()) findings.add("TARGET_REACHABILITY_UNKNOWN");

        RemediationPlan plan = new RemediationPlan();
        plan.setTenantId(tenant);
        plan.setIncidentId(incidentId);
        plan.setStatus(eligible ? "PENDING_APPROVAL" : "BLOCKED");
        plan.setActionName(assessment.action().isBlank() ? "none" : assessment.action());
        plan.setTarget(assessment.target());
        plan.setParametersJson(parameters(assessment, evidence, script, precedent.orElse(null), platform));
        plan.setSopEvidence(evidence.approvedEvidencePresent() ? evidence.excerpt() : "SOP evidence unavailable: " + evidence.reason());
        plan.setConfidenceScore(assessment.confidenceScore());
        plan.setRiskScore(assessment.riskPenalty() * 100.0);
        plan.setGuardrailStatus(eligible ? "PASS" : "BLOCK");
        plan.setGuardrailFindings(String.join(";", findings));
        plan.setRemediationScript(script.script());
        plan.setScriptLanguage(script.language());
        plan.setScriptSource(script.source());
        plan.setScriptScanLevel(script.scanLevel());
        plan.setRollbackPlan(rollbackFor(parsedAction, script));
        // The script is inside the hash. Edit one character of it and the approval no
        // longer matches, so it cannot execute.
        plan.setPlanHash(hash(tenant + "|" + incidentId + "|" + plan.getActionName() + "|" + plan.getTarget()
                + "|" + evidence.procedureIds() + "|" + plan.getParametersJson() + "|" + script.script()));
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
        assessmentAudit.put("precedentIncident", precedent.map(IncidentPrecedentService.Precedent::reference).orElse("NONE"));
        assessmentAudit.put("precedentSimilarity", precedentSimilarity);
        assessmentAudit.put("precedentMatchedTerms", precedent.map(IncidentPrecedentService.Precedent::matchedTerms).orElse(java.util.List.of()));
        assessmentAudit.put("guardrails", check.findings());
        assessmentAudit.put("targetHost", host.known() ? host.host() : "UNKNOWN");
        // FIELD means an operator named this machine; DESCRIPTION means it was read out of
        // the ticket text. A reviewer approving a restart is entitled to know which.
        assessmentAudit.put("targetHostSource", host.source());
        assessmentAudit.put("targetReachability", needsHost ? reach.status() : "NOT_APPLICABLE");
        assessmentAudit.put("targetPlatform", platform.name());
        assessmentAudit.put("targetPlatformSource", platform.source());
        assessmentAudit.put("scriptSource", script.source());
        assessmentAudit.put("scriptScanLevel", script.scanLevel());
        assessmentAudit.put("scriptFindings", script.findings());
        assessmentAudit.put("status", plan.getStatus());
        audit.record(tenant, "REMEDIATION_PLAN", plan.getId(), "PLAN_CREATED", currentUser.username(), assessmentAudit);

        if (!eligible) {
            // The reason an approver or operator actually needs: whichever gate closed.
            // The target comes first because it is the only one with a fix the operator can
            // apply themselves — name the server, or name how to reach it, and re-plan.
            String reason = !targetReason.isBlank() ? targetReason
                    : !script.reason().isBlank() ? script.reason()
                    : !grounded ? evidence.reason()
                    // Named separately from GUARDRAIL_BLOCKED, which it used to be reported
                    // as. The guardrails had passed; the score had not reached the band. An
                    // operator reading "GUARDRAIL_BLOCKED" next to two advisory findings goes
                    // looking for a dangerous script that isn't there.
                    : !"HITL_REQUIRED".equals(assessment.route())
                            ? "CONFIDENCE_BELOW_HITL_BAND:" + Math.round(assessment.confidenceScore())
                            : "GUARDRAIL_BLOCKED";
            incident.setStatus("ESCALATED");
            incident.setConfidenceScore(assessment.confidenceScore());
            incidents.save(incident);
            audit.record(tenant, "INCIDENT", incidentId, "PLAN_ESCALATED", currentUser.username(), Map.of("planId", plan.getId(), "reason", reason));
            Map<String, Object> escalation = new LinkedHashMap<>();
            escalation.put("plan", plan);
            escalation.put("hitlRequest", "");
            escalation.put("route", "ESCALATE");
            escalation.put("reason", reason);
            // What to do about it, in words an operator can act on without reading the code.
            escalation.put("action", !host.known() ? host.prompt()
                    : reach.unreachable() ? reach.detail()
                            + " Confirm the server name and the connection method on this incident, then plan again."
                    : reason.startsWith("CONFIDENCE_BELOW_HITL_BAND")
                            ? "This scored %d%% against the %.0f%% this workspace requires before a plan may be offered for approval. %s A person works this one by hand — the evidence, script and score above are still here to work from."
                                    .formatted(Math.round(assessment.confidenceScore()), agents.hitlBandPercent(),
                                            "P1".equalsIgnoreCase(incident.getPriority()) || "P2".equalsIgnoreCase(incident.getPriority())
                                                    ? incident.getPriority() + " carries a risk penalty that holds it below the band deliberately."
                                                    : "Raise the score by approving a matching procedure, or by resolving a similar incident so this one has a precedent.")
                    : "");
            return escalation;
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

    private Map<String, Object> resolveUserInfo(String username) {
        if (username == null || username.isBlank()) return Map.of("username", "", "name", "", "role", "", "department", "");
        var empOpt = memberRepository.findByUsername(username);
        if (empOpt.isPresent()) {
            var emp = empOpt.get();
            String name = (emp.getFullName() != null && !emp.getFullName().isBlank()) ? emp.getFullName() : emp.getUsername();
            String role = emp.getRole() != null ? emp.getRole() : "Operations Specialist";
            String dept = emp.getDepartment() != null ? emp.getDepartment() : (emp.getTeam() != null ? emp.getTeam().getName() : "");
            return Map.of("username", emp.getUsername(), "name", name, "role", role, "department", dept);
        }
        var userOpt = userRepository.findByUsername(username);
        if (userOpt.isPresent()) {
            var u = userOpt.get();
            String name = (u.getFullName() != null && !u.getFullName().isBlank()) ? u.getFullName() : u.getUsername();
            String role = u.getRole() != null ? u.getRole() : "User";
            String dept = u.getDepartment() != null ? u.getDepartment() : "";
            return Map.of("username", u.getUsername(), "name", name, "role", role, "department", dept);
        }
        return Map.of("username", username, "name", username, "role", "Engineer", "department", "");
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
            item.put("request", request); 
            item.put("plan", plan); 
            item.put("incident", incident);
            if (incident != null) {
                item.put("assigneeInfo", resolveUserInfo(incident.getAssignee()));
            }
            item.put("requestedByInfo", resolveUserInfo(request.getRequestedBy()));
            item.put("reviewerInfo", resolveUserInfo(request.getReviewer()));
            return item;
        }).filter(item -> item.get("plan") != null && item.get("incident") != null).toList();
    }

    @Transactional
    public Map<String, Object> decide(UUID requestId, String decision, String reason) {
        String tenant = currentUser.tenantId();
        HitlRequest request = requests.findByIdAndTenantId(requestId, tenant).orElseThrow(() -> new NoSuchElementException("Approval request not found"));
        if (!"PENDING".equals(request.getStatus())) throw new IllegalStateException("Approval request is already decided");
        // Separation of duties: the analyst who raised a plan cannot also approve it.
        // Without this, one compromised account is enough to move a plan from draft to
        // executable, which defeats the point of having a human gate at all.
        if (separationOfDutiesRequired && currentUser.username().equals(request.getRequestedBy())) {
            throw new AccessDeniedException("The requester of a plan cannot approve it. A second reviewer is required.");
        }
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

    /**
     * Incident summaries for an MCP agent.
     *
     * Deliberately a projection, not the entity. An agent's context is a place text goes
     * to be interpreted, so it gets the fields needed to triage and nothing more —
     * no free-text comment history, no assignee contact details.
     */
    public java.util.List<Map<String, Object>> openIncidentsForAgent() {
        String tenant = currentUser.tenantId();
        return incidents.findTop50ByTenantIdOrderByUpdatedAtDesc(tenant).stream()
                .filter(i -> !Set.of("RESOLVED", "CLOSED", "REJECTED").contains(String.valueOf(i.getStatus()).toUpperCase(java.util.Locale.ROOT)))
                .map(this::agentView)
                .toList();
    }

    public Map<String, Object> incidentForAgent(UUID incidentId) {
        String tenant = currentUser.tenantId();
        Incident incident = incidents.findById(incidentId).filter(i -> tenant.equals(i.getTenantId()))
                .orElseThrow(() -> new NoSuchElementException("Incident not found"));
        return agentView(incident);
    }

    private Map<String, Object> agentView(Incident incident) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("incidentId", incident.getId());
        view.put("ticket", incident.getExternalId());
        view.put("subject", incident.getSubject());
        view.put("description", incident.getDescription());
        view.put("status", incident.getStatus());
        view.put("priority", incident.getPriority());
        view.put("confidenceScore", incident.getConfidenceScore());
        view.put("createdAt", incident.getCreatedAt());
        return view;
    }

    /**
     * Everything an approver must see to make an informed decision, in one call.
     *
     * Assembled server-side rather than left to the UI to stitch together from three
     * endpoints: a reviewer looking at a partially-loaded page is a reviewer approving
     * something they cannot fully see.
     */
    public Map<String, Object> reviewDetail(UUID requestId) {
        String tenant = currentUser.tenantId();
        HitlRequest request = requests.findByIdAndTenantId(requestId, tenant)
                .orElseThrow(() -> new NoSuchElementException("Approval request not found"));
        RemediationPlan plan = plans.findById(request.getPlanId()).filter(p -> tenant.equals(p.getTenantId()))
                .orElseThrow(() -> new NoSuchElementException("Plan not found"));
        Incident incident = incidents.findById(request.getIncidentId()).filter(i -> tenant.equals(i.getTenantId()))
                .orElseThrow(() -> new NoSuchElementException("Incident not found"));

        String actionKey = approvedActionKey(plan);
        RemediationToolRegistry.ParsedAction parsed = tools.parse(actionKey);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("actionKey", actionKey);
        action.put("valid", parsed.valid());
        action.put("reason", parsed.reason());
        action.put("tool", parsed.valid() ? parsed.tool().name() : "");
        action.put("mutating", parsed.valid() && parsed.tool().mutating());
        action.put("arguments", parsed.args());

        // The script is the artifact under review. It is presented as its own block, with
        // its provenance next to it, because "who authorised this text" is the first thing
        // a reviewer has to know and the last thing they should have to go looking for.
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("script", plan.getRemediationScript() == null ? "" : plan.getRemediationScript());
        script.put("language", plan.getScriptLanguage());
        script.put("source", plan.getScriptSource());
        script.put("scanLevel", plan.getScriptScanLevel());
        script.put("grounded", !"LLM_KNOWLEDGE".equals(plan.getScriptSource()) && !"NONE".equals(plan.getScriptSource()));
        script.put("lineCount", plan.getRemediationScript() == null || plan.getRemediationScript().isBlank()
                ? 0 : plan.getRemediationScript().split("\n", -1).length);
        script.put("provenance", switch (String.valueOf(plan.getScriptSource())) {
            case "SOP_TEMPLATE" -> "Rendered from a fixed template using the approved procedure's action key. No model wrote this text.";
            case "SOP_GROUNDED" -> "Written by the model, constrained to an APPROVED procedure for this tenant.";
            case "LLM_KNOWLEDGE" -> "Written by the model from general knowledge. No approved procedure authorises it — you are the only gate.";
            default -> "No script was produced for this plan.";
        });
        // Read back from the plan, not recomputed: the reviewer must see the platform that was
        // resolved when the script was written, and its source — approving PowerShell that a
        // host confirmed is not the same act as approving it because nothing said otherwise.
        script.put("platform", pinned(plan, "targetPlatform"));
        script.put("platformSource", pinned(plan, "targetPlatformSource"));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("request", request);
        detail.put("plan", plan);
        detail.put("incident", incident);
        detail.put("assigneeInfo", resolveUserInfo(incident.getAssignee()));
        detail.put("requestedByInfo", resolveUserInfo(request.getRequestedBy()));
        detail.put("reviewerInfo", resolveUserInfo(request.getReviewer()));
        detail.put("action", action);
        detail.put("script", script);
        // "Have we done this before?" read back from the plan, not recomputed: the reviewer
        // must see the precedent that was cited when the plan was hashed, not whatever the
        // incident table happens to resemble by the time they open the page.
        detail.put("precedent", precedentOf(plan));
        detail.put("guardrailFindings", plan.getGuardrailFindings() == null || plan.getGuardrailFindings().isBlank()
                ? java.util.List.of() : java.util.List.of(plan.getGuardrailFindings().split(";")));
        detail.put("executions", executions.findByPlanIdOrderByStartedAtAsc(plan.getId()));
        detail.put("canApprove", "PENDING".equals(request.getStatus())
                && !(separationOfDutiesRequired && currentUser.username().equals(request.getRequestedBy())));
        detail.put("separationOfDutiesBlocked",
                separationOfDutiesRequired && currentUser.username().equals(request.getRequestedBy()));
        return detail;
    }

    @Transactional
    public Map<String, Object> dryRunAndExecute(UUID requestId) {
        return runApprovedPlan(requestId, true);
    }

    /**
     * Runs an approved plan for real.
     *
     * Everything checked at approval time is checked again here. An approval is a
     * statement about one exact plan — identified by its hash — and not standing
     * permission to run whatever that plan row says later.
     */
    @Transactional
    public Map<String, Object> execute(UUID requestId) {
        return runApprovedPlan(requestId, false);
    }

    private Map<String, Object> runApprovedPlan(UUID requestId, boolean dryRun) {
        String tenant = currentUser.tenantId();
        HitlRequest request = requests.findByIdAndTenantId(requestId, tenant).orElseThrow(() -> new NoSuchElementException("Approval request not found"));
        if (!"APPROVED".equals(request.getStatus())) throw new IllegalStateException("Only an approved request can execute");
        RemediationPlan plan = plans.findById(request.getPlanId()).filter(p -> tenant.equals(p.getTenantId())).orElseThrow(() -> new NoSuchElementException("Plan not found"));
        if (!Objects.equals(request.getApprovedPlanHash(), plan.getPlanHash()) || !"PASS".equals(plan.getGuardrailStatus())) {
            throw new IllegalStateException("Plan changed since approval and is no longer eligible for execution");
        }
        if (!Set.of("APPROVED", "SIMULATED").contains(plan.getStatus())) {
            throw new IllegalStateException("Plan is not in an executable state: " + plan.getStatus());
        }
        // A real run must be preceded by a passing dry run. The dry run is what validates
        // the action key against the tool table with a human watching the result.
        if (!dryRun && !"SIMULATED".equals(plan.getStatus())) {
            throw new IllegalStateException("Run the dry run first: a plan must pass simulation before it executes");
        }

        String actionKey = approvedActionKey(plan);
        // Loaded before the run, not after: the connection method the operator picked has to
        // travel with the dispatch. The host itself comes from the plan, because the plan's
        // host is inside the approved hash — editing the incident's server after approval
        // invalidates the approval rather than silently redirecting the script.
        Incident incident = incidents.findById(request.getIncidentId()).orElseThrow();
        RemediationToolRegistry.Outcome outcome = tools.execute(actionKey, plan.getRemediationScript(),
                plan.getScriptLanguage(), plan.getTarget(), IncidentTarget.connection(incident), dryRun);

        ActionExecution execution = new ActionExecution();
        execution.setTenantId(tenant); execution.setIncidentId(request.getIncidentId()); execution.setPlanId(plan.getId()); execution.setHitlRequestId(request.getId());
        execution.setMode(outcome.mode()); execution.setStatus(outcome.status());
        execution.setValidationResult("Plan hash matches the approved hash, so the script is byte-for-byte the text "
                + "that was approved. It was re-scanned against the guardrail block list at execution time"
                + (actionKey.isBlank() ? "" : " and the action key '" + actionKey + "' was re-validated against the tool allow-list")
                + ". No command was run inside this process.");
        execution.setOutput(outcome.output());
        execution.setCompletedAt(OffsetDateTime.now()); executions.save(execution);

        plan.setAttempts(plan.getAttempts() + 1);
        plan.setStatus(dryRun ? (outcome.succeeded() ? "SIMULATED" : "BLOCKED")
                              : (outcome.succeeded() ? "EXECUTED" : "FAILED"));
        plans.save(plan);

        incident.setStatus(dryRun ? "RESOLUTION_SIMULATED"
                : outcome.succeeded() ? "RESOLVED" : "ESCALATED");
        incidents.save(incident);

        // The learning loop: only a real run teaches the procedure anything. Counting a
        // simulation as a success would inflate confidence without evidence.
        if (!dryRun && !"SIMULATED".equals(outcome.mode())) {
            for (UUID procedureId : procedureIds(plan)) {
                sopProcedures.recordOutcome(procedureId, tenant, outcome.succeeded());
            }
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("planId", plan.getId());
        details.put("actionKey", actionKey);
        details.put("mode", outcome.mode());
        details.put("status", outcome.status());
        details.put("reason", outcome.reason());
        details.put("dryRun", dryRun);
        audit.record(tenant, "ACTION_EXECUTION", execution.getId(),
                dryRun ? "DRY_RUN_COMPLETED" : "EXECUTION_COMPLETED", currentUser.username(), details);

        return Map.of("execution", execution, "message",
                dryRun ? "Dry run recorded; no mutation was performed." : "Execution recorded: " + outcome.status());
    }

    /**
     * Reads the action key that was pinned into the plan hash at creation time.
     *
     * Taken from the plan rather than re-derived from the SOP table: if the procedure has
     * been edited since approval, the hash comparison above has already established that
     * the approver signed off on this exact key, and nothing else may run in its place.
     */
    private String approvedActionKey(RemediationPlan plan) {
        try {
            Map<?, ?> parsed = json.readValue(plan.getParametersJson(), Map.class);
            Object key = parsed.get("approvedActionKey");
            return key == null ? "" : key.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The precedent block pinned into the plan, or an empty map when nothing matched.
     *
     * An empty map rather than null so the UI has one shape to render: "no comparable
     * incident" is itself information a reviewer should see stated, not inferred from a
     * missing field.
     */
    private Map<String, Object> precedentOf(RemediationPlan plan) {
        try {
            Object precedent = json.readValue(plan.getParametersJson(), Map.class).get("precedent");
            if (!(precedent instanceof Map<?, ?> map)) return Map.of();
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** One hash-pinned scalar out of the plan's parameters, or "" for plans written before it existed. */
    private String pinned(RemediationPlan plan, String key) {
        try {
            Object value = json.readValue(plan.getParametersJson(), Map.class).get(key);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception e) {
            return "";
        }
    }

    private java.util.List<UUID> procedureIds(RemediationPlan plan) {        try {
            Map<?, ?> parsed = json.readValue(plan.getParametersJson(), Map.class);
            Object ids = parsed.get("procedureIds");
            if (!(ids instanceof java.util.List<?> list)) return java.util.List.of();
            java.util.List<UUID> result = new java.util.ArrayList<>();
            for (Object id : list) {
                try { result.add(UUID.fromString(id.toString())); } catch (IllegalArgumentException ignored) {}
            }
            return result;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /**
     * The rollback an approver needs to see before saying yes.
     *
     * Per-tool because "how do I undo this" has no generic answer, and an approver being
     * shown a generic placeholder is being asked to approve blind. A script with no
     * approved tool behind it gets the honest answer: nobody has written a rollback for
     * this, so the reviewer has to.
     */
    private String rollbackFor(RemediationToolRegistry.ParsedAction action, RemediationScriptService.GeneratedScript script) {
        if (!action.valid()) {
            return "LLM_KNOWLEDGE".equals(script.source())
                    ? "No rollback runbook exists: this script has no approved procedure behind it. Read every "
                        + "line before approving and be certain you can undo its effect by hand. Reject it if you cannot."
                    : "Not applicable: the action was rejected before planning.";
        }
        return switch (action.tool().name()) {
            case "CHECK_URL" -> "None required. The probe is read-only and changes nothing.";
            case "RESTART_SERVICE" -> "A restart is not reversible, but it is repeatable. If the service does not come "
                    + "back, start it manually on the target and check its log from the moment of the restart. "
                    + "Escalate to the application owner rather than restarting a second time.";
            case "CLEAR_CACHE" -> "A flushed cache cannot be restored; it repopulates from the source of truth on the "
                    + "next request. Expect elevated latency and load on the origin until it warms.";
            case "RERUN_JOB" -> "Stop the job on the target if it must be aborted. Confirm the job is idempotent before "
                    + "approving: a non-idempotent rerun can double-post its output.";
            default -> "No rollback runbook is defined for this tool. Do not approve.";
        };
    }

    private String parameters(AgentAssessmentService.Assessment assessment, SopEvidence evidence,
                              RemediationScriptService.GeneratedScript script,
                              IncidentPrecedentService.Precedent precedent,
                              IncidentTarget.Platform platform) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("classification", assessment.category());
            params.put("procedureIds", evidence.procedureIds());
            // Pinned into the plan hash: approving this plan approves this exact command.
            params.put("approvedActionKey", evidence.approvedActionKey());
            params.put("scriptSource", script.source());
            params.put("scriptLanguage", script.language());
            // Also pinned, and the reviewer's answer to "why is this PowerShell?". The source
            // matters as much as the name: HOST_REPORTED means the machine said so,
            // SOP_ACTION_KEY means nobody has confirmed it and the procedure's default was used.
            params.put("targetPlatform", platform.name());
            params.put("targetPlatformSource", platform.source());
            // Also pinned. The past ticket cited as justification is part of what the
            // reviewer is being shown, so it must not be editable after they say yes.
            if (precedent != null) {
                params.put("precedent", Map.of(
                        "reference", precedent.reference(),
                        "incidentId", precedent.incidentId().toString(),
                        "actionKey", precedent.actionKey(),
                        "similarity", Math.round(precedent.similarity() * 100.0) / 100.0,
                        "matchedTerms", precedent.matchedTerms(),
                        "resolutionNote", precedent.resolutionNote(),
                        "resolvedAt", String.valueOf(precedent.resolvedAt())));
            }
            return json.writeValueAsString(params);
        } catch (Exception ignored) { return "{}"; }
    }

    private String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : bytes) hex.append(String.format("%02x", b)); return hex.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
