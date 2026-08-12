package com.company.mcp.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "remediation_plans", schema = "incident")
public class RemediationPlan {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private String tenantId;
    @Column(name = "incident_id", nullable = false) private UUID incidentId;
    @Column(nullable = false) private String status;
    @Column(name = "action_name", nullable = false) private String actionName;
    @Column(nullable = false) private String target;
    @Column(name = "parameters_json", nullable = false, columnDefinition = "TEXT") private String parametersJson;
    @Column(name = "sop_evidence", columnDefinition = "TEXT") private String sopEvidence;
    @Column(name = "confidence_score", nullable = false) private double confidenceScore;
    @Column(name = "risk_score", nullable = false) private double riskScore;
    @Column(name = "guardrail_status", nullable = false) private String guardrailStatus;
    @Column(name = "guardrail_findings", nullable = false, columnDefinition = "TEXT") private String guardrailFindings;
    @Column(name = "rollback_plan", nullable = false, columnDefinition = "TEXT") private String rollbackPlan;
    @Column(name = "plan_hash", nullable = false) private String planHash;
    @Column(nullable = false) private int attempts;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    @PrePersist void created() { if (id == null) id = UUID.randomUUID(); if (createdAt == null) createdAt = OffsetDateTime.now(); updatedAt = OffsetDateTime.now(); }
    @PreUpdate void updated() { updatedAt = OffsetDateTime.now(); }
    public UUID getId(){return id;} public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;} public UUID getIncidentId(){return incidentId;} public void setIncidentId(UUID v){incidentId=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getActionName(){return actionName;} public void setActionName(String v){actionName=v;} public String getTarget(){return target;} public void setTarget(String v){target=v;} public String getParametersJson(){return parametersJson;} public void setParametersJson(String v){parametersJson=v;} public String getSopEvidence(){return sopEvidence;} public void setSopEvidence(String v){sopEvidence=v;} public double getConfidenceScore(){return confidenceScore;} public void setConfidenceScore(double v){confidenceScore=v;} public double getRiskScore(){return riskScore;} public void setRiskScore(double v){riskScore=v;} public String getGuardrailStatus(){return guardrailStatus;} public void setGuardrailStatus(String v){guardrailStatus=v;} public String getGuardrailFindings(){return guardrailFindings;} public void setGuardrailFindings(String v){guardrailFindings=v;} public String getRollbackPlan(){return rollbackPlan;} public void setRollbackPlan(String v){rollbackPlan=v;} public String getPlanHash(){return planHash;} public void setPlanHash(String v){planHash=v;} public int getAttempts(){return attempts;} public void setAttempts(int v){attempts=v;}
}
