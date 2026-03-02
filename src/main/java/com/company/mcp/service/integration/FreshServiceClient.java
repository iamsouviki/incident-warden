package com.company.mcp.service.integration;

import com.company.mcp.model.Incident;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * FreshService REST Client — polls the Freshservice Tickets API v2 for new/updated incidents.
 *
 * API used:  GET /api/v2/tickets?updated_since=&lt;watermark&gt;
 * Auth:     API Key (Base64-encoded as Basic auth, key:X)
 *
 * Mapping:
 *   FreshService field    →  Incident field
 *   ─────────────────────────────────────────
 *   id                    →  sourceTicketId
 *   subject               →  title
 *   description_text      →  description
 *   priority (1-4)        →  severity (P1-P4)
 *   group_id / category   →  category
 *   updated_at            →  watermark update
 *
 * Circuit-breaker: "freshservice" instance (defined in application.yml)
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mcp.freshservice.enabled", havingValue = "true")
public class FreshServiceClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mcp.freshservice.domain}")
    private String domain;

    @Value("${mcp.freshservice.api-key}")
    private String apiKey;

    @Value("${mcp.freshservice.ticket-type:Incident}")
    private String ticketType;

    @Value("${mcp.freshservice.limit:50}")
    private int pageLimit;

    /** ISO-8601 format used by FreshService API */
    private static final DateTimeFormatter FS_FMT =
            DateTimeFormatter.ISO_INSTANT;

    public FreshServiceClient(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetch tickets updated since the given watermark.
     *
     * @param since  high-water mark — only tickets updated after this instant
     * @return list of mapped Incident entities (not yet persisted)
     */
    @CircuitBreaker(name = "freshservice", fallbackMethod = "fallbackGetTickets")
    public List<Incident> getUpdatedTickets(Instant since) {
        String updatedSince = FS_FMT.format(since);
        String url = String.format("https://%s.freshservice.com/api/v2/tickets"
                        + "?updated_since=%s&per_page=%d&include=description&type=%s"
                        + "&order_by=updated_at&order_type=asc",
                domain, updatedSince, pageLimit, ticketType);

        log.debug("[FreshService] GET {}", url);

        HttpHeaders headers = buildHeaders();
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return parseResponse(response.getBody());
        }

        log.warn("[FreshService] Non-2xx response: {}", response.getStatusCode());
        return Collections.emptyList();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);

        // FreshService uses API key as Basic Auth: apiKey:X
        String credentials = apiKey + ":X";
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        headers.set("Authorization", "Basic " + encoded);

        return headers;
    }

    private List<Incident> parseResponse(String json) {
        List<Incident> incidents = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode tickets = root.path("tickets");

            if (tickets.isArray()) {
                for (JsonNode node : tickets) {
                    Incident inc = mapToIncident(node);
                    if (inc != null) {
                        incidents.add(inc);
                    }
                }
            }
            log.info("[FreshService] Parsed {} incidents from response", incidents.size());
        } catch (Exception e) {
            log.error("[FreshService] Failed to parse response: {}", e.getMessage(), e);
        }
        return incidents;
    }

    private Incident mapToIncident(JsonNode node) {
        try {
            long ticketId = node.path("id").asLong(0);
            if (ticketId == 0) return null;

            return Incident.builder()
                    .sourceSystem("FreshService")
                    .sourceTicketId("FS-" + ticketId)
                    .title(node.path("subject").asText("(no subject)"))
                    .description(node.path("description_text").asText(
                            node.path("description").asText("")))
                    .severity(mapPriority(node.path("priority").asInt(4)))
                    .category(node.path("category").asText("GENERAL"))
                    .status("PENDING")
                    .build();
        } catch (Exception e) {
            log.warn("[FreshService] Failed to map ticket node: {}", e.getMessage());
            return null;
        }
    }

    /**
     * FreshService priority:  1=Low, 2=Medium, 3=High, 4=Urgent
     * Map to incident severity:  4→P1, 3→P2, 2→P3, 1→P4
     */
    private String mapPriority(int freshPriority) {
        return switch (freshPriority) {
            case 4 -> "P1";   // Urgent
            case 3 -> "P2";   // High
            case 2 -> "P3";   // Medium
            default -> "P4";  // Low
        };
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Circuit-breaker fallback
    // ──────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    private List<Incident> fallbackGetTickets(Instant since, Throwable t) {
        log.error("[FreshService] Circuit breaker open — fallback triggered: {}", t.getMessage());
        return Collections.emptyList();
    }
}


