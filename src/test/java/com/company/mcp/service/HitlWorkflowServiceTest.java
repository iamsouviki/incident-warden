package com.company.mcp.service;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.model.HitlRequest;
import com.company.mcp.model.Incident;
import com.company.mcp.model.RemediationPlan;
import com.company.mcp.repository.ActionExecutionRepository;
import com.company.mcp.repository.HitlRequestRepository;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.repository.RemediationPlanRepository;
import com.company.mcp.repository.SystemConfigRepository;
import com.company.mcp.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HitlWorkflowServiceTest {

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final RemediationPlanRepository plans = mock(RemediationPlanRepository.class);
    private final HitlRequestRepository requests = mock(HitlRequestRepository.class);
    private final ActionExecutionRepository executions = mock(ActionExecutionRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final RagService rag = mock(RagService.class);
    private final AuditService audit = mock(AuditService.class);

    /**
     * A real script service over a mocked RagService: {@code getOrBuildChatClient()}
     * returns null, so every model-backed path reports itself unavailable instead of
     * being stubbed into success. The template path needs no model at all, which is the
     * point of having one.
     */
    private HitlWorkflowService workflow(boolean allowUngrounded) {
        RemediationScriptService scripts = new RemediationScriptService(rag, new GuardrailService(), 100);
        HitlWorkflowService workflow = new HitlWorkflowService(incidents, plans, requests, executions, currentUser,
                rag, new GuardrailService(), new AgentAssessmentService(0.85, null, null), audit, new ObjectMapper(),
                new RemediationToolRegistry(new ObjectMapper(), new GuardrailService(), null),
                mock(SopProcedureService.class), scripts, mock(IncidentPrecedentService.class),
                mock(UserRepository.class),
                // No config rows stubbed, so every UI-managed policy falls back to the
                // deployed default set below — which is what these tests are asserting on.
                mock(SystemConfigRepository.class));
        // @Value is not applied outside Spring, so the policy flags are set explicitly
        // rather than left at the Java default, which would silently test the wrong policy.
        ReflectionTestUtils.setField(workflow, "allowUngroundedScripts", allowUngrounded);
        ReflectionTestUtils.setField(workflow, "separationOfDutiesRequired", true);
        return workflow;
    }

    private UUID stubIncident(String subject, String description) {
        // A named host, because every mutating plan now needs one: a ticket without a
        // machine escalates to ask for it, which the last test in this class covers.
        return stubIncident(subject, description, "store-0042-app-01");
    }

    private UUID stubIncident(String subject, String description, String targetHost) {
        UUID incidentId = UUID.randomUUID();
        Incident incident = Incident.builder().id(incidentId)
                .subject(subject).description(description)
                .priority("P3").externalId("FS-1001").targetHost(targetHost).build();
        when(currentUser.username()).thenReturn("analyst");
        when(incidents.findById(incidentId)).thenReturn(Optional.of(incident));
        when(plans.findByIncidentIdOrderByCreatedAtDesc(incidentId)).thenReturn(List.of());
        when(plans.save(any(RemediationPlan.class))).thenAnswer(invocation -> {
            RemediationPlan plan = invocation.getArgument(0);
            ReflectionTestUtils.setField(plan, "id", UUID.randomUUID());
            return plan;
        });
        when(requests.save(any(HitlRequest.class))).thenAnswer(invocation -> {
            HitlRequest request = invocation.getArgument(0);
            ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
            return request;
        });
        return incidentId;
    }

    @Test
    void unavailableSopCreatesBlockedPlanAndNeverCreatesApprovalRequest() {
        UUID incidentId = stubIncident("Printer queue is stuck", "The printer queue is blocked");
        when(rag.findApprovedSopEvidence(any())).thenReturn(SopEvidence.unavailable("SOP_SERVICE_UNAVAILABLE"));

        Map<String, Object> result = workflow(false).createPlan(incidentId);

        assertEquals("ESCALATE", result.get("route"));
        assertTrue(result.get("plan") instanceof RemediationPlan);
        assertEquals("BLOCKED", ((RemediationPlan) result.get("plan")).getStatus());
        verify(plans).save(any(RemediationPlan.class));
        verify(requests, never()).save(any());
    }

    /**
     * Evidence alone is not enough. A procedure whose action key cannot be parsed must
     * escalate rather than reach a human, because an approver signing off on a plan that
     * cannot run has been asked for a signature that means nothing. It must also not
     * quietly fall back to a model-written script: that would manufacture authority the
     * operator never granted.
     */
    @Test
    void approvedEvidenceWithAnUnrunnableActionKeyStillBlocks() {
        UUID incidentId = stubIncident("Printer queue is stuck", "The printer queue is blocked");
        when(rag.findApprovedSopEvidence(any())).thenReturn(new SopEvidence(
                true, List.of(UUID.randomUUID()), "SOP: clear the print queue", 0.95,
                "APPROVED_SOP_MATCH", "WIPE_DISK:everything"));

        Map<String, Object> result = workflow(true).createPlan(incidentId);
        RemediationPlan plan = (RemediationPlan) result.get("plan");

        assertEquals("BLOCKED", plan.getStatus());
        assertTrue(plan.getGuardrailFindings().contains("TOOL_NOT_ALLOWLISTED"));
        assertEquals("NONE", plan.getScriptSource());
        assertTrue(plan.getRemediationScript() == null || plan.getRemediationScript().isBlank());
        verify(requests, never()).save(any());
    }

    /**
     * The grounded happy path, start to finish, with no model in the loop: an approved
     * procedure with a runnable action key renders a deterministic script, passes the
     * guardrails, and reaches a reviewer.
     */
    @Test
    void anApprovedProcedureRendersATemplatedScriptAndReachesAReviewer() {
        UUID incidentId = stubIncident("Service unavailable", "The tomcat service is not responding");
        when(rag.findApprovedSopEvidence(any())).thenReturn(new SopEvidence(
                true, List.of(UUID.randomUUID()), "SOP: restart the tomcat service", 0.95,
                "APPROVED_SOP_MATCH", "RESTART_SERVICE:tomcat:linux"));

        Map<String, Object> result = workflow(false).createPlan(incidentId);
        RemediationPlan plan = (RemediationPlan) result.get("plan");

        assertEquals("HITL_REQUIRED", result.get("route"));
        assertEquals("PENDING_APPROVAL", plan.getStatus());
        assertEquals("SOP_TEMPLATE", plan.getScriptSource());
        assertEquals("bash", plan.getScriptLanguage());
        assertTrue(plan.getRemediationScript().contains("systemctl restart 'tomcat'"));
        verify(requests).save(any(HitlRequest.class));
    }

    /**
     * With no approved procedure and no model available, the ungrounded path has nothing
     * to offer a reviewer, so it must escalate rather than create an empty plan to
     * approve. Enabling ungrounded scripts loosens where a script may come from; it does
     * not lower the bar for whether there is one.
     */
    @Test
    void theUngroundedPathStillNeedsAScript() {
        UUID incidentId = stubIncident("Disk almost full", "Root filesystem is at 97 percent on the reporting host");
        when(rag.findApprovedSopEvidence(any()))
                .thenReturn(SopEvidence.noMatch("NO_APPROVED_SOP_MATCH"));

        Map<String, Object> result = workflow(true).createPlan(incidentId);
        RemediationPlan plan = (RemediationPlan) result.get("plan");

        assertEquals("ESCALATE", result.get("route"));
        assertEquals("BLOCKED", plan.getStatus());
        assertEquals("SCRIPT_GENERATION_UNAVAILABLE", result.get("reason"));
        assertFalse(plan.getGuardrailFindings().contains("UNGROUNDED_LLM_SCRIPT"));
        verify(requests, never()).save(any());
    }

    /**
     * The same grounded path as above with one thing removed: nobody has said which
     * machine. A script that would restart "whichever host the executor guesses" must not
     * reach an approver, so the plan is blocked and the escalation carries the question
     * an operator can answer — name the server, then plan again.
     */
    @Test
    void aTicketWithNoNamedServerIsAskedForOneInsteadOfReachingAnApprover() {
        UUID incidentId = stubIncident("Service unavailable", "The tomcat service is not responding", null);
        when(rag.findApprovedSopEvidence(any())).thenReturn(new SopEvidence(
                true, List.of(UUID.randomUUID()), "SOP: restart the tomcat service", 0.95,
                "APPROVED_SOP_MATCH", "RESTART_SERVICE:tomcat:linux"));

        Map<String, Object> result = workflow(false).createPlan(incidentId);
        RemediationPlan plan = (RemediationPlan) result.get("plan");

        assertEquals("ESCALATE", result.get("route"));
        assertEquals("BLOCKED", plan.getStatus());
        assertEquals("TARGET_HOST_UNKNOWN", result.get("reason"));
        assertTrue(String.valueOf(result.get("action")).contains("Enter the server"));
        assertTrue(plan.getGuardrailFindings().contains("TARGET_HOST_UNKNOWN"));
        verify(requests, never()).save(any());
    }

    /**
     * The last rung of the refusal ladder, and the only one the route can still close. It
     * replaces the old confidence band: nothing is below a number any more, so the one way
     * past the guardrails and into an escalation is an approved procedure the classifier
     * could not turn into a tool. That must not be reported as GUARDRAIL_BLOCKED — an
     * operator sent looking for a dangerous script finds advisory findings and no
     * explanation — and the advice has to say to work the procedure by hand.
     */
    @Test
    void anSopWithNoMatchingToolSaysSoInsteadOfBlamingTheGuardrails() {
        // Semantic RAG matched the restart procedure; the keyword classifier sees no "service",
        // no "printer", no "network" in this wording, so it names no action. Production shape.
        UUID incidentId = stubIncident("Back-office till is frozen at store 0042",
                "Staff cannot take payments on lane 3.");
        when(rag.findApprovedSopEvidence(any())).thenReturn(new SopEvidence(
                true, List.of(UUID.randomUUID()), "SOP: restart the tomcat service", 0.95,
                "APPROVED_SOP_MATCH", "RESTART_SERVICE:tomcat:linux"));

        Map<String, Object> result = workflow(false).createPlan(incidentId);
        RemediationPlan plan = (RemediationPlan) result.get("plan");

        assertEquals("ESCALATE", result.get("route"));
        assertEquals("BLOCKED", plan.getStatus());
        assertEquals("NO_TOOL_FOR_THIS_INCIDENT", result.get("reason"));
        assertTrue(String.valueOf(result.get("action")).contains("by hand"),
                "action was " + result.get("action"));
        verify(requests, never()).save(any());
    }

    /**
     * A second plan on an incident that already has one awaiting a decision. Two things must
     * hold and neither did: the refusal has to say which gate closed — it was reported as
     * GUARDRAIL_BLOCKED, sending an operator to look for a dangerous script that does not
     * exist — and it must not touch the incident, which was being flipped from
     * PENDING_APPROVAL to ESCALATED while the first plan still sat in the queue.
     */
    @Test
    void aDuplicatePlanNamesTheOpenOneAndLeavesTheIncidentAlone() {
        UUID incidentId = stubIncident("Service unavailable", "The tomcat service is not responding");
        RemediationPlan open = new RemediationPlan();
        open.setStatus("PENDING_APPROVAL");
        when(plans.findByIncidentIdOrderByCreatedAtDesc(incidentId)).thenReturn(List.of(open));
        when(rag.findApprovedSopEvidence(any())).thenReturn(new SopEvidence(
                true, List.of(UUID.randomUUID()), "SOP: restart the tomcat service", 0.95,
                "APPROVED_SOP_MATCH", "RESTART_SERVICE:tomcat:linux"));

        Map<String, Object> result = workflow(false).createPlan(incidentId);

        assertEquals("ESCALATE", result.get("route"));
        assertEquals("PLAN_ALREADY_AWAITING_DECISION", result.get("reason"));
        assertTrue(String.valueOf(result.get("action")).contains("review queue"),
                "action was " + result.get("action"));
        verify(requests, never()).save(any());
        verify(incidents, never()).save(any(Incident.class));
    }
}
