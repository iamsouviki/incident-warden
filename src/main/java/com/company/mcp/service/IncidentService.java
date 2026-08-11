package com.company.mcp.service;

import com.company.mcp.model.Incident;
import com.company.mcp.model.ExternalIncident;
import com.company.mcp.model.IncidentComment;
import com.company.mcp.model.IncidentHistory;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.repository.ExternalIncidentRepository;
import com.company.mcp.repository.IncidentCommentRepository;
import com.company.mcp.repository.IncidentHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.persistence.criteria.Predicate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ExternalIncidentRepository externalIncidentRepository;

    @Autowired
    private IncidentCommentRepository incidentCommentRepository;

    @Autowired
    private IncidentHistoryRepository incidentHistoryRepository;

    @Autowired
    private org.springframework.web.client.RestClient.Builder restClientBuilder;

    @Autowired
    private RagService ragService;

    @Autowired
    private AiConfigService aiConfigService;

    @jakarta.annotation.PostConstruct
    public void populateMissingExternalIds() {
        try {
            List<Incident> manuals = incidentRepository.findAll();
            long count = 1;
            for (Incident inc : manuals) {
                boolean updated = false;
                if (inc.getExternalSource() == null || "None".equals(inc.getExternalSource())) {
                    inc.setExternalSource("Internal");
                    updated = true;
                }
                if (inc.getExternalId() == null || inc.getExternalId().isBlank()) {
                    inc.setExternalId(String.format("INC%09d", count++));
                    updated = true;
                }
                if (updated) {
                    incidentRepository.save(inc);
                }
            }
        } catch (Exception e) {
            log.error("Failed to populate missing external IDs: {}", e.getMessage());
        }
    }

    // ServiceNow Settings
    @Value("${mcp.servicenow.enabled:false}")
    private boolean servicenowEnabled;
    @Value("${mcp.servicenow.instance-url:https://your-instance.service-now.com}")
    private String servicenowUrl;
    @Value("${mcp.servicenow.username:admin}")
    private String servicenowUser;
    @Value("${mcp.servicenow.password:changeme}")
    private String servicenowPassword;

    // FreshService Settings
    @Value("${mcp.freshservice.enabled:false}")
    private boolean freshserviceEnabled;
    @Value("${mcp.freshservice.domain:your-company}")
    private String freshserviceDomain;
    @Value("${mcp.freshservice.api-key:changeme}")
    private String freshserviceApiKey;

    // Jira Settings
    @Value("${mcp.jira.enabled:false}")
    private boolean jiraEnabled;
    @Value("${mcp.jira.url:https://your-domain.atlassian.net}")
    private String jiraUrl;
    @Value("${mcp.jira.username:}")
    private String jiraUser;
    @Value("${mcp.jira.api-token:}")
    private String jiraToken;

    private synchronized String generateNextTicketNumber() {
        long maxNum = 0;
        
        List<Incident> manuals = incidentRepository.findAll();
        for (Incident inc : manuals) {
            String extId = inc.getExternalId();
            if (extId != null && extId.startsWith("INC")) {
                try {
                    long num = Long.parseLong(extId.substring(3));
                    if (num > maxNum) maxNum = num;
                } catch (NumberFormatException ignored) {}
            }
        }
        
        List<ExternalIncident> externals = externalIncidentRepository.findAll();
        for (ExternalIncident ext : externals) {
            String extId = ext.getExternalId();
            if (extId != null && extId.startsWith("INC")) {
                try {
                    long num = Long.parseLong(extId.substring(3));
                    if (num > maxNum) maxNum = num;
                } catch (NumberFormatException ignored) {}
            }
        }
        
        long nextNum = maxNum + 1;
        return String.format("INC%09d", nextNum);
    }

    public Incident createIncident(Incident incident) {
        if (incident.getCreatedAt() == null) {
            incident.setCreatedAt(OffsetDateTime.now());
        }
        incident.setDueDate(calculateDueDate(incident.getCreatedAt(), incident.getPriority()));
        incident.setUpdatedAt(OffsetDateTime.now());
        
        if (incident.getExternalSource() == null || incident.getExternalSource().equals("None")) {
            incident.setExternalSource("Internal");
        }
        if (incident.getExternalId() == null || incident.getExternalId().isBlank()) {
            incident.setExternalId(generateNextTicketNumber());
        }

        // Apply Confidence Scoring and Routing
        double score = calculateConfidenceScore(incident);
        incident.setConfidenceScore(score);
        routeIncident(incident, score);
        
        Incident saved = incidentRepository.save(incident);

        // Record initial history
        saveHistoryRecord(saved.getId(), "Incident Created", null, incident.getStatus(), "System");
        return saved;
    }

    private double calculateConfidenceScore(Incident incident) {
        // ponytail: deterministic baseline until the agent scorer exists; thresholds remain configurable.
        String subject = Optional.ofNullable(incident.getSubject()).orElse("").toLowerCase();
        String description = Optional.ofNullable(incident.getDescription()).orElse("");
        double score = 50.0;
        if (subject.contains("restart") || subject.contains("reset")) score += 20.0;
        if ("P1".equalsIgnoreCase(incident.getPriority())) score -= 10.0;
        if (description.length() > 50) score += 10.0;
        return Math.min(100.0, Math.max(0.0, score));
    }

    private double threshold(String value, double fallback) {
        try {
            double parsed = Double.parseDouble(value);
            // The UI stores thresholds as 0.80 / 1.00 while scores are 0-100.
            if (parsed > 0 && parsed <= 1.0) parsed *= 100.0;
            return Math.min(100.0, Math.max(0.0, parsed));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void routeIncident(Incident incident, double score) {
        double autoThreshold = threshold(aiConfigService.getAutoResolveThreshold(), 100.0);
        double hitlThreshold = threshold(aiConfigService.getHitlThreshold(), 80.0);

        if (score >= autoThreshold) {
            incident.setStatus("AUTO_RESOLVED");
        } else if (score >= hitlThreshold) {
            incident.setStatus("PENDING_APPROVAL");
        } else {
            incident.setStatus("New");
        }
    }

    public Map<String, Object> decideIncident(UUID id, String decision, String reason, String actor) {
        String normalized = Optional.ofNullable(decision).orElse("").trim().toUpperCase(Locale.ROOT);
        String nextStatus = switch (normalized) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            default -> throw new IllegalArgumentException("decision must be APPROVE or REJECT");
        };
        String by = actor == null || actor.isBlank() ? "User" : actor;
        String note = reason == null || reason.isBlank() ? "HITL decision: " + normalized : reason;

        Optional<Incident> manual = incidentRepository.findById(id);
        if (manual.isPresent()) {
            Incident incident = manual.get();
            String previous = incident.getStatus();
            incident.setStatus(nextStatus);
            incident.setUpdatedAt(OffsetDateTime.now());
            incidentRepository.save(incident);
            saveHistoryRecord(id, "status", previous, nextStatus, by);
            addComment(id, by, note);
            return Map.of("id", id, "status", nextStatus, "decision", normalized);
        }

        ExternalIncident incident = externalIncidentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Incident not found with ID: " + id));
        String previous = incident.getStatus();
        incident.setStatus(nextStatus);
        incident.setUpdatedAt(OffsetDateTime.now());
        externalIncidentRepository.save(incident);
        saveHistoryRecord(id, "status", previous, nextStatus, by);
        addComment(id, by, note);
        return Map.of("id", id, "status", nextStatus, "decision", normalized);
    }

    public OffsetDateTime calculateDueDate(OffsetDateTime createdAt, String priority) {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if ("P1".equalsIgnoreCase(priority)) {
            return createdAt.plusHours(8);
        } else if ("P2".equalsIgnoreCase(priority)) {
            return createdAt.plusDays(1);
        } else {
            return createdAt.plusDays(3);
        }
    }

    private Specification<Incident> buildIncidentSpecification(
            String subject, String description, String assignee, String assignedGteam,
            String priority, String createdDate, String updatedDate, String dueDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (subject != null && !subject.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("subject")), "%" + subject.toLowerCase() + "%"));
            }
            if (description != null && !description.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("description")), "%" + description.toLowerCase() + "%"));
            }
            if (assignee != null && !assignee.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("assignee")), "%" + assignee.toLowerCase() + "%"));
            }
            if (assignedGteam != null && !assignedGteam.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("assignedGteam")), "%" + assignedGteam.toLowerCase() + "%"));
            }
            if (priority != null && !priority.isBlank()) {
                predicates.add(cb.equal(root.get("priority"), priority));
            }

            if (createdDate != null && !createdDate.isBlank()) {
                try {
                    OffsetDateTime start = OffsetDateTime.parse(createdDate + "T00:00:00Z");
                    OffsetDateTime end = OffsetDateTime.parse(createdDate + "T23:59:59Z");
                    predicates.add(cb.between(root.get("createdAt"), start, end));
                } catch (Exception e) {
                    log.warn("Invalid createdDate format: {}", createdDate);
                }
            }
            if (updatedDate != null && !updatedDate.isBlank()) {
                try {
                    OffsetDateTime start = OffsetDateTime.parse(updatedDate + "T00:00:00Z");
                    OffsetDateTime end = OffsetDateTime.parse(updatedDate + "T23:59:59Z");
                    predicates.add(cb.between(root.get("updatedAt"), start, end));
                } catch (Exception e) {
                    log.warn("Invalid updatedDate format: {}", updatedDate);
                }
            }
            if (dueDate != null && !dueDate.isBlank()) {
                try {
                    OffsetDateTime start = OffsetDateTime.parse(dueDate + "T00:00:00Z");
                    OffsetDateTime end = OffsetDateTime.parse(dueDate + "T23:59:59Z");
                    predicates.add(cb.between(root.get("dueDate"), start, end));
                } catch (Exception e) {
                    log.warn("Invalid dueDate format: {}", dueDate);
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Specification<ExternalIncident> buildExternalIncidentSpecification(
            String subject, String description, String assignee, String assignedGteam,
            String priority, String createdDate, String updatedDate, String dueDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (subject != null && !subject.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("subject")), "%" + subject.toLowerCase() + "%"));
            }
            if (description != null && !description.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("description")), "%" + description.toLowerCase() + "%"));
            }
            if (assignee != null && !assignee.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("assignee")), "%" + assignee.toLowerCase() + "%"));
            }
            if (assignedGteam != null && !assignedGteam.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("assignedGteam")), "%" + assignedGteam.toLowerCase() + "%"));
            }
            if (priority != null && !priority.isBlank()) {
                predicates.add(cb.equal(root.get("priority"), priority));
            }

            if (createdDate != null && !createdDate.isBlank()) {
                try {
                    OffsetDateTime start = OffsetDateTime.parse(createdDate + "T00:00:00Z");
                    OffsetDateTime end = OffsetDateTime.parse(createdDate + "T23:59:59Z");
                    predicates.add(cb.between(root.get("createdAt"), start, end));
                } catch (Exception e) {
                    log.warn("Invalid createdDate format: {}", createdDate);
                }
            }
            if (updatedDate != null && !updatedDate.isBlank()) {
                try {
                    OffsetDateTime start = OffsetDateTime.parse(updatedDate + "T00:00:00Z");
                    OffsetDateTime end = OffsetDateTime.parse(updatedDate + "T23:59:59Z");
                    predicates.add(cb.between(root.get("updatedAt"), start, end));
                } catch (Exception e) {
                    log.warn("Invalid updatedDate format: {}", updatedDate);
                }
            }
            if (dueDate != null && !dueDate.isBlank()) {
                try {
                    OffsetDateTime start = OffsetDateTime.parse(dueDate + "T00:00:00Z");
                    OffsetDateTime end = OffsetDateTime.parse(dueDate + "T23:59:59Z");
                    predicates.add(cb.between(root.get("dueDate"), start, end));
                } catch (Exception e) {
                    log.warn("Invalid dueDate format: {}", dueDate);
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    public List<Incident> searchIncidents(
            String subject, String description, String assignee, String assignedGteam,
            String priority, String createdDate, String updatedDate, String dueDate) {

        List<Incident> manual = incidentRepository.findAll(
                buildIncidentSpecification(subject, description, assignee, assignedGteam, priority, createdDate, updatedDate, dueDate)
        );

        List<ExternalIncident> external = externalIncidentRepository.findAll(
                buildExternalIncidentSpecification(subject, description, assignee, assignedGteam, priority, createdDate, updatedDate, dueDate)
        );

        List<Incident> combined = new ArrayList<>();
        combined.addAll(manual);
        for (ExternalIncident ext : external) {
            combined.add(convertToIncident(ext));
        }

        // Sort by created date descending
        combined.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return combined;
    }

    public Incident getIncidentById(UUID id) {
        Optional<Incident> manual = incidentRepository.findById(id);
        if (manual.isPresent()) {
            return manual.get();
        }

        Optional<ExternalIncident> external = externalIncidentRepository.findById(id);
        if (external.isPresent()) {
            return convertToIncident(external.get());
        }

        throw new NoSuchElementException("Incident not found with ID: " + id);
    }

    public List<IncidentComment> getComments(UUID incidentId) {
        return incidentCommentRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId);
    }

    public IncidentComment addComment(UUID incidentId, String author, String commentText) {
        IncidentComment comment = new IncidentComment(UUID.randomUUID(), incidentId, author, commentText, OffsetDateTime.now());
        return incidentCommentRepository.save(comment);
    }

    public List<IncidentHistory> getHistory(UUID incidentId) {
        return incidentHistoryRepository.findByIncidentIdOrderByUpdatedAtDesc(incidentId);
    }

    public synchronized Incident updateIncident(UUID id, Incident details, String updatedBy) {
        Optional<Incident> manualOpt = incidentRepository.findById(id);
        if (manualOpt.isPresent()) {
            Incident existing = manualOpt.get();
            updateIncidentFields(existing, details, updatedBy);
            return incidentRepository.save(existing);
        }

        Optional<ExternalIncident> extOpt = externalIncidentRepository.findById(id);
        if (extOpt.isPresent()) {
            ExternalIncident existingExt = extOpt.get();
            Incident dummy = convertToIncident(existingExt);
            updateIncidentFields(dummy, details, updatedBy);
            
            // Map back to external
            existingExt.setSubject(dummy.getSubject());
            existingExt.setDescription(dummy.getDescription());
            existingExt.setAssignee(dummy.getAssignee());
            existingExt.setAssignedGteam(dummy.getAssignedGteam());
            existingExt.setPriority(dummy.getPriority());
            existingExt.setStatus(dummy.getStatus());
            existingExt.setDueDate(dummy.getDueDate());
            existingExt.setUpdatedAt(OffsetDateTime.now());

            externalIncidentRepository.save(existingExt);
            return dummy;
        }

        throw new NoSuchElementException("Incident not found with ID: " + id);
    }

    private void updateIncidentFields(Incident existing, Incident details, String updatedBy) {
        if (updatedBy == null || updatedBy.isBlank()) {
            updatedBy = "User";
        }

        UUID id = existing.getId();
        if (!Objects.equals(existing.getSubject(), details.getSubject())) {
            saveHistoryRecord(id, "subject", existing.getSubject(), details.getSubject(), updatedBy);
            existing.setSubject(details.getSubject());
        }
        if (!Objects.equals(existing.getDescription(), details.getDescription())) {
            saveHistoryRecord(id, "description", existing.getDescription(), details.getDescription(), updatedBy);
            existing.setDescription(details.getDescription());
        }
        if (!Objects.equals(existing.getAssignee(), details.getAssignee())) {
            saveHistoryRecord(id, "assignee", existing.getAssignee(), details.getAssignee(), updatedBy);
            existing.setAssignee(details.getAssignee());
        }
        if (!Objects.equals(existing.getAssignedGteam(), details.getAssignedGteam())) {
            saveHistoryRecord(id, "assigned_gteam", existing.getAssignedGteam(), details.getAssignedGteam(), updatedBy);
            existing.setAssignedGteam(details.getAssignedGteam());
        }
        if (!Objects.equals(existing.getPriority(), details.getPriority())) {
            saveHistoryRecord(id, "priority", existing.getPriority(), details.getPriority(), updatedBy);
            existing.setPriority(details.getPriority());
            existing.setDueDate(calculateDueDate(existing.getCreatedAt(), details.getPriority()));
        }
        if (!Objects.equals(existing.getStatus(), details.getStatus())) {
            saveHistoryRecord(id, "status", existing.getStatus(), details.getStatus(), updatedBy);
            existing.setStatus(details.getStatus());
        }
        existing.setUpdatedAt(OffsetDateTime.now());
    }

    private void saveHistoryRecord(UUID incidentId, String fieldName, String oldValue, String newValue, String updatedBy) {
        IncidentHistory history = new IncidentHistory(
                UUID.randomUUID(), incidentId, fieldName, oldValue, newValue, updatedBy, OffsetDateTime.now()
        );
        incidentHistoryRepository.save(history);
    }

    private Incident convertToIncident(ExternalIncident ext) {
        Incident inc = new Incident();
        inc.setId(ext.getId());
        inc.setSubject(ext.getSubject());
        inc.setDescription(ext.getDescription());
        inc.setAssignee(ext.getAssignee());
        inc.setAssignedGteam(ext.getAssignedGteam());
        inc.setPriority(ext.getPriority());
        inc.setStatus(ext.getStatus());
        inc.setCreatedAt(ext.getCreatedAt());
        inc.setUpdatedAt(ext.getUpdatedAt());
        inc.setDueDate(ext.getDueDate());
        inc.setExternalSource(ext.getExternalSource());
        inc.setExternalId(ext.getExternalId());
        inc.setCategory(ext.getCategory());
        inc.setConfidenceScore(ext.getConfidenceScore());
        return inc;
    }

    public synchronized Map<String, Object> syncExternalIncidents() {
        int servicenowCount = 0;
        int freshserviceCount = 0;
        int jiraCount = 0;

        // 1. ServiceNow Sync
        if (servicenowEnabled && !servicenowUrl.contains("your-instance")) {
            try {
                RestClient client = restClientBuilder
                        .baseUrl(servicenowUrl)
                        .requestInterceptor(new BasicAuthenticationInterceptor(servicenowUser, servicenowPassword))
                        .build();
                
                Map<String, Object> response = client.get()
                        .uri("/api/now/table/incident?sysparm_limit=10")
                        .retrieve()
                        .body(Map.class);
                
                if (response != null && response.containsKey("result")) {
                    List<Map<String, Object>> incidents = (List<Map<String, Object>>) response.get("result");
                    for (Map<String, Object> item : incidents) {
                        String sysId = (String) item.get("sys_id");
                        String number = (String) item.get("number");
                        String shortDesc = (String) item.get("short_description");
                        String desc = (String) item.get("description");
                        String urgency = (String) item.get("urgency");
                        String p = "P3";
                        if ("1".equals(urgency)) p = "P1";
                        else if ("2".equals(urgency)) p = "P2";

                        saveExternalIncident(sysId, shortDesc, desc, p, "ServiceNow", number);
                        servicenowCount++;
                    }
                }
            } catch (Exception e) {
                log.error("Failed ServiceNow sync: {}", e.getMessage());
            }
        } else {
            log.info("ServiceNow not configured, generating mock tickets...");
            saveExternalIncident("sn-9012", "Database connection timeout in prod", "Oracle DB connection pool exhausted.", "P1", "ServiceNow", "INC0010921");
            saveExternalIncident("sn-9013", "VPN Access issue for remote employees", "Okta MFA timeout errors.", "P3", "ServiceNow", "INC0010922");
            servicenowCount = 2;
        }

        // 2. FreshService Sync
        if (freshserviceEnabled && !freshserviceDomain.contains("your-company")) {
            try {
                String url = String.format("https://%s.freshservice.com", freshserviceDomain);
                RestClient client = restClientBuilder
                        .baseUrl(url)
                        .requestInterceptor(new BasicAuthenticationInterceptor(freshserviceApiKey, "X"))
                        .build();

                Map<String, Object> response = client.get()
                        .uri("/api/v2/tickets?per_page=10")
                        .retrieve()
                        .body(Map.class);

                if (response != null && response.containsKey("tickets")) {
                    List<Map<String, Object>> tickets = (List<Map<String, Object>>) response.get("tickets");
                    for (Map<String, Object> item : tickets) {
                        Integer id = (Integer) item.get("id");
                        String subject = (String) item.get("subject");
                        String desc = (String) item.get("description_text");
                        Integer priorityVal = (Integer) item.get("priority");
                        String p = "P3";
                        if (priorityVal != null) {
                            if (priorityVal == 4) p = "P1";
                            else if (priorityVal == 3) p = "P2";
                        }

                        saveExternalIncident(String.valueOf(id), subject, desc, p, "Freshservice", "FS-" + id);
                        freshserviceCount++;
                    }
                }
            } catch (Exception e) {
                log.error("Failed Freshservice sync: {}", e.getMessage());
            }
        } else {
            log.info("Freshservice not configured, generating mock tickets...");
            saveExternalIncident("fs-501", "LDAP synchronization failure", "LDAP connector fails to update AD group mappings.", "P2", "Freshservice", "FS-12948");
            saveExternalIncident("fs-502", "Printer offline in HR wing", "Hardware jam or paper tray empty.", "P3", "Freshservice", "FS-12949");
            freshserviceCount = 2;
        }

        // 3. Jira Sync
        if (jiraEnabled && jiraUrl != null && !jiraUrl.contains("your-domain")) {
            try {
                RestClient client = restClientBuilder
                        .baseUrl(jiraUrl)
                        .requestInterceptor(new BasicAuthenticationInterceptor(jiraUser, jiraToken))
                        .build();

                Map<String, Object> response = client.get()
                        .uri("/rest/api/2/search?jql=issuetype=Incident&maxResults=10")
                        .retrieve()
                        .body(Map.class);

                if (response != null && response.containsKey("issues")) {
                    List<Map<String, Object>> issues = (List<Map<String, Object>>) response.get("issues");
                    for (Map<String, Object> item : issues) {
                        String id = (String) item.get("id");
                        String key = (String) item.get("key");
                        Map<String, Object> fields = (Map<String, Object>) item.get("fields");
                        String summary = (String) fields.get("summary");
                        String desc = (String) fields.get("description");
                        Map<String, Object> priorityObj = (Map<String, Object>) fields.get("priority");
                        String p = "P3";
                        if (priorityObj != null) {
                            String pName = (String) priorityObj.get("name");
                            if ("Highest".equalsIgnoreCase(pName) || "Critical".equalsIgnoreCase(pName)) p = "P1";
                            else if ("High".equalsIgnoreCase(pName)) p = "P2";
                        }

                        saveExternalIncident(id, summary, desc, p, "Jira", key);
                        jiraCount++;
                    }
                }
            } catch (Exception e) {
                log.error("Failed Jira sync: {}", e.getMessage());
            }
        } else {
            log.info("Jira not configured, generating mock tickets...");
            saveExternalIncident("jira-101", "K8s ingress failing for core gateway", "Ingress controller routing loop on standard ports.", "P1", "Jira", "OPS-4829");
            saveExternalIncident("jira-102", "Disk space utilization alert 90%", "Instance /dev/sdb1 near full capacity.", "P2", "Jira", "OPS-4830");
            jiraCount = 2;
        }

        return Map.of(
                "ServiceNow", servicenowCount,
                "Freshservice", freshserviceCount,
                "Jira", jiraCount,
                "status", "success"
        );
    }

    private void saveExternalIncident(String extId, String subject, String description, String priority, String source, String extKey) {
        // Find existing external incident in external repository
        List<ExternalIncident> existing = externalIncidentRepository.findAll((Specification<ExternalIncident>) (root, query, cb) -> cb.and(
                cb.equal(root.get("externalSource"), source),
                cb.equal(root.get("externalId"), extKey)
        ));

        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            // Apply Confidence Scoring and Routing for External
            Incident dummy = Incident.builder()
                    .subject(subject)
                    .description(description)
                    .priority(priority)
                    .build();
            double score = calculateConfidenceScore(dummy);
            String status = "New";
            if (score >= threshold(aiConfigService.getAutoResolveThreshold(), 100.0)) status = "AUTO_RESOLVED";
            else if (score >= threshold(aiConfigService.getHitlThreshold(), 80.0)) status = "PENDING_APPROVAL";

            ExternalIncident incident = ExternalIncident.builder()
                    .id(id)
                    .subject(subject != null ? subject : "Untitled external ticket")
                    .description(description != null ? description : "")
                    .priority(priority)
                    .status(status)
                    .externalSource(source)
                    .externalId(extKey)
                    .assignee("Unassigned")
                    .assignedGteam("IT Ops")
                    .createdAt(OffsetDateTime.now())
                    .dueDate(calculateDueDate(OffsetDateTime.now(), priority))
                    .updatedAt(OffsetDateTime.now())
                    .confidenceScore(score)
                    .category("Universal")
                    .build();
            externalIncidentRepository.save(incident);
            
            // Record history
            saveHistoryRecord(id, "Incident Synced", null, "New", "System");
        }
    }

    public Map<String, String> analyzeIncident(String subject, String description) {
        String team = autoAssignTeam(subject, description);
        String resolution = suggestResolution(subject, description);
        return Map.of("suggestedTeam", team, "suggestedResolution", resolution);
    }

    private String autoAssignTeam(String subject, String description) {
        try {
            org.springframework.ai.chat.client.ChatClient chatClient = ragService.getOrBuildChatClient();
            if (chatClient == null) return "IT Ops";
            String prompt = """
                    You are a team classification assistant. Read the following incident subject and description, and assign it to exactly one of these teams:
                    - IT Ops (Default for general/hardware/access/OS issues)
                    - SecOps (For security, authentication, firewall, vulnerability issues)
                    - Network Team (For routing, VPN, DNS, network timeouts, bandwidth issues)
                    - Database Admins (For SQL errors, connection pools, DB performance, database timeouts)
                    
                    Subject: %s
                    Description: %s
                    
                    Respond with ONLY the exact team name from the list. Do not write any other explanation or words.
                    """.formatted(subject, description);
            String response = chatClient.prompt().user(prompt).call().content();
            if (response != null) {
                String clean = response.trim();
                if (clean.toLowerCase().contains("secops")) return "SecOps";
                if (clean.toLowerCase().contains("network")) return "Network Team";
                if (clean.toLowerCase().contains("database") || clean.toLowerCase().contains("db")) return "Database Admins";
            }
        } catch (Exception e) {
            log.error("Failed to auto-assign team: {}", e.getMessage());
        }
        return "IT Ops";
    }

    private String suggestResolution(String subject, String description) {
        String question = subject + " " + description;
        
        // 1. Try RAG
        try {
            String answer = ragService.askStrictSopRag(UUID.randomUUID().toString(), question);
            if (answer != null && !answer.contains("couldn't find") && !answer.contains("NOT_FOUND") && !answer.contains("error occurred")) {
                return answer + "\n\n(Source: RAG Knowledge Base)";
            }
        } catch (Exception e) {
            log.warn("RAG resolution check failed: {}", e.getMessage());
        }

        // 2. Try Web Search
        String searchResults = searchWeb(question);
        
        // 3. LLM resolution using web results
        try {
            org.springframework.ai.chat.client.ChatClient chatClient = ragService.getOrBuildChatClient();
            if (chatClient != null) {
                String prompt = """
                        You are a technical resolution suggestion assistant. An incident has occurred, and the RAG system has no matching SOP.
                        We searched the web and found the following references:
                        %s
                        
                        Incident Subject: %s
                        Incident Description: %s
                        
                        Based on the incident details and any web search results, suggest a step-by-step resolution.
                        """.formatted(searchResults, subject, description);
                String suggestion = chatClient.prompt().user(prompt).call().content();
                if (suggestion != null && !suggestion.isBlank()) {
                    return suggestion + "\n\n(Source: Web Search & LLM)";
                }
            }
        } catch (Exception e) {
            log.error("LLM suggestion generation failed: {}", e.getMessage());
        }

        return "No automated suggestion available. Please route to the appropriate team for diagnostics.";
    }

    private String searchWeb(String query) {
        try {
            String encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encodedQuery;
            
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .GET()
                    .build();
                    
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String html = response.body();
                java.util.regex.Pattern snippetPattern = java.util.regex.Pattern.compile("<a class=\"result__snippet\"[^>]*>(.*?)</a>", java.util.regex.Pattern.DOTALL);
                java.util.regex.Matcher matcher = snippetPattern.matcher(html);
                StringBuilder sb = new StringBuilder();
                int count = 0;
                while (matcher.find() && count < 5) {
                    String snippet = matcher.group(1).replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
                    sb.append("- ").append(snippet).append("\n");
                    count++;
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            log.warn("Web search failed, returning empty context: {}", e.getMessage());
        }
        return "No web results found due to network error.";
    }

    public List<IncidentHistory> getAllHistory() {
        return incidentHistoryRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "updatedAt"));
    }
}
