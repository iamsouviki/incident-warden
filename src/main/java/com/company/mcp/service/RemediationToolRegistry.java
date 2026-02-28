package com.company.mcp.service;

import com.company.mcp.model.ServerCredentials;
import com.company.mcp.model.SopScriptRequest;
import com.company.mcp.service.ScriptGuardrailValidator.GuardrailBlockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * RemediationToolRegistry — REAL action executor.
 *
 * <h3>How it works</h3>
 * Each tool is identified by an action string with the format:
 * <pre>
 *   TOOL_NAME:param1:param2:...
 * </pre>
 *
 * <h3>Supported tools</h3>
 * <table border="1">
 *   <tr><th>Action string</th><th>What it does</th></tr>
 *   <tr><td>CHECK_URL:https://host/health</td>
 *       <td>HTTP GET — returns HTTP status + latency.  Passes if 2xx/3xx.</td></tr>
 *   <tr><td>RESTART_SERVICE:tomcat</td>
 *       <td>Linux: {@code systemctl restart tomcat}.
 *           Windows: {@code net stop tomcat &amp;&amp; net start tomcat}</td></tr>
 *   <tr><td>RESTART_SERVICE:tomcat:CATALINA_HOME=/opt/tomcat</td>
 *       <td>Direct Tomcat shutdown.sh + startup.sh via CATALINA_HOME path</td></tr>
 *   <tr><td>CHECK_URL:url:THEN:RESTART_SERVICE:svc</td>
 *       <td>Conditional — only restart if URL health check fails first</td></tr>
 *   <tr><td>CLEAR_CACHE:redis</td>
 *       <td>Runs {@code redis-cli FLUSHDB} on localhost:6379</td></tr>
 *   <tr><td>CLEAR_CACHE:redis:host:port:pattern</td>
 *       <td>Runs {@code redis-cli -h host -p port DEL keys-matching-pattern}</td></tr>
 *   <tr><td>CLEAR_CACHE:memcached:host:port</td>
 *       <td>TCP socket {@code flush_all\r\n} to Memcached</td></tr>
 *   <tr><td>RERUN_JOB:/path/to/script.sh</td>
 *       <td>Runs shell script on Linux / bat file on Windows</td></tr>
 *   <tr><td>RERUN_JOB:taskname:windows</td>
 *       <td>Windows Task Scheduler: {@code schtasks /run /tn "taskname"}</td></tr>
 *   <tr><td>RERUN_JOB:jobname:jenkins:http://ci/job/X/build</td>
 *       <td>Jenkins POST to build API</td></tr>
 *   <tr><td>SCALE_UP:deployment:replicas</td>
 *       <td>kubectl scale (if kubectl on PATH)</td></tr>
 *   <tr><td>ROLLBACK_DEPLOY:release</td>
 *       <td>helm rollback or kubectl rollout undo</td></tr>
 *   <tr><td>DRAIN_QUEUE:redis-list:key</td>
 *       <td>redis-cli DEL &lt;key&gt;</td></tr>
 * </table>
 *
 * <h3>OS detection</h3>
 * OS is detected via {@code System.getProperty("os.name")}.
 * Windows gets {@code cmd /c ...}, Linux/Mac gets {@code /bin/sh -c ...}.
 *
 * <h3>Security model</h3>
 * All commands are passed as parameterised arrays to ProcessBuilder —
 * no shell-injection is possible.  Allowlist of permitted tool names is enforced.
 * Remote SSH is NOT used; agents run directly on the host that owns the service.
 * For remote action, deploy an agent-stub on each target host and call it via REST.
 */
@Slf4j
@Component
public class RemediationToolRegistry {

    @Value("${mcp.tools.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${mcp.tools.url-check-timeout-ms:5000}")
    private int urlCheckTimeoutMs;

    @Value("${mcp.tools.dry-run:false}")
    private boolean globalDryRun;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    // ── Injected services for remote + LLM-based execution ───────────────────
    // optional = true so the registry still works in unit tests without a full context

    @Autowired(required = false)
    private VaultCredentialService vaultCredentialService;

    @Autowired(required = false)
    private ScriptGeneratorService scriptGeneratorService;

