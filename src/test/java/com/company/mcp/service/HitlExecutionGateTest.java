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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gate in front of a real command, asserted one refusal at a time.
 *
 * {@code HitlWorkflowServiceTest} covers how a plan is built; this covers what happens when
 * somebody tries to run one that is not eligible. Every test here asserts the same two things:
 * the call fails, and {@code tools.execute} was never reached — because a gate that throws after
 * dispatching has already lost.
 */
class HitlExecutionGateTest {

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final RemediationPlanRepository plans = mock(RemediationPlanRepository.class);
    private final HitlRequestRepository requests = mock(HitlRequestRepository.class);
    private final ActionExecutionRepository executions = mock(ActionExecutionRepository.class);
    private final RemediationToolRegistry tools = mock(RemediationToolRegistry.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final SopProcedureService sopProcedures = mock(SopProcedureService.class);
    private final AuditService audit = mock(AuditService.class);

    private static final String APPROVED_HASH = "d1e5a0f2c3b4";

    private final HitlWorkflowService workflow = build();

    private HitlWorkflowService build() {
        HitlWorkflowService service = new HitlWorkflowService(incidents, plans, requests, executions, currentUser,
                mock(RagService.class), new GuardrailService(), new AgentAssessmentService(0.85, null, null), audit,
                new ObjectMapper(), tools, sopProcedures,
                new RemediationScriptService(mock(RagService.class), new GuardrailService(), 100),
                mock(IncidentPrecedentService.class), mock(UserRepository.class), mock(SystemConfigRepository.class));
        ReflectionTestUtils.setField(service, "allowUngroundedScripts", true);
        ReflectionTestUtils.setField(service, "separationOfDutiesRequired", true);
        when(currentUser.username()).thenReturn("reviewer");
        return service;
    }

    /**
     * An approval that is valid in every respect: APPROVED request, hash that still matches, a
     * guardrail PASS. Each test below spoils exactly one of those and asserts the refusal.
     */
    private UUID approvedRequest(String requestStatus, String planStatus, String planHash, String guardrailStatus) {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        Incident incident = Incident.builder().id(incidentId).subject("POS terminal offline")
                .description("register 3 will not start").priority("P2").externalId("FS-2001")
                .targetHost("store-0042-pos-01").build();

        RemediationPlan plan = new RemediationPlan();
        ReflectionTestUtils.setField(plan, "id", planId);
        plan.setIncidentId(incidentId);
        plan.setStatus(planStatus);
        plan.setActionName("Restart POS agent");
        plan.setTarget("store-0042-pos-01");
        plan.setParametersJson("{\"approvedActionKey\":\"RESTART_SERVICE:pos-agent\"}");
        plan.setPlanHash(planHash);
        plan.setGuardrailStatus(guardrailStatus);
        plan.setRemediationScript("systemctl restart pos-agent");
        plan.setScriptLanguage("bash");

        HitlRequest request = new HitlRequest();
        ReflectionTestUtils.setField(request, "id", requestId);
        request.setIncidentId(incidentId);
        request.setPlanId(planId);
        request.setStatus(requestStatus);
        request.setRequestedBy("analyst");
        request.setApprovedPlanHash(APPROVED_HASH);

        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(plans.findById(planId)).thenReturn(Optional.of(plan));
        when(incidents.findById(incidentId)).thenReturn(Optional.of(incident));
        return requestId;
    }

    private void assertRefused(UUID requestId, boolean dryRun, String expectedMessageFragment) {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> {
            if (dryRun) {
                workflow.dryRunAndExecute(requestId);
            } else {
                workflow.execute(requestId);
            }
        });
        assertEquals(true, thrown.getMessage().contains(expectedMessageFragment),
                "expected a refusal mentioning '" + expectedMessageFragment + "' but got: " + thrown.getMessage());
        verify(tools, never()).execute(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(executions, never()).save(any());
    }

    /** A rejected plan is a decision, not a delay: it cannot be run by calling execute anyway. */
    @Test
    void aRejectedRequestCannotExecute() {
        assertRefused(approvedRequest("REJECTED", "REJECTED", APPROVED_HASH, "PASS"), false,
                "Only an approved request can execute");
    }

    /** Nor can one nobody has decided yet. */
    @Test
    void aPendingRequestCannotExecute() {
        assertRefused(approvedRequest("PENDING", "PENDING_APPROVAL", APPROVED_HASH, "PASS"), false,
                "Only an approved request can execute");
    }

    /**
     * The defect this pins: an approval is a statement about one exact script. Editing the plan
     * row after approval — a different command, a different host — must invalidate the approval
     * rather than silently running the new text under the old sign-off.
     */
    @Test
    void aScriptChangedAfterApprovalIsNoLongerEligible() {
        assertRefused(approvedRequest("APPROVED", "APPROVED", "a-different-hash-entirely", "PASS"), true,
                "Plan changed since approval");
    }

    /** A guardrail verdict that is not PASS is not an execution permit, whatever the hash says. */
    @Test
    void aPlanThatDidNotPassTheGuardrailsIsNoLongerEligible() {
        assertRefused(approvedRequest("APPROVED", "APPROVED", APPROVED_HASH, "BLOCK"), true,
                "Plan changed since approval");
    }

    /** Real execution requires a dry run that passed. An APPROVED plan has not had one yet. */
    @Test
    void aRealRunWithoutAPassingDryRunIsRefused() {
        assertRefused(approvedRequest("APPROVED", "APPROVED", APPROVED_HASH, "PASS"), false,
                "Run the dry run first");
    }

    /** A dry run that failed leaves the plan BLOCKED, and BLOCKED is not an executable state. */
    @Test
    void aFailedDryRunLeavesNothingToExecute() {
        assertRefused(approvedRequest("APPROVED", "BLOCKED", APPROVED_HASH, "PASS"), false,
                "not in an executable state");
    }

    /**
     * Replay: the same approval, submitted twice. The first real run moves the plan to EXECUTED,
     * and that is what stops the second — one approval authorises one execution.
     */
    @Test
    void anAlreadyExecutedPlanCannotBeReplayed() {
        assertRefused(approvedRequest("APPROVED", "EXECUTED", APPROVED_HASH, "PASS"), false,
                "not in an executable state");
        assertRefused(approvedRequest("APPROVED", "FAILED", APPROVED_HASH, "PASS"), false,
                "not in an executable state");
    }

    /**
     * An executor that times out is a failure, not a success. The incident must escalate to a
     * human, the plan must not read as executed, and the procedure must not be credited.
     */
    @Test
    void anExecutorTimeoutEscalatesRatherThanReportingSuccess() {
        UUID requestId = approvedRequest("APPROVED", "SIMULATED", APPROVED_HASH, "PASS");
        when(tools.execute(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new RemediationToolRegistry.Outcome("FAILED", "EXECUTED",
                        "Executor did not respond within the read timeout.", "EXECUTOR_TIMEOUT"));

        workflow.execute(requestId);

        ArgumentCaptor<RemediationPlan> savedPlan = ArgumentCaptor.forClass(RemediationPlan.class);
        verify(plans).save(savedPlan.capture());
        assertEquals("FAILED", savedPlan.getValue().getStatus());

        ArgumentCaptor<Incident> savedIncident = ArgumentCaptor.forClass(Incident.class);
        verify(incidents).save(savedIncident.capture());
        assertEquals("ESCALATED", savedIncident.getValue().getStatus());

        // The learning loop must not be fed a failure as evidence the procedure works.
        verify(sopProcedures, never()).recordOutcome(any(), eq(true));
    }
}
