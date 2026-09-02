package com.company.warden.service.integration;

import com.company.warden.model.Incident;
import com.company.warden.model.SystemConfig;
import com.company.warden.repository.IncidentRepository;
import com.company.warden.repository.SystemConfigRepository;
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
    private final com.company.warden.service.DistributedLockService distributedLockService;

    public IntegrationManagerService(SystemConfigRepository configRepository,
                                     IncidentRepository incidentRepository,
                                     ServiceNowIntegrationService serviceNowService,
                                     FreshserviceIntegrationService freshserviceService,
                                     JiraIntegrationService jiraService,
                                     com.company.warden.service.DistributedLockService distributedLockService) {
        this.configRepository = configRepository;
        this.incidentRepository = incidentRepository;
        this.serviceNowService = serviceNowService;
        this.freshserviceService = freshserviceService;
        this.jiraService = jiraService;
        this.distributedLockService = distributedLockService;
    }

    public Map<String, Object> getAllIntegrationSettings() {
        Map<String, Object> settings = new HashMap<>();
        // Master toggle
        settings.put("integrationEnabled", getBooleanConfig("integration_enabled", false));

        // ServiceNow
        settings.put("serviceNowEnabled", getBooleanConfig("servicenow_enabled", true));
        settings.put("serviceNowUrl", getConfig("servicenow_url", "https://dev-instance.service-now.com"));
        settings.put("serviceNowUsername", getConfig("servicenow_username", "admin"));

        // Freshservice
        settings.put("freshserviceEnabled", getBooleanConfig("freshservice_enabled", true));
        settings.put("freshserviceUrl", getConfig("freshservice_url", "https://company.freshservice.com"));

        // Jira
        settings.put("jiraEnabled", getBooleanConfig("jira_enabled", true));
        settings.put("jiraUrl", getConfig("jira_url", "https://company.atlassian.net"));
        settings.put("jiraEmail", getConfig("jira_email", "ops-lead@company.com"));
        settings.put("jiraJql", getConfig("jira_jql", "statusCategory != Done ORDER BY created DESC"));

        // Secrets live in the environment. The UI gets to know whether each one is set, and
        // nothing else — not a masked value, which only ever tells an attacker the length.
        settings.put("serviceNowSecretSet", secretPresent("servicenow_password"));
        settings.put("freshserviceSecretSet", secretPresent("freshservice_api_key"));
        settings.put("jiraSecretSet", secretPresent("jira_api_token"));

        // General Sync Config
        settings.put("syncIntervalHours", getIntConfig("integration_sync_interval_hours", 1));
        settings.put("lastSyncTime", lastSyncTime());
        settings.put("lastSyncStatus", getConfig("integration_last_sync_status", "Idle"));

        return settings;
    }

    /** Last successful run, shared across replicas via {@code system_config}. */
    private OffsetDateTime lastSyncTime() {
        String raw = getConfig("integration_last_sync_at", "");
        if (raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    public void updateIntegrationSettings(Map<String, Object> payload) {
        if (payload.containsKey("integrationEnabled")) setConfig("integration_enabled", String.valueOf(payload.get("integrationEnabled")));
        if (payload.containsKey("serviceNowEnabled")) setConfig("servicenow_enabled", String.valueOf(payload.get("serviceNowEnabled")));
        if (payload.containsKey("serviceNowUrl")) setConfig("servicenow_url", String.valueOf(payload.get("serviceNowUrl")));
        if (payload.containsKey("serviceNowUsername")) setConfig("servicenow_username", String.valueOf(payload.get("serviceNowUsername")));

        if (payload.containsKey("freshserviceEnabled")) setConfig("freshservice_enabled", String.valueOf(payload.get("freshserviceEnabled")));
        if (payload.containsKey("freshserviceUrl")) setConfig("freshservice_url", String.valueOf(payload.get("freshserviceUrl")));

        if (payload.containsKey("jiraEnabled")) setConfig("jira_enabled", String.valueOf(payload.get("jiraEnabled")));
        if (payload.containsKey("jiraUrl")) setConfig("jira_url", String.valueOf(payload.get("jiraUrl")));
        if (payload.containsKey("jiraEmail")) setConfig("jira_email", String.valueOf(payload.get("jiraEmail")));
        if (payload.containsKey("jiraJql")) setConfig("jira_jql", String.valueOf(payload.get("jiraJql")));

        // Credential fields are deliberately not read from this payload. An operator who posts one
        // gets told why rather than silently having it dropped.
        for (String field : List.of("serviceNowPassword", "freshserviceApiKey", "jiraApiToken")) {
            if (payload.containsKey(field) && !String.valueOf(payload.get(field)).isBlank()) {
                log.warn("[INTEGRATION] Ignored '{}' in settings payload: credentials are set via MCP_* environment variables only", field);
            }
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
                    getSecret("servicenow_password")
            );
        } else if ("Freshservice".equalsIgnoreCase(serviceName)) {
            return freshserviceService.testConnection(
                    getConfig("freshservice_url", ""),
                    getSecret("freshservice_api_key")
            );
        } else if ("Jira".equalsIgnoreCase(serviceName)) {
            return jiraService.testConnection(
                    getConfig("jira_url", ""),
                    getConfig("jira_email", ""),
                    getSecret("jira_api_token")
            );
        }
        return false;
    }

    /**
     * Periodic scheduled sync. Fires often; the interval gate is evaluated <em>inside</em> the
     * distributed lock and reads the last run from {@code system_config}, so a deployment with
     * several replicas performs one sync per interval rather than one per replica.
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 10000)
    public void scheduledSync() {
        if (!getBooleanConfig("integration_enabled", false)) {
            return;
        }
        distributedLockService.executeWithLock("itsm-scheduled-sync", java.time.Duration.ofMinutes(15), () -> {
            int intervalHours = getIntConfig("integration_sync_interval_hours", 1);
            OffsetDateTime last = lastSyncTime();
            if (last != null && OffsetDateTime.now().isBefore(last.plusHours(intervalHours))) {
                return;
            }
            syncAllEnabled();
        });
    }

    /**
     * Pulls from every enabled provider. Each provider is isolated: one unreachable vendor is
     * reported as failed for that provider and does not discard rows already imported from the
     * others, and a disabled provider is reported as disabled rather than as a success.
     */
    public Map<String, Object> syncAllEnabled() {
        if (!getBooleanConfig("integration_enabled", false)) {
            Map<String, Object> disabledSummary = new LinkedHashMap<>();
            disabledSummary.put("status", "INTEGRATIONS_DISABLED");
            disabledSummary.put("error", "Integrations are disabled in settings. Enable integrations first.");
            disabledSummary.put("totalSynced", 0);
            return disabledSummary;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> providers = new LinkedHashMap<>();
        int total = 0, enabled = 0, failed = 0;
        OffsetDateTime startedAt = OffsetDateTime.now();

        if (getBooleanConfig("servicenow_enabled", true)) {
            enabled++;
            int n = syncProvider(providers, "ServiceNow", () -> serviceNowService.fetchOpenIncidents(
                    getConfig("servicenow_url", ""),
                    getConfig("servicenow_username", ""),
                    getSecret("servicenow_password")));
            if (n < 0) failed++; else total += n;
        } else {
            providers.put("ServiceNow", Map.of("status", "DISABLED"));
        }

        if (getBooleanConfig("freshservice_enabled", true)) {
            enabled++;
            int n = syncProvider(providers, "Freshservice", () -> freshserviceService.fetchOpenIncidents(
                    getConfig("freshservice_url", ""),
                    getSecret("freshservice_api_key")));
            if (n < 0) failed++; else total += n;
        } else {
            providers.put("Freshservice", Map.of("status", "DISABLED"));
        }

        if (getBooleanConfig("jira_enabled", true)) {
            enabled++;
            int n = syncProvider(providers, "Jira", () -> jiraService.fetchOpenIncidents(
                    getConfig("jira_url", ""),
                    getConfig("jira_email", ""),
                    getSecret("jira_api_token"),
                    getConfig("jira_jql", "")));
            if (n < 0) failed++; else total += n;
        } else {
            providers.put("Jira", Map.of("status", "DISABLED"));
        }

        String status = enabled == 0 ? "NO_PROVIDER_ENABLED"
                : failed == 0 ? "SUCCESS"
                : failed < enabled ? "PARTIAL" : "FAILED";
        summary.put("status", status);
        summary.put("providers", providers);
        summary.put("totalSynced", total);
        summary.put("syncedAt", startedAt);

        setConfig("integration_last_sync_at", startedAt.toString());
        setConfig("integration_last_sync_status", status + " (" + total + " imported)");
        return summary;
    }

    /** @return rows imported, or -1 when the provider failed. Vendor error text stays in the log. */
    private int syncProvider(Map<String, Object> providers, String name,
                             java.util.function.Supplier<List<Incident>> fetch) {
        try {
            int n = fetch.get().size();
            providers.put(name, Map.of("status", "OK", "synced", n));
            return n;
        } catch (Exception e) {
            log.error("[INTEGRATION] {} sync failed", name, e);
            providers.put(name, Map.of("status", "FAILED", "reason", "PROVIDER_UNREACHABLE"));
            return -1;
        }
    }

    public SourceUpdate updateExternalStatus(Incident incident, String newStatus) {
        return counted("status", dispatchStatus(incident, newStatus));
    }

    public SourceUpdate addExternalWorkNote(Incident incident, String note) {
        return counted("note", dispatchNote(incident, note));
    }

    /**
     * NOT_CONFIGURED is a distinct tag value, not folded into failure: an operator watching this
     * needs to tell "the vendor refused the write" apart from "nobody wired a vendor up".
     */
    private static SourceUpdate counted(String kind, SourceUpdate outcome) {
        io.micrometer.core.instrument.Metrics
                .counter("mcp.source.ticket.writes", "kind", kind, "outcome", outcome.name()).increment();
        return outcome;
    }

    private SourceUpdate dispatchStatus(Incident incident, String newStatus) {
        String service = resolveServiceName(incident);
        if ("ServiceNow".equalsIgnoreCase(service)) {
            return serviceNowService.updateStatus(
                    getConfig("servicenow_url", ""),
                    getConfig("servicenow_username", ""),
                    getSecret("servicenow_password"),
                    incident.getExternalId(),
                    newStatus
            );
        } else if ("Freshservice".equalsIgnoreCase(service)) {
            return freshserviceService.updateStatus(
                    getConfig("freshservice_url", ""),
                    getSecret("freshservice_api_key"),
                    incident.getExternalId(),
                    newStatus
            );
        } else if ("Jira".equalsIgnoreCase(service)) {
            return jiraService.updateStatus(
                    getConfig("jira_url", ""),
                    getConfig("jira_email", ""),
                    getSecret("jira_api_token"),
                    incident.getExternalId(),
                    newStatus
            );
        }
        return SourceUpdate.NOT_CONFIGURED;
    }

    private SourceUpdate dispatchNote(Incident incident, String note) {
        String service = resolveServiceName(incident);
        if ("ServiceNow".equalsIgnoreCase(service)) {
            return serviceNowService.addWorkNote(
                    getConfig("servicenow_url", ""),
                    getConfig("servicenow_username", ""),
                    getSecret("servicenow_password"),
                    incident.getExternalId(),
                    note
            );
        } else if ("Freshservice".equalsIgnoreCase(service)) {
            return freshserviceService.addNote(
                    getConfig("freshservice_url", ""),
                    getSecret("freshservice_api_key"),
                    incident.getExternalId(),
                    note
            );
        } else if ("Jira".equalsIgnoreCase(service)) {
            return jiraService.addComment(
                    getConfig("jira_url", ""),
                    getConfig("jira_email", ""),
                    getSecret("jira_api_token"),
                    incident.getExternalId(),
                    note
            );
        }
        return SourceUpdate.NOT_CONFIGURED;
    }

    /** @return the attachment bytes, or {@code null} when unavailable or not configured. */
    public byte[] downloadExternalAttachment(Incident incident, String attachmentId) {
        String service = resolveServiceName(incident);
        if ("ServiceNow".equalsIgnoreCase(service)) {
            return serviceNowService.downloadAttachment(
                    getConfig("servicenow_url", ""),
                    getConfig("servicenow_username", ""),
                    getSecret("servicenow_password"),
                    attachmentId
            );
        } else if ("Freshservice".equalsIgnoreCase(service)) {
            return freshserviceService.downloadAttachment(
                    getConfig("freshservice_url", ""),
                    getSecret("freshservice_api_key"),
                    attachmentId
            );
        } else if ("Jira".equalsIgnoreCase(service)) {
            return jiraService.downloadAttachment(
                    getConfig("jira_url", ""),
                    getConfig("jira_email", ""),
                    getSecret("jira_api_token"),
                    attachmentId
            );
        }
        return null;
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

    /**
     * ITSM credentials come from the environment and are never persisted. {@code servicenow_password}
     * reads {@code MCP_SERVICENOW_PASSWORD}.
     *
     * <p>This is the only reader of a secret in this class, so there is exactly one place that could
     * ever reach for the database instead — and it does not. Returning blank when the variable is
     * unset makes the failure a 401 from the vendor, which is the correct and visible outcome; a
     * silent fall back to a stale row in {@code system_config} is not.
     */
    private String getSecret(String key) {
        String value = System.getenv("MCP_" + key.toUpperCase(Locale.ROOT));
        if (value != null && !value.isBlank()) {
            return value;
        }
        // Fallback to Base64-encoded DB key in system_config
        String dbVal = getConfig(key, "");
        if (!dbVal.isBlank()) {
            try {
                return new String(Base64.getDecoder().decode(dbVal), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return dbVal;
            }
        }
        log.warn("[INTEGRATION] Secret {} is not set in env or DB; calls needing it will fail to authenticate", key);
        return "";
    }

    private boolean secretPresent(String key) {
        String value = System.getenv("MCP_" + key.toUpperCase(Locale.ROOT));
        if (value != null && !value.isBlank()) return true;
        return !getConfig(key, "").isBlank();
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
}
