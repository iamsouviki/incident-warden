package com.company.mcp.repository;

import com.company.mcp.model.SchedulerState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Scheduler state - tracks last polled timestamp for each source system.
 */
@Repository
public interface SchedulerStateRepository extends JpaRepository<SchedulerState, String> {
}
