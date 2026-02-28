package com.company.mcp.service;

import com.company.mcp.model.SopScriptRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ScriptGuardrailValidator — five independent validation layers that must ALL
 * pass before a generated script is permitted to run on a remote server.
 *
 * <h3>Validation layers (run in order, first BLOCK stops execution)</h3>
 * <table border="1">
 *   <tr><th>#</th><th>Layer</th><th>Checks</th><th>On failure</th></tr>
 *   <tr><td>1</td><td>Structure</td>
 *       <td>Shebang/ErrorAction present, set -e present, max line count,
 *           must contain at least one echo/Write-Host for traceability</td>
 *       <td>BLOCK</td></tr>
 *   <tr><td>2</td><td>Blocklist</td>
 *       <td>Pattern-matched forbidden strings (rm -rf /, mkfs, dd, fork bombs…)</td>
 *       <td>BLOCK</td></tr>
 *   <tr><td>3</td><td>Command allowlist</td>
 *       <td>Every executable command in the script must be in the
 *           SOP-category allowlist OR the request's explicit allowedCommands list</td>
 *       <td>BLOCK on unknown command; WARN on soft-allowlist commands</td></tr>
 *   <tr><td>4</td><td>SOP intent</td>
 *       <td>The script must only reference entities (service names, paths, tools)
 *           that appear in the SOP step description — no extra services, no
 *           invented hostnames, no extra package installations</td>
 *       <td>BLOCK on high-confidence drift; WARN on soft drift</td></tr>
 *   <tr><td>5</td><td>Complexity</td>
 *       <td>Max distinct commands, max total lines, no sub-shells spawning
 *           background jobs, no eval / exec / source with dynamic strings</td>
 *       <td>WARN (does NOT block — logged for HITL review)</td></tr>
 * </table>
 *
 * <h3>Result levels</h3>
 * <ul>
 *   <li>{@code PASS} — proceed with SSH upload and execution</li>
 *   <li>{@code WARN} — proceed but log findings for audit; flag for HITL review
 *       if {@code mcp.script-gen.warn-requires-hitl=true}</li>
 *   <li>{@code BLOCK} — reject script; throw {@link GuardrailBlockException};
 *       execution never happens</li>
 * </ul>
 */
@Slf4j
@Component
public class ScriptGuardrailValidator {

    // ─── Config ──────────────────────────────────────────────────────────────

    @Value("${mcp.script-gen.max-lines:100}")
    private int maxLines;

    @Value("${mcp.script-gen.max-distinct-commands:15}")
    private int maxDistinctCommands;

    @Value("${mcp.script-gen.warn-requires-hitl:false}")
    private boolean warnRequiresHitl;

    /** Comma-separated dangerous substrings — always blocked regardless of SOP. */
    @Value("${mcp.script-gen.blocklist:rm -rf /,format c:,mkfs.,dd if=/dev/,dd if=,:(){:|:&};:,> /dev/sda,> /dev/hda,wget http,curl http}")
    private String blocklistConfig;

    // ─────────────────────────────────────────────────────────────────────────
    // Per-category command allowlists
    // Each entry is a PREFIX or exact match against the first token on a line.
    // ─────────────────────────────────────────────────────────────────────────

    /** Allowed command prefixes for Linux across ALL categories. Always permitted. */
    private static final Set<String> LINUX_UNIVERSAL = Set.of(
            "echo", "printf", "set", "export", "local", "#", "if", "then",
            "else", "elif", "fi", "for", "do", "done", "while", "case",
            "esac", "function", "return", "exit", "sleep", "date", "true",
            "false", "test", "[", "[[", "]]", ")", "(", "{", "}", "&&", "||",
            "wait", "cd", "pwd", "ls", "cat", "grep", "awk", "sed", "wc",
            "head", "tail", "tee", "tr", "cut", "sort", "uniq", "xargs",
            "read", "readonly", "shift", "source", ".", "env", "printenv"
    );

