package com.company.mcp.repository;

import com.company.mcp.model.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncidentStatusRepository extends JpaRepository<IncidentStatus, UUID> {
    Optional<IncidentStatus> findByName(String name);
}
