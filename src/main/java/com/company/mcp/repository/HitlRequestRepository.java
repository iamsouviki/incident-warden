package com.company.mcp.repository;

import com.company.mcp.model.HitlRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HitlRequestRepository extends JpaRepository<HitlRequest, UUID> {
    Optional<HitlRequest> findByIncidentId(UUID incidentId);
    List<HitlRequest> findByStatusOrderByCreatedAtDesc(String status);
    List<HitlRequest> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
    List<HitlRequest> findByExpiresAtBefore(LocalDateTime now);
    Integer countByStatus(String status);
    Integer countByTenantIdAndStatus(UUID tenantId, String status);
}
