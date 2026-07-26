package com.company.mcp.controller;

import com.company.mcp.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpSession;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingestSop(@RequestBody Map<String, String> body) {
        String title = body.get("title");
        String description = body.get("description");

        if (title == null || description == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "title and description are required"));
        }

        boolean success = ragService.ingestSop(title, description);
        if (success) {
            return ResponseEntity.ok(Map.of("message", "SOP successfully ingested and embedded."));
        } else {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to ingest. Check vector DB."));
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadSop(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "title", required = false) String title) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is empty"));
        }

        boolean success = ragService.ingestFile(file.getResource(), title);
        if (success) {
            return ResponseEntity.ok(Map.of("message", "File successfully ingested and embedded."));
        } else {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to ingest file. Check vector DB."));
        }
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, String> body, HttpSession session) {
        String question = body.get("question");

        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        String answer = ragService.askStrictSopRag(session.getId(), question);
        return ResponseEntity.ok(Map.of("answer", answer));
    }

    @GetMapping("/sops")
    public ResponseEntity<?> getAllSops() {
        return ResponseEntity.ok(ragService.getAllSops());
    }

    @PutMapping("/sops/{id}")
    public ResponseEntity<?> updateSop(@PathVariable java.util.UUID id, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        String description = body.get("description");
        if (title == null || description == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "title and description are required"));
        }
        boolean success = ragService.updateSop(id, title, description);
        if (success) {
            return ResponseEntity.ok(Map.of("message", "SOP successfully updated and re-embedded."));
        } else {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to update SOP."));
        }
    }

    @DeleteMapping("/sops/{id}")
    public ResponseEntity<?> deleteSop(@PathVariable java.util.UUID id) {
        boolean success = ragService.deleteSop(id);
        if (success) {
            return ResponseEntity.ok(Map.of("message", "SOP successfully deleted."));
        } else {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to delete SOP."));
        }
    }
}
