package com.company.warden.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_events", schema = "incident")
public class AuditEvent {
    @Id private UUID id;
    @Column(name="aggregate_type", nullable=false) private String aggregateType;
    @Column(name="aggregate_id", nullable=false) private UUID aggregateId;
    @Column(name="event_type", nullable=false) private String eventType;
    @Column(nullable=false) private String actor;
    @Column(nullable=false, columnDefinition="TEXT") private String payload;
    @Column(name="previous_hash") private String previousHash;
    @Column(name="event_hash", nullable=false) private String eventHash;
    @Column(name="created_at", nullable=false) private OffsetDateTime createdAt;
    @PrePersist void created(){if(id==null)id=UUID.randomUUID();if(createdAt==null)createdAt=OffsetDateTime.now();}
    public UUID getId(){return id;} public String getAggregateType(){return aggregateType;} public void setAggregateType(String v){aggregateType=v;} public UUID getAggregateId(){return aggregateId;} public void setAggregateId(UUID v){aggregateId=v;} public String getEventType(){return eventType;} public void setEventType(String v){eventType=v;} public String getActor(){return actor;} public void setActor(String v){actor=v;} public String getPayload(){return payload;} public void setPayload(String v){payload=v;} public String getPreviousHash(){return previousHash;} public void setPreviousHash(String v){previousHash=v;} public String getEventHash(){return eventHash;} public void setEventHash(String v){eventHash=v;}
}
