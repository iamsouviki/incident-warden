package com.company.mcp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "tenant_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantSettings {

    @Id
    @Column(name = "tenant_id", columnDefinition = "UUID")
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "incident_sources", columnDefinition = "JSONB", nullable = false)
    @Builder.Default
    private List<Map<String, Object>> incidentSources = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "llm_providers", columnDefinition = "JSONB", nullable = false)
    @Builder.Default
    private List<Map<String, Object>> llmProviders = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "incident_defaults", columnDefinition = "JSONB", nullable = false)
    @Builder.Default
    private Map<String, Object> incidentDefaults = Map.of();

    @Column(name = "updated_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    @PreUpdate
    public void touch() {
        updatedAt = LocalDateTime.now();
    }
}
