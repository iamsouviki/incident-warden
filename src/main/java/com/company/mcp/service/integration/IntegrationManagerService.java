package com.company.mcp.service.integration;

import com.company.mcp.model.Incident;
import com.company.mcp.model.SystemConfig;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.repository.SystemConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class IntegrationManagerService {
    private static final Logger log = LoggerFactory.getLogger(IntegrationManagerService.class);

    private final SystemConfigRepository configRepository;
    private final IncidentRepository incidentRepository;
    private final ServiceNowIntegrationService serviceNowService;
    private final FreshserviceIntegrationService freshserviceService;
    private final JiraIntegrationService jiraService;

    private OffsetDateTime lastSyncTime;
    private String lastSyncStatus = "Idle";

    public IntegrationManagerService(SystemConfigRepository configRepository,
                                     IncidentRepository incidentRepository,
                                     ServiceNowIntegrationService serviceNowService,
                                     FreshserviceIntegrationService freshserviceService,
                                     JiraIntegrationService jiraService) {
        this.configRepository = configRepository;
        this.incidentRepository = incidentRepository;
        this.serviceNowService = serviceNowService;
        this.freshserviceService = freshserviceService;
        this.jiraService = jiraService;
    }

    public Map<String, Object> getAllIntegrationSettings() {
        Map<String, Object> settings = new HashMap<>();
        // ServiceNow
        settings.put("serviceNowEnabled", getBooleanConfig("servicenow_enabled", true));
        settings.put("serviceNowUrl", getConfig("servicenow_url", "https://dev-instance.service-now.com"));
        settings.put("serviceNowUsername", getConfig("servicenow_username", "admin"));

        // Freshservice
        settings.put("freshserviceEnabled", getBooleanConfig("freshservice_enabled", true));
        settings.put("freshserviceUrl", getConfig("freshservice_url", "https://company.freshservice.com"));
        settings.put("freshserviceApiKey", maskSecret(getConfig("freshservice_api_key", "")));

        // Jira
        settings.put("jiraEnabled", getBooleanConfig("jira_enabled", true));
        settings.put("jiraUrl", getConfig("jira_url", "https://company.atlassian.net"));
        settings.put("jiraEmail", getConfig("jira_email", "ops-lead@company.com"));
        settings.put("jiraJql", getConfig("jira_jql", "statusCategory != Done ORDER BY created DESC"));

        // General Sync Config
        settings.put("syncIntervalHours", getIntConfig("integration_sync_interval_hours", 1));
        settings.put("lastSyncTime", lastSyncTime);
        settings.put("lastSyncStatus", lastSyncStatus);

        return settings;
    }

    public void updateIntegrationSettings(Map<String, Object> payload) {
        if (payload.containsKey("serviceNowEnabled")) setConfig("servicenow_enabled", String.valueOf(payload.get("serviceNowEnabled")));
        if (payload.containsKey("serviceNowUrl")) setConfig("servicenow_url", String.valueOf(payload.get("serviceNowUrl")));
        if (payload.containsKey("serviceNowUsername")) setConfig("servicenow_username", String.valueOf(payload.get("serviceNowUsername")));
        if (payload.containsKey("serviceNowPassword") && !String.valueOf(payload.get("serviceNowPassword")).isBlank() && !String.valueOf(payload.get("serviceNowPassword")).contains("****")) {
            setConfig("servicenow_password", String.valueOf(payload.get("serviceNowPassword")));
        }

        if (payload.containsKey("freshserviceEnabled")) setConfig("freshservice_enabled", String.valueOf(payload.get("freshserviceEnabled")));
        if (payload.containsKey("freshserviceUrl")) setConfig("freshservice_url", String.valueOf(payload.get("freshserviceUrl")));
        if (payload.containsKey("freshserviceApiKey") && !String.valueOf(payload.get("freshserviceApiKey")).isBlank() && !String.valueOf(payload.get("freshserviceApiKey")).contains("****")) {
            setConfig("freshservice_api_key", String.valueOf(payload.get("freshserviceApiKey")));
        }

        if (payload.containsKey("jiraEnabled")) setConfig("jira_enabled", String.valueOf(payload.get("jiraEnabled")));
        if (payload.containsKey("jiraUrl")) setConfig("jira_url", String.valueOf(payload.get("jiraUrl")));
        if (payload.containsKey("jiraEmail")) setConfig("jira_email", String.valueOf(payload.get("jiraEmail")));
        if (payload.containsKey("jiraJql")) setConfig("jira_jql", String.valueOf(payload.get("jiraJql")));
        if (payload.containsKey("jiraApiToken") && !String.valueOf(payload.get("jiraApiToken")).isBlank() && !String.valueOf(payload.get("jiraApiToken")).contains("****")) {
            setConfig("jira_api_token", String.valueOf(payload.get("jiraApiToken")));
        }

        if (payload.containsKey("syncIntervalHours")) {
            int hours = Math.max(1, Math.min(24, Integer.parseInt(String.valueOf(payload.get("syncIntervalHours")))));
            setConfig("integration_sync_interval_hours", String.valueOf(hours));
        }
    }

    public boolean testConnection(String serviceName) {
        if ("ServiceNow".equalsIgnoreCase(serviceName)) {
            return serviceNowService.testConnection(
                    getConfig("servicenow_url", ""),
                    getConfig("servicenow_username", ""),
                    getConfig("servicenow_password", "")
            );
        } else if ("Freshservice".equalsIgnoreCase(serviceName)) {
            return freshserviceService.testConnection(
                    getConfig("freshservice_url", ""),
                    getConfig("freshservice_api_key", "")
            );
        } else if ("Jira".equalsIgnoreCase(serviceName)) {
            return jiraService.testConnection(
                    getConfig("jira_url", ""),
                    getConfig("jira_email", ""),
                    getConfig("jira_api_token", "")
            );
        }
        return false;
    }

    /**
     * Periodic scheduled sync (runs every hour; checks configured interval).
     */
    @Scheduled(fixedDelay = 3600000, initialDelay = 10000)
    public void scheduledSync() {
        int intervalHours = getIntConfig("integration_sync_interval_hours", 1);
        if (lastSyncTime != null && OffsetDateTime.now().isBefore(lastSyncTime.plusHours(intervalHours))) {
            return;
        }
        syncAllEnabled("tenant-1");
    }

    public Map<String, Object> syncAllEnabled(String tenantId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int total = 0;
        lastSyncTime = OffsetDateTime.now();

        try {
            if (getBooleanConfig("servicenow_enabled", true)) {
                List<Incident> sn = serviceNowService.fetchOpenIncidents(
                        getConfig("servicenow_url", ""),
                        getConfig("servicenow_username", ""),
                        getConfig("servicenow_password", ""),
                        tenantId
                );
                summary.put("ServiceNow", sn.size());
                total += sn.size();
            }

            if (getBooleanConfig("freshservice_enabled", true)) {
                List<Incident> fs = freshserviceService.fetchOpenIncidents(
                        getConfig("freshservice_url", ""),
                        getConfig("freshservice_api_key", ""),
                        tenantId
                );
                summary.put("Freshservice", fs.size());
                total += fs.size();
            }

            if (getBooleanConfig("jira_enabled", true)) {
                List<Incident> jr = jiraService.fetchOpenIncidents(
                        getConfig("jira_url", ""),
                        getConfig("jira_email", ""),
                        getConfig("jira_api_token", ""),
                        getConfig("jira_jql", ""),
                        tenantId
                );
                summary.put("Jira", jr.size());
                total += jr.size();
            }

            lastSyncStatus = "Success (" + total + " incidents synced)";
            summary.put("status", "SUCCESS");
            summary.put("totalSynced", total);
            summary.put("syncedAt", lastSyncTime);
        } catch (Exception e) {
            log.error("[INTEGRATION] Sync failed: {}", e.getMessage());
            lastSyncStatus = "Error: " + e.getMessage();
            summary.put("status", "ERROR");
            summary.put("error", e.getMessage());
        }

        return summary;
    }

    public boolean updateExternalStatus(Incident incident, String newStatus) {
        String service = resolveServiceName(incident);
        if ("ServiceNow".equalsIgnoreCase(service)) {
            return serviceNowService.updateStatus(
                    getConfig("servicenow_url", ""),
                    getConfig("servicenow_username", ""),
                    getConfig("servicenow_password", ""),
                    incident.getExternalId(),
                    newStatus
            );
        } else if ("Freshservice".equalsIgnoreCase(service)) {
            return freshserviceService.updateStatus(
                    getConfig("freshservice_url", ""),
                    getConfig("freshservice_api_key", ""),
                    incident.getExternalId(),
                    newStatus
            );
        } else if ("Jira".equalsIgnoreCase(service)) {
            return jiraService.updateStatus(
                    getConfig("jira_url", ""),
                    getConfig("jira_email", ""),
                    getConfig("jira_api_token", ""),
                    incident.getExternalId(),
                    newStatus
            );
        }
        return true;
    }

    public boolean addExternalWorkNote(Incident incident, String note) {
        String service = resolveServiceName(incident);
        if ("ServiceNow".equalsIgnoreCase(service)) {
            return serviceNowService.addWorkNote(
                    getConfig("servicenow_url", ""),
                    getConfig("servicenow_username", ""),
                    getConfig("servicenow_password", ""),
                    incident.getExternalId(),
                    note
            );
        } else if ("Freshservice".equalsIgnoreCase(service)) {
            return freshserviceService.addNote(
                    getConfig("freshservice_url", ""),
                    getConfig("freshservice_api_key", ""),
                    incident.getExternalId(),
                    note
            );
        } else if ("Jira".equalsIgnoreCase(service)) {
            return jiraService.addComment(
                    getConfig("jira_url", ""),
                    getConfig("jira_email", ""),
                    getConfig("jira_api_token", ""),
                    incident.getExternalId(),
                    note
            );
        }
        return true;
    }

    public byte[] downloadExternalAttachment(Incident incident, String attachmentId) {
        String service = resolveServiceName(incident);
        if ("ServiceNow".equalsIgnoreCase(service)) {
            return serviceNowService.downloadAttachment(
                    getConfig("servicenow_url", ""),
                    getConfig("servicenow_username", ""),
                    getConfig("servicenow_password", ""),
                    attachmentId
            );
        } else if ("Freshservice".equalsIgnoreCase(service)) {
            return freshserviceService.downloadAttachment(
                    getConfig("freshservice_url", ""),
                    getConfig("freshservice_api_key", ""),
                    attachmentId
            );
        } else if ("Jira".equalsIgnoreCase(service)) {
            return jiraService.downloadAttachment(
                    getConfig("jira_url", ""),
                    getConfig("jira_email", ""),
                    getConfig("jira_api_token", ""),
                    attachmentId
            );
        }
        return "Default diagnostic log report content".getBytes();
    }

    private String resolveServiceName(Incident incident) {
        if (incident.getExternalServiceName() != null && !incident.getExternalServiceName().isBlank()) {
            return incident.getExternalServiceName();
        }
        if (incident.getExternalSource() != null && !incident.getExternalSource().isBlank()) {
            return incident.getExternalSource();
        }
        if (incident.getExternalId() != null) {
            if (incident.getExternalId().startsWith("FS-")) return "Freshservice";
            if (incident.getExternalId().matches("^[A-Z]+-\\d+$")) return "Jira";
        }
        return "ServiceNow";
    }

    private String getConfig(String key, String fallback) {
        return configRepository.findById(key).map(SystemConfig::getConfigValue).orElse(fallback);
    }

    private boolean getBooleanConfig(String key, boolean fallback) {
        return configRepository.findById(key)
                .map(c -> Boolean.parseBoolean(c.getConfigValue()))
                .orElse(fallback);
    }

    private int getIntConfig(String key, int fallback) {
        return configRepository.findById(key)
                .map(c -> {
                    try { return Integer.parseInt(c.getConfigValue()); } catch (Exception e) { return fallback; }
                })
                .orElse(fallback);
    }

    private void setConfig(String key, String value) {
        configRepository.save(new SystemConfig(key, value));
    }

    private String maskSecret(String val) {
        if (val == null || val.isBlank()) return "";
        return "********";
    }
}
