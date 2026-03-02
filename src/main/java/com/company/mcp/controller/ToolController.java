package com.company.mcp.controller;

import com.company.mcp.model.CustomTool;
import com.company.mcp.repository.CustomToolRepository;
import com.company.mcp.tool.CustomToolLoader;
import com.company.mcp.tool.McpToolRegistry;
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
 * GET  /api/v1/tools           → list all registered tools (built-in + custom)
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

    private final McpToolRegistry      registry;
    private final CustomToolRepository customToolRepository;
    private final CustomToolLoader     customToolLoader;

    // ─────────────────────────────────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────────────────────────────────

    /** All registered tools (built-in + custom), grouped by category. */
    @GetMapping
    public ResponseEntity<?> listAll() {
        Collection<McpToolRegistry.ToolDefinition> defs = registry.allDefinitions();

        // Enrich with "isCustom" flag from DB names
        Set<String> customNames = customToolRepository.findByEnabledTrue()
                .stream()
                .map(t -> t.getName().toUpperCase().replace(' ', '_'))
                .collect(Collectors.toSet());

        List<Map<String, Object>> tools = defs.stream()
                .sorted(Comparator.comparing(McpToolRegistry.ToolDefinition::category)
                        .thenComparing(McpToolRegistry.ToolDefinition::name))
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name",           d.name());
                    m.put("category",       d.category());
                    m.put("description",    d.description());
                    m.put("requiredParams", d.requiredParams());
                    m.put("dangerous",      d.dangerous());
                    m.put("custom",         customNames.contains(d.name()));
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
        List<CustomTool> tools = customToolRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(CustomTool::getCreatedAt).reversed())
                .toList();
        return ResponseEntity.ok(Map.of("count", tools.size(), "tools", tools));
    }

    /** Distinct categories across all registered tools. */
    @GetMapping("/categories")
    public ResponseEntity<?> listCategories() {
        Set<String> builtIn = registry.allDefinitions().stream()
                .map(McpToolRegistry.ToolDefinition::category)
                .collect(Collectors.toCollection(TreeSet::new));
        return ResponseEntity.ok(builtIn);
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
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
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
        return customToolRepository.findById(id).map(tool -> {
            tool.setEnabled(false);
            tool.setUpdatedAt(LocalDateTime.now());
            customToolRepository.save(tool);
            registry.unregister(tool.getName());
            log.info("ToolController: disabled custom tool '{}'", tool.getName());
            return ResponseEntity.ok(Map.of("message", "Tool disabled successfully"));
        }).orElse(ResponseEntity.notFound().build());
    }
}
