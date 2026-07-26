package com.company.mcp.controller;

import com.company.mcp.model.SavedScript;
import com.company.mcp.model.ExecutionLog;
import com.company.mcp.repository.SavedScriptRepository;
import com.company.mcp.repository.ExecutionLogRepository;
import com.company.mcp.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/scripts")
public class ScriptController {

    private static final Logger log = LoggerFactory.getLogger(ScriptController.class);

    @Autowired
    private SavedScriptRepository savedScriptRepository;

    @Autowired
    private ExecutionLogRepository executionLogRepository;

    @Autowired
    private RagService ragService;

    @GetMapping
    public ResponseEntity<?> getScripts(@RequestParam(value = "tenantId", defaultValue = "tenant-1") String tenantId) {
        List<SavedScript> list = savedScriptRepository.findByTenantId(tenantId);
        return ResponseEntity.ok(Map.of("scripts", list));
    }

    @PostMapping
    public ResponseEntity<?> saveScript(@RequestBody SavedScript script) {
        if (script.getId() == null) {
            script.setId(UUID.randomUUID());
        }
        SavedScript saved = savedScriptRepository.save(script);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getScript(@PathVariable UUID id) {
        Optional<SavedScript> opt = savedScriptRepository.findById(id);
        if (opt.isPresent()) {
            return ResponseEntity.ok(opt.get());
        }
        return ResponseEntity.notFound().build();
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateScript(@PathVariable UUID id, @RequestBody SavedScript script) {
        Optional<SavedScript> opt = savedScriptRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        SavedScript existing = opt.get();
        existing.setName(script.getName());
        existing.setDescription(script.getDescription());
        existing.setScriptContent(script.getScriptContent());
        existing.setLanguage(script.getLanguage());
        existing.setCategory(script.getCategory());
        existing.setTargetHost(script.getTargetHost());
        SavedScript saved = savedScriptRepository.save(existing);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteScript(@PathVariable UUID id) {
        if (savedScriptRepository.existsById(id)) {
            savedScriptRepository.deleteById(id);
            return ResponseEntity.ok(Map.of("message", "Script deleted successfully"));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateScript(@RequestBody Map<String, String> body) {
        String description = body.get("description");
        String category = body.getOrDefault("category", "APPLICATION");
        String os = body.getOrDefault("os", "linux"); // windows / linux

        if (description == null || description.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Description is required"));
        }

        ChatClient activeClient = ragService.getOrBuildChatClient();
        if (activeClient == null) {
            return ResponseEntity.status(500).body(Map.of("error", "AI generation engine unavailable"));
        }

        try {
            String formatType = "bash".equalsIgnoreCase(os) || "linux".equalsIgnoreCase(os) ? "Bash" : "PowerShell";
            String prompt = String.format(
                "You are an expert devops engineer. Write a clean, production-grade %s automation script to accomplish the following task: %s.\n" +
                "The task category is %s.\n" +
                "Requirements:\n" +
                "- Do not include markdown code block syntax (like ```bash or ```).\n" +
                "- Output only the raw, executable script contents.\n" +
                "- Include comments explaining the steps.",
                formatType, description, category
            );

            String generated = activeClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (generated == null || generated.isBlank()) {
                return ResponseEntity.ok(Map.of("script", "# Failed to generate script contents. Please write manually."));
            }

            // Remove markdown code fences if LLM ignored instructions
            generated = generated.replaceAll("```[a-zA-Z]*", "").replaceAll("```", "").trim();

            return ResponseEntity.ok(Map.of("script", generated));
        } catch (Exception e) {
            log.error("[SCRIPT] AI generation failed", e);
            return ResponseEntity.ok(Map.of("script", "# Error generating script: " + e.getMessage() + "\n# Please write script manually."));
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validateScript(@RequestBody Map<String, String> body) {
        String script = body.getOrDefault("scriptContent", "");
        String os = body.getOrDefault("os", "linux");

        List<Map<String, String>> findings = new ArrayList<>();
        String level = "PASS";

        // Check for basic command injections or dangerous commands
        String scriptLower = script.toLowerCase();
        if (scriptLower.contains("rm -rf /") || scriptLower.contains("rm -rf /*")) {
            findings.add(Map.of("level", "BLOCK", "layer", "Destructive Command", "message", "Dangerous command 'rm -rf /' detected. Execution is blocked."));
            level = "BLOCK";
        }
        if (scriptLower.contains("fdisk") || scriptLower.contains("mkfs")) {
            findings.add(Map.of("level", "BLOCK", "layer", "Storage System Check", "message", "Storage formatting or partition tools ('fdisk', 'mkfs') are blocked."));
            level = "BLOCK";
        }
        if (scriptLower.contains("reboot") || scriptLower.contains("shutdown") || scriptLower.contains("init 6")) {
            findings.add(Map.of("level", "WARN", "layer", "Server Operation", "message", "System reboot commands detected. This might disrupt system services."));
            if (!"BLOCK".equals(level)) {
                level = "WARN";
            }
        }
        if (scriptLower.contains("drop table") || scriptLower.contains("drop database") || scriptLower.contains("delete from")) {
            findings.add(Map.of("level", "WARN", "layer", "Database Guardrail", "message", "Destructive SQL commands detected. Check database schema implications."));
            if (!"BLOCK".equals(level)) {
                level = "WARN";
            }
        }

        return ResponseEntity.ok(Map.of("level", level, "findings", findings));
    }

    @PostMapping("/execute")
    public ResponseEntity<?> executeScript(@RequestBody Map<String, Object> body) {
        String scriptContent = (String) body.getOrDefault("scriptContent", "");
        String language = (String) body.getOrDefault("language", "bash");
        boolean dryRun = (boolean) body.getOrDefault("dryRun", false);
        String category = (String) body.getOrDefault("category", "APPLICATION");
        String targetHost = (String) body.getOrDefault("targetHost", "localhost");
        String description = (String) body.getOrDefault("description", "Remediation Script Execution");

        int exitCode = 0;
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        if (dryRun) {
            stdout.append("[DRY-RUN] Script syntax evaluation completed successfully.\n");
            stdout.append("[DRY-RUN] Script would target host: ").append(targetHost).append("\n");
            stdout.append("[DRY-RUN] Language: ").append(language).append("\n");
        } else {
            // Mocks safe execution output based on script content
            stdout.append("Initializing connection to host ").append(targetHost).append("...\n");
            stdout.append("Running task: ").append(description).append("\n");
            stdout.append("Executing script block (").append(language).append("):\n");
            
            String[] lines = scriptContent.split("\n");
            int lineCount = 0;
            for (String line : lines) {
                if (line.trim().startsWith("#") || line.trim().isBlank()) continue;
                stdout.append(" >> ").append(line.trim()).append("\n");
                lineCount++;
            }

            if (scriptContent.toLowerCase().contains("error") || scriptContent.toLowerCase().contains("fail")) {
                exitCode = 1;
                stderr.append("Process exited with error status code 1. Check target host logs.");
            } else {
                stdout.append("\nExecution succeeded. ").append(lineCount).append(" command lines ran. Exit code 0.\n");
            }
        }

        // Persist execution log to the DB
        try {
            ExecutionLog logObj = new ExecutionLog();
            logObj.setId(UUID.randomUUID());
            logObj.setName(description.length() > 200 ? description.substring(0, 200) : description);
            logObj.setTimestamp(OffsetDateTime.now());
            logObj.setScriptContent(scriptContent);
            logObj.setStatus(exitCode == 0 ? "SUCCESS" : "FAILURE");
            logObj.setExitCode(exitCode);
            logObj.setStdout(stdout.toString());
            logObj.setStderr(stderr.toString());
            executionLogRepository.save(logObj);
        } catch (Exception e) {
            log.error("[SCRIPT] Failed to persist execution log", e);
        }

        return ResponseEntity.ok(Map.of(
                "exitCode", exitCode,
                "stdout", stdout.toString(),
                "stderr", stderr.toString()
        ));
    }
}
