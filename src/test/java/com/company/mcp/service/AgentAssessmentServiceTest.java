package com.company.mcp.service;

import com.company.mcp.model.Incident;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAssessmentServiceTest {
    // The production HITL band (0.80) and prior (0.85) are passed explicitly: this
    // test asserts the routing arithmetic at those values, not Spring's wiring.
    private final AgentAssessmentService agents = new AgentAssessmentService(0.80, 0.85);

    @Test
    void escalatesWhenTrustedSopEvidenceDoesNotReachTheHitlConfidenceBand() {
        Incident incident = Incident.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .subject("Printer queue is stuck")
                .description("Print jobs are not progressing on a single store printer")
                .priority("P3")
                .externalId("FS-1001")
                .build();
        SopEvidence evidence = new SopEvidence(true, true, List.of(UUID.randomUUID()),
                "Approved printer queue SOP: inspect and clear the affected printer queue.", 0.90, "APPROVED_TENANT_SOP_MATCH");

        AgentAssessmentService.Assessment assessment = agents.assess(incident, evidence);

        assertTrue(assessment.confidenceScore() < 80.0);
        assertEquals("ESCALATE", assessment.route());
        assertEquals("clear-printer-queue", assessment.action());
    }

    /**
     * The wording a store manager actually uses. This ticket names no "service" and no
     * "daemon", so it used to classify UNCLASSIFIED — which carries no action, which the
     * guardrail then blocks as ACTION_NOT_ALLOWLISTED. The approved Tomcat procedure was
     * matched and unusable at the same time.
     */
    @Test
    void aTicketWordedTheWayStoresWordItStillFindsTheApplicationAction() {
        Incident incident = Incident.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .subject("Tomcat application unresponsive at store 0042")
                .description("Back-office app is not responding. Tomcat appears down on the application server.")
                .priority("P3")
                .externalId("FS-1003")
                .build();
        SopEvidence evidence = new SopEvidence(true, true, List.of(UUID.randomUUID()),
                "Approved SOP: restart the Tomcat service, then confirm the health endpoint.", 0.90, "APPROVED_TENANT_SOP_MATCH");

        AgentAssessmentService.Assessment assessment = agents.assess(incident, evidence);

        assertEquals("APPLICATION", assessment.category());
        assertEquals("restart-approved-service", assessment.action());
    }

    @Test
    void escalatesWhenSopEvidenceIsUnavailable() {
        Incident incident = Incident.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .subject("Application service is unavailable")
                .description("One service instance is unavailable")
                .priority("P3")
                .externalId("FS-1002")
                .build();

        AgentAssessmentService.Assessment assessment = agents.assess(incident, SopEvidence.unavailable("SOP_SERVICE_UNAVAILABLE"));

        assertEquals("ESCALATE", assessment.route());
        assertEquals(0.0, assessment.sopReliability());
    }
}
