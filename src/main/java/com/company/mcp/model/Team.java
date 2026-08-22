package com.company.mcp.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "teams", schema = "teams")
public class Team {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * The group's distribution address, set by an admin on the Teams page. Nullable: a
     * team nobody has given an address to is skipped when notifications go out, rather
     * than having one guessed from its name.
     */
    @Column(name = "email")
    private String email;

    /**
     * The roster, read-only through this side. Deliberately no cascade: this collection is
     * inverse (team_employees.team_id owns the link) and the FK is already ON DELETE CASCADE,
     * so JPA cascading adds nothing — while CascadeType.ALL actively broke member removal.
     * Deleting a TeamEmployee whose parent Team was loaded in the same session left the child
     * in this EAGER collection at flush time, so the cascaded persist-on-flush un-deleted it:
     * the API answered 200 and the row was still there. Roster writes go through
     * TeamEmployeeRepository, which owns the link.
     */
    @OneToMany(mappedBy = "team", fetch = FetchType.EAGER)
    @JsonManagedReference
    private List<TeamEmployee> employees;

    public Team() {}

    public Team(UUID id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public List<TeamEmployee> getEmployees() { return employees; }
    public void setEmployees(List<TeamEmployee> employees) { this.employees = employees; }
}
