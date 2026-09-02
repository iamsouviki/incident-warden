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
public class JiraIntegrationService {
    private static final Logger log = LoggerFactory.getLogger(JiraIntegrationService.class);

    private final IncidentRepository incidentRepository;
    private final RestTemplate restTemplate;


    public JiraIntegrationService(IncidentRepository incidentRepository,
                                  @org.springframework.beans.factory.annotation.Qualifier("integrationRestTemplate") RestTemplate restTemplate) {
        this.incidentRepository = incidentRepository;
        this.restTemplate = java.util.Objects.requireNonNull(restTemplate, "integrationRestTemplate");
    }

    public boolean testConnection(String jiraUrl, String email, String apiToken) {
        if (jiraUrl == null || jiraUrl.isBlank() || email == null || email.isBlank()) return false;
        try {
            String url = jiraUrl.replaceAll("/+$", "") + "/rest/api/3/myself";
            HttpHeaders headers = createAuthHeaders(email, apiToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("[JIRA] Test connection failed: {}", e.getMessage());
            return false;
        }
    }

    public List<Incident> fetchOpenIncidents(String jiraUrl, String email, String apiToken, String jql) {
        List<Incident> synced = new ArrayList<>();
        if (jiraUrl == null || jiraUrl.isBlank() || email == null || email.isBlank()) {
            log.info("[JIRA] No Jira API credentials configured.");
            return synced;
        }

        try {
            String cleanJql = (jql != null && !jql.isBlank()) ? jql : "statusCategory != Done ORDER BY created DESC";
            String url = jiraUrl.replaceAll("/+$", "") + "/rest/api/3/search?jql=" + cleanJql + "&maxResults=50";
            HttpHeaders headers = createAuthHeaders(email, apiToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> issues = (List<Map<String, Object>>) response.getBody().get("issues");
                if (issues != null) {
                    for (Map<String, Object> issue : issues) {
                        String key = String.valueOf(issue.getOrDefault("key", ""));
                        if (key.isBlank()) continue;

                        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
                        if (fields == null) continue;

                        Optional<Incident> existing = incidentRepository.findByExternalId(key);
                        Incident inc = existing.orElseGet(() -> {
                            Incident created = new Incident();
                            created.setId(UUID.randomUUID());
                            created.setExternalId(key);
                            created.setExternalSource("Jira");
                            created.setExternalServiceName("Jira");
                            created.setCreatedAt(OffsetDateTime.now());
                            return created;
                        });

                        String summary = String.valueOf(fields.getOrDefault("summary", "Jira Issue " + key));
                        inc.setSubject(summary);

                        // Description extraction from Atlassian Document Format or string
                        inc.setDescription(extractDescription(fields.get("description"), summary));

                        Map<String, Object> priorityObj = (Map<String, Object>) fields.get("priority");
                        String priorityName = priorityObj != null ? String.valueOf(priorityObj.getOrDefault("name", "Medium")) : "Medium";
                        inc.setPriority(mapPriority(priorityName));

                        Map<String, Object> statusObj = (Map<String, Object>) fields.get("status");
                        String statusName = statusObj != null ? String.valueOf(statusObj.getOrDefault("name", "Open")) : "New";
                        inc.setStatus(mapStatus(statusName));

                        inc.setExternalServiceName("Jira");
                        inc.setExternalSource("Jira");
                        inc.setUpdatedAt(OffsetDateTime.now());

                        incidentRepository.save(inc);
                        synced.add(inc);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[JIRA] Fetch failed", e);
            throw new IntegrationUnavailableException("Jira", e);
        }
        return synced;
    }

    public SourceUpdate updateStatus(String jiraUrl, String email, String apiToken, String issueKey, String transitionId) {
        if (jiraUrl == null || jiraUrl.isBlank()) return SourceUpdate.NOT_CONFIGURED;
        try {
            String url = jiraUrl.replaceAll("/+$", "") + "/rest/api/3/issue/" + issueKey + "/transitions";
            HttpHeaders headers = createAuthHeaders(email, apiToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of("transition", Map.of("id", transitionId != null ? transitionId : "21"));
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Void> response = restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
            return response.getStatusCode().is2xxSuccessful() ? SourceUpdate.UPDATED : SourceUpdate.FAILED;
        } catch (Exception e) {
            log.error("[JIRA] Failed to transition issue {}: {}", issueKey, e.getMessage());
            return SourceUpdate.FAILED;
        }
    }

    public SourceUpdate addComment(String jiraUrl, String email, String apiToken, String issueKey, String commentText) {
        if (jiraUrl == null || jiraUrl.isBlank()) return SourceUpdate.NOT_CONFIGURED;
        try {
            String url = jiraUrl.replaceAll("/+$", "") + "/rest/api/3/issue/" + issueKey + "/comment";
            HttpHeaders headers = createAuthHeaders(email, apiToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // ADF format for Jira v3
            Map<String, Object> textNode = Map.of("type", "text", "text", commentText);
            Map<String, Object> paragraph = Map.of("type", "paragraph", "content", List.of(textNode));
            Map<String, Object> doc = Map.of("version", 1, "type", "doc", "content", List.of(paragraph));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of("body", doc), headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            return response.getStatusCode().is2xxSuccessful() ? SourceUpdate.UPDATED : SourceUpdate.FAILED;
        } catch (Exception e) {
            log.error("[JIRA] Failed to add comment for {}: {}", issueKey, e.getMessage());
            return SourceUpdate.FAILED;
        }
    }

    public byte[] downloadAttachment(String jiraUrl, String email, String apiToken, String attachmentId) {
        if (jiraUrl == null || jiraUrl.isBlank() || attachmentId == null) {
            return null;
        }
        try {
            String url = jiraUrl.replaceAll("/+$", "") + "/rest/api/3/attachment/content/" + attachmentId;
            HttpHeaders headers = createAuthHeaders(email, apiToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
            return response.getBody();
        } catch (Exception e) {
            log.error("[JIRA] Failed to download attachment {}: {}", attachmentId, e.getMessage());
            return null;
        }
    }

    private String extractDescription(Object raw, String fallback) {
        if (raw == null) return fallback;
        if (raw instanceof String s) return s;
        if (raw instanceof Map map) {
            return map.toString();
        }
        return fallback;
    }

    private HttpHeaders createAuthHeaders(String email, String apiToken) {
        HttpHeaders headers = new HttpHeaders();
        if (email != null && apiToken != null && !email.isBlank()) {
            String auth = email + ":" + apiToken;
            byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
            headers.set("Authorization", "Basic " + new String(encodedAuth));
        }
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private String mapPriority(String p) {
        if (p == null) return "P3";
        return switch (p.toLowerCase()) {
            case "highest", "blocker", "critical" -> "P1";
            case "high", "major" -> "P2";
            case "medium", "normal" -> "P3";
            case "low", "minor", "trivial" -> "P4";
            default -> "P3";
        };
    }

    private String mapStatus(String s) {
        if (s == null) return "PENDING_ANALYSIS";
        return switch (s.toLowerCase()) {
            case "to do", "open", "new", "backlog" -> "New";
            case "in progress", "in review" -> "In Progress";
            case "done", "resolved" -> "RESOLVED";
            case "closed" -> "CLOSED";
            default -> "PENDING_ANALYSIS";
        };
    }
}
