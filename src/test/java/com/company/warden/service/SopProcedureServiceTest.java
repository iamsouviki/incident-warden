package com.company.warden.service;

import com.company.warden.model.SopProcedure;
import com.company.warden.repository.SopProcedureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The point of these tests is that "no evidence" is a reachable outcome. The
 * implementation they replaced always reported approved evidence, so the fail-closed
 * gate downstream could never actually close.
 */
class SopProcedureServiceTest {

    private SopProcedureRepository repository;
    private SopProcedureService service;

    @BeforeEach
    void setUp() {
        repository = mock(SopProcedureRepository.class);
        service = new SopProcedureService(repository);
    }

    @Test
    void returnsNoMatchWhenThereIsNoApprovedProcedure() {
        when(repository.findByApprovalStatus("APPROVED")).thenReturn(List.of());

        SopEvidence evidence = service.toEvidence("Core switch in the DC is down, whole floor offline");

        assertFalse(evidence.approvedEvidencePresent(), "no approved procedure must not produce evidence");
        assertEquals("NO_APPROVED_SOP_MATCH", evidence.reason());
    }

    @Test
    void returnsNoMatchWhenNothingIsRelevantEnough() {
        when(repository.findByApprovalStatus("APPROVED"))
                .thenReturn(seed());

        // Shares no signal term with any seeded procedure.
        SopEvidence evidence = service.toEvidence("Employee badge reader rejecting valid badges at the loading dock");

        assertFalse(evidence.approvedEvidencePresent());
        assertEquals("NO_APPROVED_SOP_MATCH", evidence.reason());
    }

    @Test
    void singleSharedWordIsNotEnoughToClaimEvidence() {
        List<SopProcedure> only = List.of(
                procedure("SOP-CACHE-01", "Flush the cache tier", "Flush Redis", "cache redis stale flush", 0.8));
        when(repository.findByApprovalStatus("APPROVED")).thenReturn(only);

        // "cache" alone scores 1.5, under the 2.0 floor: one word is a coincidence, not evidence.
        SopEvidence evidence = service.toEvidence("Browser cache question from a store manager");

        assertFalse(evidence.approvedEvidencePresent());
    }

    @Test
    void matchesTheRightProcedureAndCarriesItsIdAndActionKey() {
        List<SopProcedure> seeded = seed();
        when(repository.findByApprovalStatus("APPROVED")).thenReturn(seeded);

        SopEvidence evidence = service.toEvidence("Receipt printer at register 3 shows offline, print queue is stuck");

        assertTrue(evidence.approvedEvidencePresent());
        assertEquals("APPROVED_SOP_MATCH", evidence.reason());
        assertTrue(evidence.excerpt().contains("RESTART_SERVICE:spooler:windows"),
                "the excerpt must name the action the plan is authorised to run");
        SopProcedure printer = seeded.get(0);
        assertTrue(evidence.procedureIds().contains(printer.getId()),
                "evidence must cite the real row id, not a generated one");
    }

    /** No text is not a match against everything: it is a match against nothing. */
    @Test
    void missingIncidentTextYieldsNoEvidence() {
        assertFalse(service.toEvidence(null).approvedEvidencePresent());
        assertFalse(service.toEvidence("  ").approvedEvidencePresent());
    }

    @Test
    void reliabilityFallsBackToThePriorUntilThereIsHistory() {
        SopProcedure fresh = procedure("SOP-X", "t", "d", "k", 0.75);
        assertEquals(0.75, fresh.observedSuccessRate(), 1e-9);

        fresh.setSuccessCount(3);
        fresh.setFailureCount(1);
        assertEquals(0.75, fresh.observedSuccessRate(), 1e-9);

        fresh.setSuccessCount(1);
        fresh.setFailureCount(3);
        assertEquals(0.25, fresh.observedSuccessRate(), 1e-9);
    }

    private List<SopProcedure> seed() {
        List<SopProcedure> list = new ArrayList<>();
        list.add(procedure("SOP-PRINT-01", "Store printer offline — clear queue and restart spooler",
                "Printer shows offline in the POS application. Clear the stuck print queue.",
                "printer print spooler queue offline receipt pos jam",
                "RESTART_SERVICE:spooler:windows", 0.92));
        list.add(procedure("SOP-TOMCAT-01", "Tomcat application unresponsive — restart service",
                "Application returns 502 while the host responds to ping.",
                "tomcat application unresponsive 502 timeout java webapp hung",
                "RESTART_SERVICE:tomcat:linux", 0.88));
        return list;
    }

    private SopProcedure procedure(String sopId, String title, String description, String keywords, double reliability) {
        return procedure(sopId, title, description, keywords, "CHECK_URL:http://localhost:8080/health:200", reliability);
    }

    private SopProcedure procedure(String sopId, String title, String description, String keywords,
                                   String actionKey, double reliability) {
        SopProcedure p = new SopProcedure();
        p.setId(UUID.randomUUID());
        p.setSopId(sopId);
        p.setTitle(title);
        p.setDescription(description);
        p.setMatchKeywords(keywords);
        p.setActionKey(actionKey);
        p.setApprovalStatus("APPROVED");
        p.setReliability(reliability);
        return p;
    }
}
