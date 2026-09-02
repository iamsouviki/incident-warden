package com.company.mcp.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "remediation_plans", schema = "incident")
public class RemediationPlan {
    @Id private UUID id;
    @Column(name = "incident_id", nullable = false) private UUID incidentId;
    @Column(nullable = false) private String status;
    @Column(name = "action_name", nullable = false) private String actionName;
    @Column(nullable = false) private String target;
    @Column(name = "parameters_json", nullable = false, columnDefinition = "TEXT") private String parametersJson;
    @Column(name = "sop_evidence", columnDefinition = "TEXT") private String sopEvidence;
    @Column(name = "risk_score", nullable = false) private double riskScore;
    @Column(name = "guardrail_status", nullable = false) private String guardrailStatus;
    @Column(name = "guardrail_findings", nullable = false, columnDefinition = "TEXT") private String guardrailFindings;
    @Column(name = "rollback_plan", nullable = false, columnDefinition = "TEXT") private String rollbackPlan;
    /**
     * The exact script a reviewer approves and the executor runs. Stored on the plan and
     * pinned into {@code planHash}: approving a plan approves this text, character for
     * character, and nothing else may run in its place.
     */
    @Column(name = "remediation_script", columnDefinition = "TEXT") private String remediationScript;
    /** bash | powershell */
    @Column(name = "script_language") private String scriptLanguage;
    /** SOP_TEMPLATE | SOP_GROUNDED | LLM_KNOWLEDGE | NONE — how much authority is behind the script. */
    @Column(name = "script_source") private String scriptSource;
    /** PASS | WARN | BLOCK from the deterministic script scan at plan time. */
    @Column(name = "script_scan_level") private String scriptScanLevel;
    @Column(name = "plan_hash", nullable = false) private String planHash;
    @Column(nullable = false) private int attempts;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    @PrePersist void created() { if (id == null) id = UUID.randomUUID(); if (createdAt == null) createdAt = OffsetDateTime.now(); updatedAt = OffsetDateTime.now(); }
    @PreUpdate void updated() { updatedAt = OffsetDateTime.now(); }
    public UUID getId(){return id;} public UUID getIncidentId(){return incidentId;} public void setIncidentId(UUID v){incidentId=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getActionName(){return actionName;} public void setActionName(String v){actionName=v;} public String getTarget(){return target;} public void setTarget(String v){target=v;} public String getParametersJson(){return parametersJson;} public void setParametersJson(String v){parametersJson=v;} public String getSopEvidence(){return sopEvidence;} public void setSopEvidence(String v){sopEvidence=v;} public double getRiskScore(){return riskScore;} public void setRiskScore(double v){riskScore=v;} public String getGuardrailStatus(){return guardrailStatus;} public void setGuardrailStatus(String v){guardrailStatus=v;} public String getGuardrailFindings(){return guardrailFindings;} public void setGuardrailFindings(String v){guardrailFindings=v;} public String getRollbackPlan(){return rollbackPlan;} public void setRollbackPlan(String v){rollbackPlan=v;} public String getRemediationScript(){return remediationScript;} public void setRemediationScript(String v){remediationScript=v;} public String getScriptLanguage(){return scriptLanguage;} public void setScriptLanguage(String v){scriptLanguage=v;} public String getScriptSource(){return scriptSource;} public void setScriptSource(String v){scriptSource=v;} public String getScriptScanLevel(){return scriptScanLevel;} public void setScriptScanLevel(String v){scriptScanLevel=v;} public String getPlanHash(){return planHash;} public void setPlanHash(String v){planHash=v;} public int getAttempts(){return attempts;} public void setAttempts(int v){attempts=v;}
}
