package com.company.mcp.service;

import com.company.mcp.model.AuditEvent;
import com.company.mcp.repository.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private final AuditEventRepository events;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository events, ObjectMapper objectMapper) { this.events = events; this.objectMapper = objectMapper; }

    @Transactional
    public void record(String tenantId, String aggregateType, UUID aggregateId, String eventType, String actor, Map<String, ?> payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            String previous = events.findFirstByTenantIdOrderByCreatedAtDesc(tenantId).map(AuditEvent::getEventHash).orElse("");
            AuditEvent event = new AuditEvent();
            event.setTenantId(tenantId); event.setAggregateType(aggregateType); event.setAggregateId(aggregateId);
            event.setEventType(eventType); event.setActor(actor); event.setPayload(body); event.setPreviousHash(previous);
            event.setEventHash(sha256(previous + "|" + tenantId + "|" + aggregateType + "|" + aggregateId + "|" + eventType + "|" + actor + "|" + body));
            events.save(event);
        } catch (Exception e) {
            throw new IllegalStateException("Audit event could not be recorded", e);
        }
    }

    private String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64); for (byte b : bytes) hex.append(String.format("%02x", b)); return hex.toString();
    }
}
