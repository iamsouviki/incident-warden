package com.company.mcp.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardrailServiceTest {
    private final GuardrailService guardrails = new GuardrailService();

    @Test
    void blocksDestructiveOrBroadPlans() {
        var result = guardrails.evaluate("rm -rf /", "all-devices", "Approved SOP", 0);
        assertFalse(result.passed());
        assertTrue(result.findings().stream().anyMatch(value -> value.contains("ACTION_NOT_ALLOWLISTED")));
        assertTrue(result.findings().stream().anyMatch(value -> value.contains("BLAST_RADIUS_EXCEEDED")));
    }

    @Test
    void acceptsOnlyNarrowSopBackedAllowListedPlan() {
        var evidence = new SopEvidence(true, true, java.util.List.of(java.util.UUID.randomUUID()), "Approved SOP: restart the POS service", 0.90, "APPROVED_TENANT_SOP_MATCH");
        var result = guardrails.evaluate("restart-approved-service", "store-001-pos-02", evidence, 0);
        assertTrue(result.passed());
        assertTrue(result.findings().contains("DRY_RUN_REQUIRED"));
    }

    @Test
    void blocksUnavailableSopEvidenceEvenWhenTheActionIsAllowListed() {
        var result = guardrails.evaluate("clear-printer-queue", "FS-1001", SopEvidence.unavailable("SOP_SERVICE_UNAVAILABLE"), 0);
        assertFalse(result.passed());
        assertTrue(result.findings().stream().anyMatch(value -> value.startsWith("NO_APPROVED_SOP_EVIDENCE")));
    }
}
