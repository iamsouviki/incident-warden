package com.company.mcp.service;

import com.company.mcp.model.ServerCredentials;
import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * RemoteExecutionService — uploads an LLM-generated script to a remote server
 * via SSH (JSch) and executes it, returning the full stdout/stderr output.
 *
 * <h3>Works for both Linux and Windows</h3>
 * <ul>
 *   <li><b>Linux</b> — uploads a {@code .sh} file to {@code /tmp/}, runs {@code /bin/bash}</li>
 *   <li><b>Windows</b> — Windows 10 / Server 2019+ ship with OpenSSH; uploads a
 *       {@code .ps1} file to {@code C:\Windows\Temp\}, runs
 *       {@code powershell -ExecutionPolicy Bypass -File ...}</li>
 * </ul>
 *
 * <h3>Authentication</h3>
 * Credentials come from {@link VaultCredentialService}:
 * <ul>
 *   <li>Key-based: PEM private key loaded in-memory via JSch — never written to disk.</li>
 *   <li>Password-based: standard JSch password auth.</li>
 * </ul>
 *
 * <h3>Lifecycle of a remote execution</h3>
 * <ol>
 *   <li>Open SSH session to {@code host:port}.</li>
 *   <li>SFTP-upload the script with a unique UUID-based filename.</li>
 *   <li>Open an exec channel and run the script.</li>
 *   <li>Stream stdout and stderr until the channel exits.</li>
 *   <li>Optionally delete the uploaded script file (controlled by {@code mcp.remote.cleanup-script}).</li>
 *   <li>Return {@link RemoteExecResult} containing exit code + combined output.</li>
 * </ol>
 */
@Slf4j
@Service
public class RemoteExecutionService {

    @Value("${mcp.remote.ssh-connect-timeout-ms:15000}")
    private int connectTimeoutMs;

    @Value("${mcp.remote.ssh-channel-timeout-ms:60000}")
    private int channelTimeoutMs;

    @Value("${mcp.remote.strict-host-checking:NONE}")
    private String strictHostChecking;

    @Value("${mcp.remote.linux-script-dir:/tmp}")
    private String linuxScriptDir;

    @Value("${mcp.remote.windows-script-dir:C:\\Windows\\Temp}")
    private String windowsScriptDir;

