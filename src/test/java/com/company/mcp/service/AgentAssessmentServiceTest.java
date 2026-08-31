package com.company.mcp.service;

import com.company.mcp.model.Incident;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAssessmentServiceTest {
    // The production HITL band (0.80) and prior (0.85) are passed explicitly: this
    // test asserts the routing arithmetic at those values, not Spring's wiring.
    // null repository = this tenant has no authored procedures, so classification falls back
    // to the foundational vocabulary. That is exactly the path these cases assert, and it
    // keeps them a unit test rather than a database test.
    private final AgentAssessmentService agents = new AgentAssessmentService(0.80, 0.85, null, null, null);

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

    /**
     * The ceiling the README publishes, as a test. With every input at its best — perfect
     * pattern match, perfect history, perfect SOP reliability — a P1 still lands near 24.5%
     * and a P2 near 58%, against a band of 70–80%. So no P1 or P2 is ever routed for
     * approval, which is the risk penalty working, not a bug.
     *
     * Here so that re-weighting riskPenalty or systemHealth fails loudly instead of silently
     * making the documented table wrong — or silently letting a P1 script reach an approver.
     */
    @Test
    void aP1OrP2CannotReachTheApprovalBandNoMatterHowGoodTheEvidence() {
        SopEvidence perfect = new SopEvidence(true, true, List.of(UUID.randomUUID()),
                "Approved SOP: restart the Tomcat service, then confirm the health endpoint.", 1.0,
                "APPROVED_TENANT_SOP_MATCH");

        for (String priority : List.of("P1", "P2")) {
            Incident incident = Incident.builder().id(UUID.randomUUID()).tenantId("tenant-a")
                    .subject("Tomcat application unresponsive at store 0042")
                    .description("Tomcat is not responding on the application server.")
                    .priority(priority).externalId("FS-9001").build();

            AgentAssessmentService.Assessment best = agents.assess(incident, perfect, 1.0, 1.0);

            assertEquals("ESCALATE", best.route(), priority + " reached the approval band");
            assertTrue(best.confidenceScore() < 70.0,
                    priority + " best-case score was " + best.confidenceScore() + ", which clears the local 70% band");
        }

        // The same words at P3 do clear it, so the assertion above is about priority and not
        // about the evidence being too weak to route anything at all.
        Incident p3 = Incident.builder().id(UUID.randomUUID()).tenantId("tenant-a")
                .subject("Tomcat application unresponsive at store 0042")
                .description("Tomcat is not responding on the application server.")
                .priority("P3").externalId("FS-9002").build();
        assertEquals("HITL_REQUIRED", agents.assess(p3, perfect, 1.0, 1.0).route());
    }

    /**
     * The band comes from the config row the AI configuration page writes, not from the
     * property the planner was constructed with. Before this, an admin lowering the band on
     * that page changed incident routing and nothing else — the planner kept its own copy and
     * went on refusing to raise a plan at the old number.
     */
    @Test
    void theBandAnAdminSetsOnTheConfigPageIsTheBandThePlannerUses() {
        AiConfigService config = mock(AiConfigService.class);
        when(config.getHitlThreshold()).thenReturn("0.40");
        AgentAssessmentService tuned = new AgentAssessmentService(0.80, 0.85, null, config, null);

        Incident p2 = Incident.builder().id(UUID.randomUUID()).tenantId("tenant-a")
                .subject("Tomcat application unresponsive at store 0042")
                .description("Tomcat is not responding on the application server.")
                .priority("P2").externalId("FS-9003").build();
        SopEvidence perfect = new SopEvidence(true, true, List.of(UUID.randomUUID()),
                "Approved SOP: restart the Tomcat service, then confirm the health endpoint.", 1.0,
                "APPROVED_TENANT_SOP_MATCH");

        assertEquals(40.0, tuned.hitlBandPercent());
        // Same inputs the test above proves are stuck at ESCALATE against a 70-80% band.
        assertEquals("HITL_REQUIRED", tuned.assess(p2, perfect, 1.0, 1.0).route());
        assertEquals("ESCALATE", agents.assess(p2, perfect, 1.0, 1.0).route());
    }
}
