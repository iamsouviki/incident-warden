package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Tracks the last polling timestamp for each external source system.
 */
@Entity
@Table(name = "scheduler_state")
@Data
@NoArgsConstructor
public class SchedulerState {

    @Id
    @Column(name = "source_system", length = 50)
    private String sourceSystem;

    @Column(name = "last_polled_at")
    private OffsetDateTime lastPolledAt;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "consecutive_errors")
    private Integer consecutiveErrors = 0;
}