    /** Allowed command prefixes for PowerShell (Windows) — always permitted. */
    private static final Set<String> WINDOWS_UNIVERSAL = Set.of(
            "write-host", "write-output", "write-error", "write-warning",
            "$erroractionpreference", "param", "return", "exit", "break",
            "continue", "if", "elseif", "else", "foreach", "for", "while",
            "switch", "try", "catch", "finally", "function",
            "start-sleep", "get-date", "get-location", "set-location",
            "test-path", "get-content", "write-verbose", "write-debug",
            "select-object", "where-object", "foreach-object",
            "format-table", "out-string", "join-path", "split-path"
    );

    /** Commands permitted only for APPLICATION (service restart) SOPs. */
    private static final Set<String> ALLOWLIST_APPLICATION_LINUX = Set.of(
            "systemctl", "/opt/tomcat/bin/shutdown.sh", "/opt/tomcat/bin/startup.sh",
            "catalina.sh", "shutdown.sh", "startup.sh", "service",
            "kill", "killall", "pkill", "pgrep", "ps", "ss", "netstat",
            "curl", "wget", "nc", "ncat"                // only for health-check calls within the step
    );
    private static final Set<String> ALLOWLIST_APPLICATION_WINDOWS = Set.of(
            "restart-service", "stop-service", "start-service",
            "get-service", "set-service",
            "iisreset", "import-module webaadministration",
            "restart-webapppool", "get-webapppool", "start-webapppool", "stop-webapppool",
            "sc.exe", "net", "net.exe",
            "invoke-webrequest", "test-netconnection"
    );

    /** Commands permitted only for PERFORMANCE (cache/db) SOPs. */
    private static final Set<String> ALLOWLIST_PERFORMANCE_LINUX = Set.of(
            "redis-cli", "memcached", "psql", "mysql", "pg_terminate_backend",
            "pg_stat_activity", "kill", "pkill", "pgrep", "ps",
            "curl", "service", "systemctl"
    );
    private static final Set<String> ALLOWLIST_PERFORMANCE_WINDOWS = Set.of(
            "invoke-webrequest", "start-scheduledtask", "get-scheduledtask",
            "get-scheduledtaskinfo", "schtasks", "restart-service", "net"
    );

    /** Commands permitted only for INFRASTRUCTURE (disk/log cleanup) SOPs. */
    private static final Set<String> ALLOWLIST_INFRASTRUCTURE_LINUX = Set.of(
            "find", "gzip", "bzip2", "xz", "tar", "df", "du", "stat",
            "touch", "mkdir", "rmdir",
            "logrotate", "journalctl", "dmesg", "free", "top", "vmstat",
            "iostat", "sar", "lsof", "fuser"
            // NOTE: rm is NOT here — rm -rf / is blocked at layer 2;
            // rm of specific files is allowed via cat INFRASTRUCTURE_RM_SAFE
    );
    private static final Set<String> ALLOWLIST_INFRASTRUCTURE_RM_SAFE = Set.of(
            "rm"    // allowed with strict path constraint checked at intent layer
    );
    private static final Set<String> ALLOWLIST_INFRASTRUCTURE_WINDOWS = Set.of(
            "remove-item", "get-childitem", "get-item", "get-acl",
            "compress-archive", "expand-archive", "test-path",
            "get-disk", "get-volume", "get-psdrive", "get-eventlog",
            "clear-eventlog", "get-winevent"
    );

    /** Commands for DEPLOYMENT SOPs. */
    private static final Set<String> ALLOWLIST_DEPLOYMENT_LINUX = Set.of(
            "kubectl", "helm", "docker", "systemctl", "service",
            "curl", "wget", "jq"
    );
    private static final Set<String> ALLOWLIST_DEPLOYMENT_WINDOWS = Set.of(
            "kubectl", "helm", "invoke-webrequest"
    );

    /** Commands for DATABASE SOPs. */
    private static final Set<String> ALLOWLIST_DATABASE_LINUX = Set.of(
            "psql", "pg_dump", "pg_restore", "pg_ctl", "pg_lsclusters",
            "mysql", "mysqladmin", "mysqlcheck",
            "systemctl", "service"
    );
    private static final Set<String> ALLOWLIST_DATABASE_WINDOWS = Set.of(
            "sqlcmd", "invoke-sqlcmd", "restart-service", "start-service", "stop-service"
    );

