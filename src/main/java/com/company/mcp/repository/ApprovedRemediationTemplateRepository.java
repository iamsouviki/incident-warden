package com.company.mcp.repository;

import com.company.mcp.model.ApprovedRemediationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovedRemediationTemplateRepository extends JpaRepository<ApprovedRemediationTemplate, UUID> {

    List<ApprovedRemediationTemplate> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);

    List<ApprovedRemediationTemplate> findByTenantIdAndAutoEligibleTrueOrderByUpdatedAtDesc(UUID tenantId);

    Optional<ApprovedRemediationTemplate> findByProposalId(UUID proposalId);
}
