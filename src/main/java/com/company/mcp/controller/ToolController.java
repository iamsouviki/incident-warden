package com.company.mcp.controller;

import com.company.mcp.model.CustomTool;
import com.company.mcp.repository.CustomToolRepository;
import com.company.mcp.service.SopLinkedAssetService;
import com.company.mcp.tool.CustomToolLoader;
import com.company.mcp.util.ApiErrorResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ToolController — REST API for MCP tool management.
 *
 * GET  /api/v1/tools           → list all enabled custom tools
 * GET  /api/v1/tools/custom    → list only DB-persisted custom tools
 * GET  /api/v1/tools/categories→ list distinct categories
 * POST /api/v1/tools           → create a new custom tool (persists + registers)
 * PUT  /api/v1/tools/{id}      → update a custom tool
 * DELETE /api/v1/tools/{id}    → disable/delete a custom tool
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class ToolController {

    private final CustomToolRepository customToolRepository;
    private final CustomToolLoader     customToolLoader;
    private final SopLinkedAssetService sopLinkedAssetService;

    // ─────────────────────────────────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────────────────────────────────

    /** All enabled custom tools. */
    @GetMapping
    public ResponseEntity<?> listAll() {
        List<Map<String, Object>> tools = customToolRepository.findByEnabledTrueOrderByCreatedAtDesc()
                .stream()
                .sorted(Comparator.comparing(CustomTool::getCategory)
                        .thenComparing(CustomTool::getName))
                .map(tool -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",             tool.getId());
                    m.put("name",           tool.getName());
                    m.put("category",       tool.getCategory());
                    m.put("description",    tool.getDescription());
                    m.put("requiredParams", tool.getRequiredParams());
                    m.put("dangerous",      tool.getDangerous());
                    m.put("enabled",        tool.getEnabled());
                    return m;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "count", tools.size(),
                "tools", tools
        ));
    }

    /** Only user-created custom tools with full DB metadata. */
    @GetMapping("/custom")
    public ResponseEntity<?> listCustom() {
        List<CustomTool> tools = customToolRepository.findByEnabledTrueOrderByCreatedAtDesc();
        return ResponseEntity.ok(Map.of("count", tools.size(), "tools", tools));
    }

    /** Distinct categories across all registered tools. */
    @GetMapping("/categories")
    public ResponseEntity<?> listCategories() {
        Set<String> categories = customToolRepository.findByEnabledTrue().stream()
                .map(CustomTool::getCategory)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(category -> !category.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
        return ResponseEntity.ok(Map.of("categories", categories));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CustomTool body,
                                    @RequestParam(defaultValue = "system") String createdBy) {
        try {
            // Normalise name
            String name = body.getName().toUpperCase().replace(' ', '_');
            if (customToolRepository.existsByNameIgnoreCase(name)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Tool with name '" + name + "' already exists"));
            }

            CustomTool tool = CustomTool.builder()
                    .name(name)
                    .category(body.getCategory().toUpperCase())
                    .description(body.getDescription())
                    .requiredParams(body.getRequiredParams() != null ? body.getRequiredParams() : List.of())
                    .dangerous(Boolean.TRUE.equals(body.getDangerous()))
                    .enabled(true)
                    .createdBy(createdBy)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            CustomTool saved = customToolRepository.save(tool);

            // Register immediately in the live registry
            customToolLoader.register(saved);

            log.info("ToolController: created custom tool '{}' by '{}'", name, createdBy);
            return ResponseEntity.ok(Map.of(
                    "id",      saved.getId(),
                    "name",    saved.getName(),
                    "message", "Tool created and registered successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to create tool", e);
            return ApiErrorResponses.badRequest();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE
    // ─────────────────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @RequestBody CustomTool body) {
        return customToolRepository.findById(id).map(existing -> {
            if (body.getDescription() != null) existing.setDescription(body.getDescription());
            if (body.getCategory() != null) existing.setCategory(body.getCategory().toUpperCase());
            if (body.getRequiredParams() != null) existing.setRequiredParams(body.getRequiredParams());
            if (body.getDangerous() != null) existing.setDangerous(body.getDangerous());
            existing.setUpdatedAt(LocalDateTime.now());

            CustomTool saved = customToolRepository.save(existing);
            // Re-register with updated definition
            customToolLoader.register(saved);

            return ResponseEntity.ok(Map.of("id", saved.getId(), "message", "Tool updated"));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE / DISABLE
    // ─────────────────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        try {
            boolean deleted = sopLinkedAssetService.deleteToolAndLinkedAssets(id);
            if (!deleted) {
                return ResponseEntity.notFound().build();
            }
            log.info("ToolController: deleted custom tool '{}'", id);
            return ResponseEntity.ok(Map.of("message", "Tool deleted successfully"));
        } catch (Exception e) {
            log.error("Failed to delete tool {}", id, e);
            return ApiErrorResponses.badRequest();
        }
    }
}
