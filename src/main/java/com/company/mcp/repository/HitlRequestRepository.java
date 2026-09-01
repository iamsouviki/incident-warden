package com.company.mcp.repository;

import com.company.mcp.model.HitlRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HitlRequestRepository extends JpaRepository<HitlRequest, UUID> {
    List<HitlRequest> findByTenantIdAndStatusOrderByCreatedAtAsc(String tenantId, String status);
    Optional<HitlRequest> findByIdAndTenantId(UUID id, String tenantId);
    Optional<HitlRequest> findFirstByPlanIdAndStatus(UUID planId, String status);
}
