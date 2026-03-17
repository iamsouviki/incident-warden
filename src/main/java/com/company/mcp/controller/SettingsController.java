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
            return ResponseEntity.ok(settings);
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

            TenantSettings settings = tenantSettingsRepository.findById(tid)
                    .orElse(TenantSettings.builder().tenantId(tid).build());
            settings.setIncidentSources(incidentSources);
            settings.setLlmProviders(llmProviders);
            settings.setIncidentDefaults(incidentDefaults);

            TenantSettings saved = tenantSettingsRepository.save(settings);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("Failed to save tenant settings", e);
            return ApiErrorResponses.badRequest();
        }
    }
}
