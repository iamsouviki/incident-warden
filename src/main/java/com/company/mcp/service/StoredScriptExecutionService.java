package com.company.mcp.service;

import com.company.mcp.model.ScriptWorkspace;
import com.company.mcp.model.ServerCredentials;
import com.company.mcp.model.SopScriptRequest;
import com.company.mcp.service.ScriptGuardrailValidator.GuardrailBlockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Executes persisted Script Workspace content so linked custom MCP tools can
 * run the same validated script body that operators review in the UI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoredScriptExecutionService {

    private final ScriptGuardrailValidator guardrailValidator;
    private final VaultCredentialService vaultCredentialService;
    private final RemoteExecutionService remoteExecutionService;

    @Value("${mcp.tools.timeout-seconds:30}")
    private int timeoutSeconds;

    public record ScriptRunResult(
            boolean success,
            boolean blocked,
            boolean dryRun,
            boolean remote,
            String targetHost,
            String credentialSource,
            int exitCode,
            String stdout,
            String stderr,
            String message
    ) {}

    public ScriptRunResult execute(ScriptWorkspace workspace, boolean dryRun) {
        String script = safe(workspace.getScriptContent());
        String language = defaultIfBlank(workspace.getLanguage(), "bash");
        String category = defaultIfBlank(workspace.getCategory(), "APPLICATION");
        String description = defaultIfBlank(workspace.getDescription(), workspace.getName());
        String targetHost = defaultIfBlank(workspace.getTargetHost(), "localhost");
        String os = isPowerShell(language) ? "windows" : "linux";

        if (script.isBlank()) {
            return new ScriptRunResult(false, false, dryRun, false, targetHost, null, -1,
                    "", "Stored script is empty", "Stored script is empty");
        }

        SopScriptRequest request = SopScriptRequest.builder()
                .sopStepDescription(description)
                .sopCategory(category.toUpperCase())
                .sopTitle(defaultIfBlank(workspace.getName(), "Stored Script"))
                .sopId(workspace.getSopId() != null ? workspace.getSopId().toString() : "script-" + workspace.getId())
                .targetHost(targetHost)
                .os(os)
                .build();

        try {
            guardrailValidator.validate(script, request);
        } catch (GuardrailBlockException e) {
            return new ScriptRunResult(false, true, dryRun, false, targetHost, null, -1,
                    "", e.getMessage(), "Stored script blocked by guardrails");
        } catch (Exception e) {
            return new ScriptRunResult(false, false, dryRun, false, targetHost, null, -1,
                    "", e.getMessage(), "Stored script validation failed");
        }

        boolean remoteRequested = isRemoteHost(targetHost);
        if (dryRun) {
            return new ScriptRunResult(true, false, true, remoteRequested, targetHost, null, 0,
                    "[DRY-RUN] Stored script validated and ready for execution",
                    "",
                    "Dry run completed");
        }

        if (remoteRequested) {
            return executeRemote(script, os, targetHost);
        }

        return executeLocal(script, language, targetHost);
    }

    private ScriptRunResult executeRemote(String script, String os, String targetHost) {
        try {
            ServerCredentials creds = vaultCredentialService.getCredentials(targetHost, os);
            RemoteExecutionService.RemoteExecResult result =
                    remoteExecutionService.executeRemote(script, creds);

            return new ScriptRunResult(
                    result.isSuccess(),
                    false,
                    false,
                    true,
                    targetHost,
                    creds.getCredentialSource(),
                    result.getExitCode(),
                    safe(result.getStdout()).trim(),
                    safe(result.getStderr()).trim(),
                    result.isSuccess()
                            ? "Remote script executed successfully"
                            : "Remote script failed"
            );
        } catch (Exception e) {
            log.error("[StoredScriptExec] Remote execution failed for {}", targetHost, e);
            return new ScriptRunResult(false, false, false, true, targetHost, null, -1,
                    "", e.getMessage(), "Remote execution failed");
        }
    }

    private ScriptRunResult executeLocal(String script, String language, String targetHost) {
        Path tempScript = null;
        try {
            String suffix = isPowerShell(language) ? ".ps1" : ".sh";
            tempScript = Files.createTempFile("mcp-linked-script-", suffix);
            Files.writeString(tempScript, script, StandardCharsets.UTF_8);

            if (!isPowerShell(language)) {
                tempScript.toFile().setExecutable(true);
            }

            String[] cmd = isPowerShell(language)
                    ? new String[]{"powershell", "-ExecutionPolicy", "Bypass", "-File",
                        tempScript.toAbsolutePath().toString()}
                    : new String[]{"/bin/bash", tempScript.toAbsolutePath().toString()};

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            pb.environment().putAll(System.getenv());

            Process process = pb.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread outThread = new Thread(() -> readStream(process.getInputStream(), stdout));
            Thread errThread = new Thread(() -> readStream(process.getErrorStream(), stderr));
            outThread.start();
            errThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ScriptRunResult(false, false, false, false, targetHost, null, -1,
                        stdout.toString().trim(),
                        "TIMEOUT: Script did not finish within " + timeoutSeconds + " seconds",
                        "Execution timed out");
            }

            outThread.join(2000);
            errThread.join(2000);

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;
            return new ScriptRunResult(success, false, false, false, targetHost, null, exitCode,
                    stdout.toString().trim(),
                    stderr.toString().trim(),
                    success ? "Script executed successfully" : "Script failed with exit code " + exitCode);
        } catch (Exception e) {
            log.error("[StoredScriptExec] Local execution failed", e);
            return new ScriptRunResult(false, false, false, false, targetHost, null, -1,
                    "", e.getMessage(), "Execution failed");
        } finally {
            if (tempScript != null) {
                try {
                    Files.deleteIfExists(tempScript);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void readStream(java.io.InputStream inputStream, StringBuilder target) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                target.append(line).append("\n");
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isRemoteHost(String targetHost) {
        String host = defaultIfBlank(targetHost, "localhost").trim();
        return !host.isBlank()
                && !Objects.equals(host, "localhost")
                && !Objects.equals(host, "127.0.0.1")
                && !Objects.equals(host, "::1");
    }

    private static boolean isPowerShell(String language) {
        return defaultIfBlank(language, "").equalsIgnoreCase("powershell");
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
