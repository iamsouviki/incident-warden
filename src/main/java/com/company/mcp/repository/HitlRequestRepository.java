package com.company.mcp.repository;

import com.company.mcp.model.HitlRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HitlRequestRepository extends JpaRepository<HitlRequest, UUID> {
    List<HitlRequest> findByStatusOrderByCreatedAtAsc(String status);
    Optional<HitlRequest> findFirstByPlanIdAndStatus(UUID planId, String status);
}
