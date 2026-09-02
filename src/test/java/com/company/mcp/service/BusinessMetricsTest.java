package com.company.mcp.service;

import com.company.mcp.model.AuditEvent;
import com.company.mcp.repository.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The business counters, asserted by name and tag.
 *
 * <p>HTTP, JVM, LLM call and vector-store metrics come from actuator and Spring AI's own
 * observability and need no code here. These three do not exist unless someone increments them, and
 * a dashboard panel that silently reads zero forever is worse than no panel.
 */
class BusinessMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void attach() { Metrics.addRegistry(registry); }

    @AfterEach
    void detach() { Metrics.removeRegistry(registry); registry.close(); }

    /** One counter at the audit chokepoint stands in for every business event that passes it. */
    @Test
    void everyAuditedBusinessEventIsCounted() {
        AuditEventRepository events = mock(AuditEventRepository.class);
        when(events.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.<AuditEvent>empty());
        AuditService audit = new AuditService(events, new ObjectMapper());

        audit.record("REMEDIATION_PLAN", UUID.randomUUID(), "PLAN_CREATED", "analyst", Map.of("k", "v"));
        audit.record("HITL_REQUEST", UUID.randomUUID(), "APPROVED", "reviewer", Map.of("k", "v"));

        assertThat(registry.get("mcp.audit.events").tag("event", "PLAN_CREATED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("mcp.audit.events").tag("event", "APPROVED")
                .tag("aggregate", "HITL_REQUEST").counter().count()).isEqualTo(1.0);
    }

    /** A block and a pass must be distinguishable, or the guardrail panel says nothing. */
    @Test
    void guardrailVerdictsAreCountedByOutcome() {
        GuardrailService guardrails = new GuardrailService();

        guardrails.scanScript("systemctl restart pos-agent");
        guardrails.scanScript("cat /etc/shadow");
        guardrails.evaluate("not-an-allowlisted-action", "store-0042-pos-01", (SopEvidence) null, 0);

        assertThat(registry.get("mcp.guardrail.scans").tag("lane", "script")
                .tag("verdict", "PASS").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("mcp.guardrail.scans").tag("lane", "script")
                .tag("verdict", "BLOCK").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("mcp.guardrail.scans").tag("lane", "plan")
                .tag("verdict", "BLOCK").counter().count()).isEqualTo(1.0);
    }
}
