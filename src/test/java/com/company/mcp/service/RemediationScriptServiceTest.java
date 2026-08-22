package com.company.mcp.service;

import com.company.mcp.model.Incident;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The templated path is tested because it is the one that must work with no model
 * running: a deployment with Ollama down should still be able to remediate an incident
 * that an operator has already written an approved procedure for.
 */
class RemediationScriptServiceTest {

    private final RemediationToolRegistry registry = new RemediationToolRegistry(new ObjectMapper(), new GuardrailService());

    /** A RagService whose chat client is absent, so any model-backed path reports unavailable. */
    private RemediationScriptService service(int maxLines) {
        RagService rag = mock(RagService.class);
        when(rag.getOrBuildChatClient()).thenReturn(null);
        return new RemediationScriptService(rag, new GuardrailService(), maxLines);
    }

    private Incident incident() {
        return Incident.builder().id(UUID.randomUUID()).tenantId("tenant-a")
                .subject("Service unavailable").description("tomcat is not responding")
                .priority("P3").externalId("FS-1001").build();
    }

    private SopEvidence evidence(String actionKey) {
        return new SopEvidence(true, true, List.of(UUID.randomUUID()), "SOP: restart the service", 0.9,
                "APPROVED_TENANT_SOP_MATCH", actionKey);
    }

    @Test
    void restartServiceTemplatesWithoutAModel() {
        RemediationScriptService.GeneratedScript script = service(100).generate(
                incident(), evidence("RESTART_SERVICE:tomcat:linux"), registry.parse("RESTART_SERVICE:tomcat:linux"));

        assertEquals("SOP_TEMPLATE", script.source());
        assertEquals("bash", script.language());
        assertEquals("PASS", script.scanLevel());
        assertTrue(script.script().contains("systemctl restart 'tomcat'"));
        // Every template verifies its own effect; without the check a dry run proves nothing.
        assertTrue(script.script().contains("systemctl is-active 'tomcat'"));
        assertTrue(script.usable());
    }

    @Test
    void theOsSegmentSelectsPowershell() {
        RemediationScriptService.GeneratedScript script = service(100).generate(
                incident(), evidence("RESTART_SERVICE:W3SVC:windows"), registry.parse("RESTART_SERVICE:W3SVC:windows"));

        assertEquals("powershell", script.language());
        assertTrue(script.script().contains("Restart-Service -Name 'W3SVC'"));
    }

    /**
     * A cache tier with no known command must not have one invented for it. With no model
     * available the honest outcome is "no script", not a plausible-looking guess.
     */
    @Test
    void anUnknownCacheTierIsNotInvented() {
        RemediationScriptService.GeneratedScript script = service(100).generate(
                incident(), evidence("CLEAR_CACHE:memcached:cache-01:11211"),
                registry.parse("CLEAR_CACHE:memcached:cache-01:11211"));

        assertEquals("NONE", script.source());
        assertEquals("SCRIPT_GENERATION_UNAVAILABLE", script.reason());
        assertFalse(script.usable());
    }

    @Test
    void aScriptOverTheLineLimitIsBlocked() {
        RemediationScriptService.GeneratedScript script = service(3).generate(
                incident(), evidence("RESTART_SERVICE:tomcat:linux"), registry.parse("RESTART_SERVICE:tomcat:linux"));

        assertEquals("BLOCK", script.scanLevel());
        assertEquals("SCRIPT_TOO_LONG", script.reason());
        assertFalse(script.usable());
    }

    /**
     * The asymmetry that keeps the ungrounded path from being the loose one: a WARN is
     * tolerable on a script an operator curated, and not on one the model invented.
     */
    @Test
    void aWarningIsToleratedOnlyWhenAnOperatorApprovedTheProcedure() {
        RemediationScriptService.GeneratedScript warned = new RemediationScriptService.GeneratedScript(
                "systemctl reboot", "bash", "SOP_GROUNDED", "WARN", List.of("WARN:Service Disruption:reboot"), "");

        assertTrue(warned.usable());
        assertFalse(warned.usableUngrounded());
    }
}
