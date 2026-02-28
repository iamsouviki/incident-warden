package com.company.mcp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Carries everything ScriptGeneratorService needs to produce a
 * SOP-scoped remediation script.
 *
 * <p>Every field is derived from the SOP procedure record and the
 * specific {@code action_plan_json} step being executed — nothing
 * comes from free-form user input.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SopScriptRequest {

    // ── The exact SOP step being implemented ─────────────────────────────────

    /**
     * The literal REMOTE_EXEC description from the SOP action plan.
     * Example: "Tomcat is returning 503 — stop it gracefully, wait 5 seconds, then start it again."
     */
    private String sopStepDescription;

    /**
     * SOP category: APPLICATION | PERFORMANCE | INFRASTRUCTURE |
     *               DATABASE | DEPLOYMENT | SCHEDULED_JOB | NETWORK
     * Used by {@link com.company.mcp.service.ScriptGuardrailValidator}
     * to select the correct command allowlist.
     */
    private String sopCategory;

    /**
     * Human-readable SOP title for log messages and audit events.
     */
    private String sopTitle;

    /**
     * SOP ID — included in the generated script as a comment header
     * so every remote execution is traceable back to a SOP record.
     */
    private String sopId;

    // ── Target execution context ──────────────────────────────────────────────

    /** Target host — e.g. {@code app-server-01}. */
    private String targetHost;

    /**
     * Target OS: {@code "linux"} or {@code "windows"}.
     * Determines shebang line, command syntax, and allowlist.
     */
    private String os;

    // ── Scope constraints passed to the LLM ──────────────────────────────────

    /**
     * Explicit list of commands the script is ALLOWED to use.
     * When null, the validator uses the default allowlist for the SOP category.
     * Example: {@code ["systemctl", "/opt/tomcat/bin/shutdown.sh", "/opt/tomcat/bin/startup.sh"]}
     */
    private List<String> allowedCommands;

    /**
     * Optional extra context for the LLM (e.g. service name, port, config file).
     * Sourced from the SOP metadata — never from the live incident alert text.
     */
    private String additionalContext;

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns true when this is a Windows execution target. */
    public boolean isWindows() {
        return "windows".equalsIgnoreCase(os);
    }

    /** Returns the shell name for log messages: {@code "Bash"} or {@code "PowerShell"}. */
    public String shellName() {
        return isWindows() ? "PowerShell" : "Bash";
    }

    /** Safe display string for logging — does not include credentials. */
    @Override
    public String toString() {
        return "SopScriptRequest{sopId='" + sopId + "', host='" + targetHost
                + "', os='" + os + "', category='" + sopCategory + "'}";
    }
}
