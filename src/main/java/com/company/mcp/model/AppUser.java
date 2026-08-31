package com.company.mcp.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DB-backed user for local JWT auth.
 * sso_provider / sso_subject reserved for future SSO integration.
 */
@Entity
@Table(schema = "auth", name = "users")
public class AppUser {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "full_name")
    private String fullName;

    private String email;

    private String department;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "tenant_name")
    private String tenantName;

    @Column(name = "sso_provider")
    private String ssoProvider;

    @Column(name = "sso_subject")
    private String ssoSubject;

    @Column(nullable = false)
    private boolean enabled = true;

    /** True while the account still carries the password an admin handed over. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public AppUser() {}

    public UUID getId()                { return id; }
    public void setId(UUID id)         { this.id = id; }

    public String getUsername()              { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getFullName()              { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail()           { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDepartment()                { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getPasswordHash()                    { return passwordHash; }
    public void setPasswordHash(String passwordHash)   { this.passwordHash = passwordHash; }

    public String getRole()            { return role; }
    public void setRole(String role)   { this.role = role; }

    public String getTenantId()                { return tenantId; }
    public void setTenantId(String tenantId)   { this.tenantId = tenantId; }

    public String getTenantName()                  { return tenantName; }
    public void setTenantName(String tenantName)   { this.tenantName = tenantName; }

    public String getSsoProvider()                     { return ssoProvider; }
    public void setSsoProvider(String ssoProvider)     { this.ssoProvider = ssoProvider; }

    public String getSsoSubject()                    { return ssoSubject; }
    public void setSsoSubject(String ssoSubject)     { this.ssoSubject = ssoSubject; }

    public boolean isEnabled()               { return enabled; }
    public void setEnabled(boolean enabled)  { this.enabled = enabled; }

    public boolean isMustChangePassword()                  { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChange)   { this.mustChangePassword = mustChange; }

    public OffsetDateTime getCreatedAt()                     { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt)       { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt()                     { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt)       { this.updatedAt = updatedAt; }
}
