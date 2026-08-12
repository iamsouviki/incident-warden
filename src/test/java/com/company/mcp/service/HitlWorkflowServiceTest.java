package com.company.mcp.service;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.model.Incident;
import com.company.mcp.model.RemediationPlan;
import com.company.mcp.repository.ActionExecutionRepository;
import com.company.mcp.repository.HitlRequestRepository;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.repository.RemediationPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HitlWorkflowServiceTest {

    @Test
    void unavailableSopCreatesBlockedPlanAndNeverCreatesApprovalRequest() {
        IncidentRepository incidents = mock(IncidentRepository.class);
        RemediationPlanRepository plans = mock(RemediationPlanRepository.class);
        HitlRequestRepository requests = mock(HitlRequestRepository.class);
        ActionExecutionRepository executions = mock(ActionExecutionRepository.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        RagService rag = mock(RagService.class);
        AuditService audit = mock(AuditService.class);
        UUID incidentId = UUID.randomUUID();
        Incident incident = Incident.builder().id(incidentId).tenantId("tenant-a")
                .subject("Printer queue is stuck").description("The printer queue is blocked")
                .priority("P3").externalId("FS-1001").build();

        when(currentUser.tenantId()).thenReturn("tenant-a");
        when(currentUser.username()).thenReturn("analyst");
        when(incidents.findById(incidentId)).thenReturn(Optional.of(incident));
        when(plans.findByIncidentIdOrderByCreatedAtDesc(incidentId)).thenReturn(List.of());
        when(plans.save(any(RemediationPlan.class))).thenAnswer(invocation -> {
            RemediationPlan plan = invocation.getArgument(0);
            ReflectionTestUtils.setField(plan, "id", UUID.randomUUID());
            return plan;
        });
        when(rag.findApprovedSopEvidence(eq("tenant-a"), any())).thenReturn(SopEvidence.unavailable("SOP_SERVICE_UNAVAILABLE"));

        HitlWorkflowService workflow = new HitlWorkflowService(incidents, plans, requests, executions, currentUser,
                rag, new GuardrailService(), new AgentAssessmentService(), audit, new ObjectMapper());
        Map<String, Object> result = workflow.createPlan(incidentId);

        assertEquals("ESCALATE", result.get("route"));
        assertTrue(result.get("plan") instanceof RemediationPlan);
        assertEquals("BLOCKED", ((RemediationPlan) result.get("plan")).getStatus());
        verify(plans).save(any(RemediationPlan.class));
        verify(requests, never()).save(any());
    }
}
