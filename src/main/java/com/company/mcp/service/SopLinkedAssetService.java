package com.company.mcp.service;

import com.company.mcp.model.CustomTool;
import com.company.mcp.model.ScriptWorkspace;
import com.company.mcp.model.SopProcedure;
import com.company.mcp.repository.ConfidenceLogRepository;
import com.company.mcp.repository.CustomToolRepository;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.repository.ScriptWorkspaceRepository;
import com.company.mcp.repository.SopProcedureRepository;
import com.company.mcp.tool.CustomToolLoader;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists the script/tool assets produced during SOP authoring and stores the
 * linkage back on the SOP row for later inspection and execution.
 */
@Service
@RequiredArgsConstructor
public class SopLinkedAssetService {

    private static final int TOOL_NAME_MAX_LENGTH = 100;
    private static final int SCRIPT_NAME_MAX_LENGTH = 200;

    private final SopProcedureRepository sopRepository;
    private final ScriptWorkspaceRepository scriptWorkspaceRepository;
    private final CustomToolRepository customToolRepository;
    private final IncidentRepository incidentRepository;
    private final ConfidenceLogRepository confidenceLogRepository;
    private final JdbcTemplate jdbcTemplate;
    private final CustomToolLoader customToolLoader;

    @Transactional
    public SopProcedure saveWithLinkedAssets(SopProcedure sop, String mcpToolScript, String createdBy) {
        SopProcedure saved = sopRepository.save(sop);
        if (mcpToolScript == null || mcpToolScript.isBlank()) {
            return saved;
        }

        ScriptWorkspace scriptWorkspace = upsertScriptWorkspace(saved, mcpToolScript, createdBy);
        CustomTool customTool = upsertCustomTool(saved, scriptWorkspace, createdBy);

        if (!customTool.getName().equals(scriptWorkspace.getToolName())) {
            scriptWorkspace.setToolName(customTool.getName());
            scriptWorkspace.setUpdatedAt(LocalDateTime.now());
            scriptWorkspace = scriptWorkspaceRepository.save(scriptWorkspace);
        }

        saved.setLinkedScriptId(scriptWorkspace.getId());
        saved.setLinkedScriptName(scriptWorkspace.getName());
        saved.setLinkedToolId(customTool.getId());
        saved.setLinkedToolName(customTool.getName());
        saved.setUpdatedAt(LocalDateTime.now());

        SopProcedure linked = sopRepository.save(saved);
        customToolLoader.register(customTool);
        return linked;
    }

    @Transactional
    public boolean deleteSopAndLinkedAssets(UUID sopId) {
        Optional<SopProcedure> existing = sopRepository.findById(sopId);
        if (existing.isEmpty()) {
            return false;
        }

        SopProcedure sop = existing.get();
        UUID linkedToolId = sop.getLinkedToolId();
        UUID linkedScriptId = sop.getLinkedScriptId();

        removeSopReferences(sopId);

        if (linkedToolId != null || linkedScriptId != null) {
            sop.setLinkedToolId(null);
            sop.setLinkedToolName(null);
            sop.setLinkedScriptId(null);
            sop.setLinkedScriptName(null);
            sop.setUpdatedAt(LocalDateTime.now());
            sopRepository.save(sop);
        }

        Set<UUID> deletedScriptIds = new HashSet<>();
        for (CustomTool tool : customToolRepository.findBySopId(sopId)) {
            if (tool.getScriptWorkspaceId() != null) {
                deletedScriptIds.add(tool.getScriptWorkspaceId());
            }
            deleteToolInternal(tool, true);
        }

        for (ScriptWorkspace scriptWorkspace : scriptWorkspaceRepository.findBySopId(sopId)) {
            if (!deletedScriptIds.contains(scriptWorkspace.getId())) {
                deleteScriptWorkspaceIfPresent(scriptWorkspace.getId());
            }
        }

        if (linkedToolId != null && customToolRepository.existsById(linkedToolId)) {
            customToolRepository.findById(linkedToolId)
                    .ifPresent(tool -> deleteToolInternal(tool, true));
        } else if (linkedScriptId != null && scriptWorkspaceRepository.existsById(linkedScriptId)) {
            deleteScriptWorkspaceIfPresent(linkedScriptId);
        }

        sopRepository.delete(sop);
        return true;
    }

    @Transactional
    public boolean deleteToolAndLinkedAssets(UUID toolId) {
        return customToolRepository.findById(toolId)
                .map(tool -> {
                    deleteToolInternal(tool, false);
                    return true;
                })
                .orElse(false);
    }

