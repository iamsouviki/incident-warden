package com.company.mcp.controller;

import com.company.mcp.model.TenantSettings;
import com.company.mcp.repository.TenantRepository;
import com.company.mcp.repository.TenantSettingsRepository;
import com.company.mcp.util.ApiErrorResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final TenantSettingsRepository tenantSettingsRepository;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<?> getSettings(@RequestParam String tenantId) {
        try {
            UUID tid = UUID.fromString(tenantId);
            TenantSettings settings = tenantSettingsRepository.findById(tid)
                    .orElse(TenantSettings.builder().tenantId(tid).build());
            return ResponseEntity.ok(Map.of(
                    "tenantId", settings.getTenantId(),
                    "incidentSources", settings.getIncidentSources(),
                    "llmProviders", settings.getLlmProviders(),
                    "incidentDefaults", settings.getIncidentDefaults(),
                    "envVariables", sanitizeEnvVariables(settings.getEnvVariables()),
                    "updatedAt", settings.getUpdatedAt()
            ));
        } catch (Exception e) {
            log.error("Failed to load tenant settings", e);
            return ApiErrorResponses.badRequest();
        }
    }

    @PutMapping
    public ResponseEntity<?> saveSettings(@RequestParam String tenantId,
                                          @RequestBody Map<String, Object> body) {
        try {
            UUID tid = UUID.fromString(tenantId);
            if (!tenantRepository.existsById(tid)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown tenant"));
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> incidentSources = body.get("incidentSources") instanceof List<?> list
                    ? (List<Map<String, Object>>) list : List.of();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> llmProviders = body.get("llmProviders") instanceof List<?> list
                    ? (List<Map<String, Object>>) list : List.of();
            @SuppressWarnings("unchecked")
            Map<String, Object> incidentDefaults = body.get("incidentDefaults") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> envVariables = body.get("envVariables") instanceof List<?> list
                    ? (List<Map<String, Object>>) list : List.of();

            TenantSettings settings = tenantSettingsRepository.findById(tid)
                    .orElse(TenantSettings.builder().tenantId(tid).build());
            settings.setIncidentSources(incidentSources);
            settings.setLlmProviders(llmProviders);
            settings.setIncidentDefaults(incidentDefaults);
            settings.setEnvVariables(mergeEnvVariables(settings.getEnvVariables(), envVariables));

            TenantSettings saved = tenantSettingsRepository.save(settings);
            return ResponseEntity.ok(Map.of(
                    "tenantId", saved.getTenantId(),
                    "incidentSources", saved.getIncidentSources(),
                    "llmProviders", saved.getLlmProviders(),
                    "incidentDefaults", saved.getIncidentDefaults(),
                    "envVariables", sanitizeEnvVariables(saved.getEnvVariables()),
                    "updatedAt", saved.getUpdatedAt()
            ));
        } catch (Exception e) {
            log.error("Failed to save tenant settings", e);
            return ApiErrorResponses.badRequest();
        }
    }

    private static List<Map<String, Object>> sanitizeEnvVariables(List<Map<String, Object>> envVariables) {
        return envVariables.stream().map(SettingsController::sanitizeEnvVariable).toList();
    }

    private static Map<String, Object> sanitizeEnvVariable(Map<String, Object> envVar) {
        Map<String, Object> sanitized = new LinkedHashMap<>(envVar);
        sanitized.put("value", "");
        return sanitized;
    }

    private static List<Map<String, Object>> mergeEnvVariables(List<Map<String, Object>> existing,
                                                               List<Map<String, Object>> incoming) {
        Map<String, Map<String, Object>> existingById = new java.util.HashMap<>();
        for (Map<String, Object> item : existing) {
            existingById.put(String.valueOf(item.get("id")), item);
        }

        List<Map<String, Object>> merged = new java.util.ArrayList<>();
        for (Map<String, Object> item : incoming) {
            Map<String, Object> next = new LinkedHashMap<>(item);
            String id = String.valueOf(item.get("id"));
            if ((next.get("value") == null || String.valueOf(next.get("value")).isBlank()) && existingById.containsKey(id)) {
                next.put("value", existingById.get(id).get("value"));
            }
            if (!next.containsKey("maskedValue")) {
                String value = String.valueOf(next.getOrDefault("value", ""));
                next.put("maskedValue", value.isBlank() ? "" : value.substring(0, Math.min(2, value.length())) + "****");
            }
            merged.add(next);
        }
        return merged;
    }
}
