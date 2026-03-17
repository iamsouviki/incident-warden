package com.company.mcp.service;

import com.company.mcp.model.ApprovedRemediationTemplate;
import com.company.mcp.model.Incident;
import com.company.mcp.model.ScriptProposal;
import com.company.mcp.repository.ApprovedRemediationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RemediationTemplateService {

    private final ApprovedRemediationTemplateRepository templateRepository;
    private final RagService ragService;

    public ApprovedRemediationTemplate createFromSuccessfulProposal(ScriptProposal proposal,
                                                                    Incident incident,
                                                                    boolean resolved,
                                                                    String confirmedBy,
                                                                    String comment) {
        ApprovedRemediationTemplate template = templateRepository.findByProposalId(proposal.getId())
                .orElseGet(() -> ApprovedRemediationTemplate.builder()
                        .tenantId(proposal.getTenantId())
                        .incidentId(proposal.getIncidentId())
                        .proposalId(proposal.getId())
                        .name(buildTemplateName(proposal, incident))
                        .serviceName(incident != null ? incident.getCategory() : null)
                        .incidentFingerprint(buildFingerprint(incident))
                        .shellType(proposal.getShellType())
                        .actionClass(classifyAction(proposal.getScriptContent()))
                        .riskLevel(proposal.getRiskLevel())
                        .autoEligible(isAutoEligible(proposal))
                        .dataManipulation(isDataManipulation(proposal.getScriptContent()))
                        .scriptContent(proposal.getScriptContent())
                        .scriptHash(proposal.getScriptHash())
                        .explanation(proposal.getExplanation())
                        .rollbackPlan(proposal.getRollbackPlan())
                        .validationPlanJson(proposal.getValidationPlanJson())
                        .metadataJson(new LinkedHashMap<>())
                        .createdBy(proposal.getCreatedBy())
                        .approvedBy(confirmedBy)
                        .build());

        template.setLastUsedAt(LocalDateTime.now());
        template.setApprovedBy(confirmedBy);
        template.setRiskLevel(proposal.getRiskLevel());

        Map<String, Object> metadata = new LinkedHashMap<>(template.getMetadataJson());
        metadata.put("resolved", resolved);
        metadata.put("confirmedBy", confirmedBy);
        metadata.put("comment", comment);
        template.setMetadataJson(metadata);

        if (resolved) {
            template.setSuccessCount(template.getSuccessCount() + (template.getId() == null ? 0 : 1));
        } else {
            template.setFailureCount(template.getFailureCount() + 1);
        }

        ApprovedRemediationTemplate saved = templateRepository.save(template);
        ingestTemplate(saved);
        return saved;
    }

    public List<ApprovedRemediationTemplate> list(UUID tenantId) {
        return templateRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    private void ingestTemplate(ApprovedRemediationTemplate template) {
        String content = String.join("\n", List.of(
                "Template: " + template.getName(),
                "Action class: " + template.getActionClass(),
                "Risk: " + template.getRiskLevel(),
                "Explanation: " + safe(template.getExplanation()),
                "Script: " + template.getScriptContent(),
                "Rollback: " + safe(template.getRollbackPlan())
        ));
        boolean ingested = ragService.ingest(
                template.getId().toString(),
                content,
                RagService.TYPE_RUNBOOK,
                Map.of(
                        "template_id", template.getId().toString(),
                        "tenant_id", template.getTenantId().toString(),
                        "auto_eligible", template.getAutoEligible(),
                        "action_class", template.getActionClass()
                )
        );
        if (ingested) {
            template.setEmbeddingIngested(true);
            templateRepository.save(template);
        }
    }

    private static String buildTemplateName(ScriptProposal proposal, Incident incident) {
        String base = incident != null && incident.getTitle() != null ? incident.getTitle() : "approved-remediation";
        return base.toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }

    private static String buildFingerprint(Incident incident) {
        if (incident == null) {
            return null;
        }
        return String.join("|",
                safe(incident.getSourceSystem()),
                safe(incident.getCategory()),
                safe(incident.getSubCategory()),
                safe(incident.getSeverity()),
                safe(incident.getTitle()));
    }

    private static boolean isAutoEligible(ScriptProposal proposal) {
        String actionClass = classifyAction(proposal.getScriptContent());
        return "LOW".equalsIgnoreCase(proposal.getRiskLevel())
                && !isDataManipulation(proposal.getScriptContent())
                && List.of("restart_service", "restart_job", "clear_cache").contains(actionClass);
    }

    private static String classifyAction(String script) {
        String lower = safe(script).toLowerCase();
        if (lower.contains("systemctl restart") || lower.contains("restart-service")) {
            return "restart_service";
        }
        if (lower.contains("cache") || lower.contains("redis-cli") || lower.contains("flush")) {
            return "clear_cache";
        }
        if (lower.contains("schtasks") || lower.contains("cron") || lower.contains("job")) {
            return "restart_job";
        }
        return "manual_review";
    }

    private static boolean isDataManipulation(String script) {
        String lower = safe(script).toLowerCase();
        return lower.contains("delete ")
                || lower.contains("truncate ")
                || lower.contains("drop table")
                || lower.contains("update ")
                || lower.contains("insert ");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
