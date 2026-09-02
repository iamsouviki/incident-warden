package com.company.warden.controller;

import com.company.warden.config.CurrentUser;
import com.company.warden.model.ChatMessage;
import com.company.warden.model.ChatSession;
import com.company.warden.service.ChatSessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/chat/sessions")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final CurrentUser currentUser;

    public ChatSessionController(ChatSessionService chatSessionService, CurrentUser currentUser) {
        this.chatSessionService = chatSessionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ResponseEntity<List<ChatSession>> listSessions() {
        return ResponseEntity.ok(chatSessionService.listSessions(currentUser.username()));
    }

    @PostMapping
    public ResponseEntity<ChatSession> createSession(@RequestBody(required = false) Map<String, String> body) {
        String title = body != null ? body.get("title") : null;
        ChatSession session = chatSessionService.createSession(currentUser.username(), title);
        return ResponseEntity.ok(session);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getSessionDetails(@PathVariable UUID id) {
        Optional<ChatSession> sessionOpt = chatSessionService.getSession(id, currentUser.username());
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<ChatMessage> messages = chatSessionService.getSessionMessages(id);
        Map<String, Object> resp = new HashMap<>();
        resp.put("session", sessionOpt.get());
        resp.put("messages", messages);
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateSessionTitle(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }
        Optional<ChatSession> updated = chatSessionService.updateTitle(id, currentUser.username(), title);
        return updated.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable UUID id) {
        boolean deleted = chatSessionService.deleteSession(id, currentUser.username());
        return deleted
                ? ResponseEntity.ok(Map.of("message", "Session deleted successfully"))
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<?> syncOrAppendMessages(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        if (body.containsKey("messages") && body.get("messages") instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> turns = (List<Map<String, Object>>) list;
            List<ChatMessage> saved = chatSessionService.syncMessages(id, currentUser.username(), turns);
            return ResponseEntity.ok(Map.of("savedCount", saved.size(), "messages", saved));
        }

        String role = (String) body.getOrDefault("role", "user");
        String content = (String) body.getOrDefault("content", "");
        Object metadata = body.get("metadata");
        return chatSessionService
                .appendMessage(id, currentUser.username(), role, content, metadata)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
