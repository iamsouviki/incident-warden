package com.company.warden.service;

import com.company.warden.model.ChatMessage;
import com.company.warden.model.ChatSession;
import com.company.warden.repository.ChatMessageRepository;
import com.company.warden.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);
    public static final int MAX_TTL_DAYS = 30;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final int sessionTtlDays;

    public ChatSessionService(ChatSessionRepository sessionRepository,
                              ChatMessageRepository messageRepository,
                              ObjectMapper objectMapper,
                              @Value("${mcp.chat.session-ttl-days:30}") int sessionTtlDays) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        // Max session TTL is 30 days
        this.sessionTtlDays = Math.min(Math.max(sessionTtlDays, 1), MAX_TTL_DAYS);
    }

    public List<ChatSession> listSessions(String username) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(sessionTtlDays);
        return sessionRepository.findByUsernameAndIsArchivedFalseAndUpdatedAtAfterOrderByUpdatedAtDesc(username, cutoff);
    }

    public Optional<ChatSession> getSession(UUID sessionId, String username) {
        Optional<ChatSession> opt = sessionRepository.findByIdAndUsername(sessionId, username);
        if (opt.isPresent() && opt.get().getUpdatedAt() != null
                && opt.get().getUpdatedAt().isBefore(OffsetDateTime.now().minusDays(sessionTtlDays))) {
            return Optional.empty();
        }
        return opt;
    }

    public List<ChatMessage> getSessionMessages(UUID sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /**
     * Purge sessions older than the max 30 days TTL every 24 hours.
     */
    @Scheduled(cron = "${mcp.chat.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpiredSessions() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(sessionTtlDays);
        try {
            int deleted = sessionRepository.deleteSessionsOlderThan(cutoff);
            int orphaned = messageRepository.deleteOrphanedMessages();
            if (deleted > 0 || orphaned > 0) {
                log.info("[CHAT-TTL] Purged {} expired sessions and {} orphaned messages older than {} days", deleted, orphaned, sessionTtlDays);
            }
        } catch (Exception e) {
            log.warn("[CHAT-TTL] Failed to purge expired chat sessions: {}", e.getMessage());
        }
    }

    @Transactional
    public ChatSession createSession(String username, String title) {
        ChatSession session = new ChatSession();
        session.setUsername(username != null ? username : "anonymous");
        session.setTitle(title != null && !title.isBlank() ? title.trim() : "New Conversation");
        session.setCreatedAt(OffsetDateTime.now());
        session.setUpdatedAt(OffsetDateTime.now());
        session.setArchived(false);
        return sessionRepository.save(session);
    }

    @Transactional
    public Optional<ChatSession> updateTitle(UUID sessionId, String username, String newTitle) {
        Optional<ChatSession> opt = sessionRepository.findByIdAndUsername(sessionId, username);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        ChatSession session = opt.get();
        session.setTitle(newTitle != null && !newTitle.isBlank() ? newTitle.trim() : "New Conversation");
        session.setUpdatedAt(OffsetDateTime.now());
        return Optional.of(sessionRepository.save(session));
    }

    @Transactional
    public boolean deleteSession(UUID sessionId, String username) {
        Optional<ChatSession> opt = sessionRepository.findByIdAndUsername(sessionId, username);
        if (opt.isEmpty()) {
            return false;
        }
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(opt.get());
        return true;
    }

    /**
     * Appends one turn, but only to a session that exists and belongs to the caller.
     *
     * The id arrives in a request body, so it is a trust boundary, and this method is the only
     * sink for it. Unchecked it did two wrong things at once: a fabricated id reached the insert
     * and tripped the {@code chat_messages_session_id_fkey} constraint, surfacing as a 500 on a
     * chat that otherwise worked; and a real id belonging to somebody else was appended to
     * without complaint, so any signed-in user could write into another user's history.
     * {@link #syncMessages} has always scoped its lookup this way — this is the same rule applied
     * to the path that skipped it.
     *
     * @return the saved message, or empty when the session is not the caller's to write to
     */
    @Transactional
    public Optional<ChatMessage> appendMessage(UUID sessionId, String username,
                                              String role, String content, Object metadata) {
        Optional<ChatSession> owned = sessionRepository.findByIdAndUsername(sessionId, username);
        if (owned.isEmpty()) {
            log.warn("[CHAT] Rejected message for session {} — not present for this user", sessionId);
            return Optional.empty();
        }

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
        ChatSession session = owned.get();
        session.setUpdatedAt(OffsetDateTime.now());
        // If default title, optionally update from first user prompt
        if ("New Conversation".equalsIgnoreCase(session.getTitle()) && "user".equalsIgnoreCase(role)
                && content != null && !content.isBlank()) {
            String candidate = content.trim().replaceAll("\\s+", " ");
            if (candidate.length() > 60) {
                candidate = candidate.substring(0, 57) + "...";
            }
            session.setTitle(candidate);
        }
        sessionRepository.save(session);

        return Optional.of(saved);
    }

    @Transactional
    public List<ChatMessage> syncMessages(UUID sessionId, String username, List<Map<String, Object>> turns) {
        Optional<ChatSession> opt = sessionRepository.findByIdAndUsername(sessionId, username);
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
