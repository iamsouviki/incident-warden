package com.company.warden.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "statuses", schema = "incident")
public class IncidentStatus {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    public IncidentStatus() {}

    public IncidentStatus(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
