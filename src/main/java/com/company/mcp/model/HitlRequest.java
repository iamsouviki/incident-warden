package com.company.mcp.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "hitl_requests", schema = "incident")
public class HitlRequest {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private String tenantId;
    @Column(name = "incident_id", nullable = false) private UUID incidentId;
    @Column(name = "plan_id", nullable = false) private UUID planId;
    @Column(nullable = false) private String status;
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    private String reviewer;
    @Column(name = "decision_reason", columnDefinition = "TEXT") private String decisionReason;
    @Column(name = "approved_plan_hash") private String approvedPlanHash;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "decided_at") private OffsetDateTime decidedAt;
    @PrePersist void created(){if(id==null)id=UUID.randomUUID();if(createdAt==null)createdAt=OffsetDateTime.now();}
    public UUID getId(){return id;} public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;} public UUID getIncidentId(){return incidentId;} public void setIncidentId(UUID v){incidentId=v;} public UUID getPlanId(){return planId;} public void setPlanId(UUID v){planId=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getRequestedBy(){return requestedBy;} public void setRequestedBy(String v){requestedBy=v;} public String getReviewer(){return reviewer;} public void setReviewer(String v){reviewer=v;} public String getDecisionReason(){return decisionReason;} public void setDecisionReason(String v){decisionReason=v;} public String getApprovedPlanHash(){return approvedPlanHash;} public void setApprovedPlanHash(String v){approvedPlanHash=v;} public void setDecidedAt(OffsetDateTime v){decidedAt=v;}
}
