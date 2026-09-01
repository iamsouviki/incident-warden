package com.company.mcp.service;

import com.company.mcp.model.ChatMessage;
import com.company.mcp.model.ChatSession;
import com.company.mcp.repository.ChatMessageRepository;
import com.company.mcp.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public ChatSessionService(ChatSessionRepository sessionRepository,
                              ChatMessageRepository messageRepository,
                              ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    public List<ChatSession> listSessions(String tenantId, String username) {
        return sessionRepository.findByTenantIdAndUsernameAndIsArchivedFalseOrderByUpdatedAtDesc(tenantId, username);
    }

    public Optional<ChatSession> getSession(UUID sessionId, String tenantId, String username) {
        return sessionRepository.findByIdAndTenantIdAndUsername(sessionId, tenantId, username);
    }

    public List<ChatMessage> getSessionMessages(UUID sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Transactional
    public ChatSession createSession(String tenantId, String username, String title) {
        ChatSession session = new ChatSession();
        session.setTenantId(tenantId != null ? tenantId : "tenant-1");
        session.setUsername(username != null ? username : "anonymous");
        session.setTitle(title != null && !title.isBlank() ? title.trim() : "New Conversation");
        session.setCreatedAt(OffsetDateTime.now());
        session.setUpdatedAt(OffsetDateTime.now());
        session.setArchived(false);
        return sessionRepository.save(session);
    }

    @Transactional
    public Optional<ChatSession> updateTitle(UUID sessionId, String tenantId, String username, String newTitle) {
        Optional<ChatSession> opt = sessionRepository.findByIdAndTenantIdAndUsername(sessionId, tenantId, username);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        ChatSession session = opt.get();
        session.setTitle(newTitle != null && !newTitle.isBlank() ? newTitle.trim() : "New Conversation");
        session.setUpdatedAt(OffsetDateTime.now());
        return Optional.of(sessionRepository.save(session));
    }

    @Transactional
    public boolean deleteSession(UUID sessionId, String tenantId, String username) {
        Optional<ChatSession> opt = sessionRepository.findByIdAndTenantIdAndUsername(sessionId, tenantId, username);
        if (opt.isEmpty()) {
            return false;
        }
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(opt.get());
        return true;
    }

    @Transactional
    public ChatMessage appendMessage(UUID sessionId, String role, String content, Object metadata) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role != null ? role : "user");
        msg.setContent(content != null ? content : "");
        if (metadata != null) {
            try {
                if (metadata instanceof String s) {
                    msg.setMetadata(s);
                } else {
                    msg.setMetadata(objectMapper.writeValueAsString(metadata));
                }
            } catch (Exception e) {
                log.warn("Failed to serialize message metadata: {}", e.getMessage());
            }
        }
        msg.setCreatedAt(OffsetDateTime.now());
        ChatMessage saved = messageRepository.save(msg);

        // Touch session updated_at
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setUpdatedAt(OffsetDateTime.now());
            // If default title, optionally update from first user prompt
            if ("New Conversation".equalsIgnoreCase(session.getTitle()) && "user".equalsIgnoreCase(role) && !content.isBlank()) {
                String candidate = content.trim().replaceAll("\\s+", " ");
                if (candidate.length() > 60) {
                    candidate = candidate.substring(0, 57) + "...";
                }
                session.setTitle(candidate);
            }
            sessionRepository.save(session);
        });

        return saved;
    }

    @Transactional
    public List<ChatMessage> syncMessages(UUID sessionId, String tenantId, String username, List<Map<String, Object>> turns) {
        Optional<ChatSession> opt = sessionRepository.findByIdAndTenantIdAndUsername(sessionId, tenantId, username);
        if (opt.isEmpty()) {
            return List.of();
        }
        messageRepository.deleteBySessionId(sessionId);
        List<ChatMessage> savedList = new ArrayList<>();
        for (Map<String, Object> turn : turns) {
            String role = String.valueOf(turn.getOrDefault("role", "user"));
            String content = String.valueOf(turn.getOrDefault("content", turn.getOrDefault("text", "")));
            Object meta = turn.get("metadata");
            if (meta == null && turn.containsKey("stats")) meta = Map.of("stats", turn.get("stats"));
            if (content.isBlank() && meta == null) continue;

            ChatMessage msg = new ChatMessage();
            msg.setSessionId(sessionId);
            msg.setRole(role);
            msg.setContent(content);
            if (meta != null) {
                try {
                    msg.setMetadata(objectMapper.writeValueAsString(meta));
                } catch (Exception ignored) {}
            }
            msg.setCreatedAt(OffsetDateTime.now());
            savedList.add(messageRepository.save(msg));
        }

        ChatSession s = opt.get();
        s.setUpdatedAt(OffsetDateTime.now());
        sessionRepository.save(s);
        return savedList;
    }
}
