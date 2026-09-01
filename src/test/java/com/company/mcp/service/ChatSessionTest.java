package com.company.mcp.service;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.controller.ChatSessionController;
import com.company.mcp.model.ChatMessage;
import com.company.mcp.model.ChatSession;
import com.company.mcp.repository.ChatMessageRepository;
import com.company.mcp.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ChatSessionTest {

    private final ChatSessionRepository sessionRepo = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messageRepo = mock(ChatMessageRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private ChatSessionService sessionService;
    private ChatSessionController controller;

    private final Map<UUID, ChatSession> sessionTable = new HashMap<>();
    private final List<ChatMessage> messageList = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sessionTable.clear();
        messageList.clear();

        when(currentUser.tenantId()).thenReturn("tenant-1");
        when(currentUser.username()).thenReturn("analyst.user");
        when(currentUser.role()).thenReturn("ANALYST");

        when(sessionRepo.save(any(ChatSession.class))).thenAnswer(inv -> {
            ChatSession s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            if (s.getCreatedAt() == null) {
                s.setCreatedAt(OffsetDateTime.now());
            }
            s.setUpdatedAt(OffsetDateTime.now());
            sessionTable.put(s.getId(), s);
            return s;
        });

        when(sessionRepo.findByTenantIdAndUsernameAndIsArchivedFalseOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenAnswer(inv -> new ArrayList<>(sessionTable.values()));

        when(sessionRepo.findByTenantIdAndUsernameAndIsArchivedFalseAndUpdatedAtAfterOrderByUpdatedAtDesc(anyString(), anyString(), any(OffsetDateTime.class)))
                .thenAnswer(inv -> {
                    OffsetDateTime cutoff = inv.getArgument(2);
                    return sessionTable.values().stream()
                            .filter(s -> s.getUpdatedAt() != null && s.getUpdatedAt().isAfter(cutoff))
                            .toList();
                });

        when(sessionRepo.findByIdAndTenantIdAndUsername(any(UUID.class), anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(sessionTable.get(inv.getArgument(0))));

        when(sessionRepo.findByIdAndTenantId(any(UUID.class), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(sessionTable.get(inv.getArgument(0))));

        doAnswer(inv -> {
            ChatSession s = inv.getArgument(0);
            sessionTable.remove(s.getId());
            return null;
        }).when(sessionRepo).delete(any(ChatSession.class));

        when(messageRepo.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(UUID.randomUUID());
            }
            if (m.getCreatedAt() == null) {
                m.setCreatedAt(OffsetDateTime.now());
            }
            messageList.add(m);
            return m;
        });

        when(messageRepo.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID sId = inv.getArgument(0);
                    return messageList.stream().filter(m -> sId.equals(m.getSessionId())).toList();
                });

        sessionService = new ChatSessionService(sessionRepo, messageRepo, objectMapper, 30);
        controller = new ChatSessionController(sessionService, currentUser);
    }

    @Test
    void createSessionAndAddMessages() {
        ChatSession session = sessionService.createSession("tenant-1", "analyst.user", "POS Troubleshooting");
        assertThat(session.getId()).isNotNull();
        assertThat(session.getTitle()).isEqualTo("POS Troubleshooting");

        ChatMessage userMsg = sessionService.appendMessage(session.getId(), "user", "How do I fix printer offline?", null);
        assertThat(userMsg.getId()).isNotNull();
        assertThat(userMsg.getContent()).contains("printer offline");

        ChatMessage botMsg = sessionService.appendMessage(session.getId(), "assistant", "Restart the spooler service.", Map.of("confidence", 0.95));
        assertThat(botMsg.getId()).isNotNull();

        List<ChatMessage> messages = sessionService.getSessionMessages(session.getId());
        assertThat(messages).hasSize(2);
    }

    @Test
    void controllerLifecycleFlow() {
        // 1. Create session
        ResponseEntity<ChatSession> createdResp = controller.createSession(Map.of("title", "New Incident Investigation"));
        assertThat(createdResp.getStatusCode().value()).isEqualTo(200);
        ChatSession created = createdResp.getBody();
        assertThat(created).isNotNull();
        UUID sessionId = created.getId();

        // 2. List sessions
        ResponseEntity<List<ChatSession>> listResp = controller.listSessions();
        assertThat(listResp.getBody()).hasSize(1);

        // 3. Update title
        ResponseEntity<?> updateResp = controller.updateSessionTitle(sessionId, Map.of("title", "Updated POS Title"));
        assertThat(updateResp.getStatusCode().value()).isEqualTo(200);
        ChatSession updated = (ChatSession) updateResp.getBody();
        assertThat(updated.getTitle()).isEqualTo("Updated POS Title");

        // 4. Append message via controller
        ResponseEntity<?> appendResp = controller.syncOrAppendMessages(sessionId, Map.of(
                "role", "user",
                "content", "What is the status of store 42?"
        ));
        assertThat(appendResp.getStatusCode().value()).isEqualTo(200);

        // 5. Get session details with messages
        ResponseEntity<?> detailsResp = controller.getSessionDetails(sessionId);
        assertThat(detailsResp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) detailsResp.getBody();
        assertThat(body).containsKey("session");
        assertThat(body).containsKey("messages");

        // 6. Delete session
        ResponseEntity<?> delResp = controller.deleteSession(sessionId);
        assertThat(delResp.getStatusCode().value()).isEqualTo(200);
        assertThat(sessionTable).doesNotContainKey(sessionId);
    }
}