    private ScriptWorkspace upsertScriptWorkspace(SopProcedure sop, String scriptContent, String createdBy) {
        ScriptWorkspace scriptWorkspace = sop.getLinkedScriptId() != null
                ? scriptWorkspaceRepository.findById(sop.getLinkedScriptId()).orElseGet(ScriptWorkspace::new)
                : new ScriptWorkspace();

        String scriptName = sop.getLinkedScriptName() != null && !sop.getLinkedScriptName().isBlank()
                ? sop.getLinkedScriptName()
                : truncate(sop.getTitle() + " MCP Script", SCRIPT_NAME_MAX_LENGTH);

        scriptWorkspace.setTenantId(sop.getTenantId());
        scriptWorkspace.setName(scriptName);
        scriptWorkspace.setDescription("Generated script linked to SOP '" + sop.getTitle() + "'");
        scriptWorkspace.setScriptContent(scriptContent.strip());
        scriptWorkspace.setLanguage(detectLanguage(scriptContent));
        scriptWorkspace.setCategory(normalizeCategory(sop.getCategory()));
        scriptWorkspace.setTargetHost(scriptWorkspace.getTargetHost() != null
                ? scriptWorkspace.getTargetHost()
                : "");
        scriptWorkspace.setSopId(sop.getId());
        scriptWorkspace.setStatus("VALIDATED");
        scriptWorkspace.setCreatedBy(scriptWorkspace.getCreatedBy() != null
                ? scriptWorkspace.getCreatedBy()
                : createdBy);
        if (scriptWorkspace.getCreatedAt() == null) {
            scriptWorkspace.setCreatedAt(LocalDateTime.now());
        }
        scriptWorkspace.setUpdatedAt(LocalDateTime.now());
        return scriptWorkspaceRepository.save(scriptWorkspace);
    }

    private CustomTool upsertCustomTool(SopProcedure sop, ScriptWorkspace scriptWorkspace, String createdBy) {
        CustomTool customTool = sop.getLinkedToolId() != null
                ? customToolRepository.findById(sop.getLinkedToolId()).orElseGet(CustomTool::new)
                : new CustomTool();

        String toolName = customTool.getName() != null && !customTool.getName().isBlank()
                ? customTool.getName()
                : buildToolName(sop);

        customTool.setName(toolName);
        customTool.setCategory(normalizeCategory(sop.getCategory()));
        customTool.setDescription("Script-backed MCP tool linked to SOP '" + sop.getTitle() + "'");
        customTool.setRequiredParams(List.of());
        customTool.setDangerous(Boolean.TRUE);
        customTool.setEnabled(Boolean.TRUE);
        customTool.setScriptWorkspaceId(scriptWorkspace.getId());
        customTool.setSopId(sop.getId());
        customTool.setCreatedBy(customTool.getCreatedBy() != null ? customTool.getCreatedBy() : createdBy);
        if (customTool.getCreatedAt() == null) {
            customTool.setCreatedAt(LocalDateTime.now());
        }
        customTool.setUpdatedAt(LocalDateTime.now());
        return customToolRepository.save(customTool);
    }

    private void deleteToolInternal(CustomTool tool, boolean deletingParentSop) {
        UUID toolId = tool.getId();
        UUID sopId = tool.getSopId();
        UUID scriptId = tool.getScriptWorkspaceId();

        if (!deletingParentSop && sopId != null) {
            clearSopLinks(sopId, toolId, scriptId);
        }
        if (tool.getName() != null && !tool.getName().isBlank()) {
            customToolLoader.unregister(tool.getName());
        }
        customToolRepository.delete(tool);
        if (scriptId != null) {
            deleteScriptWorkspaceIfPresent(scriptId);
        }
    }

    private void deleteScriptWorkspaceIfPresent(UUID scriptId) {
        scriptWorkspaceRepository.findById(scriptId).ifPresent(scriptWorkspaceRepository::delete);
    }

    private void clearSopLinks(UUID sopId, UUID toolId, UUID scriptId) {
        sopRepository.findById(sopId).ifPresent(sop -> {
            boolean changed = false;

            if (toolId != null && toolId.equals(sop.getLinkedToolId())) {
                sop.setLinkedToolId(null);
                sop.setLinkedToolName(null);
                changed = true;
            }
            if (scriptId != null && scriptId.equals(sop.getLinkedScriptId())) {
                sop.setLinkedScriptId(null);
                sop.setLinkedScriptName(null);
                changed = true;
            }

            if (changed) {
                sop.setUpdatedAt(LocalDateTime.now());
                sopRepository.save(sop);
            }
        });
    }

    private void removeSopReferences(UUID sopId) {
        incidentRepository.clearMatchedSop(sopId);
        jdbcTemplate.update("""
                UPDATE hitl_requests
                SET confidence_log_id = NULL
                WHERE confidence_log_id IN (
                    SELECT id FROM confidence_logs WHERE sop_id = ?
                )
                """, sopId);
        confidenceLogRepository.deleteBySopId(sopId);
        jdbcTemplate.update("DELETE FROM pattern_sop_links WHERE sop_id = ?", sopId);
        jdbcTemplate.update("UPDATE resolved_incident_kb SET matched_sop_id = NULL WHERE matched_sop_id = ?", sopId);
    }

    private String buildToolName(SopProcedure sop) {
        String base = sop.getTitle() == null ? "SOP_TOOL" : sop.getTitle()
                .toUpperCase()
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isBlank()) {
            base = "SOP_TOOL";
        }

        String suffix = sop.getId().toString().replace("-", "").substring(0, 8).toUpperCase();
        int maxBaseLength = TOOL_NAME_MAX_LENGTH - suffix.length() - 1;
        if (base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength);
        }
        return base + "_" + suffix;
    }

    private static String normalizeCategory(String category) {
        return category == null || category.isBlank() ? "APPLICATION" : category.toUpperCase();
    }

    private static String detectLanguage(String scriptContent) {
        String script = scriptContent == null ? "" : scriptContent;
        String lower = script.toLowerCase();
        if (lower.contains("$erroractionpreference")
                || lower.contains("write-host")
                || lower.contains("start-service")
                || lower.contains("stop-service")) {
            return "powershell";
        }
        return "bash";
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
