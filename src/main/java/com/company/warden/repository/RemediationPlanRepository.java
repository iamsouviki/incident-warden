package com.company.warden.repository;

import com.company.warden.model.RemediationPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RemediationPlanRepository extends JpaRepository<RemediationPlan, UUID> {
    List<RemediationPlan> findByStatus(String status);
    List<RemediationPlan> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
    Optional<RemediationPlan> findFirstByIncidentIdAndStatusOrderByCreatedAtDesc(UUID incidentId, String status);
}
