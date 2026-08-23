package com.company.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Action-key parsing is the last gate before something runs against a real system, so it
 * is tested for what it rejects rather than only for what it accepts.
 */
class RemediationToolRegistryTest {

    private RemediationToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RemediationToolRegistry(new ObjectMapper(), new GuardrailService());
    }

    @Test
    void parsesTheDocumentedActionKeyFormats() {
        assertTrue(registry.parse("RESTART_SERVICE:tomcat:linux").valid());
        assertTrue(registry.parse("CLEAR_CACHE:redis:cache-server-01:6379").valid());
        assertTrue(registry.parse("RERUN_JOB:linux:/opt/batch/nightly_report.sh").valid());
    }

    @Test
    void keepsAUrlIntactDespiteItsColons() {
        RemediationToolRegistry.ParsedAction parsed = registry.parse("CHECK_URL:http://host:8080/health:200");

        assertTrue(parsed.valid(), () -> "rejected: " + parsed.reason());
        assertEquals("http://host:8080/health", parsed.args().get(0));
        assertEquals("200", parsed.args().get(1));
    }

    /** The same problem from the other end: a Windows path carries the delimiter too. */
    @Test
    void keepsAWindowsJobPathIntactDespiteItsColon() {
        RemediationToolRegistry.ParsedAction parsed = registry.parse("RERUN_JOB:windows:C:\\batch\\nightly.ps1");

        assertTrue(parsed.valid(), () -> "rejected: " + parsed.reason());
        assertEquals("windows", parsed.args().get(0));
        assertEquals("C:\\batch\\nightly.ps1", parsed.args().get(1));
    }

    /**
     * The action key's OS segment is a hint and nothing more — the host's own answer outranks
     * it (see IncidentTargetTest). Tools that name no OS must say so rather than default,
     * otherwise a CHECK_URL key would silently vote for Linux on a Windows till.
     */
    @Test
    void theOsSegmentIsOfferedAsAHintOnlyWhereTheKeyHasOne() {
        assertEquals("windows", registry.parse("RESTART_SERVICE:spooler:windows").platformHint());
        assertEquals("linux", registry.parse("RERUN_JOB:linux:/opt/batch/nightly.sh").platformHint());
        assertEquals("", registry.parse("CHECK_URL:http://host:8080/health:200").platformHint());
        assertEquals("", registry.parse("CLEAR_CACHE:redis:cache-01:6379").platformHint());
        assertEquals("", registry.parse("DELETE_DATABASE:customers").platformHint());
    }

    @Test
    void rejectsAToolThatIsNotOnTheAllowList() {
        RemediationToolRegistry.ParsedAction parsed = registry.parse("DELETE_DATABASE:customers");

        assertFalse(parsed.valid());
        assertTrue(parsed.reason().startsWith("TOOL_NOT_ALLOWLISTED"));
    }

    @Test
    void rejectsCommandInjectionSmuggledIntoAnArgument() {
        // Each of these is a real shape an attacker would try if the arguments ever
        // reached a shell. None can parse, so none can be dispatched.
        assertFalse(registry.parse("RESTART_SERVICE:tomcat;rm -rf /:linux").valid());
        assertFalse(registry.parse("RESTART_SERVICE:tomcat|whoami:linux").valid());
        assertFalse(registry.parse("RESTART_SERVICE:$(whoami):linux").valid());
        assertFalse(registry.parse("RESTART_SERVICE:tomcat `id`:linux").valid());
        assertFalse(registry.parse("RERUN_JOB:linux:/opt/x.sh && cat /etc/shadow").valid());
    }

    @Test
    void rejectsTheWrongNumberOfArguments() {
        assertFalse(registry.parse("RESTART_SERVICE:tomcat").valid());
        assertFalse(registry.parse("RESTART_SERVICE:tomcat:linux:extra").valid());
        assertFalse(registry.parse("CLEAR_CACHE:redis:localhost").valid());
        assertFalse(registry.parse("").valid());
        assertFalse(registry.parse(null).valid());
    }

    @Test
    void aMutatingScriptDoesNotRunInADryRun() {
        RemediationToolRegistry.Outcome outcome = registry.execute("RESTART_SERVICE:tomcat:linux",
                "systemctl restart tomcat", "bash", "FS-1001", true);

        assertEquals("DRY_RUN_PASSED", outcome.status());
        assertEquals("SIMULATED", outcome.mode());
    }

    /**
     * The guardrail scan runs again at dispatch, not only at plan time. An approval that
     * predates a newly blocked term must still be stopped by it.
     */
    @Test
    void aScriptWithADestructiveCommandIsBlockedRatherThanDispatched() {
        RemediationToolRegistry.Outcome outcome = registry.execute("RESTART_SERVICE:tomcat:linux",
                "systemctl stop tomcat\nrm -rf /var/lib/tomcat", "bash", "FS-1001", false);

        assertEquals("BLOCKED", outcome.status());
        assertEquals("SIMULATED", outcome.mode());
        assertEquals("SCRIPT_BLOCKED_BY_GUARDRAILS", outcome.reason());
    }

    /** A plan with no script attached cannot execute, whatever its approval says. */
    @Test
    void anEmptyScriptIsBlocked() {
        RemediationToolRegistry.Outcome outcome = registry.execute("RESTART_SERVICE:tomcat:linux", "", "bash", "FS-1001", false);

        assertEquals("BLOCKED", outcome.status());
        assertEquals("SCRIPT_MISSING", outcome.reason());
    }

    /**
     * A script with no approved action key behind it (the LLM_KNOWLEDGE path) still runs
     * through the same gates — it is not a second, looser execution route.
     */
    @Test
    void anUngroundedScriptTakesTheSameExecutionPath() {
        RemediationToolRegistry.Outcome outcome = registry.execute("", "echo checking disk\ndf -h", "bash", "FS-2002", true);

        assertEquals("DRY_RUN_PASSED", outcome.status());
        assertEquals("SIMULATED", outcome.mode());
    }

    @Test
    void scriptsSimulateWhenExecutionIsDisabled() {
        // executionEnabled defaults to false because @Value is not applied outside Spring,
        // which is the same fail-closed state a misconfigured deployment lands in.
        RemediationToolRegistry.Outcome outcome = registry.execute("RESTART_SERVICE:tomcat:linux",
                "systemctl restart tomcat", "bash", "FS-1001", false);

        assertEquals("SIMULATED", outcome.mode());
        assertEquals("EXECUTION_DISABLED", outcome.reason());
    }

    @Test
    void onlyHttpProbesArePermitted() {
        RemediationToolRegistry.Outcome outcome = registry.execute("CHECK_URL:file:///etc/passwd:200",
                "curl file:///etc/passwd", "bash", "FS-1001", false);

        assertFalse(outcome.succeeded());
        assertTrue("UNSAFE_ACTION_ARGUMENT".equals(outcome.reason()) || "UNSUPPORTED_SCHEME".equals(outcome.reason()),
                () -> "unexpected reason: " + outcome.reason());
    }

    /**
     * The Autonomy page's mode must agree with what execution actually does. It used to read
     * a separate execution-mode property and displayed SIMULATED while real dispatch was on.
     */
    @Test
    void reportedModeMatchesWhatExecutionActuallyDoes() {
        RemediationToolRegistry.Outcome outcome = registry.execute("RESTART_SERVICE:tomcat:linux",
                "systemctl restart tomcat", "bash", "FS-1001", false);

        assertEquals(outcome.mode(), registry.dispatchMode());
        assertEquals("SIMULATED", registry.dispatchMode());
    }
}
