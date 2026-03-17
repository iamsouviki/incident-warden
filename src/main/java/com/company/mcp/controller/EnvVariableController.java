package com.company.mcp.controller;

import com.company.mcp.model.TenantSettings;
import com.company.mcp.repository.TenantRepository;
import com.company.mcp.repository.TenantSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/settings/env-vars")
@RequiredArgsConstructor
public class EnvVariableController {

    private final TenantSettingsRepository tenantSettingsRepository;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String tenantId) {
        TenantSettings settings = getSettings(tenantId);
        return ResponseEntity.ok(Map.of(
                "count", settings.getEnvVariables().size(),
                "envVariables", settings.getEnvVariables().stream().map(EnvVariableController::sanitize).toList()
        ));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestParam String tenantId,
                                    @RequestBody Map<String, Object> body) {
        TenantSettings settings = getSettings(tenantId);
        List<Map<String, Object>> envVariables = new ArrayList<>(settings.getEnvVariables());
        envVariables.add(toStoredEnvVar(body));
        settings.setEnvVariables(envVariables);
        tenantSettingsRepository.save(settings);
        return ResponseEntity.ok(Map.of(
                "envVariables", envVariables.stream().map(EnvVariableController::sanitize).toList(),
                "count", envVariables.size()
        ));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                    @RequestParam String tenantId,
                                    @RequestBody Map<String, Object> body) {
        TenantSettings settings = getSettings(tenantId);
        List<Map<String, Object>> envVariables = new ArrayList<>();
        for (Map<String, Object> envVar : settings.getEnvVariables()) {
            if (id.equals(String.valueOf(envVar.get("id")))) {
                Map<String, Object> updated = new LinkedHashMap<>(envVar);
                updated.putAll(toStoredEnvVar(body));
                updated.put("id", id);
                updated.put("updatedAt", LocalDateTime.now().toString());
                envVariables.add(updated);
            } else {
                envVariables.add(envVar);
            }
        }
        settings.setEnvVariables(envVariables);
        tenantSettingsRepository.save(settings);
        return ResponseEntity.ok(Map.of(
                "envVariables", envVariables.stream().map(EnvVariableController::sanitize).toList(),
                "count", envVariables.size()
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id,
                                    @RequestParam String tenantId) {
        TenantSettings settings = getSettings(tenantId);
        List<Map<String, Object>> envVariables = settings.getEnvVariables().stream()
                .filter(envVar -> !id.equals(String.valueOf(envVar.get("id"))))
                .toList();
        settings.setEnvVariables(envVariables);
        tenantSettingsRepository.save(settings);
        return ResponseEntity.ok(Map.of(
                "envVariables", envVariables.stream().map(EnvVariableController::sanitize).toList(),
                "count", envVariables.size()
        ));
    }

    private TenantSettings getSettings(String tenantId) {
        UUID tid = UUID.fromString(tenantId);
        if (!tenantRepository.existsById(tid)) {
            throw new IllegalArgumentException("Unknown tenant");
        }
        return tenantSettingsRepository.findById(tid)
                .orElse(TenantSettings.builder().tenantId(tid).build());
    }

    private static Map<String, Object> toStoredEnvVar(Map<String, Object> body) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("id", body.getOrDefault("id", UUID.randomUUID().toString()));
        stored.put("key", String.valueOf(body.getOrDefault("key", "")).trim());
        stored.put("value", String.valueOf(body.getOrDefault("value", "")));
        stored.put("secret", Boolean.parseBoolean(String.valueOf(body.getOrDefault("secret", true))));
        stored.put("scope", String.valueOf(body.getOrDefault("scope", "TENANT")));
        stored.put("targetEnvironment", String.valueOf(body.getOrDefault("targetEnvironment", "default")));
        stored.put("description", String.valueOf(body.getOrDefault("description", "")));
        stored.put("createdAt", body.getOrDefault("createdAt", LocalDateTime.now().toString()));
        stored.put("updatedAt", LocalDateTime.now().toString());
        stored.put("maskedValue", mask(String.valueOf(body.getOrDefault("value", ""))));
        return stored;
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private static Map<String, Object> sanitize(Map<String, Object> envVar) {
        Map<String, Object> sanitized = new LinkedHashMap<>(envVar);
        sanitized.put("value", "");
        return sanitized;
    }
}
