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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ServiceNow REST Client — polls the ServiceNow Table API for new/updated incidents.
 *
 * API used:  GET /api/now/table/incident
 * Filters:  sys_updated_on &gt; watermark  (only new/updated tickets)
 * Auth:     Basic (username + password)
 *
 * Mapping:
 *   ServiceNow field      →  Incident field
 *   ─────────────────────────────────────────
 *   number                →  sourceTicketId
 *   short_description     →  title
 *   description           →  description
 *   priority (1-5)        →  severity (P1-P4)
 *   assignment_group      →  category
 *   sys_updated_on        →  watermark update
 *
 * Circuit-breaker: "servicenow" instance (defined in application.yml)
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mcp.servicenow.enabled", havingValue = "true")
public class ServiceNowClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mcp.servicenow.instance-url}")
    private String instanceUrl;

    @Value("${mcp.servicenow.username}")
    private String username;

    @Value("${mcp.servicenow.password}")
    private String password;

    @Value("${mcp.servicenow.table:incident}")
    private String tableName;

    @Value("${mcp.servicenow.query-filter:}")
    private String extraQueryFilter;

    @Value("${mcp.servicenow.limit:50}")
    private int pageLimit;

    /** ServiceNow datetime format: yyyy-MM-dd HH:mm:ss */
    private static final DateTimeFormatter SNOW_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public ServiceNowClient(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetch incidents updated since the given watermark.
     *
     * @param since  high-water mark — only tickets updated after this instant
     * @return list of mapped Incident entities (not yet persisted)
     */
    @CircuitBreaker(name = "servicenow", fallbackMethod = "fallbackGetIncidents")
    public List<Incident> getUpdatedIncidents(Instant since) {
        String sysparm = buildQuery(since);
        String url = String.format("%s/api/now/table/%s?sysparm_query=%s&sysparm_limit=%d"
                        + "&sysparm_fields=number,short_description,description,priority,"
                        + "assignment_group,sys_updated_on,urgency,impact,state,category",
                instanceUrl, tableName, sysparm, pageLimit);

        log.debug("[ServiceNow] GET {}", url);

        HttpHeaders headers = buildHeaders();
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return parseResponse(response.getBody());
        }

        log.warn("[ServiceNow] Non-2xx response: {}", response.getStatusCode());
        return Collections.emptyList();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Basic Auth
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        headers.set("Authorization", "Basic " + encoded);

        return headers;
    }

    private String buildQuery(Instant since) {
        StringBuilder sb = new StringBuilder();
        sb.append("sys_updated_on>").append(SNOW_FMT.format(since));
        sb.append("^stateIN1,2,3"); // New, In-Progress, On-Hold
        if (extraQueryFilter != null && !extraQueryFilter.isBlank()) {
            sb.append("^").append(extraQueryFilter);
        }
        sb.append("^ORDERBYsys_updated_on");
        return sb.toString();
    }

    private List<Incident> parseResponse(String json) {
        List<Incident> incidents = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.path("result");

            if (results.isArray()) {
                for (JsonNode node : results) {
                    Incident inc = mapToIncident(node);
                    if (inc != null) {
                        incidents.add(inc);
                    }
                }
            }
            log.info("[ServiceNow] Parsed {} incidents from response", incidents.size());
        } catch (Exception e) {
            log.error("[ServiceNow] Failed to parse response: {}", e.getMessage(), e);
        }
        return incidents;
    }

    private Incident mapToIncident(JsonNode node) {
        try {
            String number = node.path("number").asText("");
            if (number.isBlank()) return null;

            return Incident.builder()
                    .sourceSystem("ServiceNow")
                    .sourceTicketId(number)
                    .title(node.path("short_description").asText("(no title)"))
                    .description(node.path("description").asText(""))
                    .severity(mapPriority(node.path("priority").asText("4")))
                    .category(node.path("assignment_group").asText(
                            node.path("category").asText("GENERAL")))
                    .status("PENDING")
                    .build();
        } catch (Exception e) {
            log.warn("[ServiceNow] Failed to map incident node: {}", e.getMessage());
            return null;
        }
    }

    /**
     * ServiceNow priority 1–5  →  P1–P4 (5 maps to P4).
     */
    private String mapPriority(String snowPriority) {
        return switch (snowPriority) {
            case "1" -> "P1";   // Critical
            case "2" -> "P2";   // High
            case "3" -> "P3";   // Moderate
            default  -> "P4";   // Low / Planning
        };
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Circuit-breaker fallback
    // ──────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    private List<Incident> fallbackGetIncidents(Instant since, Throwable t) {
        log.error("[ServiceNow] Circuit breaker open — fallback triggered: {}", t.getMessage());
        return Collections.emptyList();
    }
}

