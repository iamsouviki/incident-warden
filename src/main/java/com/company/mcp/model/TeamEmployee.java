package com.company.mcp.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonBackReference;
import java.util.UUID;

@Entity
@Table(name = "team_employees", schema = "teams")
public class TeamEmployee {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "email")
    private String email;

    @ManyToOne
    @JoinColumn(name = "team_id", nullable = false)
    @JsonBackReference
    private Team team;

    public TeamEmployee() {}

    public TeamEmployee(UUID id, String username, String email, Team team) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.team = team;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }
}
