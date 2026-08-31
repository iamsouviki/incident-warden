package com.company.mcp.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The explanation is what a non-shell-reading approver actually reads before clicking run, so
 * the two things worth pinning are that it never understates a script and that it says out loud
 * when nothing authorised one.
 */
class ScriptExplainerTest {

    @Test
    void namesTheEffectAndTheStepsOfAGeneratedRestart() {
        ScriptExplainer.Explanation e = ScriptExplainer.explain("RESTART_SERVICE:tomcat:linux",
                "Restart an allow-listed service.",
                """
                #!/bin/bash
                set -euo pipefail
                systemctl is-active tomcat
                systemctl restart tomcat
                sleep 5
                systemctl is-active tomcat
                journalctl -u tomcat -n 50
                """,
                "bash", "store-0042-pos-01");

        assertTrue(e.what().contains("tomcat"));
        assertTrue(e.what().contains("store-0042-pos-01"));
        // The repeated status check is stated once; the shebang and `set -euo` are not steps.
        assertEquals(4, e.how().size(), () -> "steps: " + e.how());
        assertTrue(e.how().get(0).contains("running"));
        assertTrue(e.how().get(1).contains("Restarts"));
        assertFalse(String.join(" ", e.how()).contains("set -euo"));
    }

    /**
     * The load-bearing property: a line the phrase table does not recognise is still reported.
     * An explanation that silently drops a command is worse than none, because the reviewer
     * stops reading the script itself.
     */
    @Test
    void anUnrecognisedLineIsStillReported() {
        ScriptExplainer.Explanation e = ScriptExplainer.explain("RESTART_SERVICE:tomcat:linux", "",
                "systemctl restart tomcat\nchown -R nobody:nobody /var/lib/tomcat", "bash", "host-1");

        assertEquals(2, e.how().size(), () -> "steps: " + e.how());
        assertTrue(e.how().get(1).startsWith("Runs: chown"));
    }

    /** An ungrounded script must be labelled as one — that is the whole risk of the plan. */
    @Test
    void anUngroundedScriptSaysNoProcedureAuthorisedIt() {
        ScriptExplainer.Explanation e =
                ScriptExplainer.explain("", "", "rm -rf /var/tmp/cache", "bash", "host-1");

        assertTrue(e.what().contains("no approved procedure"));
        assertTrue(e.how().get(0).contains("Deletes files"));
    }

    @Test
    void anEmptyScriptExplainsThatThereIsNothingToRead() {
        ScriptExplainer.Explanation e = ScriptExplainer.explain("CHECK_URL:http://h/health:200",
                "Probe a URL.", "", "bash", "host-1");

        assertTrue(e.what().contains("no script"));
        assertTrue(e.how().isEmpty());
        assertEquals(0, e.lines());
    }

    /**
     * The load-bearing regression check: the phrase table is only useful if it covers the
     * commands this platform's own generator emits. Every RESTART_SERVICE template that
     * RemediationScriptService can produce must come out fully in words — a "Runs: …" line here
     * means the generator grew a command the explanation cannot read.
     */
    @Test
    void everyRestartTemplateTheGeneratorEmitsIsFullyExplained() {
        String linux = """
                #!/usr/bin/env bash
                # SOP-approved remediation: restart the 'tomcat' service.
                set -euo pipefail
                systemctl is-active 'tomcat' || true
                systemctl restart 'tomcat'
                sleep 5
                systemctl is-active 'tomcat'
                """;
        String darwin = """
                #!/usr/bin/env bash
                # SOP-approved remediation: restart the 'tomcat' service (launchd).
                set -euo pipefail
                launchctl print 'system/tomcat' >/dev/null 2>&1 || true
                launchctl kickstart -k 'system/tomcat'
                sleep 5
                launchctl print 'system/tomcat' >/dev/null
                """;
        String windows = """
                # SOP-approved remediation: restart the 'tomcat' service.
                $ErrorActionPreference = 'Stop'
                Write-Output "Before: $((Get-Service -Name 'tomcat').Status)"
                Restart-Service -Name 'tomcat'
                Start-Sleep -Seconds 5
                $after = (Get-Service -Name 'tomcat').Status
                Write-Output "After: $after"
                if ($after -ne 'Running') { exit 1 }
                """;

        for (String script : new String[]{linux, darwin, windows}) {
            ScriptExplainer.Explanation e = ScriptExplainer.explain("RESTART_SERVICE:tomcat:linux", "",
                    script, script == windows ? "powershell" : "bash", "store-0042-app-01");
            assertFalse(String.join(" | ", e.how()).contains("Runs: "),
                    () -> "unexplained line in:\n" + script + "\nsteps: " + e.how());
            assertTrue(e.how().stream().anyMatch(s -> s.startsWith("Restarts the service")),
                    () -> "no restart step for:\n" + script);
        }
    }

    /** The URL probe template wraps curl in an assignment, which is easy to miss. */
    @Test
    void theCheckUrlTemplateIsFullyExplained() {
        ScriptExplainer.Explanation e = ScriptExplainer.explain("CHECK_URL:http://host:8080/health:200", "",
                """
                #!/usr/bin/env bash
                set -euo pipefail
                code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 'http://host:8080/health')
                echo "GET http://host:8080/health returned $code (expected 200)"
                test "$code" = "200"
                """, "bash", "host");

        assertFalse(String.join(" | ", e.how()).contains("Runs: "), () -> "steps: " + e.how());
        assertTrue(e.what().contains("Read-only"));
    }
}
