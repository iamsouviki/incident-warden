package com.company.warden.repository;

import com.company.warden.model.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncidentStatusRepository extends JpaRepository<IncidentStatus, UUID> {
    Optional<IncidentStatus> findByName(String name);
}
