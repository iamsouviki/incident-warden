package com.company.mcp.repository;

import com.company.mcp.model.TelemetryEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TelemetryEventRepository extends JpaRepository<TelemetryEvent, UUID> {
    List<TelemetryEvent> findTop100ByOrderByReceivedAtDesc();
}
