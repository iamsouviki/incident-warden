package com.company.mcp.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "action_executions", schema = "incident")
public class ActionExecution {
    @Id private UUID id;
    @Column(name="tenant_id", nullable=false) private String tenantId;
    @Column(name="incident_id", nullable=false) private UUID incidentId;
    @Column(name="plan_id", nullable=false) private UUID planId;
    @Column(name="hitl_request_id") private UUID hitlRequestId;
    @Column(nullable=false) private String mode;
    @Column(nullable=false) private String status;
    @Column(columnDefinition="TEXT") private String output;
    @Column(name="validation_result", columnDefinition="TEXT") private String validationResult;
    @Column(name="started_at", nullable=false) private OffsetDateTime startedAt;
    @Column(name="completed_at") private OffsetDateTime completedAt;
    @PrePersist void created(){if(id==null)id=UUID.randomUUID();if(startedAt==null)startedAt=OffsetDateTime.now();}
    public UUID getId(){return id;} public void setTenantId(String v){tenantId=v;} public void setIncidentId(UUID v){incidentId=v;} public void setPlanId(UUID v){planId=v;} public void setHitlRequestId(UUID v){hitlRequestId=v;} public void setMode(String v){mode=v;} public void setStatus(String v){status=v;} public void setOutput(String v){output=v;} public void setValidationResult(String v){validationResult=v;} public void setCompletedAt(OffsetDateTime v){completedAt=v;}
}
