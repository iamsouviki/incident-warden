package com.company.mcp.service;

import com.company.mcp.model.Incident;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The routing rule, which is now the whole decision: an approved procedure backs this incident
 * and the classifier named a tool, so a human is offered the plan. There is no score and no band.
 *
 * <p>These cases are the check that confidence was <em>removed</em> rather than defaulted to a
 * value that always passes — each one pins route to the two inputs that are allowed to decide it,
 * so reintroducing any third factor fails here.
 */
class AgentAssessmentServiceTest {
    // null repository = no authored procedures, so classification falls back
    // to the foundational vocabulary. That is exactly the path these cases assert, and it
    // keeps them a unit test rather than a database test.
    private final AgentAssessmentService agents = new AgentAssessmentService(0.85, null, null);

    private static final SopEvidence APPROVED_PRINTER_SOP = new SopEvidence(true, List.of(UUID.randomUUID()),
            "Approved printer queue SOP: inspect and clear the affected printer queue.", 0.90,
            "APPROVED_SOP_MATCH");

    private static Incident incident(String subject, String description, String priority, String externalId) {
        return Incident.builder().id(UUID.randomUUID())
                .subject(subject).description(description).priority(priority).externalId(externalId).build();
    }

    /** Scenario A: SOP present, tool known. This is the only route to an approval request. */
    @Test
    void routesToApprovalWhenAnApprovedSopAndAKnownToolBothExist() {
        AgentAssessmentService.Assessment assessment = agents.assess(
                incident("Printer queue is stuck", "Print jobs are not progressing on a single store printer",
                        "P3", "FS-1001"),
                APPROVED_PRINTER_SOP);

        assertEquals("HITL_REQUIRED", assessment.route());
        assertEquals("clear-printer-queue", assessment.action());
    }

    /** Scenario B/C: no approved procedure, so nothing is offered to run regardless of wording. */
    @Test
    void escalatesWhenSopEvidenceIsUnavailable() {
        AgentAssessmentService.Assessment assessment = agents.assess(
                incident("Application service is unavailable", "One service instance is unavailable",
                        "P3", "FS-1002"),
                SopEvidence.unavailable("SOP_SERVICE_UNAVAILABLE"));

        assertEquals("ESCALATE", assessment.route());
        assertEquals(0.0, assessment.sopReliability());
    }

    /**
     * The other half of the rule: an approved procedure matched, but nothing in the text names an
     * action this platform can run. That escalates — and it is the single reason the planner can
     * now report NO_TOOL_FOR_THIS_INCIDENT, which is why it needs its own case.
     */
    @Test
    void escalatesWhenAnSopExistsButNoToolCoversTheIncident() {
        SopEvidence unrelatedSop = new SopEvidence(true, List.of(UUID.randomUUID()),
                "Approved SOP: escort the auditor to the server room and log the visit.", 0.90,
                "APPROVED_SOP_MATCH");

        AgentAssessmentService.Assessment assessment = agents.assess(
                incident("Quarterly compliance walkthrough", "An auditor needs supervised access to the cage.",
                        "P3", "FS-1004"),
                unrelatedSop);

        assertEquals("", assessment.action());
        assertEquals("ESCALATE", assessment.route());
    }

    /**
     * Priority no longer gates the route. It used to: a P1 with perfect evidence scored ~24%
     * against an 80% band and was escalated for being a P1, so the operator got a refusal
     * instead of the script and rollback they still had to work from by hand.
     *
     * Risk has not gone anywhere — riskPenalty still reaches the reviewer as the plan's risk
     * score — but a person approves every plan either way, so suppressing the plan bought
     * nothing and cost the reviewer their evidence.
     */
    @Test
    void priorityChangesTheRiskItReportsAndNotTheRoute() {
        SopEvidence perfect = new SopEvidence(true, List.of(UUID.randomUUID()),
                "Approved SOP: restart the Tomcat service, then confirm the health endpoint.", 1.0,
                "APPROVED_SOP_MATCH");

        for (String priority : List.of("P1", "P2", "P3")) {
            AgentAssessmentService.Assessment assessment = agents.assess(
                    incident("Tomcat application unresponsive at store 0042",
                            "Tomcat is not responding on the application server.", priority, "FS-9001"),
                    perfect, 1.0, 1.0);

            assertEquals("HITL_REQUIRED", assessment.route(), priority + " did not reach a reviewer");
            assertEquals("restart-approved-service", assessment.action());
        }

        // Same evidence, different risk reported to that reviewer.
        assertEquals(0.60, agents.assess(incident("Tomcat application unresponsive", "Tomcat is not responding.",
                "P1", "FS-9002"), perfect, 1.0, 1.0).riskPenalty());
        assertEquals(0.10, agents.assess(incident("Tomcat application unresponsive", "Tomcat is not responding.",
                "P3", "FS-9003"), perfect, 1.0, 1.0).riskPenalty());
    }

    /**
     * The wording a store manager actually uses. This ticket names no "service" and no
     * "daemon", so it used to classify UNCLASSIFIED — which carries no action, which the
     * guardrail then blocks as ACTION_NOT_ALLOWLISTED. The approved Tomcat procedure was
     * matched and unusable at the same time.
     */
    @Test
    void aTicketWordedTheWayStoresWordItStillFindsTheApplicationAction() {
        SopEvidence evidence = new SopEvidence(true, List.of(UUID.randomUUID()),
                "Approved SOP: restart the Tomcat service, then confirm the health endpoint.", 0.90,
                "APPROVED_SOP_MATCH");

        AgentAssessmentService.Assessment assessment = agents.assess(
                incident("Tomcat application unresponsive at store 0042",
                        "Back-office app is not responding. Tomcat appears down on the application server.",
                        "P3", "FS-1003"),
                evidence);

        assertEquals("APPLICATION", assessment.category());
        assertEquals("restart-approved-service", assessment.action());
    }
}
