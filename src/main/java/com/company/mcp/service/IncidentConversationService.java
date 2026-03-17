package com.company.mcp.service;

import com.company.mcp.model.Incident;
import com.company.mcp.model.IncidentConversationMessage;
import com.company.mcp.model.IncidentConversationThread;
import com.company.mcp.model.ScriptProposal;
import com.company.mcp.repository.IncidentConversationMessageRepository;
import com.company.mcp.repository.IncidentConversationThreadRepository;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.repository.ScriptProposalRepository;
import com.company.mcp.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IncidentConversationService {

    private final IncidentConversationThreadRepository threadRepository;
    private final IncidentConversationMessageRepository messageRepository;
    private final ScriptProposalRepository scriptProposalRepository;
    private final IncidentRepository incidentRepository;
    private final TenantRepository tenantRepository;
    private final RemediationTemplateService remediationTemplateService;
    private final KnowledgeBaseService knowledgeBaseService;

    public Map<String, Object> listThreads(String tenantId) {
        UUID tid = parseTenantId(tenantId);
        List<Map<String, Object>> threads = threadRepository.findByTenantIdOrderByUpdatedAtDesc(tid)
                .stream()
                .map(thread -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", thread.getId());
                    item.put("title", thread.getTitle());
                    item.put("status", thread.getStatus());
                    item.put("currentAttempt", thread.getCurrentAttempt());
                    item.put("incidentId", thread.getIncidentId());
                    item.put("latestProposalId", thread.getLatestProposalId());
                    item.put("updatedAt", thread.getUpdatedAt());
                    return item;
                })
                .toList();
        return Map.of("count", threads.size(), "threads", threads);
    }

    public Map<String, Object> createThread(String tenantId, UUID incidentId, String title, String createdBy) {
        UUID tid = parseTenantId(tenantId);
        Incident incident = incidentId != null ? incidentRepository.findById(incidentId).orElse(null) : null;
        String threadTitle = title != null && !title.isBlank()
                ? title.trim()
                : (incident != null ? incident.getTitle() : "Incident Collaboration Thread");

        IncidentConversationThread thread = threadRepository.save(IncidentConversationThread.builder()
                .tenantId(tid)
                .incidentId(incidentId)
                .title(threadTitle)
                .summaryJson(Map.of(
                        "confirmedFacts", List.of(),
                        "operatorConstraints", List.of(),
                        "rejectedActions", List.of()
                ))
                .build());

        String initialMessage = buildInitialAgentMessage(incident);
        messageRepository.save(IncidentConversationMessage.builder()
                .threadId(thread.getId())
                .role("agent")
                .messageType("analysis")
                .content(initialMessage)
                .structuredPayload(Map.of(
                        "createdBy", createdBy == null ? "system" : createdBy,
                        "incidentId", incidentId
                ))
                .build());

        ScriptProposal proposal = createOrRefreshProposal(thread, incident, createdBy, Map.of());
        thread.setLatestProposalId(proposal.getId());
        threadRepository.save(thread);

        return getThread(thread.getId());
    }

    public Map<String, Object> getThread(UUID threadId) {
        IncidentConversationThread thread = getThreadEntity(threadId);
        List<IncidentConversationMessage> messages = messageRepository.findByThreadIdOrderByCreatedAtAsc(threadId);
        List<ScriptProposal> proposals = scriptProposalRepository.findByThreadIdOrderByCreatedAtDesc(threadId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("thread", thread);
        response.put("messages", messages);
        response.put("proposals", proposals);
        response.put("latestProposal", proposals.isEmpty() ? null : proposals.get(0));
        return response;
    }

    public Map<String, Object> addMessage(UUID threadId,
                                          String role,
                                          String messageType,
                                          String content,
                                          Map<String, Object> structuredPayload) {
        IncidentConversationThread thread = getThreadEntity(threadId);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message content is required");
        }

        IncidentConversationMessage message = messageRepository.save(IncidentConversationMessage.builder()
                .threadId(threadId)
                .role(role == null || role.isBlank() ? "user" : role.trim().toLowerCase())
                .messageType(messageType == null || messageType.isBlank() ? "comment" : messageType.trim())
                .content(content.trim())
                .structuredPayload(structuredPayload == null ? Map.of() : structuredPayload)
                .build());

        Incident incident = thread.getIncidentId() != null
                ? incidentRepository.findById(thread.getIncidentId()).orElse(null)
                : null;

        updateSummary(thread, content);
        ScriptProposal proposal = createOrRefreshProposal(thread, incident, "agent", structuredPayload == null ? Map.of() : structuredPayload);

        thread.setLatestProposalId(proposal.getId());
        thread.setUpdatedAt(LocalDateTime.now());
        threadRepository.save(thread);

        String agentResponse = buildAgentResponse(content, proposal);
        messageRepository.save(IncidentConversationMessage.builder()
                .threadId(threadId)
                .role("agent")
                .messageType("explanation")
                .content(agentResponse)
                .structuredPayload(Map.of("proposalId", proposal.getId()))
                .build());

        return Map.of(
                "message", message,
                "proposal", proposal,
                "thread", thread
        );
    }

    public Map<String, Object> approveProposal(UUID proposalId, String approvedBy) {
        ScriptProposal proposal = scriptProposalRepository.findById(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found"));
        proposal.setStatus("APPROVED");
        proposal.setApprovedBy(approvedBy == null || approvedBy.isBlank() ? "dashboard-user" : approvedBy.trim());
        proposal.setApprovedAt(LocalDateTime.now());
        scriptProposalRepository.save(proposal);

        messageRepository.save(IncidentConversationMessage.builder()
                .threadId(proposal.getThreadId())
                .role("system")
                .messageType("approval")
                .content("Script approved. Execution can proceed through the MCP execution boundary.")
                .structuredPayload(Map.of("proposalId", proposalId, "approvedBy", proposal.getApprovedBy()))
                .build());

        return Map.of("proposal", proposal, "status", "APPROVED");
    }

    public Map<String, Object> validateProposal(UUID threadId,
                                                boolean resolved,
                                                String confirmedBy,
                                                String comment) {
        IncidentConversationThread thread = getThreadEntity(threadId);
        ScriptProposal proposal = scriptProposalRepository.findFirstByThreadIdOrderByCreatedAtDesc(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No proposal found for thread"));
        Incident incident = proposal.getIncidentId() != null
                ? incidentRepository.findById(proposal.getIncidentId()).orElse(null)
                : null;

        proposal.setStatus(resolved ? "RESOLVED" : "FAILED_VALIDATION");
        scriptProposalRepository.save(proposal);

        thread.setStatus(resolved ? "RESOLVED" : "NEEDS_RETRY");
        if (!resolved) {
            thread.setCurrentAttempt(Math.min(thread.getCurrentAttempt() + 1, 3));
        }
        threadRepository.save(thread);

        messageRepository.save(IncidentConversationMessage.builder()
                .threadId(threadId)
                .role("system")
                .messageType("validation")
                .content(resolved
                        ? "Operator confirmed the issue is resolved. The remediation will be stored as reusable memory."
                        : "Operator marked the issue unresolved. The next attempt should use a different script and require approval.")
                .structuredPayload(Map.of("confirmedBy", confirmedBy, "comment", comment))
                .build());

        if (resolved) {
            remediationTemplateService.createFromSuccessfulProposal(proposal, incident, true, confirmedBy, comment);
            if (incident != null) {
                knowledgeBaseService.archiveResolved(
                        incident,
                        proposal.getExplanation(),
                        "Validated through incident collaboration workflow",
                        List.of(Map.of(
                                "step", proposal.getAttemptNo(),
                                "action", "execute_approved_script",
                                "result", "resolved",
                                "scriptHash", proposal.getScriptHash()
                        )),
                        List.of(Map.of(
                                "author", confirmedBy,
                                "role", "OPERATOR",
                                "text", comment == null ? "Resolved after execution" : comment,
                                "ts", LocalDateTime.now().toString()
                        )),
                        confirmedBy
                );
            }
        }

        return Map.of(
                "thread", thread,
                "proposal", proposal,
                "resolved", resolved
        );
    }

    private ScriptProposal createOrRefreshProposal(IncidentConversationThread thread,
                                                   Incident incident,
                                                   String createdBy,
                                                   Map<String, Object> structuredPayload) {
        String targetRef = readString(structuredPayload, "targetRef");
        String shell = readString(structuredPayload, "shell");
        String operatorHint = readString(structuredPayload, "operatorHint");
        boolean autoEligible = isLowRiskAutoEligible(incident, operatorHint);

        String serviceName = incident != null && incident.getCategory() != null
                ? incident.getCategory().toLowerCase()
                : "service";
        String script = "bash".equalsIgnoreCase(shell) || shell == null || shell.isBlank()
                ? "systemctl restart " + serviceName + "\n"
                  + "sleep 5\n"
                  + "curl -f ${SERVICE_HEALTH_URL:-http://localhost:8080/health}"
                : "Restart-Service -Name \"" + serviceName + "\"\n"
                  + "Start-Sleep -Seconds 5";

        String explanation = autoEligible
                ? "Exact low-risk remediation pattern matched. This proposal is eligible for policy-driven auto execution after guardrail confirmation."
                : "Generated remediation proposal based on incident context, operator hints, and the current low-risk policy. Human approval is required before execution.";

        ScriptProposal proposal = ScriptProposal.builder()
                .threadId(thread.getId())
                .tenantId(thread.getTenantId())
                .incidentId(thread.getIncidentId())
                .attemptNo(thread.getCurrentAttempt())
                .shellType(shell == null || shell.isBlank() ? "bash" : shell)
                .targetRef(targetRef)
                .scriptContent(script)
                .explanation(explanation)
                .riskLevel(autoEligible ? "LOW" : "MEDIUM")
                .approvalRequired(!autoEligible)
                .status(autoEligible ? "AUTO_APPROVED_CANDIDATE" : "PENDING_APPROVAL")
                .rollbackPlan("Revert the operational change and re-run service health validation before escalating.")
                .validationPlanJson(List.of(
                        "Confirm service health endpoint returns success",
                        "Confirm operator validates the incident as resolved"
                ))
                .evidenceJson(buildEvidenceJson(thread, incident, operatorHint))
                .createdBy(createdBy == null ? "agent" : createdBy)
                .build();

        return scriptProposalRepository.save(proposal);
    }

    private boolean isLowRiskAutoEligible(Incident incident, String operatorHint) {
        if (incident == null || incident.getCategory() == null) {
            return false;
        }
        String category = incident.getCategory().toUpperCase();
        boolean approvedClass = List.of("CACHE", "PERFORMANCE", "INFRASTRUCTURE", "NETWORK").contains(category);
        boolean noExplicitManualOverride = operatorHint == null || !operatorHint.toLowerCase().contains("manual");
        return approvedClass && noExplicitManualOverride;
    }

    private void updateSummary(IncidentConversationThread thread, String content) {
        Map<String, Object> summary = new LinkedHashMap<>(thread.getSummaryJson() == null ? Map.of() : thread.getSummaryJson());
        List<String> constraints = toStringList(summary.get("operatorConstraints"));
        constraints.add(content);
        summary.put("operatorConstraints", constraints);
        summary.put("lastOperatorMessage", content);
        thread.setSummaryJson(summary);
    }

    private IncidentConversationThread getThreadEntity(UUID threadId) {
        return threadRepository.findById(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation thread not found"));
    }

    private UUID parseTenantId(String tenantId) {
        try {
            UUID tid = UUID.fromString(tenantId);
            if (!tenantRepository.existsById(tid)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tenant");
            }
            return tid;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tenantId");
        }
    }

    private static String buildInitialAgentMessage(Incident incident) {
        if (incident == null) {
            return "I created a collaboration thread. Share device APIs, DB lookups, environment constraints, or restart restrictions before I generate the final remediation proposal.";
        }
        return "I analyzed the incident \"" + incident.getTitle() + "\" and prepared the first remediation proposal. "
                + "You can correct assumptions, add API/DB lookup instructions, or constrain the execution target before approval.";
    }

    private static String buildAgentResponse(String content, ScriptProposal proposal) {
        return "I incorporated your instruction: \"" + content + "\". "
                + "The current proposal is attempt #" + proposal.getAttemptNo()
                + " with risk level " + proposal.getRiskLevel()
                + ". Review the updated script, rollback, and validation plan before approval.";
    }

    private static Map<String, Object> buildEvidenceJson(IncidentConversationThread thread,
                                                         Incident incident,
                                                         String operatorHint) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("incidentId", thread.getIncidentId());
        if (incident != null && incident.getCategory() != null) {
            evidence.put("serviceCategory", incident.getCategory());
        }
        if (operatorHint != null && !operatorHint.isBlank()) {
            evidence.put("operatorHint", operatorHint);
        }
        return evidence;
    }

    private static String readString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return new java.util.ArrayList<>(list.stream().map(String::valueOf).toList());
        }
        return new java.util.ArrayList<>();
    }
}
