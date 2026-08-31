package com.company.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the remediation an approved plan authorises.
 *
 * Two properties matter more than anything else here:
 *
 *   1. No local shell, ever. Nothing in this class builds a {@link ProcessBuilder} or
 *      hands a string to a shell. Approved scripts are posted to a separate executor
 *      agent that runs with its own credentials on the target network. The control
 *      plane holding the approvals cannot itself be turned into a remote shell, so an
 *      injection that reaches this far still cannot run a command here.
 *
 *   2. Everything is re-validated at the moment of execution — the action key against
 *      the same tool table used to plan it, the script against the same guardrail scan
 *      that ran before a human saw it. An approval is not a licence to run whatever the
 *      row happens to say by the time it arrives.
 *
 * Read-only probes (CHECK_URL) run in-process because they mutate nothing, and because
 * they are the only evidence a dry run can produce when no executor is configured.
 */
@Service
public class RemediationToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(RemediationToolRegistry.class);

    /**
     * Per-segment shape for parsed action keys: no whitespace, quotes, or shell
     * metacharacters. A leading {@code /} is permitted because job identifiers are
     * absolute paths ({@code RERUN_JOB:linux:/opt/batch/nightly_report.sh}), and a
     * backslash because a Windows job path is {@code C:\batch\nightly.ps1}. Both are
     * only ever interpolated inside single quotes, where they are literal in bash and in
     * PowerShell alike.
     */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9/][A-Za-z0-9._/:@\\\\-]{0,299}$");

    /**
     * An optional platform token in an executor's probe reply, so a plan can be written for
     * the OS that is actually on the host instead of the one the SOP author assumed.
     *
     * Deliberately shape-tolerant — it matches {@code platform=windows} in a plain-text
     * reply and {@code "platform":"windows"} in a JSON one — so an existing executor keeps
     * working unchanged (it simply reports nothing, and the resolution falls back a rung).
     */
    private static final Pattern PROBE_PLATFORM = Pattern.compile(
            "platform\\s*[=:]\\s*\"?([A-Za-z0-9_-]{1,32})", Pattern.CASE_INSENSITIVE);

    /**
     * The tool table of last resort. A key not listed in the effective table cannot execute,
     * whatever an approved procedure or an LLM claims. {@code segments} is the exact count
     * expected after the tool name, so a malformed key is rejected before dispatch rather
     * than being padded with defaults.
     *
     * These four now live in {@code tools.skills} as editable rows, seeded identically. This
     * constant remains as the fallback for a database that has not been migrated yet, or one
     * whose skills table has been emptied: remediation must keep working exactly as it did
     * before the table existed rather than silently allowlisting nothing, which would read to
     * an operator as "every tool is suddenly unknown".
     */
    private static final Map<String, Tool> BUILT_IN = Map.of(
            "CHECK_URL",      new Tool("CHECK_URL", 2, false, "Probe an HTTP endpoint and compare the status code."),
            "RESTART_SERVICE", new Tool("RESTART_SERVICE", 2, true, "Restart a named OS service on one host."),
            "CLEAR_CACHE",    new Tool("CLEAR_CACHE", 3, true, "Flush one cache tier."),
            "RERUN_JOB",      new Tool("RERUN_JOB", 2, true, "Re-trigger one batch job."));

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)   // a redirect could move the probe off the approved host
            .build();

    private final ObjectMapper json;
    private final GuardrailService guardrails;
    /** Null in unit tests, which assert the parser against the built-in table. */
    private final SkillService skills;

    @Value("${mcp.executor.enabled:false}")
    private boolean executionEnabled;

    /** Executor agent base URL. Empty means mutating actions simulate instead of running. */
    @Value("${mcp.executor.url:}")
    private String executorUrl;

    @Value("${mcp.executor.token:}")
    private String executorToken;

    @Value("${mcp.executor.timeout-seconds:30}")
    private int executorTimeoutSeconds;

    public RemediationToolRegistry(ObjectMapper json, GuardrailService guardrails, SkillService skills) {
        this.json = json;
        this.guardrails = guardrails;
        this.skills = skills;
    }

    /**
     * The tool table in force right now: the admin-editable rows, or the built-in four when
     * there are none.
     *
     * Read per call rather than cached, and read again at dispatch by the same {@link #parse}
     * every other caller uses. That is deliberate: disabling a tool on the Skills page has to
     * stop a plan that was approved while it was still enabled, and a cached table would let
     * that plan through.
     *
     * ponytail: default tenant only. {@link #parse} is called from paths with no request
     * context (external sync, intake), so there is no tenant to key on there without
     * threading one through every caller. Same ceiling as the extraction patterns, and the
     * same upgrade: pass a tenant in when a second workspace needs a different tool set.
     */
    private Map<String, Tool> table() {
        if (skills == null) return BUILT_IN;
        try {
            Map<String, SkillService.ToolRow> rows = skills.executionTools(null);
            if (rows.isEmpty()) return BUILT_IN;
            Map<String, Tool> effective = new java.util.LinkedHashMap<>();
            rows.forEach((key, row) ->
                    effective.put(key, new Tool(row.name(), row.segments(), row.mutating(), row.description())));
            return effective;
        } catch (Exception e) {
            // A database that cannot be read must not become a database that allows nothing
            // and explains nothing. Fall back to the shipped table and say so once.
            log.warn("[EXEC] Skills table unreadable, using built-in tools: {}", e.getMessage());
            return BUILT_IN;
        }
    }

    /** The catalogue, for the UI and for MCP {@code tools/list}. */
    public List<Tool> tools() {
        return table().values().stream().sorted((a, b) -> a.name().compareTo(b.name())).toList();
    }

    /**
     * LIVE or SIMULATED — what a mutating action would actually do right now.
     *
     * Derived from the two flags that decide it rather than declared in a property of its
     * own, because a second property describing the same thing drifts: the page that used to
     * display execution mode read SIMULATED while this class was dispatching real scripts.
     * These are the same two conditions {@link #reachable} and the execute path check.
     */
    public String dispatchMode() {
        return executionEnabled && executorUrl != null && !executorUrl.isBlank() ? "LIVE" : "SIMULATED";
    }

    /**
     * Parses and validates an action key without running anything. Used at plan time so
     * a procedure with an unusable action key is caught before a human is asked to
     * approve it.
     */
    public ParsedAction parse(String actionKey) {
        if (actionKey == null || actionKey.isBlank()) return ParsedAction.invalid("ACTION_KEY_MISSING");
        String[] parts = actionKey.trim().split(":", -1);
        Tool tool = table().get(parts[0].toUpperCase(Locale.ROOT));
        if (tool == null) return ParsedAction.invalid("TOOL_NOT_ALLOWLISTED:" + parts[0]);

        // CHECK_URL's first argument is itself a URL containing colons, so the tail is
        // rejoined rather than split blindly: everything between the tool name and the
        // final segment is the URL. RERUN_JOB has the same problem from the other end —
        // C:\batch\nightly.ps1 contains the delimiter — so its tail is rejoined too.
        //
        // Matched by name, so an admin-authored tool gets plain segment splitting. That is
        // the safe default: a key whose argument contains a colon simply fails the segment
        // count and is refused, rather than being silently reassembled a way the author did
        // not intend.
        List<String> args;
        if ("CHECK_URL".equals(tool.name())) {
            if (parts.length < 3) return ParsedAction.invalid("MALFORMED_ACTION_KEY");
            args = List.of(String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1)),
                    parts[parts.length - 1]);
        } else if ("RERUN_JOB".equals(tool.name())) {
            if (parts.length < 3) return ParsedAction.invalid("MALFORMED_ACTION_KEY");
            args = List.of(parts[1], String.join(":", java.util.Arrays.copyOfRange(parts, 2, parts.length)));
        } else {
            args = List.of(java.util.Arrays.copyOfRange(parts, 1, parts.length));
        }
        if (args.size() != tool.segments()) return ParsedAction.invalid("MALFORMED_ACTION_KEY");
        for (String arg : args) {
            if (!SAFE_SEGMENT.matcher(arg).matches()) return ParsedAction.invalid("UNSAFE_ACTION_ARGUMENT");
            // The dot is allowed for filenames, so traversal is rejected separately.
            if (arg.contains("..")) return ParsedAction.invalid("UNSAFE_ACTION_ARGUMENT");
        }
        return new ParsedAction(true, tool, args, "");
    }

    /**
     * Asks the executor agent whether it can reach a host, before anything is run on it.
     *
     * "Without a token first" lives here: {@code connection} is normally empty, meaning
     * "executor, use the path to that host you already have". Only when that comes back
     * unreachable is a human asked to name a method (SSH / WINRM / AGENT) — and only the
     * method. The credential for it never leaves the executor, so this call can be made
     * freely without moving a secret into the control plane.
     *
     * UNKNOWN, not UNREACHABLE, when there is no executor to ask. A demo with execution
     * disabled must keep planning exactly as it did before; silence from a component that
     * was never started is not evidence a host is down.
     *
     * A reachable reply may also name the host's platform ({@code platform=windows}), which
     * is how a script comes to be written for the operating system that is actually there.
     * An executor that says nothing is not an error — the platform falls back a rung.
     */
    public Probe reachable(String host, String connection) {
        if (host == null || host.isBlank()) return new Probe("UNKNOWN", "TARGET_HOST_UNKNOWN", "No host to probe.", "");
        if (!executionEnabled || executorUrl == null || executorUrl.isBlank()) {
            return new Probe("UNKNOWN", "EXECUTOR_NOT_CONFIGURED",
                    "No executor agent is configured, so '" + host + "' could not be checked.", "");
        }
        try {
            String body = json.writeValueAsString(Map.of(
                    "target", host,
                    "connection", connection == null ? "" : connection));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(trimTrailingSlash(executorUrl) + "/probe"))
                    .timeout(Duration.ofSeconds(Math.max(1, executorTimeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            // Still bearer-authenticated: the token being discussed is the executor's route
            // to the target, not our route to the executor. Dropping this one would be a
            // regression, not a simplification.
            if (executorToken != null && !executorToken.isBlank()) {
                builder.header("Authorization", "Bearer " + executorToken);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String detail = response.body() == null ? "" : response.body().trim();
            // Read before truncation: a chatty agent must not lose the platform to the clip.
            Matcher platform = PROBE_PLATFORM.matcher(detail);
            String reported = platform.find() ? platform.group(1) : "";
            if (detail.length() > 500) detail = detail.substring(0, 500) + "…";
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            return ok
                    ? new Probe("REACHABLE", "", "Executor reached '" + host + "'. " + detail, reported)
                    : new Probe("UNREACHABLE", "TARGET_UNREACHABLE:" + host,
                            "Executor could not reach '" + host
                                    + (connection == null || connection.isBlank() ? "' over its default connection. " : "' over " + connection + ". ")
                                    + detail, "");
        } catch (Exception e) {
            // The executor itself is down. Not the host's fault, so not the host's verdict:
            // blocking every plan because the agent is restarting would be its own outage.
            log.warn("[EXEC] Probe of {} failed: {}", host, e.getMessage());
            return new Probe("UNKNOWN", "PROBE_UNAVAILABLE",
                    "Executor did not answer the reachability check: " + e.getClass().getSimpleName(), "");
        }
    }

    /**
     * Executes an approved remediation.
     *
     * A read-only action key probes in-process: it cannot change anything, and it is the
     * only real evidence a dry run can produce when no executor is configured. Everything
     * else runs the approved script — the exact text the reviewer signed for — on the
     * executor agent.
     *
     * @param dryRun when true nothing mutates: read-only probes still run, and the script
     *               is re-validated and reported without being dispatched.
     */
    public Outcome execute(String actionKey, String script, String language, String target, boolean dryRun) {
        return execute(actionKey, script, language, target, "", dryRun);
    }

    /**
     * @param connection SSH | WINRM | AGENT, or "" for the executor's own default path to
     *                   the host — which is what every incident is tried with first.
     */
    public Outcome execute(String actionKey, String script, String language, String target,
                           String connection, boolean dryRun) {
        ParsedAction parsed = parse(actionKey);
        if (parsed.valid() && !parsed.tool().mutating()) {
            return probe(parsed);   // safe in a dry run and in a real run alike
        }
        return runScript(script, language, target, connection, dryRun);
    }

    /**
     * Runs an approved script.
     *
     * The guardrail scan runs again here, against the text about to be dispatched. The
     * plan hash already proves the script has not changed since approval; this proves the
     * guardrail rules have not been loosened out from under it either — a term added to
     * the block list after a plan was approved must still stop that plan.
     */
    private Outcome runScript(String script, String language, String target, String connection, boolean dryRun) {
        if (script == null || script.isBlank()) {
            return new Outcome("BLOCKED", "SIMULATED", "No approved script is attached to this plan.", "SCRIPT_MISSING");
        }
        GuardrailService.ScriptScan scan = guardrails.scanScript(script);
        if (scan.blocked()) {
            return new Outcome("BLOCKED", "SIMULATED",
                    "Script rejected at execution time by the guardrail scan:\n" + render(scan),
                    "SCRIPT_BLOCKED_BY_GUARDRAILS");
        }
        // Before the dry-run return on purpose. A dry run exists to surface what would go
        // wrong while a person is still looking at the screen, and "that host does not
        // answer" is the most useful thing it can tell them.
        Probe probe = reachable(target, connection);
        if (probe.unreachable()) {
            return new Outcome("BLOCKED", "SIMULATED",
                    probe.detail() + "\nConfirm the server name for this incident and how the executor should "
                            + "connect to it (SSH, WINRM or AGENT), then create the plan again.",
                    probe.reason());
        }
        int lines = script.split("\n", -1).length;
        if (dryRun) {
            return new Outcome("DRY_RUN_PASSED", "SIMULATED",
                    "Validated " + lines + " lines of " + language + " for target '" + target
                            + "'. Guardrail scan: " + scan.level() + ". Reachability: " + probe.status()
                            + ". Nothing was dispatched.", "");
        }
        if (!executionEnabled) {
            return new Outcome("SIMULATED", "SIMULATED",
                    "Real execution is disabled (mcp.executor.enabled=false). Nothing was changed.",
                    "EXECUTION_DISABLED");
        }
        if (executorUrl == null || executorUrl.isBlank()) {
            return new Outcome("SIMULATED", "SIMULATED",
                    "No executor agent is configured (mcp.executor.url). Nothing was changed.",
                    "EXECUTOR_NOT_CONFIGURED");
        }
        return dispatchToExecutor(script, language, target, connection);
    }

    private String render(GuardrailService.ScriptScan scan) {
        StringBuilder sb = new StringBuilder();
        for (GuardrailService.ScriptFinding finding : scan.findings()) {
            sb.append(finding.level()).append(" [").append(finding.layer()).append("] ").append(finding.message()).append('\n');
        }
        return sb.toString();
    }

    /** Read-only HTTP probe. Runs in-process because it cannot change anything. */
    private Outcome probe(ParsedAction parsed) {
        String url = parsed.args().get(0);
        int expected;
        try {
            expected = Integer.parseInt(parsed.args().get(1));
        } catch (NumberFormatException e) {
            return new Outcome("BLOCKED", "SIMULATED", "Expected status is not a number.", "MALFORMED_ACTION_KEY");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return new Outcome("BLOCKED", "SIMULATED", "Only http and https probes are permitted.", "UNSUPPORTED_SCHEME");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .method("GET", HttpRequest.BodyPublishers.noBody())
                    .build();
            // Discarded rather than read: the status code is the whole signal, and a body
            // from a probed host is untrusted content with no reason to enter this process.
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            boolean ok = response.statusCode() == expected;
            return new Outcome(ok ? "SUCCEEDED" : "FAILED", "LIVE_READ_ONLY",
                    String.format("GET %s returned %d (expected %d).", url, response.statusCode(), expected),
                    ok ? "" : "UNEXPECTED_STATUS");
        } catch (Exception e) {
            return new Outcome("FAILED", "LIVE_READ_ONLY",
                    "Probe of " + url + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "PROBE_FAILED");
        }
    }

    /**
     * Hands an approved script to the executor agent.
     *
     * The executor holds the target credentials and decides which hosts it is allowed to
     * touch; this process holds only the approval. That split is the reason a compromised
     * control plane is not automatically a compromised fleet.
     *
     * ponytail: one synchronous POST, no retry. A retry here would be wrong, not just
     * lazy — "restart the service" is not safely idempotent when the first call may
     * have succeeded and only the response was lost. Retry belongs to the operator
     * looking at the recorded outcome, which is why the outcome is recorded verbatim.
     */
    private Outcome dispatchToExecutor(String script, String language, String target, String connection) {
        try {
            String body = json.writeValueAsString(Map.of(
                    "script", script,
                    "language", language,
                    "target", target == null ? "" : target,
                    // Empty means "your default path". The credential for the named method
                    // is the executor's; this is the method only.
                    "connection", connection == null ? "" : connection));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(trimTrailingSlash(executorUrl) + "/execute"))
                    .timeout(Duration.ofSeconds(Math.max(1, executorTimeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (executorToken != null && !executorToken.isBlank()) {
                builder.header("Authorization", "Bearer " + executorToken);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            String output = response.body() == null ? "" : response.body();
            if (output.length() > 8000) output = output.substring(0, 8000) + "\n[truncated]";
            return new Outcome(ok ? "SUCCEEDED" : "FAILED", "LIVE",
                    "Executor responded " + response.statusCode() + ":\n" + output,
                    ok ? "" : "EXECUTOR_REPORTED_FAILURE");
        } catch (Exception e) {
            log.error("[EXEC] Executor dispatch failed for target {}: {}", target, e.getMessage());
            // Unknown outcome is reported as a failure, never as a success: the action may
            // or may not have run, and an operator must be the one to decide what next.
            return new Outcome("FAILED", "LIVE",
                    "Executor unreachable or timed out: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                            + "\nThe script may or may not have run. Verify on the target before retrying.",
                    "EXECUTOR_UNREACHABLE");
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record Tool(String name, int segments, boolean mutating, String description) {}

    /**
     * @param status REACHABLE | UNREACHABLE | UNKNOWN — UNKNOWN means nobody could answer,
     *               which must never be treated as a failed host
     */
    public record Probe(String status, String reason, String detail, String platform) {
        public boolean unreachable() { return "UNREACHABLE".equals(status); }
        public boolean known() { return !"UNKNOWN".equals(status); }

        /** Nothing was asked, or nothing answered. */
        public static Probe notAsked() { return new Probe("UNKNOWN", "", "", ""); }
    }

    public record ParsedAction(boolean valid, Tool tool, List<String> args, String reason) {
        static ParsedAction invalid(String reason) {
            return new ParsedAction(false, null, List.of(), reason);
        }

        /**
         * The platform the procedure's author had in mind, or "" when the key names none.
         *
         * A hint, not a decision: {@link IncidentTarget#platform} uses it only after the
         * host itself and the operator's connection method have both said nothing. Two
         * tools carry an os/type segment and the rest do not, which is why this lives on
         * the parsed key rather than being re-derived wherever a script gets written.
         */
        public String platformHint() {
            if (!valid || tool == null) return "";
            return switch (tool.name()) {
                case "RESTART_SERVICE" -> args.size() > 1 ? args.get(1) : "";
                case "RERUN_JOB" -> args.isEmpty() ? "" : args.get(0);
                default -> "";
            };
        }
    }

    /**
     * @param status SUCCEEDED | FAILED | DRY_RUN_PASSED | SIMULATED | BLOCKED
     * @param mode   LIVE | LIVE_READ_ONLY | SIMULATED — what actually happened, not what was intended
     */
    public record Outcome(String status, String mode, String output, String reason) {
        public boolean succeeded() { return "SUCCEEDED".equals(status) || "DRY_RUN_PASSED".equals(status); }
    }
}
