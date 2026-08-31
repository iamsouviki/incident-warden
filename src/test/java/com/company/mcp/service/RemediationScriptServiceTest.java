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
 *
 * The other half is the platform: the same approved procedure has to produce PowerShell
 * for a Windows till and bash for a Linux server, decided by the machine and not by
 * whoever authored the action key.
 */
class RemediationScriptServiceTest {

    private final RemediationToolRegistry registry = new RemediationToolRegistry(new ObjectMapper(), new GuardrailService(), null);

    /** What the host itself reported, which is the rung that outranks the action key. */
    private IncidentTarget.Platform host(String name) {
        return new IncidentTarget.Platform(name, "HOST_REPORTED");
    }

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
                incident(), evidence("RESTART_SERVICE:tomcat:linux"), registry.parse("RESTART_SERVICE:tomcat:linux"),
                host("linux"));

        assertEquals("SOP_TEMPLATE", script.source());
        assertEquals("bash", script.language());
        assertEquals("PASS", script.scanLevel());
        assertTrue(script.script().contains("systemctl restart 'tomcat'"));
        // Every template verifies its own effect; without the check a dry run proves nothing.
        assertTrue(script.script().contains("systemctl is-active 'tomcat'"));
        assertTrue(script.usable());
    }

    /**
     * The whole point of resolving the platform from the host: the action key's OS segment
     * is the author's guess about machines they never saw, and the machine wins. Asserted in
     * both directions, because a one-way test passes just as well against code that always
     * writes PowerShell.
     */
    @Test
    void theHostPlatformOverridesTheActionKeyInBothDirections() {
        RemediationScriptService.GeneratedScript onWindows = service(100).generate(
                incident(), evidence("RESTART_SERVICE:tomcat:linux"), registry.parse("RESTART_SERVICE:tomcat:linux"),
                host("windows"));

        assertEquals("powershell", onWindows.language());
        assertTrue(onWindows.script().contains("Restart-Service -Name 'tomcat'"));
        assertFalse(onWindows.script().contains("systemctl"));

        RemediationScriptService.GeneratedScript onLinux = service(100).generate(
                incident(), evidence("RESTART_SERVICE:W3SVC:windows"), registry.parse("RESTART_SERVICE:W3SVC:windows"),
                host("linux"));

        assertEquals("bash", onLinux.language());
        assertTrue(onLinux.script().contains("systemctl restart 'W3SVC'"));
        assertFalse(onLinux.script().contains("Restart-Service"));
    }

    /**
     * linux and darwin are both bash and share no service manager, which is why the
     * platform and the language are two separate things. A template that only knows
     * systemctl turns the developer's own laptop into a command-not-found.
     */
    @Test
    void macOsGetsLaunchctlNotSystemctl() {
        RemediationScriptService.GeneratedScript script = service(100).generate(
                incident(), evidence("RESTART_SERVICE:tomcat:linux"), registry.parse("RESTART_SERVICE:tomcat:linux"),
                host("darwin"));

        assertEquals("bash", script.language());
        assertEquals("SOP_TEMPLATE", script.source());
        assertTrue(script.script().contains("launchctl kickstart -k 'system/tomcat'"));
        assertFalse(script.script().contains("systemctl"));
    }

    /** Read-only checks are templated for Windows too, rather than being handed to the model. */
    @Test
    void checkUrlIsTemplatedOnWindows() {
        RemediationScriptService.GeneratedScript script = service(100).generate(
                incident(), evidence("CHECK_URL:http://localhost:8080/health:200"),
                registry.parse("CHECK_URL:http://localhost:8080/health:200"), host("windows"));

        assertEquals("SOP_TEMPLATE", script.source());
        assertEquals("powershell", script.language());
        assertTrue(script.script().contains("Invoke-WebRequest -Uri 'http://localhost:8080/health'"));
    }

    /**
     * A cache tier with no known command must not have one invented for it. With no model
     * available the honest outcome is "no script", not a plausible-looking guess.
     */
    @Test
    void anUnknownCacheTierIsNotInvented() {
        RemediationScriptService.GeneratedScript script = service(100).generate(
                incident(), evidence("CLEAR_CACHE:memcached:cache-01:11211"),
                registry.parse("CLEAR_CACHE:memcached:cache-01:11211"), host("linux"));

        assertEquals("NONE", script.source());
        assertEquals("SCRIPT_GENERATION_UNAVAILABLE", script.reason());
        assertFalse(script.usable());
    }

    /** redis-cli is not on a Windows host, so the same key falls through there. */
    @Test
    void redisIsTemplatedOnLinuxAndNotOnWindows() {
        assertEquals("SOP_TEMPLATE", service(100).generate(incident(), evidence("CLEAR_CACHE:redis:localhost:6379"),
                registry.parse("CLEAR_CACHE:redis:localhost:6379"), host("linux")).source());
        assertEquals("NONE", service(100).generate(incident(), evidence("CLEAR_CACHE:redis:localhost:6379"),
                registry.parse("CLEAR_CACHE:redis:localhost:6379"), host("windows")).source());
    }

    @Test
    void aScriptOverTheLineLimitIsBlocked() {
        RemediationScriptService.GeneratedScript script = service(3).generate(
                incident(), evidence("RESTART_SERVICE:tomcat:linux"), registry.parse("RESTART_SERVICE:tomcat:linux"),
                host("linux"));

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
