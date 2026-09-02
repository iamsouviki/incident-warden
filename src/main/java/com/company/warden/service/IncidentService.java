package com.company.warden.service;

import com.company.warden.config.CurrentUser;
import com.company.warden.model.Incident;
import com.company.warden.model.IncidentComment;
import com.company.warden.repository.IncidentRepository;
import com.company.warden.repository.IncidentCommentRepository;
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
    private IncidentCommentRepository incidentCommentRepository;

    @Autowired
    private org.springframework.web.client.RestClient.Builder restClientBuilder;

    @Autowired
    private RagService ragService;

    @Autowired
    private AiConfigService aiConfigService;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private NotificationService notificationService;

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
        long maxNum = orZero(incidentRepository.findMaxInternalTicketNumber());
        return String.format("INC%09d", maxNum + 1);
    }

    private long orZero(Long value) { return value == null ? 0L : value; }

    public Optional<Incident> findTelemetryIncident(String correlationKey) {
        return incidentRepository.findFirstByExternalSourceAndExternalId("Telemetry", correlationKey);
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

        if ((incident.getReporterEmail() == null || incident.getReporterEmail().isBlank())
                && "Internal".equals(incident.getExternalSource())) {
            incident.setReporterEmail(notificationService.addressOfUser(currentUser.username()));
        }

        // No score, and so no scoring branch. The number this used to compute was a keyword
        // heuristic ("subject contains printer" was worth 25 points) whose only effect was
        // choosing between two status labels. A new incident is New; what happens to it is
        // decided by whether an approved SOP and a known tool exist, which is the planner's
        // job and is evidence rather than arithmetic.
        return incidentRepository.save(incident);
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

        Incident incident = incidentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Incident not found with ID: " + id));
        incident.setStatus(nextStatus);
        incident.setUpdatedAt(OffsetDateTime.now());
        incidentRepository.save(incident);
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

    private <T> Specification<T> buildIncidentSearchSpecification(
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

            addDayRange(predicates, cb, root, "createdAt", createdDate);
            addDayRange(predicates, cb, root, "updatedAt", updatedDate);
            addDayRange(predicates, cb, root, "dueDate", dueDate);

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private void addDayRange(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder cb,
                             jakarta.persistence.criteria.Root<?> root, String field, String day) {
        if (day == null || day.isBlank()) return;
        try {
            predicates.add(cb.between(root.get(field),
                    OffsetDateTime.parse(day + "T00:00:00Z"),
                    OffsetDateTime.parse(day + "T23:59:59Z")));
        } catch (Exception e) {
            log.warn("Invalid {} format: {}", field, day);
        }
    }

    public List<Incident> searchIncidents(
            String subject, String description, String assignee, String assignedGteam,
            String priority, String createdDate, String updatedDate, String dueDate) {

        List<Incident> results = incidentRepository.findAll(
                this.<Incident>buildIncidentSearchSpecification(subject, description, assignee,
                        assignedGteam, priority, createdDate, updatedDate, dueDate)
        );

        results.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return results;
    }

    public Incident getIncidentById(UUID id) {
        return incidentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Incident not found with ID: " + id));
    }

    public List<IncidentComment> getComments(UUID incidentId) {
        getIncidentById(incidentId);
        return incidentCommentRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId);
    }

    public IncidentComment addComment(UUID incidentId, String author, String commentText) {
        getIncidentById(incidentId);
        IncidentComment comment = new IncidentComment(UUID.randomUUID(), incidentId, author, commentText, OffsetDateTime.now());
        return incidentCommentRepository.save(comment);
    }

    public List<Map<String, Object>> getHistory(UUID incidentId) {
        getIncidentById(incidentId);
        return List.of();
    }

    public synchronized Incident updateIncident(UUID id, Incident details, String updatedBy) {
        Incident existing = incidentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Incident not found with ID: " + id));
        List<String> changes = updateIncidentFields(existing, details, updatedBy);
        Incident saved = incidentRepository.save(existing);
        notificationService.notifyIncidentUpdated(saved, changes, updatedBy);
        return saved;
    }

    private List<String> updateIncidentFields(Incident existing, Incident details, String updatedBy) {
        if (updatedBy == null || updatedBy.isBlank()) {
            updatedBy = "User";
        }

        List<String> changes = new ArrayList<>();
        if (supplied(existing.getSubject(), details.getSubject())) {
            changes.add(describe("Subject", existing.getSubject(), details.getSubject()));
            existing.setSubject(details.getSubject());
        }
        if (supplied(existing.getDescription(), details.getDescription())) {
            changes.add("Description edited");
            existing.setDescription(details.getDescription());
        }
        if (supplied(existing.getAssignee(), details.getAssignee())) {
            changes.add(describe("Assignee", existing.getAssignee(), details.getAssignee()));
            existing.setAssignee(details.getAssignee());
        }
        if (supplied(existing.getAssignedGteam(), details.getAssignedGteam())) {
            changes.add(describe("Assigned group", existing.getAssignedGteam(), details.getAssignedGteam()));
            existing.setAssignedGteam(details.getAssignedGteam());
        }
        if (supplied(existing.getPriority(), details.getPriority())) {
            changes.add(describe("Priority", existing.getPriority(), details.getPriority()));
            existing.setPriority(details.getPriority());
            existing.setDueDate(calculateDueDate(existing.getCreatedAt(), details.getPriority()));
        }
        if (supplied(existing.getStatus(), details.getStatus())) {
            changes.add(describe("Status", existing.getStatus(), details.getStatus()));
            existing.setStatus(details.getStatus());
        }
        if (supplied(existing.getStoreNumber(), details.getStoreNumber())) {
            changes.add(describe("Store", existing.getStoreNumber(), details.getStoreNumber()));
            existing.setStoreNumber(details.getStoreNumber());
        }
        if (supplied(existing.getTargetHost(), details.getTargetHost())) {
            changes.add(describe("Server", existing.getTargetHost(), details.getTargetHost()));
            existing.setTargetHost(details.getTargetHost());
        }
        if (supplied(existing.getConnectionMethod(), details.getConnectionMethod())) {
            changes.add(describe("Connection", existing.getConnectionMethod(), details.getConnectionMethod()));
            existing.setConnectionMethod(details.getConnectionMethod());
        }
        if (supplied(existing.getTargetPlatform(), details.getTargetPlatform())) {
            changes.add(describe("Platform", existing.getTargetPlatform(), details.getTargetPlatform()));
            existing.setTargetPlatform(details.getTargetPlatform());
        }
        existing.setUpdatedAt(OffsetDateTime.now());
        return changes;
    }

    private static boolean supplied(String current, String incoming) {
        return incoming != null && !Objects.equals(current, incoming);
    }

    private static String describe(String field, String from, String to) {
        return "%s: %s → %s".formatted(field,
                from == null || from.isBlank() ? "(unset)" : from,
                to == null || to.isBlank() ? "(unset)" : to);
    }

    private void saveExternalIncident(String extId, String subject, String description, String priority, String source, String extKey) {
        Optional<Incident> existing = incidentRepository.findByExternalId(extKey);
        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();

            Incident incident = Incident.builder()
                    .id(id)
                    .subject(subject != null ? subject : "Untitled external ticket")
                    .description(description != null ? description : "")
                    .priority(priority)
                    .status("New")
                    .externalSource(source)
                    .externalId(extKey)
                    .assignee("Unassigned")
                    .assignedGteam("IT Ops")
                    .createdAt(OffsetDateTime.now())
                    .dueDate(calculateDueDate(OffsetDateTime.now(), priority))
                    .updatedAt(OffsetDateTime.now())
                    .category("Universal")
                    .build();
            incidentRepository.save(incident);
        }
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

    public Map<String, String> analyzeIncident(String subject, String description) {
        Suggestion refusal = analysisRefusal(subject, description);
        if (refusal != null) return toMap("IT Ops", refusal);
        String team = autoAssignTeam(subject, description);
        Suggestion suggestion = suggestResolution(subject, description);
        return toMap(team, suggestion);
    }

    /** Longest ticket text worth sending to a model. Shared with chat: {@link RagService#MAX_TEXT_CHARS}. */
    static final int MAX_ANALYSIS_CHARS = RagService.MAX_TEXT_CHARS;

    /**
     * Why this ticket will not be analysed, or null to go ahead.
     *
     * This endpoint is the most expensive one in the product — two to three model calls per
     * request — and it used to run all of that on any text an
     * authenticated caller posted. Two consequences, both real: an oversized body became an
     * unbounded prompt, and a ticket that was not about IT at all ("write me a poem about
     * cats") missed every SOP and came back as a general-purpose
     * assistant answer wearing the platform's badge. The chat endpoint already refused
     * exactly that; this one had no equivalent.
     *
     * Refusing costs nothing and is the fast path: no model call at all.
     *
     * ponytail: a keyword scope list, shared with chat. It is a filter on obvious misuse,
     * not a defence against a determined prompt injection inside a plausible ticket — that
     * needs output-side constraints, and the guarded-plan allowlist is where the platform
     * already puts them, because nothing this method returns can execute.
     */
    private Suggestion analysisRefusal(String subject, String description) {
        String text = (trim(subject) + " " + trim(description)).trim();
        RagService.Refusal refusal = ragService.refuse(text);
        if (refusal == null) return null;
        switch (refusal) {
            case BLANK:
                return new Suggestion("Add a subject or description before asking for analysis.",
                        "NONE", "Nothing to analyse", "The ticket has no text yet, so there is nothing to compare against your SOPs.");
            case TOO_LONG:
                return new Suggestion("This ticket is too long to analyse. Shorten it to " + RagService.MAX_TEXT_CHARS + " characters or fewer.",
                        "NONE", "Too long to analyse",
                        "The subject and description together are " + text.length() + " characters. Trim them to the essentials and try again.");
            default:
                log.info("[ANALYZE] Refused out-of-scope text ({} chars)", text.length());
                return new Suggestion("This does not look like an IT incident, so it was not analysed.",
                        "NONE", "Outside what this assistant covers",
                        "The assistant only answers questions about incidents, devices, services and your own runbooks. Reword the ticket around the fault you are seeing.");
        }
    }

    private Map<String, String> toMap(String team, Suggestion suggestion) {
        return Map.of("suggestedTeam", team,
                "suggestedResolution", suggestion.text(),
                "source", suggestion.source(),
                "sourceLabel", suggestion.label(),
                "sourceDetail", suggestion.detail());
    }

    /**
     * A suggested fix and an honest account of where it came from.
     *
     * The provenance used to be a suffix glued onto the prose — "(Source: RAG Knowledge
     * Base)" — which is both untranslatable in the UI and jargon at the one place a
     * non-engineer is deciding whether to trust the advice. Separate fields instead, and
     * the words are the reader's: "your team's approved SOP", never "RAG".
     *
     * @param source SOP | AI | NONE — for styling and logic
     * @param label  the one-line badge the operator reads
     * @param detail why that source was used, in plain language
     */
    private record Suggestion(String text, String source, String label, String detail) {}

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

    /**
     * Plain-language rules every suggestion prompt inherits. The reader is a service desk
     * agent, not the engineer who wrote the runbook.
     */
    private static final String PLAIN_LANGUAGE_RULES = """
            Write for a service desk agent who is not a systems engineer:
            - Short numbered steps, one action per step.
            - Plain text only. No markdown, no asterisks, no headings, no bold, no ``` fences
              — the answer is shown as-is in a plain panel, so markup is just clutter on
              screen.
            - No jargon. If a step needs a command, write the command exactly as it must be
              typed, then say in one short clause what it does.
            - Say plainly when a step needs someone with server access.
            - Never invent a hostname, path, service name or credential that is not given
              to you.
            """;

    /**
     * What to try on this incident, and where that came from.
     *
     * Which source is used is decided by a database question — is there an approved procedure
     * that matches? — asked once, before any model is called.
     *
     * It used to be decided by reading the assistant's English: if the answer did not
     * contain "couldn't find" or "NOT_FOUND" it was treated as SOP-backed. That is why the
     * same ticket answered from the SOP on one click and from somewhere else on the next —
     * the two runs worded their non-answer differently. Worse, askStrictSopRag's own notices
     * ("that is outside the SOPs I have", "the knowledge service is not available") passed
     * that test and were shown to the operator as if they were the runbook's advice.
     *
     * No approved procedure means the assistant's own reasoning, labelled as such. There is
     * no third source: ticket text does not leave this network to be researched.
     */
    private Suggestion suggestResolution(String subject, String description) {
        String question = (trim(subject) + " " + trim(description)).trim();
        SopEvidence evidence = ragService.findApprovedSopEvidence(question);

        if (evidence.approvedEvidencePresent()) {
            // Grounded on the approved text itself rather than routed through
            // askStrictSopRag, whose scope check and vector-store availability are separate
            // questions from "does an approved procedure exist". The excerpt is the source
            // of truth; the model only reformats it, and if there is no model the operator
            // still gets the procedure verbatim.
            String steps = ask("""
                    %s
                    Below is your organisation's APPROVED procedure for this kind of incident.
                    Rewrite it as the steps to take on this specific ticket. Use only what the
                    procedure says — if it does not cover something, say that instead of filling
                    the gap yourself.

                    Approved procedure:
                    %s

                    Ticket subject: %s
                    Ticket description: %s
                    """.formatted(PLAIN_LANGUAGE_RULES, evidence.excerpt(), subject, description));
            int matched = evidence.procedureIds().size();
            return new Suggestion(steps.isBlank() ? evidence.excerpt() : steps, "SOP",
                    "From your approved SOP",
                    matched == 1
                            ? "1 approved procedure in your workspace covers this ticket, so these steps come from your own runbook."
                            : matched + " approved procedures in your workspace cover this ticket, so these steps come from your own runbook.");
        }

        String steps = ask("""
                %s
                Your organisation has NO approved procedure for this incident, so you are
                suggesting a starting point that a human must review before acting. Work only
                from what the ticket says and from general operational practice; do not claim a
                procedure exists.

                Ticket subject: %s
                Ticket description: %s
                """.formatted(PLAIN_LANGUAGE_RULES, subject, description));

        if (steps.isBlank())
            return new Suggestion(
                    "No suggestion could be produced for this ticket. Assign it to the right team for a look.",
                    "NONE", "Nothing found",
                    "No approved procedure matched this ticket and the assistant is unavailable, so there is nothing to show yet.");

        return new Suggestion(steps, "AI", "Suggested by the assistant",
                "No approved procedure matched this ticket, so this is the assistant's own reasoning. Check it before acting, then consider adding an SOP.");
    }

    /** One prompt, one answer, never an exception and never null. "" means "no answer". */
    private String ask(String prompt) {
        try {
            org.springframework.ai.chat.client.ChatClient chatClient = ragService.getOrBuildChatClient();
            if (chatClient == null) return "";
            String answer = chatClient.prompt().user(prompt).call().content();
            return answer == null ? "" : answer.trim();
        } catch (Exception e) {
            log.warn("Suggestion generation failed: {}", e.getMessage());
            return "";
        }
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }

    public List<Map<String, Object>> getAllHistory() {
        return List.of();
    }
}
