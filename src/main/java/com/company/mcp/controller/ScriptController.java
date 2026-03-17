package com.company.mcp.controller;

import com.company.mcp.model.ScriptWorkspace;
import com.company.mcp.model.SopScriptRequest;
import com.company.mcp.repository.ScriptWorkspaceRepository;
import com.company.mcp.service.RemoteExecutionService;
import com.company.mcp.service.ScriptGeneratorService;
import com.company.mcp.service.ScriptGuardrailValidator;
import com.company.mcp.service.ScriptGuardrailValidator.GuardrailBlockException;
import com.company.mcp.service.ScriptGuardrailValidator.ValidationResult;
import com.company.mcp.service.VaultCredentialService;
import com.company.mcp.util.ApiErrorResponses;
import com.company.mcp.model.ServerCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ScriptController — REST API for the Script Editor & Dynamic Execution feature.
 *
 * <h3>Endpoints</h3>
 * <pre>
 * POST /api/v1/scripts/generate   → generate script from description via LLM
 * POST /api/v1/scripts/validate   → validate script through 5-layer guardrails
 * POST /api/v1/scripts/execute    → execute script (with dry-run option)
 * POST /api/v1/scripts            → save script to workspace
 * GET  /api/v1/scripts            → list all saved scripts
 * GET  /api/v1/scripts/{id}       → get one script
 * PUT  /api/v1/scripts/{id}       → update script
 * DELETE /api/v1/scripts/{id}     → delete script
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/scripts")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptWorkspaceRepository repository;
    private final ScriptGeneratorService    scriptGenerator;
    private final ScriptGuardrailValidator  guardrailValidator;
    private final VaultCredentialService    vaultCredentialService;
    private final RemoteExecutionService    remoteExecutionService;

    @Value("${mcp.tools.timeout-seconds:30}")
    private int timeoutSeconds;

    // ─────────────────────────────────────────────────────────────────────────
    // GENERATE — LLM-powered script generation from natural language
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a remediation script from a description.
     *
     * Request body:
     * <pre>
     * {
     *   "description": "Restart Tomcat and verify health endpoint",
     *   "category": "APPLICATION",
     *   "targetHost": "app-server-01",
     *   "os": "linux",
     *   "allowedCommands": ["systemctl", "curl"]   // optional
     * }
     * </pre>
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Map<String, Object> body) {
        try {
            String description = getString(body, "description", "");
            String category    = getString(body, "category", "APPLICATION");
            String targetHost  = getString(body, "targetHost", "localhost");
            String os          = getString(body, "os", "linux");

            if (description.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Description is required"));
            }

            @SuppressWarnings("unchecked")
            List<String> allowedCommands = body.containsKey("allowedCommands")
                    ? (List<String>) body.get("allowedCommands") : null;

            SopScriptRequest request = SopScriptRequest.builder()
                    .sopStepDescription(description)
                    .sopCategory(category.toUpperCase())
                    .sopTitle("Script Editor — " + description.substring(0, Math.min(60, description.length())))
                    .sopId("editor-" + UUID.randomUUID().toString().substring(0, 8))
                    .targetHost(targetHost)
                    .os(os.toLowerCase())
                    .allowedCommands(allowedCommands)
                    .build();

            String script = scriptGenerator.generateFromSopStep(request);

            log.info("[ScriptEditor] Generated {} script ({} lines) for: {}",
                    os, script.lines().count(), description);

            return ResponseEntity.ok(Map.of(
                    "script",   script,
                    "language", os.equalsIgnoreCase("windows") ? "powershell" : "bash",
                    "lines",    script.lines().count(),
                    "message",  "Script generated and passed guardrail validation"
            ));

        } catch (GuardrailBlockException e) {
            return ResponseEntity.ok(Map.of(
                    "script",  "",
                    "error",   ApiErrorResponses.SIMPLE_ERROR_MESSAGE
            ));
        } catch (ScriptGeneratorService.ScriptGenerationException e) {
            return ApiErrorResponses.internalServerError();
        } catch (Exception e) {
            log.error("[ScriptEditor] Generate error", e);
            return ApiErrorResponses.internalServerError();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VALIDATE — run 5-layer guardrails on user-written/edited script
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validate a script through the guardrail pipeline.
     * Does NOT throw on BLOCK — returns findings as JSON.
     *
     * Request body:
     * <pre>
     * {
     *   "scriptContent": "#!/bin/bash\nset -e\necho 'hello'\n...",
     *   "category": "APPLICATION",
     *   "os": "linux",
     *   "description": "Restart Tomcat"   // optional — used for SOP-intent check
     * }
     * </pre>
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody Map<String, Object> body) {
        try {
            String script      = getString(body, "scriptContent", "");
            String category    = getString(body, "category", "APPLICATION");
            String os          = getString(body, "os", "linux");
            String description = getString(body, "description", "User-edited script");

            if (script.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Script content is required"));
            }

            SopScriptRequest request = SopScriptRequest.builder()
                    .sopStepDescription(description)
                    .sopCategory(category.toUpperCase())
                    .sopTitle("Script Validation")
                    .sopId("validate-" + UUID.randomUUID().toString().substring(0, 8))
                    .targetHost("localhost")
                    .os(os.toLowerCase())
                    .build();

            // Catch GuardrailBlockException and return findings instead of 500
            ValidationResult result;
            try {
                result = guardrailValidator.validate(script, request);
            } catch (GuardrailBlockException e) {
                // Parse findings from the exception message
                List<Map<String, String>> findings = parseFindingsFromMessage(e.getMessage());
                return ResponseEntity.ok(Map.of(
                        "level",    "BLOCK",
                        "passed",   false,
                        "findings", findings,
                        "summary",  "Script failed validation"
                ));
            }

            List<Map<String, String>> findingsList = result.findings().stream()
                    .map(f -> Map.of(
                            "level",   f.level().name(),
                            "layer",   f.layer(),
                            "message", f.message()))
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "level",    result.overall().name(),
                    "passed",   result.isPassed() || result.isWarning(),
                    "findings", findingsList,
                    "summary",  result.summary()
            ));

        } catch (Exception e) {
            log.error("[ScriptEditor] Validate error", e);
            return ApiErrorResponses.internalServerError();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXECUTE — run a script locally with timeout and guardrail gate
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Execute a script.  Always validates through guardrails first.
     *
     * Request body:
     * <pre>
     * {
     *   "scriptContent": "#!/bin/bash\nset -e\necho 'hello world'\n",
     *   "language": "bash",
     *   "dryRun": true,
     *   "category": "APPLICATION",
     *   "description": "Test script"
     * }
     * </pre>
     */
    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestBody Map<String, Object> body) {
        String script   = getString(body, "scriptContent", "");
        String language = getString(body, "language", "bash");
        boolean dryRun  = Boolean.TRUE.equals(body.get("dryRun"));
        String category = getString(body, "category", "APPLICATION");
        String desc     = getString(body, "description", "User script execution");
        String targetHost = getString(body, "targetHost", "localhost").trim();

        if (script.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Script content is required"));
        }

        // ── Step 1: Guardrail validation gate ────────────────────────────────
        String os = language.equalsIgnoreCase("powershell") ? "windows" : "linux";
        SopScriptRequest sopReq = SopScriptRequest.builder()
                .sopStepDescription(desc)
                .sopCategory(category.toUpperCase())
                .sopTitle("Script Execution")
                .sopId("exec-" + UUID.randomUUID().toString().substring(0, 8))
                .targetHost("localhost")
                .os(os)
                .build();

        try {
            guardrailValidator.validate(script, sopReq);
        } catch (GuardrailBlockException e) {
            return ResponseEntity.ok(Map.of(
                    "success",  false,
                    "blocked",  true,
                    "exitCode", -1,
                    "stdout",   "",
                    "stderr",   "BLOCKED by guardrails",
                    "message",  "Script execution blocked by safety guardrails"
            ));
        }

        // ── Step 2: Dry-run mode — just report what would happen ─────────────
        boolean remoteRequested = !targetHost.isBlank()
                && !targetHost.equalsIgnoreCase("localhost")
                && !targetHost.equals("127.0.0.1")
                && !targetHost.equals("::1");

        if (dryRun) {
            long lineCount = script.lines().count();
            return ResponseEntity.ok(Map.of(
                    "success",  true,
                    "dryRun",   true,
                    "remote",   remoteRequested,
                    "targetHost", targetHost,
                    "exitCode", 0,
                    "stdout",   "[DRY-RUN] Script validated and ready to execute.\n"
                              + "[DRY-RUN] Language: " + language + "\n"
                              + "[DRY-RUN] Lines: " + lineCount + "\n"
                              + "[DRY-RUN] Category: " + category + "\n"
                              + "[DRY-RUN] Mode: " + (remoteRequested ? "REMOTE (" + targetHost + ")" : "LOCAL") + "\n"
                              + "[DRY-RUN] Guardrails: PASSED",
                    "stderr",   "",
                    "message",  "Dry run completed — script is safe to execute"
            ));
        }

        // ── Step 3: Remote execution via Vault + SSH (when targetHost != localhost) ──
        if (remoteRequested) {
            try {
                ServerCredentials creds = vaultCredentialService.getCredentials(targetHost, os);
                RemoteExecutionService.RemoteExecResult result = remoteExecutionService.executeRemote(script, creds);

                return ResponseEntity.ok(Map.of(
                        "success",    result.isSuccess(),
                        "remote",     true,
                        "targetHost", targetHost,
                        "credSource", creds.getCredentialSource(),
                        "exitCode",   result.getExitCode(),
                        "stdout",     result.getStdout() != null ? result.getStdout().trim() : "",
                        "stderr",     result.getStderr() != null ? result.getStderr().trim() : "",
                        "message",    result.isSuccess()
                                ? "Remote script executed successfully on " + targetHost
                                : "Remote script failed on " + targetHost + " (exit " + result.getExitCode() + ")"
                ));
            } catch (Exception e) {
                log.error("[ScriptEditor] Remote execution error on {}: {}", targetHost, e.getMessage(), e);
                return ApiErrorResponses.internalServerError();
            }
        }

        // ── Step 4: Write to temp file and execute via ProcessBuilder (local) ────────
        Path tempScript = null;
        try {
            String suffix = language.equalsIgnoreCase("powershell") ? ".ps1" : ".sh";
            tempScript = Files.createTempFile("mcp-script-", suffix);
            Files.writeString(tempScript, script, StandardCharsets.UTF_8);

            // Make executable on Linux
            if (!language.equalsIgnoreCase("powershell")) {
                tempScript.toFile().setExecutable(true);
            }

            String[] cmd;
            if (language.equalsIgnoreCase("powershell")) {
                cmd = new String[]{"powershell", "-ExecutionPolicy", "Bypass", "-File",
                        tempScript.toAbsolutePath().toString()};
            } else {
                cmd = new String[]{"/bin/bash", tempScript.toAbsolutePath().toString()};
            }

            log.info("[ScriptEditor] Executing script: {} ({} lines)", tempScript.getFileName(),
                    script.lines().count());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            pb.environment().putAll(System.getenv());

            Process process = pb.start();

            // Read stdout + stderr concurrently
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread outThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) stdout.append(line).append("\n");
                } catch (Exception ignored) {}
            });
            Thread errThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) stderr.append(line).append("\n");
                } catch (Exception ignored) {}
            });

            outThread.start();
            errThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ResponseEntity.ok(Map.of(
                        "success",  false,
                        "exitCode", -1,
                        "stdout",   stdout.toString().trim(),
                        "stderr",   "TIMEOUT: Script did not finish within " + timeoutSeconds + " seconds",
                        "message",  "Execution timed out"
                ));
            }

            outThread.join(2000);
            errThread.join(2000);

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;

            log.info("[ScriptEditor] Script finished: exitCode={} stdout={}chars stderr={}chars",
                    exitCode, stdout.length(), stderr.length());

            return ResponseEntity.ok(Map.of(
                    "success",  success,
                    "exitCode", exitCode,
                    "stdout",   stdout.toString().trim(),
                    "stderr",   stderr.toString().trim(),
                    "message",  success ? "Script executed successfully" : "Script failed with exit code " + exitCode
            ));

        } catch (Exception e) {
            log.error("[ScriptEditor] Execution error", e);
            return ApiErrorResponses.internalServerError();
        } finally {
            // ── Cleanup temp file ────────────────────────────────────────────
            if (tempScript != null) {
                try { Files.deleteIfExists(tempScript); }
                catch (Exception ignored) {}
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD — Script Workspace persistence
    // ─────────────────────────────────────────────────────────────────────────

    /** List all saved scripts. */
    @GetMapping
    public ResponseEntity<?> listAll(@RequestParam(required = false) String tenantId) {
        List<ScriptWorkspace> scripts;
        if (tenantId != null && !tenantId.isBlank()) {
            scripts = repository.findByTenantIdOrderByUpdatedAtDesc(UUID.fromString(tenantId));
        } else {
            scripts = repository.findAllByOrderByUpdatedAtDesc();
        }
        return ResponseEntity.ok(Map.of("count", scripts.size(), "scripts", scripts));
    }

    /** Get a single script by ID. */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable UUID id) {
        return repository.findById(id)
                .map(s -> ResponseEntity.ok((Object) s))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Save a new script to the workspace. */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body,
                                  @RequestParam(defaultValue = "system") String createdBy) {
        try {
            ScriptWorkspace script = ScriptWorkspace.builder()
                    .name(getString(body, "name", "Untitled Script"))
                    .description(getString(body, "description", ""))
                    .scriptContent(getString(body, "scriptContent", ""))
                    .language(getString(body, "language", "bash"))
                    .category(getString(body, "category", "APPLICATION"))
                    .targetHost(getString(body, "targetHost", ""))
                    .toolName(getString(body, "toolName", ""))
                    .status("DRAFT")
                    .createdBy(createdBy)
                    .tenantId(body.containsKey("tenantId")
                            ? UUID.fromString((String) body.get("tenantId")) : null)
                    .sopId(body.containsKey("sopId") && body.get("sopId") instanceof String sopId && !sopId.isBlank()
                            ? UUID.fromString(sopId) : null)
                    .build();

            if (script.getScriptContent().isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Script content is required"));
            }

            ScriptWorkspace saved = repository.save(script);
            log.info("[ScriptEditor] Saved script '{}' (id={})", saved.getName(), saved.getId());

            return ResponseEntity.ok(Map.of(
                    "id",      saved.getId(),
                    "name",    saved.getName(),
                    "message", "Script saved successfully"
            ));
        } catch (Exception e) {
            log.error("[ScriptEditor] Save error", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /** Update an existing script. */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @RequestBody Map<String, Object> body) {
        return repository.findById(id).map(existing -> {
            if (body.containsKey("name"))          existing.setName(getString(body, "name", existing.getName()));
            if (body.containsKey("description"))   existing.setDescription(getString(body, "description", ""));
            if (body.containsKey("scriptContent")) existing.setScriptContent(getString(body, "scriptContent", ""));
            if (body.containsKey("language"))       existing.setLanguage(getString(body, "language", "bash"));
            if (body.containsKey("category"))       existing.setCategory(getString(body, "category", "APPLICATION"));
            if (body.containsKey("targetHost"))     existing.setTargetHost(getString(body, "targetHost", ""));
            if (body.containsKey("toolName"))       existing.setToolName(getString(body, "toolName", ""));
            if (body.containsKey("status"))         existing.setStatus(getString(body, "status", "DRAFT"));
            if (body.containsKey("sopId")) {
                Object sopId = body.get("sopId");
                existing.setSopId(sopId instanceof String value && !value.isBlank() ? UUID.fromString(value) : null);
            }

            // Persist validation/execution results if provided
            if (body.containsKey("lastValidationResult")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> valResult = (Map<String, Object>) body.get("lastValidationResult");
                existing.setLastValidationResult(valResult);
            }
            if (body.containsKey("lastExecutionOutput")) {
                existing.setLastExecutionOutput(getString(body, "lastExecutionOutput", ""));
            }
            if (body.containsKey("lastExecutionExitCode")) {
                existing.setLastExecutionExitCode((Integer) body.get("lastExecutionExitCode"));
            }

            ScriptWorkspace saved = repository.save(existing);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "message", "Script updated"));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Delete a script. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        return repository.findById(id).map(script -> {
            repository.delete(script);
            log.info("[ScriptEditor] Deleted script '{}' (id={})", script.getName(), id);
            return ResponseEntity.ok(Map.of("message", "Script deleted"));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    /**
     * Parse guardrail findings from a GuardrailBlockException message.
     * The message format is: "[LEVEL] Layer: message\n..."
     */
    private List<Map<String, String>> parseFindingsFromMessage(String message) {
        if (message == null || message.isBlank()) return List.of();
        return message.lines()
                .filter(l -> l.startsWith("["))
                .map(line -> {
                    // [BLOCK] L2:Blocklist: Forbidden pattern detected...
                    String level = "BLOCK";
                    String layer = "";
                    String msg = line;
                    try {
                        int closeBracket = line.indexOf(']');
                        if (closeBracket > 1) {
                            level = line.substring(1, closeBracket);
                            String rest = line.substring(closeBracket + 2).trim();
                            int colonPos = rest.indexOf(':');
                            if (colonPos > 0) {
                                layer = rest.substring(0, colonPos).trim();
                                msg = rest.substring(colonPos + 1).trim();
                            }
                        }
                    } catch (Exception ignored) {}
                    return Map.of("level", level, "layer", layer, "message", msg);
                })
                .collect(Collectors.toList());
    }
}