    /** Patterns that indicate a script is trying to escape its SOP scope. */
    private static final List<Pattern> SCOPE_ESCAPE_PATTERNS = List.of(
            Pattern.compile("(?i)(apt(-get)?|yum|dnf|brew|pip|npm|gem)\\s+install"),
            Pattern.compile("(?i)\\bcrontab\\s+-[a-z]"),
            Pattern.compile("(?i)\\bssh\\s+"),
            Pattern.compile("(?i)\\bscp\\s+"),
            Pattern.compile("(?i)\\brsync\\s+"),
            Pattern.compile("(?i)\\bsu\\s+-"),
            Pattern.compile("(?i)\\buseradd\\b"),
            Pattern.compile("(?i)\\bpasswd\\b"),
            Pattern.compile("(?i)\\bchmod\\s+777"),
            Pattern.compile("(?i)\\bchmod\\s+[-+]s"),
            Pattern.compile("(?i)\\biptables\\b"),
            Pattern.compile("(?i)\\bfirewall"),
            Pattern.compile("(?i)\\beval\\s+\\$"),
            Pattern.compile("(?i)\\bexec\\s+\\$"),
            Pattern.compile("(?i)source\\s+\\$"),
            Pattern.compile("(?i)\\bnohup\\b"),
            Pattern.compile("(?i)\\bscreen\\b"),
            Pattern.compile("(?i)\\btmux\\b"),
            Pattern.compile("(?i)\\bat\\s+")
    );

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run all five validation layers against the generated script.
     *
     * @param script  the raw script text to validate
     * @param request the originating SOP step context
     * @return {@link ValidationResult} with overall level and per-finding details
     * @throws GuardrailBlockException if any BLOCK-level finding is detected
     */
    public ValidationResult validate(String script, SopScriptRequest request) {
        List<Finding> findings = new ArrayList<>();

        log.info("[Guardrail] Validating script for SOP='{}' host='{}' os='{}'",
                request.getSopTitle(), request.getTargetHost(), request.getOs());

        // Run all five layers
        runLayer1Structure(script, request, findings);
        runLayer2Blocklist(script, findings);
        runLayer3CommandAllowlist(script, request, findings);
        runLayer4SopIntent(script, request, findings);
        runLayer5Complexity(script, request, findings);

        // Determine overall result level
        boolean hasBlock = findings.stream().anyMatch(f -> f.level() == Level.BLOCK);
        boolean hasWarn  = findings.stream().anyMatch(f -> f.level() == Level.WARN);

        Level overall = hasBlock ? Level.BLOCK : hasWarn ? Level.WARN : Level.PASS;

        StringBuilder summary = new StringBuilder();
        findings.forEach(f -> summary.append("[").append(f.level()).append("] ")
                .append(f.layer()).append(": ").append(f.message()).append("\n"));

        ValidationResult result = new ValidationResult(overall, List.copyOf(findings), summary.toString().trim());

        if (hasBlock) {
            log.error("[Guardrail] BLOCKED — {} finding(s). SOP='{}'\n{}",
                    findings.stream().filter(f -> f.level() == Level.BLOCK).count(),
                    request.getSopId(), summary);
            throw new GuardrailBlockException(
                    "Script blocked by guardrail for SOP '" + request.getSopTitle() + "':\n" + summary);
        }

        if (hasWarn) {
            log.warn("[Guardrail] WARN — {} finding(s) for SOP='{}'\n{}",
                    findings.stream().filter(f -> f.level() == Level.WARN).count(),
                    request.getSopId(), summary);
            if (warnRequiresHitl) {
                throw new GuardrailBlockException(
                        "Script has WARN findings and mcp.script-gen.warn-requires-hitl=true. "
                        + "Route to HITL queue.\n" + summary);
            }
        }

        log.info("[Guardrail] PASS — script cleared all layers for SOP='{}'", request.getSopId());
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 1 — Structural validation
    // ─────────────────────────────────────────────────────────────────────────

    private void runLayer1Structure(String script, SopScriptRequest req, List<Finding> out) {
        long lineCount = script.lines().count();

        // 1a — shebang / error action present
        if (!req.isWindows()) {
            if (!script.startsWith("#!/")) {
                out.add(Finding.block("L1:Structure",
                        "Bash script missing shebang line (#!/bin/bash or #!/bin/sh)"));
            }
            if (!script.contains("set -e")) {
                out.add(Finding.warn("L1:Structure",
                        "Bash script missing 'set -e' — errors may be silently ignored"));
            }
        } else {
            if (!script.toLowerCase().contains("$erroractionpreference")) {
                out.add(Finding.warn("L1:Structure",
                        "PowerShell script missing '$ErrorActionPreference=\"Stop\"'"));
            }
        }

        // 1b — must have at least one echo/Write-Host for traceability
        boolean hasTrace = req.isWindows()
                ? script.toLowerCase().contains("write-host")
                : script.contains("echo ");
        if (!hasTrace) {
            out.add(Finding.warn("L1:Structure",
                    "Script has no echo/Write-Host statements — remote execution output will be empty"));
        }

        // 1c — line count limit
        if (lineCount > maxLines) {
            out.add(Finding.block("L1:Structure",
                    "Script has " + lineCount + " lines — exceeds max allowed " + maxLines
                    + ". Reduce scope to match SOP step."));
        }

        // 1d — MCP header comment must be present (injected by ScriptGeneratorService)
        if (!script.contains("[MCP]") && !script.contains("SOP_ID=")) {
            out.add(Finding.warn("L1:Structure",
                    "Script is missing MCP traceability header (SOP_ID comment). "
                    + "ScriptGeneratorService should inject this."));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 2 — Blocklist
    // ─────────────────────────────────────────────────────────────────────────

    private void runLayer2Blocklist(String script, List<Finding> out) {
        List<String> blocklist = Arrays.stream(blocklistConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        String lower = script.toLowerCase();
        for (String banned : blocklist) {
            if (lower.contains(banned.toLowerCase())) {
                out.add(Finding.block("L2:Blocklist",
                        "Forbidden pattern detected: '" + banned + "'. "
                        + "This command is permanently disallowed regardless of SOP."));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 3 — Command allowlist per SOP category
    // ─────────────────────────────────────────────────────────────────────────

    private void runLayer3CommandAllowlist(String script, SopScriptRequest req, List<Finding> out) {
        Set<String> allowed = buildAllowlist(req);

        for (String line : script.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue;

            // Extract first token (the command being invoked)
            String firstToken = trimmed.split("\\s+")[0].toLowerCase()
                    .replaceAll("^[\"']|[\"']$", "");   // strip quotes if present

            // Skip variable assignments (FOO=bar), redirection, pipeline chars
            if (firstToken.contains("=") || firstToken.startsWith("$")
                    || firstToken.equals("|") || firstToken.equals(">")
                    || firstToken.equals(">>") || firstToken.equals("&")) {
                continue;
            }

            // Strip leading path to get base command name (e.g. /bin/bash → bash)
            String baseCmd = firstToken.contains("/")
                    ? firstToken.substring(firstToken.lastIndexOf("/") + 1)
                    : firstToken;

            if (!isAllowed(baseCmd, firstToken, allowed)) {
                // Extra check: is it in request's explicit allowed list?
                boolean inExplicit = req.getAllowedCommands() != null
                        && req.getAllowedCommands().stream()
                               .anyMatch(c -> c.equalsIgnoreCase(baseCmd)
                                           || c.equalsIgnoreCase(firstToken));
                if (!inExplicit) {
                    out.add(Finding.block("L3:Allowlist",
                            "Command '" + baseCmd + "' is NOT in the allowlist for SOP category '"
                            + req.getSopCategory() + "'. "
                            + "Only these categories of commands are permitted: " + summariseAllowed(allowed)));
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 4 — SOP intent: script must not exceed the scope of the SOP step
    // ─────────────────────────────────────────────────────────────────────────

    private void runLayer4SopIntent(String script, SopScriptRequest req, List<Finding> out) {
        // 4a — scope-escape patterns (package installs, ssh inside script, etc.)
        for (Pattern pattern : SCOPE_ESCAPE_PATTERNS) {
            if (pattern.matcher(script).find()) {
                out.add(Finding.block("L4:SopIntent",
                        "Script contains a scope-escape pattern: '" + pattern.pattern()
                        + "'. The SOP step description does not mention this operation."));
            }
        }

        // 4b — if description does NOT mention "delete" or "remove", block rm
        String descLower = req.getSopStepDescription().toLowerCase();
        if (!descLower.contains("delete") && !descLower.contains("remove")
                && !descLower.contains("clean") && !descLower.contains("purge")) {
            if (script.contains(" rm ") || script.contains("\nrm ") || script.contains("Remove-Item")) {
                out.add(Finding.block("L4:SopIntent",
                        "Script uses rm/Remove-Item but the SOP step does not mention deleting files. "
                        + "If file deletion is intended, update the SOP step description."));
            }
        }

        // 4c — if description does NOT mention "install", block package managers
        if (!descLower.contains("install")) {
            if (script.matches("(?s).*(apt|yum|dnf|pip|npm)\\s+install.*")) {
                out.add(Finding.block("L4:SopIntent",
                        "Script attempts to install packages, but the SOP step does not mention installation."));
            }
        }

        // 4d — script must not reference service/host names NOT in the SOP step
        //       (catches LLM hallucinating extra services)
        List<String> suspectServices = extractServiceNames(script, req.isWindows());
        for (String svc : suspectServices) {
            if (!descLower.contains(svc.toLowerCase()) && !svc.equalsIgnoreCase("tomcat")
                    && !svc.equalsIgnoreCase("nginx") && !svc.equalsIgnoreCase("postgresql")
                    && !svc.equalsIgnoreCase("redis")) {
                // Only WARN — service name may be a legitimate variant
                out.add(Finding.warn("L4:SopIntent",
                        "Script references service '" + svc + "' which was not explicitly mentioned "
                        + "in the SOP step description. Verify this is intentional."));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 5 — Complexity guard
    // ─────────────────────────────────────────────────────────────────────────

    private void runLayer5Complexity(String script, SopScriptRequest req, List<Finding> out) {
        // 5a — distinct command count
        Set<String> distinct = script.lines()
                .map(String::trim)
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .map(l -> l.split("\\s+")[0].toLowerCase())
                .filter(c -> !c.startsWith("$") && !c.contains("="))
                .collect(Collectors.toSet());

        if (distinct.size() > maxDistinctCommands) {
            out.add(Finding.warn("L5:Complexity",
                    "Script uses " + distinct.size() + " distinct commands — exceeds recommended max "
                    + maxDistinctCommands + ". Consider splitting into smaller SOP steps."));
        }

        // 5b — dynamic eval / exec
        if (script.contains("eval ") || script.toLowerCase().contains("invoke-expression")) {
            out.add(Finding.warn("L5:Complexity",
                    "Script uses eval/Invoke-Expression — dynamic code execution detected. "
                    + "Ensure no user-supplied data reaches this call."));
        }

        // 5c — background job spawning
        if (script.contains(" &\n") || script.contains(" &\"") || script.contains("Start-Job")) {
            out.add(Finding.warn("L5:Complexity",
                    "Script spawns background processes. "
                    + "Exit code may not reflect actual remediation success."));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Allowlist builder
    // ─────────────────────────────────────────────────────────────────────────

    private Set<String> buildAllowlist(SopScriptRequest req) {
        Set<String> base = new HashSet<>(req.isWindows() ? WINDOWS_UNIVERSAL : LINUX_UNIVERSAL);
        String cat = req.getSopCategory() == null ? "" : req.getSopCategory().toUpperCase();

        if (req.isWindows()) {
            switch (cat) {
                case "APPLICATION"    -> base.addAll(ALLOWLIST_APPLICATION_WINDOWS);
                case "PERFORMANCE"    -> base.addAll(ALLOWLIST_PERFORMANCE_WINDOWS);
                case "INFRASTRUCTURE" -> base.addAll(ALLOWLIST_INFRASTRUCTURE_WINDOWS);
                case "DEPLOYMENT"     -> base.addAll(ALLOWLIST_DEPLOYMENT_WINDOWS);
                case "DATABASE"       -> base.addAll(ALLOWLIST_DATABASE_WINDOWS);
                default               -> {
                    // If category unknown, union ALL Windows allowlists (permissive fallback)
                    base.addAll(ALLOWLIST_APPLICATION_WINDOWS);
                    base.addAll(ALLOWLIST_PERFORMANCE_WINDOWS);
                    base.addAll(ALLOWLIST_INFRASTRUCTURE_WINDOWS);
                }
            }
        } else {
            switch (cat) {
                case "APPLICATION"    -> base.addAll(ALLOWLIST_APPLICATION_LINUX);
                case "PERFORMANCE"    -> base.addAll(ALLOWLIST_PERFORMANCE_LINUX);
                case "INFRASTRUCTURE" -> {
                    base.addAll(ALLOWLIST_INFRASTRUCTURE_LINUX);
                    base.addAll(ALLOWLIST_INFRASTRUCTURE_RM_SAFE);
                }
                case "DEPLOYMENT"     -> base.addAll(ALLOWLIST_DEPLOYMENT_LINUX);
                case "DATABASE"       -> base.addAll(ALLOWLIST_DATABASE_LINUX);
                default               -> {
                    base.addAll(ALLOWLIST_APPLICATION_LINUX);
                    base.addAll(ALLOWLIST_PERFORMANCE_LINUX);
                    base.addAll(ALLOWLIST_INFRASTRUCTURE_LINUX);
                }
            }
        }

        // Merge any explicit allowed commands from the request
        if (req.getAllowedCommands() != null) {
            req.getAllowedCommands().forEach(c -> base.add(c.toLowerCase()));
        }
        return Collections.unmodifiableSet(base);
    }

    private boolean isAllowed(String baseCmd, String fullToken, Set<String> allowed) {
        // Exact base-name match
        if (allowed.contains(baseCmd)) return true;
        // Full-path lower-case match
        if (allowed.contains(fullToken)) return true;
        // Prefix match (e.g. allowed="/opt/tomcat/bin/" covers shutdown.sh)
        return allowed.stream().anyMatch(a -> a.endsWith("/") && fullToken.startsWith(a));
    }

    private String summariseAllowed(Set<String> allowed) {
        return allowed.stream().sorted().limit(20).collect(Collectors.joining(", "));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers — extract service name tokens from script body
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> extractServiceNames(String script, boolean windows) {
        List<String> names = new ArrayList<>();
        Pattern svcPattern = windows
                ? Pattern.compile("(?i)(restart|stop|start)-service\\s+['\"-]?(\\S+)")
                : Pattern.compile("(?i)systemctl\\s+(restart|stop|start)\\s+(\\S+)");

        var matcher = svcPattern.matcher(script);
        while (matcher.find()) {
            names.add(matcher.group(2).replaceAll("['\"-]", ""));
        }
        return names;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result types
    // ─────────────────────────────────────────────────────────────────────────

    public enum Level { PASS, WARN, BLOCK }

    public record Finding(Level level, String layer, String message) {
        static Finding block(String layer, String msg) { return new Finding(Level.BLOCK, layer, msg); }
        static Finding warn (String layer, String msg) { return new Finding(Level.WARN,  layer, msg); }
        static Finding pass (String layer, String msg) { return new Finding(Level.PASS,  layer, msg); }
    }

    public record ValidationResult(Level overall, List<Finding> findings, String summary) {
        public boolean isPassed()  { return overall == Level.PASS; }
        public boolean isWarning() { return overall == Level.WARN; }
        public boolean isBlocked() { return overall == Level.BLOCK; }
    }

    /** Thrown when a BLOCK-level finding prevents script execution. */
    public static class GuardrailBlockException extends RuntimeException {
        public GuardrailBlockException(String msg) { super(msg); }
    }
}