    @Value("${mcp.remote.cleanup-script:true}")
    private boolean cleanupScript;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Execute a script on a remote server.
     *
     * @param scriptBody  the full script text (bash or PowerShell)
     * @param creds       server credentials (from Vault or env-vars)
     * @return {@link RemoteExecResult} with exit code, stdout, stderr
     */
    public RemoteExecResult executeRemote(String scriptBody, ServerCredentials creds) {
        log.info("[SSH] Connecting to {}:{} as {} (auth={})",
                creds.getHost(), creds.getSshPort(), creds.getUsername(),
                creds.isKeyBased() ? "key" : "password");

        Session session = null;
        try {
            session = createSession(creds);
            session.connect(connectTimeoutMs);
            log.info("[SSH] Connected to {}", creds.getHost());

            // 1 — upload the script via SFTP
            String remotePath = uploadScript(session, scriptBody, creds);
            log.info("[SSH] Script uploaded to {}", remotePath);

            // 2 — execute the script
            RemoteExecResult result = runScript(session, remotePath, creds);
            log.info("[SSH] Execution complete on {}: exit={}", creds.getHost(), result.getExitCode());

            // 3 — clean up
            if (cleanupScript) {
                deleteRemoteFile(session, remotePath, creds);
            }

            return result;

        } catch (Exception e) {
            log.error("[SSH] Remote execution failed on {}: {}", creds.getHost(), e.getMessage(), e);
            return RemoteExecResult.failure(creds.getHost(), e.getMessage());
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
                log.debug("[SSH] Disconnected from {}", creds.getHost());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1 — Create and configure the SSH session
    // ─────────────────────────────────────────────────────────────────────────

    private Session createSession(ServerCredentials creds) throws JSchException {
        JSch jsch = new JSch();

        // ── Key-based auth ───────────────────────────────────────────────────
        if (creds.isKeyBased()) {
            byte[] keyBytes = creds.getPrivateKeyPem().getBytes(StandardCharsets.UTF_8);
            byte[] passBytes = creds.getPrivateKeyPassphrase() != null
                    ? creds.getPrivateKeyPassphrase().getBytes(StandardCharsets.UTF_8)
                    : null;
            // Loaded in-memory — never touches the disk
            jsch.addIdentity(
                    creds.getHost() + "-key",   // identity name (for logging)
                    keyBytes,                   // private key PEM bytes
                    null,                       // public key (null = derive from private)
                    passBytes);
        }

        Session session = jsch.getSession(creds.getUsername(), creds.getHost(), creds.getSshPort());

        // ── Password fallback ────────────────────────────────────────────────
        if (!creds.isKeyBased() && creds.getPassword() != null) {
            session.setPassword(creds.getPassword());
        }

        // ── Host-key policy ──────────────────────────────────────────────────
        // NONE = accept all host keys (fine for private networks; set strict for internet hosts)
        if ("NONE".equalsIgnoreCase(strictHostChecking)) {
            session.setConfig("StrictHostKeyChecking", "no");
        } else if ("YES".equalsIgnoreCase(strictHostChecking)) {
            session.setConfig("StrictHostKeyChecking", "yes");
        }

        session.setConfig("PreferredAuthentications",
                creds.isKeyBased() ? "publickey" : "password");

        return session;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2 — Upload script via SFTP
    // ─────────────────────────────────────────────────────────────────────────

    private String uploadScript(Session session, String scriptBody, ServerCredentials creds)
            throws JSchException, SftpException {

        String ext        = creds.isWindows() ? ".ps1" : ".sh";
        String filename   = "mcp_remediation_" + UUID.randomUUID().toString().replace("-", "") + ext;
        String scriptDir  = creds.isWindows() ? windowsScriptDir : linuxScriptDir;
        String remotePath = creds.isWindows()
                ? scriptDir + "\\" + filename
                : scriptDir + "/" + filename;

        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect(connectTimeoutMs);
        try {
            byte[] scriptBytes = scriptBody.getBytes(StandardCharsets.UTF_8);
            sftp.put(new ByteArrayInputStream(scriptBytes), remotePath);
        } finally {
            sftp.disconnect();
        }
        return remotePath;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3 — Execute the uploaded script via exec channel
    // ─────────────────────────────────────────────────────────────────────────

    private RemoteExecResult runScript(Session session, String remotePath, ServerCredentials creds)
            throws JSchException {

        // Build exec command
        String execCommand = buildExecCommand(remotePath, creds);
        log.debug("[SSH] Exec command: {}", execCommand);

        ChannelExec exec = (ChannelExec) session.openChannel("exec");
        exec.setCommand(execCommand);
        exec.setErrStream(null); // we read stderr manually below

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try (InputStream stdoutStream = exec.getInputStream();
             InputStream stderrStream = exec.getErrStream()) {

            exec.connect(connectTimeoutMs);

            // ── drain stdout and stderr interleaved ──────────────────────────
            long deadline = System.currentTimeMillis() + channelTimeoutMs;
            byte[] buf = new byte[4096];

            while (!exec.isClosed() && System.currentTimeMillis() < deadline) {
                drainStream(stdoutStream, stdout, buf);
                drainStream(stderrStream, stderr, buf);
                if (!exec.isClosed()) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            // Final drain after channel closes
            drainStream(stdoutStream, stdout, buf);
            drainStream(stderrStream, stderr, buf);

            int exitCode = exec.getExitStatus();
            String stdoutStr = stdout.toString(StandardCharsets.UTF_8);
            String stderrStr = stderr.toString(StandardCharsets.UTF_8);

            if (!stdoutStr.isBlank()) log.info("[SSH:stdout] {}", stdoutStr.trim());
            if (!stderrStr.isBlank()) log.warn("[SSH:stderr] {}", stderrStr.trim());

            return RemoteExecResult.builder()
                    .host(creds.getHost())
                    .exitCode(exitCode)
                    .success(exitCode == 0)
                    .stdout(stdoutStr)
                    .stderr(stderrStr)
                    .command(execCommand)
                    .build();

        } catch (Exception e) {
            return RemoteExecResult.failure(creds.getHost(), e.getMessage());
        } finally {
            exec.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4 — Delete remote script after execution
    // ─────────────────────────────────────────────────────────────────────────

    private void deleteRemoteFile(Session session, String remotePath, ServerCredentials creds) {
        try {
            ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(connectTimeoutMs);
            try { sftp.rm(remotePath); } finally { sftp.disconnect(); }
            log.debug("[SSH] Deleted remote script: {}", remotePath);
        } catch (Exception e) {
            log.warn("[SSH] Could not delete remote script '{}': {}", remotePath, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the exec command string for the uploaded script file.
     *
     * <ul>
     *   <li>Linux:   {@code /bin/bash /tmp/mcp_remediation_<uuid>.sh}</li>
     *   <li>Linux (sudo): {@code echo <pass> | sudo -S /bin/bash /tmp/mcp_remediation_<uuid>.sh}</li>
     *   <li>Windows: {@code powershell -ExecutionPolicy Bypass -File C:\Windows\Temp\mcp_remediation_<uuid>.ps1}</li>
     * </ul>
     */
    private String buildExecCommand(String remotePath, ServerCredentials creds) {
        if (creds.isWindows()) {
            return "powershell -ExecutionPolicy Bypass -NonInteractive -File \"" + remotePath + "\"";
        }

        String baseCmd = "/bin/bash \"" + remotePath + "\"";

        // Privilege escalation with sudo if sudoPassword is provided
        if (creds.getSudoPassword() != null && !creds.getSudoPassword().isBlank()) {
            // echo '<pass>' | sudo -S /bin/bash <script>
            // (the script itself should use sudo; we pass it the password via stdin)
            return "echo '" + creds.getSudoPassword().replace("'", "'\\''") + "' | sudo -S " + baseCmd;
        }

        return baseCmd;
    }

    private void drainStream(InputStream in, ByteArrayOutputStream out, byte[] buf) {
        try {
            while (in.available() > 0) {
                int n = in.read(buf);
                if (n > 0) out.write(buf, 0, n);
            }
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result DTO
    // ─────────────────────────────────────────────────────────────────────────

    @lombok.Builder
    @lombok.Data
    public static class RemoteExecResult {
        private String  host;
        private boolean success;
        private int     exitCode;
        private String  stdout;
        private String  stderr;
        private String  command;
        private String  errorMessage;

        public static RemoteExecResult failure(String host, String msg) {
            return RemoteExecResult.builder()
                    .host(host)
                    .success(false)
                    .exitCode(-1)
                    .stdout("")
                    .stderr(msg)
                    .errorMessage(msg)
                    .build();
        }

        /** Convenience map for ActionExecutorAgent result handling. */
        public Map<String, Object> toResultMap() {
            return Map.of(
                    "success",  success,
                    "exitCode", exitCode,
                    "stdout",   stdout  != null ? stdout  : "",
                    "stderr",   stderr  != null ? stderr  : "",
                    "host",     host    != null ? host    : "",
                    "message",  success
                            ? "Remote script executed successfully on " + host + " (exit 0)"
                            : "Remote script failed on " + host + " (exit " + exitCode + "): "
                              + (stderr != null && !stderr.isBlank() ? stderr.trim() : errorMessage)
            );
        }
    }
}
