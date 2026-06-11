package com.company.mcp.controller;

import com.company.mcp.service.AiConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai/config")
public class AiConfigController {

    private final AiConfigService aiConfigService;

    public AiConfigController(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    @GetMapping
    public ResponseEntity<?> getConfig() {
        return ResponseEntity.ok(Map.of(
                "chatModel", aiConfigService.getActiveChatModel(),
                "embeddingModel", aiConfigService.getActiveEmbeddingModel()
        ));
    }

    @PostMapping
    public ResponseEntity<?> setConfig(@RequestBody Map<String, String> body) {
        String chatModel = body.get("chatModel");
        String embeddingModel = body.get("embeddingModel");
        if (chatModel == null || chatModel.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "chatModel is required"));
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "embeddingModel is required"));
        }
        aiConfigService.setActiveChatModel(chatModel);
        aiConfigService.setActiveEmbeddingModel(embeddingModel);
        return ResponseEntity.ok(Map.of(
                "message", "AI Configuration updated successfully",
                "chatModel", chatModel,
                "embeddingModel", embeddingModel
        ));
    }
}
