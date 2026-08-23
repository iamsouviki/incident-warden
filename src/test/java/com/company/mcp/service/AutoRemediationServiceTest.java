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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * This is the only code path in the platform that touches a system without a human
 * saying yes first, so the interesting assertions are all refusals. Each test removes
 * exactly one thing from an otherwise-passing case and expects nothing to run — a gate
 * that stops being load-bearing shows up here as a failure rather than as an unattended
 * restart in production.
 */
class AutoRemediationServiceTest {

    private final SystemConfigRepository config = mock(SystemConfigRepository.class);
    private final IncidentPrecedentService precedents = mock(IncidentPrecedentService.class);
    private final RemediationPlanRepository plans = mock(RemediationPlanRepository.class);
    private final ActionExecutionRepository executions = mock(ActionExecutionRepository.class);
    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentHistoryRepository history = mock(IncidentHistoryRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final NotificationService notifications = mock(NotificationService.class);

    private final AutoRemediationService service = new AutoRemediationService(config, precedents,
            new RemediationToolRegistry(new ObjectMapper(), new GuardrailService()), new GuardrailService(),
            plans, executions, incidents, history, audit, notifications, new ObjectMapper());

    @BeforeEach
    void allowByDefault() {
        when(config.findById(AutoRemediationService.ENABLED_KEY))
                .thenReturn(Optional.of(new SystemConfig(AutoRemediationService.ENABLED_KEY, "true")));
        when(plans.findByIncidentIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(plans.save(any(RemediationPlan.class))).thenAnswer(call -> {
            RemediationPlan plan = call.getArgument(0);
            if (plan.getId() == null) ReflectionTestUtils.setField(plan, "id", UUID.randomUUID());
            return plan;
        });
        when(executions.save(any(ActionExecution.class))).thenAnswer(call -> {
            ActionExecution execution = call.getArgument(0);
            ReflectionTestUtils.setField(execution, "id", UUID.randomUUID());
            return execution;
        });
    }

    @Test
    void offByDefaultAndOffMeansOff() {
        when(config.findById(AutoRemediationService.ENABLED_KEY)).thenReturn(Optional.empty());
        givenPrecedent("RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE", 0.95, List.of("printer", "queue", "stuck"));

        AutoRemediationService.Result result = service.considerNewIncident(incident("P3"));

        assertFalse(result.ran());
        assertEquals("AUTORUN_DISABLED", result.reason());
        assertFalse(service.enabled());
        nothingHappened();
    }

    @Test
    void aP1AlwaysWaitsForAHuman() {
        givenPrecedent("RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE", 0.95, List.of("printer", "queue", "stuck"));

        AutoRemediationService.Result result = service.considerNewIncident(incident("P1"));

        assertEquals("P1_ALWAYS_NEEDS_A_HUMAN", result.reason());
        nothingHappened();
    }

    @Test
    void anIncidentAlreadyAwaitingApprovalIsLeftAlone() {
        Incident incident = incident("P3");
        RemediationPlan pending = new RemediationPlan();
        pending.setStatus("PENDING_APPROVAL");
        when(plans.findByIncidentIdOrderByCreatedAtDesc(incident.getId())).thenReturn(List.of(pending));
        givenPrecedent("RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE", 0.95, List.of("printer", "queue", "stuck"));

        assertEquals("PLAN_ALREADY_IN_FLIGHT", service.considerNewIncident(incident).reason());
        nothingHappened();
    }

    @Test
    void aWeakResemblanceIsNotPermission() {
        givenPrecedent("RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE", 0.45, List.of("printer", "queue", "stuck"));

        assertTrue(service.considerNewIncident(incident("P3")).reason().startsWith("PRECEDENT_TOO_WEAK"));
        nothingHappened();
    }

    /**
     * Coverage alone is gameable: a two-word ticket matches an old one perfectly while
     * sharing almost nothing. The term floor is what makes a high score mean something.
     */
    @Test
    void aPerfectMatchOnTwoWordsIsStillTooThin() {
        givenPrecedent("RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE", 1.0, List.of("printer", "queue"));

        assertTrue(service.considerNewIncident(incident("P3")).reason().startsWith("PRECEDENT_TOO_THIN"));
        nothingHappened();
    }

    /** One reviewer approving a model's guess once is not a standing licence to repeat it. */
    @Test
    void aModelWrittenScriptIsNeverRepeatedUnattended() {
        givenPrecedent("RESTART_SERVICE:spooler:windows", "LLM_KNOWLEDGE", 0.95, List.of("printer", "queue", "stuck"));

        assertEquals("SCRIPT_SOURCE_NOT_TRUSTED:LLM_KNOWLEDGE", service.considerNewIncident(incident("P3")).reason());
        nothingHappened();
    }

    /** A flushed cache cannot be un-flushed, so CLEAR_CACHE keeps needing a person. */
    @Test
    void onlyReadOnlyAndRestartToolsQualify() {
        givenPrecedent("CLEAR_CACHE:printer:spool:linux", "SOP_TEMPLATE", 0.95, List.of("printer", "queue", "stuck"));

        assertEquals("TOOL_NOT_AUTO_RUNNABLE:CLEAR_CACHE", service.considerNewIncident(incident("P3")).reason());
        nothingHappened();
    }

    /**
     * A past plan that cites no approved procedure is a fix somebody performed, not a fix
     * the tenant documented. Repeating it would invent authority nobody granted.
     */
    @Test
    void aPrecedentWithNoApprovedProcedureBehindItIsRefused() {
        IncidentPrecedentService.Precedent precedent = new IncidentPrecedentService.Precedent(
                UUID.randomUUID(), "INC-OLD", UUID.randomUUID(), "restart-approved-service",
                "RESTART_SERVICE:spooler:windows", "Restart-Service -Name 'spooler'", "powershell", "SOP_TEMPLATE",
                "", List.of(), 0.95, List.of("printer", "queue", "stuck"),
                "Restarted the spooler.", OffsetDateTime.now(), "0042", "");
        when(precedents.findPrecedent(anyString(), any())).thenReturn(Optional.of(precedent));

        assertEquals("PRECEDENT_NOT_SOP_BACKED", service.considerNewIncident(incident("P3")).reason());
        nothingHappened();
    }

    /**
     * Re-scanned at run time, not trusted from the old approval: a term added to the block
     * list after that approval must still stop this script.
     */
    @Test
    void aScriptThatNoLongerScansCleanIsRefused() {
        IncidentPrecedentService.Precedent precedent = precedent("RESTART_SERVICE:spooler:windows",
                "SOP_TEMPLATE", 0.95, List.of("printer", "queue", "stuck"), "rm -rf /var/spool/printers");
        when(precedents.findPrecedent(anyString(), any())).thenReturn(Optional.of(precedent));

        assertEquals("SCRIPT_SCAN_NOT_CLEAN:BLOCK", service.considerNewIncident(incident("P3")).reason());
        nothingHappened();
    }

    /**
     * The guardrail boundary is re-evaluated against the new incident, so an action that
     * is not on the allow list cannot ride in on a precedent.
     */
    @Test
    void theGuardrailBoundaryStillApplies() {
        IncidentPrecedentService.Precedent precedent = new IncidentPrecedentService.Precedent(
                UUID.randomUUID(), "INC-OLD", UUID.randomUUID(), "delete-everything",
                "RESTART_SERVICE:spooler:windows", "Restart-Service -Name 'spooler'", "powershell", "SOP_TEMPLATE",
                "SOP: restart the spooler", List.of(UUID.randomUUID()), 0.95, List.of("printer", "queue", "stuck"),
                "Restarted the spooler.", OffsetDateTime.now(), "0042", "");
        when(precedents.findPrecedent(anyString(), any())).thenReturn(Optional.of(precedent));

        assertTrue(service.considerNewIncident(incident("P3")).reason().startsWith("GUARDRAIL_BLOCKED"));
        nothingHappened();
    }

    /**
     * Every gate passes, but nothing is wired to change anything: execution is disabled, so
     * the tool registry simulates. The run is recorded and audited — and no mail goes out,
     * because "Resolved" and "FAILED" would both be lies about an action that never started.
     */
    @Test
    void aSimulatedRunIsRecordedButNeverAnnounced() {
        Incident incident = incident("P3");
        givenPrecedent("RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE", 0.95, List.of("printer", "queue", "stuck"));

        AutoRemediationService.Result result = service.considerNewIncident(incident);

        assertFalse(result.ran());
        assertTrue(result.reason().startsWith("NOTHING_EXECUTED:EXECUTION_DISABLED"));
        assertEquals("New", incident.getStatus());                 // untouched: the HITL lane still owns it
        verify(executions).save(any(ActionExecution.class));
        verify(audit).record(any(), any(), any(), any(), any(), any());
        verify(notifications, never()).notifyAutoRemediation(any(), any(), any(), any(), anyBoolean(), any());
        verify(history, never()).save(any(IncidentHistory.class));
    }

    /**
     * The end-to-end case, with a read-only probe as the action that really runs: an
     * unreachable endpoint is a genuine LIVE_READ_ONLY failure, so the incident escalates
     * and the reporter is told. The recorded execution carries no hitl_request_id, which is
     * what stops this run from becoming the authority for the next one.
     */
    @Test
    void aRealRunEscalatesAndNotifiesAndCannotBecomeItsOwnPrecedent() {
        Incident incident = incident("P3");
        IncidentPrecedentService.Precedent precedent = new IncidentPrecedentService.Precedent(
                UUID.randomUUID(), "INC-OLD", UUID.randomUUID(), "refresh-network-session",
                // Port 1 on loopback: nothing listens, so the probe fails without leaving the host.
                "CHECK_URL:http://127.0.0.1:1/health:200", "", "", "SOP_TEMPLATE",
                "SOP: check the health endpoint", List.of(UUID.randomUUID()), 0.95, List.of("printer", "queue", "stuck"),
                "Probed the endpoint.", OffsetDateTime.now(), "0042", "");
        when(precedents.findPrecedent(anyString(), any())).thenReturn(Optional.of(precedent));
        when(notifications.notifyAutoRemediation(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);

        AutoRemediationService.Result result = service.considerNewIncident(incident);

        assertTrue(result.ran());
        assertFalse(result.resolved());
        assertTrue(result.notified());
        assertEquals("ESCALATED", incident.getStatus());
        verify(notifications).notifyAutoRemediation(any(), any(), any(), any(), anyBoolean(), any());
        verify(history).save(any(IncidentHistory.class));

        org.mockito.ArgumentCaptor<ActionExecution> saved = org.mockito.ArgumentCaptor.forClass(ActionExecution.class);
        verify(executions).save(saved.capture());
        assertNull(saved.getValue().getHitlRequestId());
        assertEquals("LIVE_READ_ONLY", saved.getValue().getMode());
        assertTrue(saved.getValue().getValidationResult().contains("INC-OLD"));
    }

    /** The plan the run records must pin the script that ran, so the audit trail is checkable. */
    @Test
    void theRecordedPlanPinsTheScriptAndTheCitedPrecedent() {
        givenPrecedent("RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE", 0.95, List.of("printer", "queue", "stuck"));

        service.considerNewIncident(incident("P3"));

        org.mockito.ArgumentCaptor<RemediationPlan> saved = org.mockito.ArgumentCaptor.forClass(RemediationPlan.class);
        verify(plans, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        RemediationPlan plan = saved.getValue();
        assertEquals("Restart-Service -Name 'spooler'", plan.getRemediationScript());
        assertEquals(64, plan.getPlanHash().length());
        assertTrue(plan.getParametersJson().contains("\"reference\":\"INC-OLD\""));
        assertTrue(plan.getGuardrailFindings().contains("AUTO_RUN_FROM_APPROVED_PRECEDENT"));
        // The target is this incident's host, not the one the precedent was fixed on.
        assertEquals("store-0042-pos-01", plan.getTarget());
    }

    /**
     * The store gate, which is what makes inherited autonomy mean anything. A human
     * approving a restart at store 0042 said something about store 0042's till, network
     * and opening hours — not about store 0099's, however identically worded the ticket.
     */
    @Test
    void anApprovalAtOneStoreIsNotPermissionAtAnother() {
        Incident elsewhere = incident("P3");
        elsewhere.setStoreNumber("0099");
        givenPrecedent("RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE", 0.95, List.of("printer", "queue", "stuck"));

        assertEquals("STORE_MISMATCH:0099!=0042", service.considerNewIncident(elsewhere).reason());
        nothingHappened();
    }

    /**
     * Nobody is watching an unattended run, so there is nobody to ask which machine. A
     * mutating tool with no named host stops here rather than letting the executor pick.
     */
    @Test
    void aMutatingToolWithNoNamedMachineIsRefused() {
        Incident nameless = incident("P3");
        nameless.setTargetHost(null);
        givenPrecedent("RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE", 0.95, List.of("printer", "queue", "stuck"));

        assertEquals("TARGET_HOST_UNKNOWN", service.considerNewIncident(nameless).reason());
        nothingHappened();
    }

    /**
     * The stored script is replayed verbatim, so it has to be a script this machine can run.
     * A bash precedent aimed at a Windows till would reach the host and fail on the first
     * line — noisy, and an unattended lane has nobody to read the noise. Refusing hands it
     * back to the HITL lane, which regenerates for the platform it actually resolved.
     */
    @Test
    void aScriptForTheWrongPlatformIsRefused() {
        IncidentPrecedentService.Precedent bashOnAWindowsTill = new IncidentPrecedentService.Precedent(
                UUID.randomUUID(), "INC-OLD", UUID.randomUUID(), "restart-approved-service",
                "RESTART_SERVICE:spooler:windows", "systemctl restart 'spooler'", "bash", "SOP_TEMPLATE",
                "SOP: restart the spooler", List.of(UUID.randomUUID()), 0.95, List.of("printer", "queue", "stuck"),
                "Restarted the spooler.", OffsetDateTime.now(), "0042", "");
        when(precedents.findPrecedent(anyString(), any())).thenReturn(Optional.of(bashOnAWindowsTill));

        assertEquals("PLATFORM_MISMATCH:bash!=powershell", service.considerNewIncident(incident("P3")).reason());
        nothingHappened();
    }

    private void givenPrecedent(String actionKey, String scriptSource, double similarity, List<String> matchedTerms) {
        when(precedents.findPrecedent(anyString(), any()))
                .thenReturn(Optional.of(precedent(actionKey, scriptSource, similarity, matchedTerms,
                        "Restart-Service -Name 'spooler'")));
    }

    /**
     * A Windows till running the print spooler, which is what the incident describes and what
     * the action key's OS segment says. The script and its language have to agree with that:
     * an incoherent fixture is now caught by the platform gate rather than quietly restarting
     * a service with a command the host does not have.
     */
    private IncidentPrecedentService.Precedent precedent(String actionKey, String scriptSource, double similarity,
                                                         List<String> matchedTerms, String script) {
        return new IncidentPrecedentService.Precedent(UUID.randomUUID(), "INC-OLD", UUID.randomUUID(),
                "restart-approved-service", actionKey, script, "powershell", scriptSource,
                "SOP: restart the approved service", List.of(UUID.randomUUID()), similarity, matchedTerms,
                "Restarted the spooler.", OffsetDateTime.now(), "0042", "");
    }

    private Incident incident(String priority) {
        return Incident.builder().id(UUID.fromString("00000000-0000-0000-0000-0000000000ab"))
                .tenantId("tenant-a").subject("Printer queue stuck").description("Nothing prints")
                .priority(priority).status("New").externalId("inc-new-1")
                .storeNumber("0042").targetHost("store-0042-pos-01")
                .reporterEmail("reporter@example.com").build();
    }

    private void nothingHappened() {
        verify(plans, never()).save(any(RemediationPlan.class));
        verify(executions, never()).save(any(ActionExecution.class));
        verify(notifications, never()).notifyAutoRemediation(any(), any(), any(), any(), anyBoolean(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    /** The precedent record must survive the round trip into the evidence the guardrails read. */
    @Test
    void theCitedPrecedentBecomesTheEvidenceTheGuardrailsSee() {
        SopEvidence evidence = precedent("RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE", 0.9,
                List.of("printer", "queue", "stuck"), "Restart-Service -Name 'spooler'").asEvidence();

        assertTrue(evidence.approvedEvidencePresent());
        assertEquals("PRECEDENT_APPROVED_EXECUTION:INC-OLD", evidence.reason());
    }
}
