package com.company.warden.repository;

import com.company.warden.model.SopProcedure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Derived queries only — no native SQL — so the same repository works on Postgres and
 * on the H2 local profile.
 */
@Repository
public interface SopProcedureRepository extends JpaRepository<SopProcedure, UUID> {

    List<SopProcedure> findByApprovalStatus(String approvalStatus);

    List<SopProcedure> findAllByOrderBySopIdAscStepNumberAsc();

    List<SopProcedure> findBySopIdOrderByExecutionOrderAsc(String sopId);

    boolean existsBySopIdAndStepNumber(String sopId, int stepNumber);
}
