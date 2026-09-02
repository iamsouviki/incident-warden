package com.company.mcp.service.integration;

import com.company.mcp.model.Incident;
import com.company.mcp.repository.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class FreshserviceIntegrationService {
    private static final Logger log = LoggerFactory.getLogger(FreshserviceIntegrationService.class);

    private final IncidentRepository incidentRepository;
    private final RestTemplate restTemplate;


    public FreshserviceIntegrationService(IncidentRepository incidentRepository,
                                          @org.springframework.beans.factory.annotation.Qualifier("integrationRestTemplate") RestTemplate restTemplate) {
        this.incidentRepository = incidentRepository;
        this.restTemplate = java.util.Objects.requireNonNull(restTemplate, "integrationRestTemplate");
    }

    public boolean testConnection(String domainUrl, String apiKey) {
        if (domainUrl == null || domainUrl.isBlank() || apiKey == null || apiKey.isBlank()) return false;
        try {
            String url = domainUrl.replaceAll("/+$", "") + "/api/v2/tickets?per_page=1";
            HttpHeaders headers = createAuthHeaders(apiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("[FRESHSERVICE] Test connection failed: {}", e.getMessage());
            return false;
        }
    }

    public List<Incident> fetchOpenIncidents(String domainUrl, String apiKey) {
        List<Incident> synced = new ArrayList<>();
        if (domainUrl == null || domainUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            log.info("[FRESHSERVICE] No Freshservice API credentials configured.");
            return synced;
        }

        try {
            // Freshservice: filter=open or status not resolved/closed
            String url = domainUrl.replaceAll("/+$", "") + "/api/v2/tickets?filter=open_and_pending&per_page=50";
            HttpHeaders headers = createAuthHeaders(apiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> tickets = (List<Map<String, Object>>) response.getBody().get("tickets");
                if (tickets != null) {
                    for (Map<String, Object> ticket : tickets) {
                        String id = String.valueOf(ticket.getOrDefault("id", ""));
                        if (id.isBlank()) continue;
                        String externalId = "FS-" + id;

                        Optional<Incident> existing = incidentRepository.findByExternalId(externalId);
                        Incident inc = existing.orElseGet(() -> {
                            Incident created = new Incident();
                            created.setId(UUID.randomUUID());
                            created.setExternalId(externalId);
                            created.setExternalSource("Freshservice");
                            created.setExternalServiceName("Freshservice");
                            created.setCreatedAt(OffsetDateTime.now());
                            return created;
                        });

                        inc.setSubject(String.valueOf(ticket.getOrDefault("subject", "Freshservice Ticket " + id)));
                        inc.setDescription(String.valueOf(ticket.getOrDefault("description_text", inc.getSubject())));
                        inc.setPriority(mapPriority(NumberToLong(ticket.get("priority"))));
                        inc.setStatus(mapStatus(NumberToLong(ticket.get("status"))));
                        inc.setExternalServiceName("Freshservice");
                        inc.setExternalSource("Freshservice");
                        inc.setUpdatedAt(OffsetDateTime.now());

                        incidentRepository.save(inc);
                        synced.add(inc);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[FRESHSERVICE] Fetch failed", e);
            throw new IntegrationUnavailableException("Freshservice", e);
        }
        return synced;
    }

    public SourceUpdate updateStatus(String domainUrl, String apiKey, String ticketId, String status) {
        if (domainUrl == null || domainUrl.isBlank()) return SourceUpdate.NOT_CONFIGURED;
        try {
            String cleanId = ticketId.replace("FS-", "").trim();
            String url = domainUrl.replaceAll("/+$", "") + "/api/v2/tickets/" + cleanId;
            HttpHeaders headers = createAuthHeaders(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of("status", mapToFreshserviceStatus(status));
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);
            return response.getStatusCode().is2xxSuccessful() ? SourceUpdate.UPDATED : SourceUpdate.FAILED;
        } catch (Exception e) {
            log.error("[FRESHSERVICE] Failed to update ticket status for {}: {}", ticketId, e.getMessage());
            return SourceUpdate.FAILED;
        }
    }

    public SourceUpdate addNote(String domainUrl, String apiKey, String ticketId, String noteContent) {
        if (domainUrl == null || domainUrl.isBlank()) return SourceUpdate.NOT_CONFIGURED;
        try {
            String cleanId = ticketId.replace("FS-", "").trim();
            String url = domainUrl.replaceAll("/+$", "") + "/api/v2/tickets/" + cleanId + "/notes";
            HttpHeaders headers = createAuthHeaders(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of("body", noteContent, "private", true);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            return response.getStatusCode().is2xxSuccessful() ? SourceUpdate.UPDATED : SourceUpdate.FAILED;
        } catch (Exception e) {
            log.error("[FRESHSERVICE] Failed to add note for {}: {}", ticketId, e.getMessage());
            return SourceUpdate.FAILED;
        }
    }

    public byte[] downloadAttachment(String domainUrl, String apiKey, String attachmentId) {
        if (domainUrl == null || domainUrl.isBlank() || attachmentId == null) {
            return null;
        }
        try {
            String url = domainUrl.replaceAll("/+$", "") + "/api/v2/attachments/" + attachmentId;
            HttpHeaders headers = createAuthHeaders(apiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
            return response.getBody();
        } catch (Exception e) {
            log.error("[FRESHSERVICE] Failed to download attachment {}: {}", attachmentId, e.getMessage());
            return null;
        }
    }

    private HttpHeaders createAuthHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null && !apiKey.isBlank()) {
            String auth = apiKey + ":X";
            byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
            headers.set("Authorization", "Basic " + new String(encodedAuth));
        }
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private long NumberToLong(Object obj) {
        if (obj instanceof Number n) return n.longValue();
        if (obj instanceof String s) {
            try { return Long.parseLong(s); } catch (Exception ignored) {}
        }
        return 2;
    }

    private String mapPriority(long p) {
        return switch ((int) p) {
            case 4 -> "P1";
            case 3 -> "P2";
            case 2 -> "P3";
            case 1 -> "P4";
            default -> "P3";
        };
    }

    private String mapStatus(long s) {
        return switch ((int) s) {
            case 2 -> "New";
            case 3 -> "Pending";
            case 4 -> "RESOLVED";
            case 5 -> "CLOSED";
            default -> "PENDING_ANALYSIS";
        };
    }

    private int mapToFreshserviceStatus(String status) {
        if (status == null) return 2;
        return switch (status.toUpperCase()) {
            case "NEW", "PENDING_ANALYSIS" -> 2;
            case "IN PROGRESS", "PENDING" -> 3;
            case "RESOLVED" -> 4;
            case "CLOSED" -> 5;
            default -> 2;
        };
    }
}