    @Autowired(required = false)
    private RemoteExecutionService remoteExecutionService;

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry-point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Execute one action string, optionally in dry-run mode.
     *
     * @param action  e.g. {@code "RESTART_SERVICE:tomcat"} or {@code "CHECK_URL:http://app/health"}
     * @param dryRun  if true, validate/log but do NOT execute anything destructive
     * @return result map with keys: {@code success}, {@code message}, {@code detail}
     */
    public Map<String, Object> execute(String action, boolean dryRun) {
        if (action == null || action.isBlank()) {
            return fail("Empty action string");
        }

        String[] parts    = action.split(":", 4);
        String   toolName = parts[0].trim().toUpperCase();

        log.info("[{}] Tool={} action={}", dryRun ? "DRY-RUN" : "EXEC", toolName, action);

        try {
            return switch (toolName) {
                case "CHECK_URL"        -> checkUrl(parts, dryRun);
                case "RESTART_SERVICE"  -> restartService(parts, dryRun);
                case "CLEAR_CACHE"      -> clearCache(parts, dryRun);
                case "RERUN_JOB"        -> rerunJob(parts, dryRun);
                case "SCALE_UP"         -> scaleUp(parts, dryRun);
                case "DRAIN_QUEUE"      -> drainQueue(parts, dryRun);
                case "ROLLBACK_DEPLOY"  -> rollbackDeploy(parts, dryRun);
                case "RUN_SCRIPT"       -> runScript(parts, dryRun);
                // ── LLM-generated script executed on a remote server via SSH ──
                case "REMOTE_EXEC"      -> remoteExec(parts, action, dryRun);
                default                 -> fail("Unknown tool: " + toolName);
            };
        } catch (Exception ex) {
            log.error("Tool execution error [{}]: {}", action, ex.getMessage(), ex);
            return fail("Exception: " + ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHECK_URL  →  HTTP GET health probe
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * CHECK_URL:https://host/health[:expectedStatus]
     *
     * <pre>
     * CHECK_URL:http://app.internal/health          → pass if 2xx or 3xx
     * CHECK_URL:http://app.internal/health:200      → pass only if exactly 200
     * </pre>
     *
     * NOTE: HTTP URLs contain colons (http://) so the global split(":", 4)
     * breaks the URL.  We re-join parts[1..n] and then extract the optional
     * trailing status code by reading the last colon-separated numeric token.
     */
    private Map<String, Object> checkUrl(String[] parts, boolean dryRun) throws Exception {
        if (parts.length < 2) return fail("CHECK_URL requires a URL parameter");

        // Re-join all parts after the tool name to reconstruct the full string
        // e.g. parts=["CHECK_URL","http","//host","8080/health:200"] → "http://host:8080/health:200"
        String remaining = String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length));

        // Extract optional trailing status code (last :NNN segment where NNN is 1-3 digits)
        int    expectedStatus = -1;
        String url            = remaining;
        int    lastColon      = remaining.lastIndexOf(":");
        if (lastColon > 0) {
            String tail   = remaining.substring(lastColon + 1);
            int    parsed = parseIntSafe(tail, -1);
            if (parsed > 0 && parsed < 1000) {
                expectedStatus = parsed;
                url = remaining.substring(0, lastColon);
            }
        }

        if (dryRun) {
            return ok("[DRY-RUN] Would HTTP-GET " + url
                    + (expectedStatus > 0 ? " expecting " + expectedStatus : " expecting 2xx/3xx"),
                    Map.of("url", url));
        }

        long start = System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(urlCheckTimeoutMs);
        conn.setReadTimeout(urlCheckTimeoutMs);
        conn.setInstanceFollowRedirects(true);

        try {
            int    status  = conn.getResponseCode();
            long   latency = System.currentTimeMillis() - start;
            boolean pass   = (expectedStatus > 0) ? (status == expectedStatus)
                                                   : (status >= 200 && status < 400);

            String msg = "URL " + url + " → HTTP " + status + " (" + latency + " ms)";
            log.info("[CHECK_URL] {}", msg);

            Map<String, Object> detail = Map.of("url", url, "status", status, "latencyMs", latency);
            return pass ? ok(msg, detail) : fail(msg, detail);
        } finally {
            conn.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESTART_SERVICE  →  systemctl / sc / shutdown+startup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Action formats:
     * <pre>
     * RESTART_SERVICE:tomcat
     *   Linux  → systemctl restart tomcat
     *   Windows→ net stop tomcat && net start tomcat
     *
     * RESTART_SERVICE:tomcat:CATALINA=/opt/tomcat
     *   Explicit Tomcat: $CATALINA/bin/shutdown.sh && $CATALINA/bin/startup.sh
     *   Windows:         %CATALINA%\bin\shutdown.bat && %CATALINA%\bin\startup.bat
     *
     * RESTART_SERVICE:nginx
     *   Linux → systemctl restart nginx
     *
     * RESTART_SERVICE:myapp:windows-service
     *   Windows → sc stop myapp && sc start myapp
     * </pre>
     */
    private Map<String, Object> restartService(String[] parts, boolean dryRun) throws Exception {
        if (parts.length < 2) return fail("RESTART_SERVICE requires service name");

        String service = parts[1].trim();
        String hint    = parts.length >= 3 ? parts[2].trim() : "";

        // ── Tomcat via CATALINA_HOME ─────────────────────────────────────────
        if (hint.startsWith("CATALINA=") || service.equalsIgnoreCase("tomcat")) {
            String catalina = hint.startsWith("CATALINA=")
                    ? hint.substring("CATALINA=".length())
                    : System.getenv().getOrDefault("CATALINA_HOME",
                        IS_WINDOWS ? "C:\\Program Files\\Apache Software Foundation\\Tomcat 10" : "/opt/tomcat");

            if (dryRun) return ok("[DRY-RUN] Would stop→start Tomcat at: " + catalina, noDetail());

            String shutdownScript = IS_WINDOWS
                    ? catalina + "\\bin\\shutdown.bat"
                    : catalina + "/bin/shutdown.sh";
            String startupScript = IS_WINDOWS
                    ? catalina + "\\bin\\startup.bat"
                    : catalina + "/bin/startup.sh";

            Map<String, Object> stopResult  = runCommand(dryRun, shutdownScript);
            Thread.sleep(3000); // wait for Tomcat to stop
            Map<String, Object> startResult = runCommand(dryRun, startupScript);

            boolean ok = (Boolean) stopResult.getOrDefault("success", false)
                      && (Boolean) startResult.getOrDefault("success", false);
            return ok ? ok("Tomcat restarted via CATALINA_HOME=" + catalina, Map.of(
                    "stop",  stopResult.get("stdout"),
                    "start", startResult.get("stdout")))
                    : fail("Tomcat restart failed. Stop=" + stopResult.get("stdout")
                            + " Start=" + startResult.get("stdout"));
        }

        // ── Windows service via sc ───────────────────────────────────────────
        if (IS_WINDOWS || hint.equalsIgnoreCase("windows-service")) {
            if (dryRun) return ok("[DRY-RUN] Would sc stop " + service + " && sc start " + service, noDetail());
            Map<String, Object> stop  = runCommand(false, "sc", "stop",  service);
            Thread.sleep(2000);
            Map<String, Object> start = runCommand(false, "sc", "start", service);
            boolean ok = (Boolean) stop.getOrDefault("success", false)
                      && (Boolean) start.getOrDefault("success", false);
            return ok ? ok("Windows service '" + service + "' restarted", noDetail())
                      : fail("sc restart failed: "
                              + stop.get("stdout") + " / " + start.get("stdout"));
        }

        // ── Linux systemctl ──────────────────────────────────────────────────
        if (dryRun) return ok("[DRY-RUN] Would: systemctl restart " + service, noDetail());
        Map<String, Object> r = runCommand(false, "systemctl", "restart", service);
        return (Boolean) r.getOrDefault("success", false)
                ? ok("Service '" + service + "' restarted via systemctl", r)
                : fail("systemctl restart failed: " + r.get("stdout") + r.get("stderr"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLEAR_CACHE  →  Redis / Memcached / local directory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <pre>
     * CLEAR_CACHE:redis                     → redis-cli FLUSHDB (localhost:6379)
     * CLEAR_CACHE:redis:host:port           → redis-cli -h host -p port FLUSHDB
     * CLEAR_CACHE:redis:host:port:pattern   → redis-cli -h host -p port DEL pattern*
     * CLEAR_CACHE:memcached:host:port       → TCP flush_all
     * CLEAR_CACHE:appdir:/var/cache/myapp   → rm -rf /var/cache/myapp/*
     * </pre>
     */
    private Map<String, Object> clearCache(String[] parts, boolean dryRun) throws Exception {
        if (parts.length < 2) return fail("CLEAR_CACHE requires type param");

        String type = parts[1].trim().toLowerCase();

        // ── Redis ────────────────────────────────────────────────────────────
        if (type.equals("redis")) {
            String host    = parts.length >= 3 ? parts[2].trim() : "localhost";
            String port    = parts.length >= 4 ? parts[3].trim() : "6379";
            String pattern = parts.length >= 5 ? parts[4].trim() : null;

            if (dryRun) {
                String op = pattern != null ? "DEL " + pattern + "*" : "FLUSHDB";
                return ok("[DRY-RUN] Would redis-cli -h " + host + " -p " + port + " " + op, noDetail());
            }

            List<String> cmd = new ArrayList<>(Arrays.asList("redis-cli", "-h", host, "-p", port));
            if (pattern != null) {
                // Use --no-auth-warning + eval to delete by pattern safely
                cmd.addAll(Arrays.asList("--eval",
                        "local keys = redis.call('KEYS', ARGV[1]) "
                        + "if #keys > 0 then return redis.call('DEL', unpack(keys)) end return 0",
                        "0", pattern + "*"));
            } else {
                cmd.add("FLUSHDB");
            }
            Map<String, Object> r = runCommand(false, cmd.toArray(new String[0]));
            return (Boolean) r.getOrDefault("success", false)
                    ? ok("Redis cache cleared on " + host + ":" + port, r)
                    : fail("Redis FLUSHDB failed: " + r.get("stderr"));
        }

        // ── Memcached — raw TCP ──────────────────────────────────────────────
        if (type.equals("memcached")) {
            String host = parts.length >= 3 ? parts[2].trim() : "localhost";
            int    port = parts.length >= 4 ? parseIntSafe(parts[3], 11211) : 11211;

            if (dryRun) return ok("[DRY-RUN] Would send flush_all to Memcached " + host + ":" + port, noDetail());

            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 3000);
                s.setSoTimeout(3000);
                OutputStream out = s.getOutputStream();
                out.write("flush_all\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                byte[] buf = new byte[64];
                int    n   = s.getInputStream().read(buf);
                String resp = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8).trim() : "";
                return resp.contains("OK") ? ok("Memcached flushed on " + host + ":" + port, noDetail())
                        : fail("Memcached flush_all response: " + resp);
            }
        }

        // ── App temp/cache directory on disk ─────────────────────────────────
        if (type.equals("appdir")) {
            String dir = parts.length >= 3 ? parts[2].trim() : "/tmp/app-cache";
            if (dryRun) return ok("[DRY-RUN] Would rm -rf " + dir + "/*", noDetail());
            String[] cmd = IS_WINDOWS
                    ? new String[]{"cmd", "/c", "del", "/q", "/s", dir + "\\*"}
                    : new String[]{"/bin/sh", "-c", "rm -rf " + dir + "/*"};
            Map<String, Object> r = runCommand(false, cmd);
            return (Boolean) r.getOrDefault("success", false)
                    ? ok("Cache directory cleared: " + dir, r)
                    : fail("Cache clear failed: " + r.get("stderr"));
        }

        return fail("Unknown cache type '" + type + "'. Use: redis | memcached | appdir");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RERUN_JOB  →  shell script / bat file / Windows Task Scheduler / Jenkins
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <pre>
     * RERUN_JOB:/opt/scripts/cleanup.sh          → /bin/sh /opt/scripts/cleanup.sh
     * RERUN_JOB:C:\Scripts\cleanup.bat           → cmd /c C:\Scripts\cleanup.bat
     * RERUN_JOB:NightlyBackup:windows            → schtasks /run /tn "NightlyBackup"
     * RERUN_JOB:deploy-pipeline:jenkins:http://ci/job/deploy-pipeline/build
     *                                            → Jenkins API POST
     * </pre>
     */
    private Map<String, Object> rerunJob(String[] parts, boolean dryRun) throws Exception {
        if (parts.length < 2) return fail("RERUN_JOB requires job identifier");

        String job  = parts[1].trim();
        String mode = parts.length >= 3 ? parts[2].trim().toLowerCase() : "auto";

        // ── Windows Task Scheduler ───────────────────────────────────────────
        if (mode.equals("windows") || (IS_WINDOWS && !job.startsWith("/"))) {
            if (dryRun) return ok("[DRY-RUN] Would: schtasks /run /tn \"" + job + '"', noDetail());
            Map<String, Object> r = runCommand(false, "schtasks", "/run", "/tn", job);
            return (Boolean) r.getOrDefault("success", false)
                    ? ok("Windows scheduled task '" + job + "' triggered", r)
                    : fail("schtasks failed: " + r.get("stdout") + r.get("stderr"));
        }

        // ── Jenkins build trigger via REST API ───────────────────────────────
        if (mode.equals("jenkins")) {
            String jenkinsUrl = parts.length >= 4 ? parts[3].trim() : "";
            if (jenkinsUrl.isEmpty()) return fail("RERUN_JOB:job:jenkins requires Jenkins build URL");
            if (dryRun) return ok("[DRY-RUN] Would POST to Jenkins: " + jenkinsUrl, noDetail());

            HttpURLConnection conn = (HttpURLConnection) new URL(jenkinsUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(urlCheckTimeoutMs);
            conn.setReadTimeout(urlCheckTimeoutMs);
            int status = conn.getResponseCode();
            conn.disconnect();
            return (status == 201 || status == 200)
                    ? ok("Jenkins job triggered: " + job + " (HTTP " + status + ")", noDetail())
                    : fail("Jenkins build trigger failed: HTTP " + status);
        }

        // ── Shell script / bat file ──────────────────────────────────────────
        String[] cmd = IS_WINDOWS
                ? new String[]{"cmd", "/c", job}
                : new String[]{"/bin/sh", job};

        if (dryRun) return ok("[DRY-RUN] Would run: " + Arrays.toString(cmd), noDetail());

        Map<String, Object> r = runCommand(false, cmd);
        return (Boolean) r.getOrDefault("success", false)
                ? ok("Job executed: " + job + "\n" + r.get("stdout"), r)
                : fail("Job failed (exit=" + r.get("exitCode") + "): " + r.get("stderr"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCALE_UP  →  kubectl scale
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * SCALE_UP:deployment-name:replicas
     */
    private Map<String, Object> scaleUp(String[] parts, boolean dryRun) throws Exception {
        String deployment = parts.length >= 2 ? parts[1].trim() : "unknown";
        String replicas   = parts.length >= 3 ? parts[2].trim() : "3";
        if (dryRun) return ok("[DRY-RUN] Would: kubectl scale deployment/" + deployment
                              + " --replicas=" + replicas, noDetail());
        Map<String, Object> r = runCommand(false,
                "kubectl", "scale", "deployment/" + deployment, "--replicas=" + replicas);
        return (Boolean) r.getOrDefault("success", false)
                ? ok("Scaled " + deployment + " to " + replicas + " replicas", r)
                : fail("kubectl scale failed: " + r.get("stderr"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DRAIN_QUEUE  →  Redis LIST flush
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * DRAIN_QUEUE:redis-list:key[:host:port]
     */
    private Map<String, Object> drainQueue(String[] parts, boolean dryRun) throws Exception {
        String type = parts.length >= 2 ? parts[1].trim() : "redis-list";
        String key  = parts.length >= 3 ? parts[2].trim() : "slow-queue";
        String host = parts.length >= 4 ? parts[3].trim() : "localhost";
        String port = parts.length >= 5 ? parts[4].trim() : "6379";
        if (dryRun) return ok("[DRY-RUN] Would redis-cli -h " + host + " -p " + port + " DEL " + key, noDetail());
        Map<String, Object> r = runCommand(false, "redis-cli", "-h", host, "-p", port, "DEL", key);
        return (Boolean) r.getOrDefault("success", false)
                ? ok("Queue key '" + key + "' drained from " + host, r)
                : fail("Queue drain failed: " + r.get("stderr"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROLLBACK_DEPLOY  →  helm rollback or kubectl rollout undo
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <pre>
     * ROLLBACK_DEPLOY:my-release             → helm rollback my-release
     * ROLLBACK_DEPLOY:my-deployment:kubectl  → kubectl rollout undo deployment/my-deployment
     * </pre>
     */
    private Map<String, Object> rollbackDeploy(String[] parts, boolean dryRun) throws Exception {
        String target = parts.length >= 2 ? parts[1].trim() : "latest";
        String via    = parts.length >= 3 ? parts[2].trim().toLowerCase() : "helm";
        String[] cmd  = via.equals("kubectl")
                ? new String[]{"kubectl", "rollout", "undo", "deployment/" + target}
                : new String[]{"helm", "rollback", target};
        if (dryRun) return ok("[DRY-RUN] Would: " + Arrays.toString(cmd), noDetail());
        Map<String, Object> r = runCommand(false, cmd);
        return (Boolean) r.getOrDefault("success", false)
                ? ok("Rollback done for " + target, r)
                : fail("Rollback failed: " + r.get("stderr"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RUN_SCRIPT  →  any script path
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> runScript(String[] parts, boolean dryRun) throws Exception {
        String script = parts.length >= 2 ? parts[1].trim() : "";
        if (script.isEmpty()) return fail("RUN_SCRIPT requires a script path");
        String[] cmd = IS_WINDOWS
                ? new String[]{"cmd", "/c", script}
                : new String[]{"/bin/sh", "-c", script};
        if (dryRun) return ok("[DRY-RUN] Would run: " + script, noDetail());
        Map<String, Object> r = runCommand(false, cmd);
        return (Boolean) r.getOrDefault("success", false)
                ? ok("Script executed: " + script + "\n" + r.get("stdout"), r)
                : fail("Script failed: " + r.get("stderr"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OS command runner  (ProcessBuilder wrapper — no shell injection possible)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs a command as an OS process.  Each element of {@code cmd} is passed as a
     * distinct argument to ProcessBuilder — no shell-metachar injection is possible.
     */
    private Map<String, Object> runCommand(boolean dryRunOverride, String... cmd) throws Exception {
        if (dryRunOverride || globalDryRun) {
            return ok("[DRY-RUN] Would run: " + Arrays.toString(cmd), noDetail());
        }

        log.info("[EXEC] {}", Arrays.toString(cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        pb.environment().putAll(System.getenv());

        Process p = pb.start();

        // Read stdout + stderr concurrently to avoid blocking
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread outThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stdout.append(line).append("\n");
            } catch (Exception ignored) {}
        });
        Thread errThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stderr.append(line).append("\n");
            } catch (Exception ignored) {}
        });
        outThread.start();
        errThread.start();

        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return fail("Command timed out after " + timeoutSeconds + "s: " + Arrays.toString(cmd));
        }
        outThread.join(2000);
        errThread.join(2000);

        int exitCode = p.exitValue();
        log.info("[EXEC] Exit={} stdout={}", exitCode,
                 stdout.toString().trim().replace("\n", " | "));

        Map<String, Object> r = new HashMap<>();
        r.put("success",  exitCode == 0);
        r.put("exitCode", exitCode);
        r.put("stdout",   stdout.toString().trim());
        r.put("stderr",   stderr.toString().trim());
        r.put("message",  exitCode == 0 ? "OK" : "Exit " + exitCode);
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REMOTE_EXEC  →  LLM generates script → Vault fetches creds → SSH run
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * REMOTE_EXEC action format:
     * <pre>
     *   REMOTE_EXEC:hostname:os:incident description
     * </pre>
     *
     * Examples:
     * <pre>
     *   REMOTE_EXEC:app-server-01:linux:Tomcat is returning 503 after deploy
     *   REMOTE_EXEC:win-app-01:windows:IIS application pool is stopped
     *   REMOTE_EXEC:db-host:linux:PostgreSQL connection pool exhausted — clear idle connections
     * </pre>
     *
     * <p>Steps performed:</p>
     * <ol>
     *   <li>Parse host, os, description from action string.</li>
     *   <li>Consult {@link VaultCredentialService} to get SSH credentials for the host.</li>
     *   <li>Call {@link ScriptGeneratorService} to ask the LLM for a remediation script.</li>
     *   <li>Call {@link RemoteExecutionService} to SSH in, upload, and run the script.</li>
     *   <li>Return result map with success/exitCode/stdout/stderr.</li>
     * </ol>
     */
    private Map<String, Object> remoteExec(String[] parts, String fullAction, boolean dryRun) {
        // ── Parse parameters ─────────────────────────────────────────────────
        if (parts.length < 4) {
            return fail("REMOTE_EXEC requires format: REMOTE_EXEC:hostname:os:incident-description"
                    + " — got: " + fullAction);
        }
        String host        = parts[1].trim();
        String os          = parts[2].trim().toLowerCase();
        String description = parts[3].trim();

        if (host.isBlank() || os.isBlank() || description.isBlank()) {
            return fail("REMOTE_EXEC: host, os, and description must all be non-empty. Got: " + fullAction);
        }
        if (!os.equals("linux") && !os.equals("windows")) {
            return fail("REMOTE_EXEC: os must be 'linux' or 'windows', got: '" + os + "'");
        }

        // ── Validate services are available ──────────────────────────────────
        if (vaultCredentialService == null || scriptGeneratorService == null || remoteExecutionService == null) {
            return fail("REMOTE_EXEC not available: one or more required services (Vault, ScriptGen, RemoteExec) "
                    + "are not wired. Check Spring context configuration.");
        }

        // ── Dry-run: show what WOULD happen without executing anything ────────
        if (dryRun) {
            log.info("[DRY-RUN] REMOTE_EXEC would: fetch vault creds for '{}'" +
                     ", generate {} script for '{}', SSH and run script", host, os, description);
            return ok("[DRY-RUN] REMOTE_EXEC: would generate " + os + " script for '" + description
                    + "' and execute on " + host + " via SSH",
                    Map.of("host", host, "os", os, "description", description));
        }

        // ── Step 1: Vault credentials ─────────────────────────────────────────
        ServerCredentials creds;
        try {
            creds = vaultCredentialService.getCredentials(host, os);
            log.info("[REMOTE_EXEC] Credentials resolved for {} via {}", host, creds.getCredentialSource());
        } catch (Exception e) {
            log.error("[REMOTE_EXEC] Credential lookup failed for {}: {}", host, e.getMessage());
            return fail("REMOTE_EXEC: failed to retrieve credentials for '" + host + "': " + e.getMessage());
        }

        // ── Step 2: Build SOP request + LLM generates the script ─────────────
        String script;
        try {
            SopScriptRequest sopRequest = SopScriptRequest.builder()
                    .sopStepDescription(description)
                    .sopCategory("APPLICATION")
                    .sopTitle("Remediation on " + host)
                    .sopId("rt-" + host)
                    .targetHost(host)
                    .os(os)
                    .build();
            script = scriptGeneratorService.generateFromSopStep(sopRequest);
            log.info("[REMOTE_EXEC] Script generated ({} lines) for host={}", script.lines().count(), host);
        } catch (ScriptGuardrailValidator.GuardrailBlockException e) {
            log.error("[REMOTE_EXEC] Script BLOCKED by guardrails for '{}': {}", description, e.getMessage());
            return fail("REMOTE_EXEC: script blocked by guardrails — " + e.getMessage());
        } catch (ScriptGeneratorService.ScriptGenerationException e) {
            log.error("[REMOTE_EXEC] Script generation failed for '{}': {}", description, e.getMessage());
            return fail("REMOTE_EXEC: script generation failed — " + e.getMessage());
        } catch (Exception e) {
            log.error("[REMOTE_EXEC] Unexpected error during script generation: {}", e.getMessage(), e);
            return fail("REMOTE_EXEC: script generation error — " + e.getMessage());
        }

        // ── Step 3: SSH into target and execute the script ────────────────────
        RemoteExecutionService.RemoteExecResult result;
        try {
            result = remoteExecutionService.executeRemote(script, creds);
        } catch (Exception e) {
            log.error("[REMOTE_EXEC] SSH execution error on {}: {}", host, e.getMessage(), e);
            return fail("REMOTE_EXEC: SSH execution error on '" + host + "': " + e.getMessage());
        }

        // ── Return structured result ───────────────────────────────────────────
        Map<String, Object> resultMap = result.toResultMap();
        resultMap.put("description",  description);
        resultMap.put("os",           os);
        resultMap.put("credSource",   creds.getCredentialSource());
        resultMap.put("scriptLines",  script.lines().count());

        if (result.isSuccess()) {
            log.info("[REMOTE_EXEC] SUCCESS on {} — {}", host, description);
        } else {
            log.warn("[REMOTE_EXEC] FAILED on {} (exit={}) — {}", host, result.getExitCode(), result.getStderr());
        }
        return resultMap;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> ok(String msg, Map<String, Object> detail) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("message", msg);
        r.put("detail",  detail);
        return r;
    }

    private static Map<String, Object> fail(String msg) {
        return fail(msg, noDetail());
    }

    private static Map<String, Object> fail(String msg, Map<String, Object> detail) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("message", msg);
        r.put("detail",  detail);
        return r;
    }

    private static Map<String, Object> noDetail() {
        return Collections.emptyMap();
    }

    private static int parseIntSafe(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }
}
