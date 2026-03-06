package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Represents a user-defined MCP tool created via the UI.
 * Persisted to {@code custom_tools} and loaded at startup by
 * {@link com.company.mcp.tool.CustomToolLoader}.
 */
@Entity
@Table(name = "custom_tools")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomTool {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    /** JSON array of required parameter keys, e.g. ["serviceName","replicas"] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_params", columnDefinition = "JSONB")
    @Builder.Default
    private List<String> requiredParams = List.of();

    @Column(nullable = false)
    @Builder.Default
    private Boolean dangerous = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "script_workspace_id", columnDefinition = "UUID")
    private UUID scriptWorkspaceId;

    @Column(name = "sop_id", columnDefinition = "UUID")
    private UUID sopId;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
