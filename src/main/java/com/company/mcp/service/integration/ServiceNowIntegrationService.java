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
public class ServiceNowIntegrationService {
    private static final Logger log = LoggerFactory.getLogger(ServiceNowIntegrationService.class);

    private final IncidentRepository incidentRepository;
    private final RestTemplate restTemplate;

    public ServiceNowIntegrationService(IncidentRepository incidentRepository) {
        this(incidentRepository, new RestTemplate());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ServiceNowIntegrationService(IncidentRepository incidentRepository,
                                        @org.springframework.beans.factory.annotation.Qualifier("integrationRestTemplate") RestTemplate restTemplate) {
        this.incidentRepository = incidentRepository;
        this.restTemplate = restTemplate != null ? restTemplate : new RestTemplate();
    }

    public boolean testConnection(String instanceUrl, String username, String password) {
        if (instanceUrl == null || instanceUrl.isBlank()) return false;
        try {
            String url = instanceUrl.replaceAll("/+$", "") + "/api/now/table/incident?sysparm_limit=1";
            HttpHeaders headers = createAuthHeaders(username, password);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("[SERVICENOW] Test connection failed: {}", e.getMessage());
            return false;
        }
    }

    public List<Incident> fetchOpenIncidents(String instanceUrl, String username, String password, String tenantId) {
        List<Incident> synced = new ArrayList<>();
        if (instanceUrl == null || instanceUrl.isBlank()) {
            log.info("[SERVICENOW] No ServiceNow instance URL configured.");
            return synced;
        }

        try {
            String url = instanceUrl.replaceAll("/+$", "") + "/api/now/table/incident?sysparm_query=stateNOT IN6,7^sysparm_limit=50";
            HttpHeaders headers = createAuthHeaders(username, password);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> result = (List<Map<String, Object>>) response.getBody().get("result");
                if (result != null) {
                    for (Map<String, Object> record : result) {
                        String number = String.valueOf(record.getOrDefault("number", ""));
                        if (number.isBlank()) continue;

                        Optional<Incident> existing = incidentRepository.findByExternalId(number);
                        Incident inc = existing.orElseGet(() -> {
                            Incident created = new Incident();
                            created.setId(UUID.randomUUID());
                            created.setExternalId(number);
                            created.setExternalSource("ServiceNow");
                            created.setExternalServiceName("ServiceNow");
                            created.setTenantId(tenantId != null ? tenantId : "tenant-1");
                            created.setCreatedAt(OffsetDateTime.now());
                            return created;
                        });

                        inc.setSubject(String.valueOf(record.getOrDefault("short_description", "ServiceNow Incident " + number)));
                        inc.setDescription(String.valueOf(record.getOrDefault("description", inc.getSubject())));
                        inc.setPriority(mapPriority(String.valueOf(record.getOrDefault("priority", "3"))));
                        inc.setStatus(mapStatus(String.valueOf(record.getOrDefault("state", "1"))));
                        inc.setExternalServiceName("ServiceNow");
                        inc.setExternalSource("ServiceNow");
                        inc.setUpdatedAt(OffsetDateTime.now());

                        incidentRepository.save(inc);
                        synced.add(inc);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[SERVICENOW] Failed to fetch incidents from instance: {}", e.getMessage());
        }
        return synced;
    }

    public boolean updateStatus(String instanceUrl, String username, String password, String sysIdOrNumber, String status) {
        if (instanceUrl == null || instanceUrl.isBlank()) return true;
        try {
            String url = instanceUrl.replaceAll("/+$", "") + "/api/now/table/incident/" + sysIdOrNumber;
            HttpHeaders headers = createAuthHeaders(username, password);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of("state", mapToServiceNowState(status));
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PATCH, entity, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("[SERVICENOW] Failed to update status on ServiceNow for {}: {}", sysIdOrNumber, e.getMessage());
            return false;
        }
    }

    public boolean addWorkNote(String instanceUrl, String username, String password, String sysIdOrNumber, String note) {
        if (instanceUrl == null || instanceUrl.isBlank()) return true;
        try {
            String url = instanceUrl.replaceAll("/+$", "") + "/api/now/table/incident/" + sysIdOrNumber;
            HttpHeaders headers = createAuthHeaders(username, password);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of("work_notes", note);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PATCH, entity, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("[SERVICENOW] Failed to add work note for {}: {}", sysIdOrNumber, e.getMessage());
            return false;
        }
    }

    public byte[] downloadAttachment(String instanceUrl, String username, String password, String attachmentId) {
        if (instanceUrl == null || instanceUrl.isBlank() || attachmentId == null) {
            return "Sample incident diagnostic attachment report content".getBytes();
        }
        try {
            String url = instanceUrl.replaceAll("/+$", "") + "/api/now/attachment/" + attachmentId + "/file";
            HttpHeaders headers = createAuthHeaders(username, password);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
            return response.getBody();
        } catch (Exception e) {
            log.error("[SERVICENOW] Failed to download attachment {}: {}", attachmentId, e.getMessage());
            return "Attachment content could not be retrieved from ServiceNow.".getBytes();
        }
    }

    private HttpHeaders createAuthHeaders(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        if (username != null && password != null && !username.isBlank()) {
            String auth = username + ":" + password;
            byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
            headers.set("Authorization", "Basic " + new String(encodedAuth));
        }
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private String mapPriority(String p) {
        return switch (p) {
            case "1" -> "P1";
            case "2" -> "P2";
            case "3" -> "P3";
            case "4" -> "P4";
            default -> "P3";
        };
    }

    private String mapStatus(String s) {
        return switch (s) {
            case "1" -> "New";
            case "2" -> "In Progress";
            case "6" -> "RESOLVED";
            case "7" -> "CLOSED";
            default -> "PENDING_ANALYSIS";
        };
    }

    private String mapToServiceNowState(String status) {
        if (status == null) return "2";
        return switch (status.toUpperCase()) {
            case "NEW", "PENDING_ANALYSIS" -> "1";
            case "IN PROGRESS", "IN_PROGRESS" -> "2";
            case "ON HOLD" -> "3";
            case "RESOLVED" -> "6";
            case "CLOSED" -> "7";
            case "CANCELED", "CANCELLED" -> "8";
            default -> "2";
        };
    }
}
