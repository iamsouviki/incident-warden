package com.company.mcp.repository;

import com.company.mcp.model.SopProcedure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Derived queries only — no native SQL — so the same repository works on Postgres and
 * on the H2 local profile. Every finder takes a tenant so a caller cannot accidentally
 * read another tenant's approved procedures.
 */
@Repository
public interface SopProcedureRepository extends JpaRepository<SopProcedure, UUID> {

    List<SopProcedure> findByTenantIdAndApprovalStatus(String tenantId, String approvalStatus);

    List<SopProcedure> findByTenantIdOrderBySopIdAscStepNumberAsc(String tenantId);

    List<SopProcedure> findByTenantIdAndSopIdOrderByExecutionOrderAsc(String tenantId, String sopId);

    Optional<SopProcedure> findByIdAndTenantId(UUID id, String tenantId);

    boolean existsByTenantIdAndSopIdAndStepNumber(String tenantId, String sopId, int stepNumber);
}
